package com.sotto

import android.content.Context
import android.util.Base64
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
/**
 * The anti-replay window, as arithmetic and nothing else, so it can be tested on a laptop.
 *
 * A private message may arrive by sound in a second or be carried in somebody's pocket for two
 * days, so genuine traffic turns up out of order and "must be higher than the last one" would
 * throw the carried half away. This keeps the highest counter seen and a bitmap of the [SIZE]
 * below it: anything not already seen is fresh, anything seen is not, and anything older than
 * the window cannot be judged and is refused.
 */
object ReplayWindow {
    const val SIZE = 64

    fun isFresh(high: Int, mask: Long, counter: Int): Boolean {
        if (counter <= 0) return false
        if (counter > high) return true
        val back = high - counter
        return back < SIZE && (mask and (1L shl back)) == 0L
    }

    /** The window after accepting [counter]. Call only for one [isFresh] said yes to. */
    fun accept(high: Int, mask: Long, counter: Int): Pair<Int, Long> {
        if (counter <= 0) return high to mask
        if (counter > high) {
            val shift = counter - high
            return counter to (if (shift >= SIZE) 1L else (mask shl shift) or 1L)
        }
        val back = high - counter
        if (back >= SIZE) return high to mask
        return high to (mask or (1L shl back))
    }
}

class IdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("sotto", Context.MODE_PRIVATE)

    /** This install's X25519 keypair, made once. Everything the phone is called derives from it. */
    val privateKey: ByteArray = prefs.getString("priv", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        ?: Crypto.newPrivateKey().also { prefs.edit().putString("priv", Base64.encodeToString(it, Base64.NO_WRAP)).apply() }
    /**
     * Cached on disk. Deriving it is a 255-step Montgomery ladder in BigInteger, and it is
     * needed at construction to work out this phone's id, so on every single launch it ran on
     * the main thread before anything could be drawn. It cannot change, so it is computed once
     * in the life of the install.
     */
    val publicKey: ByteArray = prefs.getString("pub", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        ?.takeIf { it.size == 32 }
        ?: Crypto.publicKey(privateKey).also { prefs.edit().putString("pub", Base64.encodeToString(it, Base64.NO_WRAP)).apply() }

    /** The id the carry network uses: 64 bits of the public key's hash. */
    val id64: Long = com.sotto.carry.Ids.id64(publicKey)

    /** The id the sound frames use: the low 16 bits of the same, so both name one phone. */
    val id: Int = com.sotto.carry.Ids.id16(id64).let { if (it == 0) 1 else it }

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


    /** Symmetric keys per peer, derived once their public key has arrived by sound. */
    val peerKeys = mutableStateMapOf<Int, ByteArray>()
    /** The public keys themselves, for fingerprints. */
    val peerPubs = mutableStateMapOf<Int, ByteArray>()

    init {
        runCatching {
            val j = JSONObject(prefs.getString("peerkeys", "{}") ?: "{}")
            for (k in j.keys()) peerKeys[k.toInt()] = Base64.decode(j.getString(k), Base64.NO_WRAP)
            val pj = JSONObject(prefs.getString("peerpubs", "{}") ?: "{}")
            for (k in pj.keys()) peerPubs[k.toInt()] = Base64.decode(pj.getString(k), Base64.NO_WRAP)
        }
    }

    /** Derives the pair key. False for a public key that yields no usable secret. */
    fun learnPublicKey(id: Int, pub: ByteArray): Boolean {
        val shared = Crypto.sharedSecret(privateKey, pub) ?: return false
        peerKeys[id] = Crypto.pairKey(shared)
        peerPubs[id] = pub
        val j = JSONObject()
        for ((k, v) in peerKeys) j.put(k.toString(), Base64.encodeToString(v, Base64.NO_WRAP))
        val pj = JSONObject()
        for ((k, v) in peerPubs) pj.put(k.toString(), Base64.encodeToString(v, Base64.NO_WRAP))
        // A new key means a new pair key, so the counter space starts again -- window and all.
        prefs.edit().putString("peerkeys", j.toString()).putString("peerpubs", pj.toString())
            .putInt("rx$id", 0).putLong("rxm$id", 0L).putInt("ctr$id", 0).apply()
        return true
    }

    /** The 64-bit id of a phone we know, when we have seen it advertise or read its profile. */
    val wideIds = mutableStateMapOf<Int, Long>()

    fun learnWideId(id64: Long) {
        val id16 = com.sotto.carry.Ids.id16(id64)
        if (wideIds[id16] != id64) {
            wideIds[id16] = id64
            prefs.edit().putLong("wide$id16", id64).apply()
        }
    }

    fun wideId(id16: Int): Long? = wideIds[id16] ?: prefs.getLong("wide$id16", 0L).takeIf { it != 0L }?.also { wideIds[id16] = it }

    /** True if [pub] is the key we already hold for [id]. */
    fun samePublicKey(id: Int, pub: ByteArray): Boolean = peerPubs[id]?.contentEquals(pub) == true

    /**
     * Anti-replay across both routes at once.
     *
     * A plain "higher than the last one" rule cannot work here: a message that travelled by
     * sound arrives in seconds and one that was carried arrives hours later, so genuine traffic
     * turns up out of order and the older one would look like a replay. This is the usual
     * window instead -- the highest counter seen, plus a bitmap of the sixty-four below it --
     * so anything that has genuinely not been seen is accepted, and nothing is accepted twice.
     */
    fun isFreshCounter(peer: Int, counter: Int): Boolean =
        ReplayWindow.isFresh(prefs.getInt("rx$peer", 0), prefs.getLong("rxm$peer", 0L), counter)

    /** Records [counter] as seen. Call only once the message has actually decrypted. */
    fun recordCounter(peer: Int, counter: Int) {
        val (high, mask) = ReplayWindow.accept(prefs.getInt("rx$peer", 0), prefs.getLong("rxm$peer", 0L), counter)
        prefs.edit().putInt("rx$peer", high).putLong("rxm$peer", mask).apply()
    }

    /**
     * Next send counter towards [peer]. Every private message to that person takes its next
     * value from here, whether it goes by sound or is handed to the carry network, because
     * they end up in the same AES-GCM nonce under the same key: two counters would collide,
     * and a repeated nonce does not merely leak one message, it hands over the key that
     * authenticates all of them.
     *
     * Committed, not applied. apply() returns before the write reaches disk, so a process
     * killed just after sending could hand out the same counter twice.
     */
    fun nextCounter(peer: Int): Int {
        val key = "ctr$peer"
        val n = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, n).commit()
        return n
    }

    /**
     * Next message number for this install. The live sound frames carry its low byte, which is
     * all they can afford, and the carried bundles carry all 32 bits; both therefore name the
     * same message, so one that arrives twice by two routes is shown once.
     */
    fun nextSeq(): Int {
        val n = prefs.getInt("seq", 0) + 1
        prefs.edit().putInt("seq", n).apply()
        return n
    }

    /**
     * Settings that outlive the process. Every one of these was state in the ViewModel and
     * nothing else, so a phone that had been told not to carry other people's messages, or not
     * to use Bluetooth, or not to relay, quietly went back to doing all three the next time
     * Android restarted the process. A choice about what your phone broadcasts has to stick.
     */
    fun flag(name: String, fallback: Boolean): Boolean = prefs.getBoolean("set.$name", fallback)
    fun setFlag(name: String, value: Boolean) { prefs.edit().putBoolean("set.$name", value).apply() }
    fun number(name: String, fallback: Int): Int = prefs.getInt("set.$name", fallback)
    fun setNumber(name: String, value: Int) { prefs.edit().putInt("set.$name", value).apply() }

    fun rename(value: String) {
        val v = Wire.cleanName(value)
        if (v.isEmpty()) return
        name = v
        prefs.edit().putString("name", name).apply()
    }

    /** A frame from [id] arrived; keep the name we have, refresh the time. [direct] means this phone heard it itself, not through a relay. */
    fun heard(id: Int, name: String? = null, direct: Boolean = true) {
        val old = contacts[id]
        val now = System.currentTimeMillis()
        contacts[id] = Contact(name ?: old?.name ?: "", now, if (direct) now else old?.lastDirect ?: 0L)
        // Anyone in range can mint ids, and this map used to grow on every frame and be written
        // out whole each time. Keep the ones heard most recently and write at a human pace;
        // something genuinely new is worth the write immediately.
        if (contacts.size > MAX_CONTACTS) {
            contacts.entries.sortedBy { it.value.lastHeard }
                .take(contacts.size - MAX_CONTACTS)
                .forEach { contacts.remove(it.key) }
        }
        persist(force = old == null || (name != null && name != old.name))
    }

    /** Ids heard directly within [withinMs]. */
    fun nearby(withinMs: Long, now: Long = System.currentTimeMillis()): List<Int> =
        contacts.entries.filter { now - it.value.lastDirect <= withinMs }.map { it.key }

    /** Ids heard only through a relay within [withinMs]. */
    fun farther(withinMs: Long, now: Long = System.currentTimeMillis()): List<Int> =
        contacts.entries.filter { now - it.value.lastHeard <= withinMs && now - it.value.lastDirect > withinMs }.map { it.key }

    fun nameFor(id: Int?): String? = id?.let { contacts[it]?.name?.takeIf { n -> n.isNotEmpty() } ?: tagOf(it) }

    private var lastPersist = 0L

    private fun persist(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastPersist < PERSIST_EVERY_MS) return
        lastPersist = now
        val j = JSONObject()
        for ((k, v) in contacts) j.put(k.toString(), JSONObject().put("name", v.name).put("heard", v.lastHeard).put("direct", v.lastDirect))
        prefs.edit().putString("contacts", j.toString()).apply()
    }

    companion object {
        const val MAX_NAME = 24
        /** Phones remembered. Beyond this the ones heard longest ago are forgotten. */
        const val MAX_CONTACTS = 500
        private const val PERSIST_EVERY_MS = 5_000L
        private const val ALPHABET = "23456789ACEFHKMP"   // 16 symbols, no look-alikes

        fun tagOf(id: Int): String = "#" + (3 downTo 0).joinToString("") { ALPHABET[(id shr (it * 4)) and 15].toString() }

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
 *   KEY    AB | fromHi fromLo | toHi toLo | x25519 public key (32)
 *   PRIV   AC | fromHi fromLo | toHi toLo | seq | hopsLeft | counter (4) | AES-GCM sealed text
 *   ACK    AD | fromHi fromLo | toHi toLo | seq | hopsLeft            "got your message seq"
 *   PROBE  AE | fromHi fromLo | seq                                    reach test, answered by all
 *   REPLY  AF | fromHi fromLo | toHi toLo | seq | snr (dB, 0..255)     how loudly the probe arrived
 *   CARD   B0 | fromHi fromLo | seq | kind | fields joined by 0x1F      link, Wi-Fi or contact, at arm's length
 */
object Wire {
    private const val TAG_TEXT = 0xA1
    private const val TAG_HELLO = 0xA2
    private const val TAG_RELAY = 0xA4
    private const val TAG_HERE = 0xA9
    private const val TAG_KEY = 0xAB
    private const val TAG_PRIVATE = 0xAC
    private const val TAG_ACK = 0xAD
    private const val TAG_PROBE = 0xAE
    private const val TAG_PROBE_REPLY = 0xAF
    private const val TAG_CARD = 0xB0
    const val CARD_LINK = 1
    const val CARD_WIFI = 2
    const val CARD_CONTACT = 3

    sealed class Parsed {
        class Text(val id: Int, val seq: Int, val hops: Int, val text: String, val via: Int?) : Parsed()
        class Hello(val id: Int, val name: String) : Parsed()
        class Here(val id: Int) : Parsed()
        class Key(val from: Int, val to: Int, val publicKey: ByteArray) : Parsed()
        class Private(val from: Int, val to: Int, val seq: Int, val hops: Int, val counter: Int, val sealed: ByteArray, val raw: ByteArray) : Parsed()
        class Ack(val from: Int, val to: Int, val seq: Int, val hops: Int, val raw: ByteArray) : Parsed()
        class Probe(val from: Int, val seq: Int) : Parsed()
        class ProbeReply(val from: Int, val to: Int, val seq: Int, val snrDb: Int) : Parsed()
        class Card(val from: Int, val seq: Int, val kind: Int, val fields: List<String>) : Parsed()
        class Plain(val text: String) : Parsed()
    }

    fun key(from: Int, to: Int, publicKey: ByteArray): ByteArray =
        byteArrayOf(TAG_KEY.toByte(), (from shr 8).toByte(), from.toByte(), (to shr 8).toByte(), to.toByte()) + publicKey

    fun private(from: Int, to: Int, seq: Int, hops: Int, counter: Int, sealed: ByteArray): ByteArray =
        byteArrayOf(
            TAG_PRIVATE.toByte(), (from shr 8).toByte(), from.toByte(), (to shr 8).toByte(), to.toByte(), seq.toByte(), hops.toByte(),
            (counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte(),
        ) + sealed

    /**
     * One hop fewer, for a relay repeating a frame. Clamped at zero: the hop byte is read back
     * unsigned, so decrementing a spent frame would wrap it to 255 and let it circulate forever.
     */
    private fun oneHopFewer(raw: ByteArray): ByteArray = raw.copyOf().also {
        val left = it[6].toInt() and 0xFF
        it[6] = (if (left > 0) left - 1 else 0).toByte()
    }

    /** The same private frame with one hop fewer, for relays that cannot read it. */
    fun relayPrivate(raw: ByteArray): ByteArray = oneHopFewer(raw)

    fun ack(from: Int, to: Int, seq: Int, hops: Int): ByteArray =
        byteArrayOf(TAG_ACK.toByte(), (from shr 8).toByte(), from.toByte(), (to shr 8).toByte(), to.toByte(), seq.toByte(), hops.toByte())

    fun relayAck(raw: ByteArray): ByteArray = oneHopFewer(raw)

    fun probe(from: Int, seq: Int): ByteArray = byteArrayOf(TAG_PROBE.toByte(), (from shr 8).toByte(), from.toByte(), seq.toByte())

    fun card(from: Int, seq: Int, kind: Int, fields: List<String>): ByteArray =
        byteArrayOf(TAG_CARD.toByte(), (from shr 8).toByte(), from.toByte(), seq.toByte(), kind.toByte()) +
            fields.joinToString("\u001F").toByteArray(Charsets.UTF_8)

    fun probeReply(from: Int, to: Int, seq: Int, snrDb: Int): ByteArray =
        byteArrayOf(TAG_PROBE_REPLY.toByte(), (from shr 8).toByte(), from.toByte(), (to shr 8).toByte(), to.toByte(), seq.toByte(), snrDb.coerceIn(0, 255).toByte())

    fun here(id: Int): ByteArray = byteArrayOf(TAG_HERE.toByte(), (id shr 8).toByte(), id.toByte())

    fun text(id: Int, seq: Int, hops: Int, text: String): ByteArray =
        byteArrayOf(TAG_TEXT.toByte(), (id shr 8).toByte(), id.toByte(), seq.toByte(), hops.toByte()) + text.toByteArray(Charsets.UTF_8)

    fun relay(id: Int, seq: Int, hops: Int, via: Int, text: String): ByteArray =
        byteArrayOf(TAG_RELAY.toByte(), (id shr 8).toByte(), id.toByte(), seq.toByte(), hops.toByte(), (via shr 8).toByte(), via.toByte()) +
            text.toByteArray(Charsets.UTF_8)

    fun hello(id: Int, name: String): ByteArray =
        byteArrayOf(TAG_HELLO.toByte(), (id shr 8).toByte(), id.toByte()) + cleanName(name).toByteArray(Charsets.UTF_8)

    /**
     * A name is written by whoever is in the room, and it is drawn in a list beside everyone
     * else's. Strip the control characters and the bidirectional overrides, which can reorder
     * the text around them on screen, then hold it to what the wire allows -- counted in bytes,
     * because that is what travels, and cut on a character boundary rather than through one.
     *
     * This is the single definition of a legal name: what is typed, what is sent, and what
     * arrives all pass through here, so the phone shows the same thing at both ends.
     */
    fun cleanName(raw: String): String {
        val clean = raw
            .filter { it.code >= 0x20 && it.code != 0x7F && it.code !in 0x202A..0x202E && it.code !in 0x2066..0x2069 }
            .trim()
        val bytes = clean.toByteArray(Charsets.UTF_8)
        if (bytes.size <= IdentityStore.MAX_NAME) return clean
        var end = IdentityStore.MAX_NAME
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8).trim()
    }

    private fun u16(p: ByteArray, at: Int) = ((p[at].toInt() and 0xFF) shl 8) or (p[at + 1].toInt() and 0xFF)

    fun parse(p: ByteArray): Parsed {
        if (p.size >= 3) {
            val id = u16(p, 1)
            when (p[0].toInt() and 0xFF) {
                TAG_TEXT -> if (p.size >= 5) return Parsed.Text(id, p[3].toInt() and 0xFF, p[4].toInt() and 0xFF, String(p, 5, p.size - 5, Charsets.UTF_8), null)
                TAG_RELAY -> if (p.size >= 7) return Parsed.Text(id, p[3].toInt() and 0xFF, p[4].toInt() and 0xFF, String(p, 7, p.size - 7, Charsets.UTF_8), u16(p, 5))
                TAG_HELLO -> return Parsed.Hello(id, cleanName(String(p, 3, p.size - 3, Charsets.UTF_8)))
                TAG_HERE -> return Parsed.Here(id)
                TAG_KEY -> if (p.size == 5 + 32) return Parsed.Key(id, u16(p, 3), p.copyOfRange(5, 37))
                TAG_ACK -> if (p.size == 7) return Parsed.Ack(id, u16(p, 3), p[5].toInt() and 0xFF, p[6].toInt() and 0xFF, p)
                TAG_PROBE -> if (p.size == 4) return Parsed.Probe(id, p[3].toInt() and 0xFF)
                TAG_CARD -> if (p.size >= 5) return Parsed.Card(id, p[3].toInt() and 0xFF, p[4].toInt() and 0xFF, String(p, 5, p.size - 5, Charsets.UTF_8).split('\u001F'))
                TAG_PROBE_REPLY -> if (p.size == 7) return Parsed.ProbeReply(id, u16(p, 3), p[5].toInt() and 0xFF, p[6].toInt() and 0xFF)
                TAG_PRIVATE -> if (p.size >= 11) return Parsed.Private(
                    id, u16(p, 3), p[5].toInt() and 0xFF, p[6].toInt() and 0xFF,
                    ((p[7].toInt() and 0xFF) shl 24) or ((p[8].toInt() and 0xFF) shl 16) or ((p[9].toInt() and 0xFF) shl 8) or (p[10].toInt() and 0xFF),
                    p.copyOfRange(11, p.size), p,
                )
            }
        }
        return Parsed.Plain(String(p, Charsets.UTF_8))
    }
}
