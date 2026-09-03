package com.sotto

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Who this phone is, and who it has heard.
 *
 * A 16-bit id is drawn once per install and shown as a four-character tag such as #7K2Q.
 * Messages carry only the id (two bytes of airtime); names travel in separate "hello"
 * frames, once per acquaintance, and are remembered here.
 */
class IdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("sotto", Context.MODE_PRIVATE)

    val id: Int = prefs.getInt("id", 0).takeIf { it != 0 } ?: newId().also { prefs.edit().putInt("id", it).apply() }
    var name: String by mutableStateOf(prefs.getString("name", "") ?: "")
        private set

    class Contact(val name: String, val lastHeard: Long)

    val contacts = mutableStateMapOf<Int, Contact>()

    init {
        runCatching {
            val j = JSONObject(prefs.getString("contacts", "{}") ?: "{}")
            for (k in j.keys()) {
                val o = j.getJSONObject(k)
                contacts[k.toInt()] = Contact(o.optString("name"), o.optLong("heard"))
            }
        }
    }

    val tag: String get() = tagOf(id)

    fun rename(value: String) {
        name = value.trim().take(MAX_NAME)
        prefs.edit().putString("name", name).apply()
    }

    /** A frame from [id] arrived; keep the name we have, refresh the time. */
    fun heard(id: Int, name: String? = null) {
        val old = contacts[id]
        contacts[id] = Contact(name ?: old?.name ?: "", System.currentTimeMillis())
        persist()
    }

    fun nameFor(id: Int?): String? = id?.let { contacts[it]?.name?.takeIf { n -> n.isNotEmpty() } ?: tagOf(it) }

    private fun persist() {
        val j = JSONObject()
        for ((k, v) in contacts) j.put(k.toString(), JSONObject().put("name", v.name).put("heard", v.lastHeard))
        prefs.edit().putString("contacts", j.toString()).apply()
    }

    companion object {
        const val MAX_NAME = 24
        private const val ALPHABET = "23456789ACEFHKMP"   // 16 symbols, no look-alikes

        fun tagOf(id: Int): String = "#" + (3 downTo 0).joinToString("") { ALPHABET[(id shr (it * 4)) and 15].toString() }

        private fun newId(): Int {
            val r = SecureRandom()
            var v = 0
            while (v == 0) v = r.nextInt(0x10000)
            return v
        }
    }
}

/**
 * Framing for text and hellos. A tag byte that cannot begin UTF-8 text marks our frames;
 * anything else is shown as plain text from an unknown sender (ggwave's other apps).
 *
 *   TEXT   A1 | idHi | idLo | utf-8
 *   HELLO  A2 | idHi | idLo | name (utf-8, at most 24 bytes)
 */
object Wire {
    private const val TAG_TEXT = 0xA1
    private const val TAG_HELLO = 0xA2

    sealed class Parsed {
        class Text(val id: Int, val text: String) : Parsed()
        class Hello(val id: Int, val name: String) : Parsed()
        class Plain(val text: String) : Parsed()
    }

    fun text(id: Int, text: String): ByteArray = byteArrayOf(TAG_TEXT.toByte(), (id shr 8).toByte(), id.toByte()) + text.toByteArray(Charsets.UTF_8)

    fun hello(id: Int, name: String): ByteArray {
        var n = name.toByteArray(Charsets.UTF_8)
        if (n.size > IdentityStore.MAX_NAME) n = n.copyOf(IdentityStore.MAX_NAME)
        return byteArrayOf(TAG_HELLO.toByte(), (id shr 8).toByte(), id.toByte()) + n
    }

    fun parse(p: ByteArray): Parsed {
        if (p.size >= 3) {
            val id = ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
            when (p[0].toInt() and 0xFF) {
                TAG_TEXT -> return Parsed.Text(id, String(p, 3, p.size - 3, Charsets.UTF_8))
                TAG_HELLO -> return Parsed.Hello(id, String(p, 3, p.size - 3, Charsets.UTF_8).trim())
            }
        }
        return Parsed.Plain(String(p, Charsets.UTF_8))
    }
}
