package com.sotto.carry

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ln
import kotlin.math.roundToInt

/** Identities as the carry network sees them: 64 bits from the phone's public key. */
object Ids {
    fun id64(publicKey: ByteArray): Long =
        ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(publicKey), 0, 8).long

    /** The 16-bit id the sound frames use, so both worlds name the same phone. */
    fun id16(id64: Long): Int = (id64 and 0xFFFF).toInt()
}

/** (origin, sequence): unique for the life of the network. Twelve bytes on the wire. */
data class BundleKey(val origin: Long, val seq: Int) {
    fun encode(): ByteArray = ByteBuffer.allocate(12).putLong(origin).putInt(seq).array()

    companion object {
        const val SIZE = 12
        fun decode(b: ByteArray, at: Int = 0): BundleKey = ByteBuffer.wrap(b, at, 12).let { BundleKey(it.long, it.int) }
    }
}

/**
 * One message as it is carried: who wrote it, its lifetime, whom it is for, how far it has
 * come, and how many copies it may still spawn (0 means spread to everyone).
 *
 * Wire: kind 1 | origin 8 | seq 4 | created 4 (epoch seconds) | ttl 2 (minutes) | dest 8 |
 *       hops 1 | copies 1 | length 2 | payload
 */
class Bundle(
    val origin: Long,
    val seq: Int,
    val kind: Int,
    val created: Long,
    val ttlMinutes: Int,
    val dest: Long,
    val hops: Int,
    val copies: Int,
    val payload: ByteArray,
) {
    val key: BundleKey get() = BundleKey(origin, seq)
    val expiresAt: Long get() = created + ttlMinutes * 60L
    val floods: Boolean get() = copies == 0

    fun withHops(h: Int, c: Int = copies) = Bundle(origin, seq, kind, created, ttlMinutes, dest, h, c, payload)

    fun encode(): ByteArray = ByteBuffer.allocate(HEADER + payload.size)
        .also { require(payload.size <= MAX_PAYLOAD) { "payload ${payload.size} B exceeds the length field" } }
        .put(kind.toByte()).putLong(origin).putInt(seq).putInt(created.toInt()).putShort(ttlMinutes.toShort())
        .putLong(dest).put(hops.toByte()).put(copies.toByte()).putShort(payload.size.toShort()).put(payload).array()

    companion object {
        const val HEADER = 31
        const val MAX_PAYLOAD = 10_000
        const val KIND_ROOM = 1
        const val KIND_PRIVATE = 2
        const val KIND_RECEIPT = 3
        const val KIND_PROFILE = 4
        const val KIND_PRIVATE_PHOTO = 5

        /** The longest a bundle of each kind may live, whatever its header claims. */
        fun maxTtlMinutes(kind: Int): Int = when (kind) {
            KIND_ROOM -> 24 * 60
            KIND_PROFILE -> 7 * 24 * 60
            else -> 72 * 60          // private, photo, receipt
        }

        /** The most copies a bundle of each kind may claim. Zero means flood, and only a room
         *  message or a profile is allowed to: a private one that floods is a privacy leak. */
        fun legalCopies(kind: Int, claimed: Int): Int = when (kind) {
            KIND_ROOM, KIND_PROFILE, KIND_RECEIPT -> 0
            else -> claimed.coerceIn(1, MAX_COPIES)
        }

        const val MAX_COPIES = 16

        fun decode(b: ByteArray, at: Int = 0): Bundle? {
            if (b.size - at < HEADER) return null
            val bb = ByteBuffer.wrap(b, at, b.size - at)
            val kind = bb.get().toInt() and 0xFF
            val origin = bb.long; val seq = bb.int
            val created = bb.int.toLong() and 0xFFFFFFFFL
            val ttl = bb.short.toInt() and 0xFFFF
            val dest = bb.long
            val hops = bb.get().toInt() and 0xFF
            val copies = bb.get().toInt() and 0xFF
            val len = bb.short.toInt() and 0xFFFF
            if (len > MAX_PAYLOAD || bb.remaining() < len) return null
            val payload = ByteArray(len).also { bb.get(it) }
            return Bundle(origin, seq, kind, created, ttl, dest, hops, copies, payload)
        }

        /**
         * A private bundle's payload: the AES-GCM nonce counter, then the sealed text. The
         * counter has to travel with the message. The bundle's own sequence identifies the
         * message, not the conversation, and the nonce is built from the pair's counter -- the
         * one the sound path also draws from, so that one key never sees one nonce twice.
         */
        fun sealedWithCounter(counter: Int, sealed: ByteArray): ByteArray =
            ByteBuffer.allocate(4 + sealed.size).putInt(counter).put(sealed).array()

        fun counterOf(payload: ByteArray): Int? =
            if (payload.size < 5) null else ByteBuffer.wrap(payload, 0, 4).int

        fun sealedOf(payload: ByteArray): ByteArray = payload.copyOfRange(4, payload.size)

        /** Receipt payload: the delivered key, the hops it arrived with, minutes it took. */
        fun receiptPayload(target: BundleKey, hops: Int, minutes: Int): ByteArray =
            ByteBuffer.allocate(15).put(target.encode()).put(hops.toByte()).putShort(minutes.coerceIn(0, 65535).toShort()).array()

        fun parseReceipt(payload: ByteArray): Triple<BundleKey, Int, Int>? {
            if (payload.size < 15) return null
            val bb = ByteBuffer.wrap(payload)
            val key = BundleKey.decode(payload, 0); bb.position(12)
            return Triple(key, bb.get().toInt() and 0xFF, bb.short.toInt() and 0xFFFF)
        }

        /** Profile payload: name length, name, 32-byte X25519 public key. */
        fun profilePayload(name: String, publicKey: ByteArray): ByteArray {
            require(publicKey.size >= 32) { "a profile carries a 32-byte public key" }
            val n = com.sotto.Wire.cleanName(name).toByteArray(Charsets.UTF_8)
            return ByteArrayOutputStream().apply { write(n.size); write(n); write(publicKey, 0, 32) }.toByteArray()
        }

        /** The name comes back through the same sieve: this one floods the network for a week. */
        fun parseProfile(payload: ByteArray): Pair<String, ByteArray>? {
            if (payload.isEmpty()) return null
            val n = payload[0].toInt() and 0xFF
            if (payload.size < 1 + n + 32) return null
            return com.sotto.Wire.cleanName(String(payload, 1, n, Charsets.UTF_8)) to payload.copyOfRange(1 + n, 1 + n + 32)
        }
    }
}

/**
 * A rough count of the distinct phones a bundle has visited: 256 bits, each phone sets one,
 * and merging is a bitwise or. Linear counting gives about ±10% up to a few hundred phones.
 */
class Sketch(val bits: ByteArray = ByteArray(SIZE)) {
    fun mark(phone: Long, key: BundleKey) {
        val h = (phone * 0x9E3779B97F4A7C15uL.toLong()) xor (key.origin * 31 + key.seq)
        val bit = ((h ushr 32) xor h).toInt() and 0xFF
        bits[bit ushr 3] = (bits[bit ushr 3].toInt() or (1 shl (bit and 7))).toByte()
    }

    fun merge(other: Sketch) { for (i in bits.indices) bits[i] = (bits[i].toInt() or other.bits[i].toInt()).toByte() }

    fun estimate(): Int {
        var zeros = 0
        for (b in bits) zeros += 8 - Integer.bitCount(b.toInt() and 0xFF)
        if (zeros == 0) return BITS * 6
        return (BITS * ln(BITS.toDouble() / zeros)).roundToInt()
    }

    fun copy() = Sketch(bits.copyOf())

    companion object {
        const val SIZE = 32
        private const val BITS = SIZE * 8
    }
}
