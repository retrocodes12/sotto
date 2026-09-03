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

    class Contact(val name: String, val lastHeard: Long, val lastDirect: Long = 0L)

    val contacts = mutableStateMapOf<Int, Contact>()

    init {
        runCatching {
            val j = JSONObject(prefs.getString("contacts", "{}") ?: "{}")
            for (k in j.keys()) {
                val o = j.getJSONObject(k)
                contacts[k.toInt()] = Contact(o.optString("name"), o.optLong("heard"), o.optLong("direct"))
            }
        }
    }

    val tag: String get() = tagOf(id)

    /** Next per-install message number (0..255), so relays and receivers can tell copies apart. */
    fun nextSeq(): Int {
        val n = (prefs.getInt("seq", 0) + 1) and 0xFF
        prefs.edit().putInt("seq", n).apply()
        return n
    }

    fun rename(value: String) {
        name = value.trim().take(MAX_NAME)
        prefs.edit().putString("name", name).apply()
    }

    /** A frame from [id] arrived; keep the name we have, refresh the time. [direct] means this phone heard it itself, not through a relay. */
    fun heard(id: Int, name: String? = null, direct: Boolean = true) {
        val old = contacts[id]
        val now = System.currentTimeMillis()
        contacts[id] = Contact(name ?: old?.name ?: "", now, if (direct) now else old?.lastDirect ?: 0L)
        persist()
    }

    /** Ids heard directly within [withinMs]. */
    fun nearby(withinMs: Long, now: Long = System.currentTimeMillis()): List<Int> =
        contacts.entries.filter { now - it.value.lastDirect <= withinMs }.map { it.key }

    /** Ids heard only through a relay within [withinMs]. */
    fun farther(withinMs: Long, now: Long = System.currentTimeMillis()): List<Int> =
        contacts.entries.filter { now - it.value.lastHeard <= withinMs && now - it.value.lastDirect > withinMs }.map { it.key }

    fun nameFor(id: Int?): String? = id?.let { contacts[it]?.name?.takeIf { n -> n.isNotEmpty() } ?: tagOf(it) }

    private fun persist() {
        val j = JSONObject()
        for ((k, v) in contacts) j.put(k.toString(), JSONObject().put("name", v.name).put("heard", v.lastHeard).put("direct", v.lastDirect))
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
 *   TEXT   A1 | idHi idLo | seq | hopsLeft | utf-8                  as sent by its author
 *   RELAY  A4 | idHi idLo | seq | hopsLeft | viaHi viaLo | utf-8    repeated by another phone
 *   HELLO  A2 | idHi idLo | name (utf-8, at most 24 bytes)
 *   HERE   A9 | idHi idLo                                     presence, when a phone has been quiet
 */
object Wire {
    private const val TAG_TEXT = 0xA1
    private const val TAG_HELLO = 0xA2
    private const val TAG_RELAY = 0xA4
    private const val TAG_HERE = 0xA9

    sealed class Parsed {
        class Text(val id: Int, val seq: Int, val hops: Int, val text: String, val via: Int?) : Parsed()
        class Hello(val id: Int, val name: String) : Parsed()
        class Here(val id: Int) : Parsed()
        class Plain(val text: String) : Parsed()
    }

    fun here(id: Int): ByteArray = byteArrayOf(TAG_HERE.toByte(), (id shr 8).toByte(), id.toByte())

    fun text(id: Int, seq: Int, hops: Int, text: String): ByteArray =
        byteArrayOf(TAG_TEXT.toByte(), (id shr 8).toByte(), id.toByte(), seq.toByte(), hops.toByte()) + text.toByteArray(Charsets.UTF_8)

    fun relay(id: Int, seq: Int, hops: Int, via: Int, text: String): ByteArray =
        byteArrayOf(TAG_RELAY.toByte(), (id shr 8).toByte(), id.toByte(), seq.toByte(), hops.toByte(), (via shr 8).toByte(), via.toByte()) +
            text.toByteArray(Charsets.UTF_8)

    fun hello(id: Int, name: String): ByteArray {
        var n = name.toByteArray(Charsets.UTF_8)
        if (n.size > IdentityStore.MAX_NAME) n = n.copyOf(IdentityStore.MAX_NAME)
        return byteArrayOf(TAG_HELLO.toByte(), (id shr 8).toByte(), id.toByte()) + n
    }

    private fun u16(p: ByteArray, at: Int) = ((p[at].toInt() and 0xFF) shl 8) or (p[at + 1].toInt() and 0xFF)

    fun parse(p: ByteArray): Parsed {
        if (p.size >= 3) {
            val id = u16(p, 1)
            when (p[0].toInt() and 0xFF) {
                TAG_TEXT -> if (p.size >= 5) return Parsed.Text(id, p[3].toInt() and 0xFF, p[4].toInt() and 0xFF, String(p, 5, p.size - 5, Charsets.UTF_8), null)
                TAG_RELAY -> if (p.size >= 7) return Parsed.Text(id, p[3].toInt() and 0xFF, p[4].toInt() and 0xFF, String(p, 7, p.size - 7, Charsets.UTF_8), u16(p, 5))
                TAG_HELLO -> return Parsed.Hello(id, String(p, 3, p.size - 3, Charsets.UTF_8).trim())
                TAG_HERE -> return Parsed.Here(id)
            }
        }
        return Parsed.Plain(String(p, Charsets.UTF_8))
    }
}
