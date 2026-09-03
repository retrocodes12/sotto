package com.sotto

import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Private messages: X25519 key agreement (RFC 7748, written out because Android below 13
 * has no XDH provider), a fixed-info HMAC derivation, and AES-256-GCM with a 96-bit tag.
 * Checked against the RFC test vectors; see tools/ for the reference implementation.
 */
object Crypto {
    // valueOf(2), not BigInteger.TWO: that constant only exists from Android 12 and crashed Android 9 at launch
    private val P = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    fun newPrivateKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun publicKey(priv: ByteArray): ByteArray = x25519(priv, BASE)

    /** Null for a low-order point (an all-zero secret anyone could compute). */
    fun sharedSecret(priv: ByteArray, theirPub: ByteArray): ByteArray? =
        x25519(priv, theirPub).takeIf { s -> s.any { it.toInt() != 0 } }

    /** Eight hex characters of SHA-256 of a public key, for reading out loud. */
    fun fingerprint(pub: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(pub)
        val h = toHex(d.copyOf(4)).uppercase()
        return h.substring(0, 4) + " " + h.substring(4)
    }

    private fun le(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    fun x25519(kBytes: ByteArray, uBytes: ByteArray): ByteArray {
        val kc = kBytes.copyOf(32); kc[0] = (kc[0].toInt() and 248).toByte(); kc[31] = ((kc[31].toInt() and 127) or 64).toByte()
        val uc = uBytes.copyOf(32); uc[31] = (uc[31].toInt() and 127).toByte()
        val k = le(kc)
        val x1 = le(uc)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO; var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = k.shiftRight(t).and(BigInteger.ONE).toInt()
            swap = swap xor kt
            if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = kt
            val a = x2.add(z2).mod(P); val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P); val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P); val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P); val cb = c.multiply(b).mod(P)
            val s = da.add(cb); x3 = s.multiply(s).mod(P)
            val df = da.subtract(cb); z3 = x1.multiply(df.multiply(df)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
        val r = x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P)
        val out = r.toByteArray().reversedArray().copyOf(32)   // little-endian, padded
        return out
    }

    /** Symmetric key for a pair: HMAC-SHA256("sotto-private", shared). */
    fun pairKey(shared: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("sotto-private".toByteArray(), "HmacSHA256"))
        return mac.doFinal(shared)
    }

    /** 12-byte nonce from the pair and a per-direction counter; never repeats while the counter grows. */
    private fun nonce(from: Int, to: Int, counter: Int) = byteArrayOf(
        (from shr 8).toByte(), from.toByte(), (to shr 8).toByte(), to.toByte(),
        (counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte(),
        0, 0, 0, 0,
    )

    fun encrypt(key: ByteArray, from: Int, to: Int, counter: Int, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(96, nonce(from, to, counter)))
        return c.doFinal(plain)
    }

    fun decrypt(key: ByteArray, from: Int, to: Int, counter: Int, sealed: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(96, nonce(from, to, counter)))
        c.doFinal(sealed)
    } catch (e: Exception) {
        null
    }

    /** RFC 7748 vector 1; false means the port is broken and private chat must not be offered. */
    fun selfTest(): Boolean {
        val k = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        return x25519(k, u).contentEquals(hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"))
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
