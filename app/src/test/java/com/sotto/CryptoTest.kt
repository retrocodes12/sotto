package com.sotto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The X25519 in Crypto.kt is written out by hand, because Android below 13 ships no XDH
 * provider. Hand-written curve arithmetic is exactly the kind of code that is silently
 * wrong, so it is pinned here against the RFC 7748 vectors rather than against itself.
 */
class CryptoTest {

    private fun hex(s: String) = Crypto.hex(s)

    // RFC 7748 section 5.2, vector 1
    @Test fun rfc7748Vector1() {
        assertArrayEquals(
            hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"),
            Crypto.x25519(
                hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
            ),
        )
    }

    // RFC 7748 section 5.2, vector 2
    @Test fun rfc7748Vector2() {
        assertArrayEquals(
            hex("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"),
            Crypto.x25519(
                hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"),
            ),
        )
    }

    /** RFC 7748 section 5.2 iterated test, one round. The 1000-round vector is too slow for BigInteger. */
    @Test fun rfc7748IteratedOnce() {
        val k = hex("0900000000000000000000000000000000000000000000000000000000000000")
        assertArrayEquals(hex("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079"), Crypto.x25519(k, k))
    }

    // RFC 7748 section 6.1: the whole Diffie-Hellman, including public key derivation
    @Test fun rfc7748DiffieHellman() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePub = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        assertArrayEquals("Alice's public key", alicePub, Crypto.publicKey(alicePriv))
        assertArrayEquals("Bob's public key", bobPub, Crypto.publicKey(bobPriv))
        assertArrayEquals("Alice's view", shared, Crypto.sharedSecret(alicePriv, bobPub))
        assertArrayEquals("Bob's view", shared, Crypto.sharedSecret(bobPriv, alicePub))
    }

    @Test fun selfTestPasses() = assertTrue(Crypto.selfTest())

    /** Two fresh installs must agree, and the output must be 32 bytes every time. */
    @Test fun freshKeypairsAgree() {
        repeat(8) {
            val a = Crypto.newPrivateKey()
            val b = Crypto.newPrivateKey()
            val ab = Crypto.sharedSecret(a, Crypto.publicKey(b))
            val ba = Crypto.sharedSecret(b, Crypto.publicKey(a))
            assertNotNull(ab)
            assertEquals(32, ab!!.size)
            assertArrayEquals(ab, ba)
        }
    }

    /**
     * RFC 7748 section 6.1 says to reject an all-zero shared secret. These are the low-order
     * points; without the check, anyone could force a secret both sides "agree" on.
     */
    @Test fun lowOrderPointsRejected() {
        val lowOrder = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        )
        val priv = Crypto.newPrivateKey()
        for (p in lowOrder) assertNull("low order point $p was accepted", Crypto.sharedSecret(priv, hex(p)))
    }

    @Test fun sealedTextComesBack() {
        val key = Crypto.pairKey(ByteArray(32) { it.toByte() })
        assertEquals(32, key.size)
        val plain = "meet me at the usual place".toByteArray()
        val sealed = Crypto.encrypt(key, 0x1234, 0x5678, 7, plain)
        assertArrayEquals(plain, Crypto.decrypt(key, 0x1234, 0x5678, 7, sealed))
    }

    @Test fun wrongKeyDoesNotOpen() {
        val key = Crypto.pairKey(ByteArray(32) { it.toByte() })
        val other = Crypto.pairKey(ByteArray(32) { (it + 1).toByte() })
        val sealed = Crypto.encrypt(key, 1, 2, 3, "hello".toByteArray())
        assertNull(Crypto.decrypt(other, 1, 2, 3, sealed))
    }

    /** Every field that goes into the nonce must be authenticated in effect: change one, it must not open. */
    @Test fun changedHeaderDoesNotOpen() {
        val key = Crypto.pairKey(ByteArray(32) { it.toByte() })
        val sealed = Crypto.encrypt(key, 1, 2, 3, "hello".toByteArray())
        assertNull("sender swapped", Crypto.decrypt(key, 9, 2, 3, sealed))
        assertNull("recipient swapped", Crypto.decrypt(key, 1, 9, 3, sealed))
        assertNull("counter replayed at a different value", Crypto.decrypt(key, 1, 2, 4, sealed))
    }

    @Test fun tamperedCiphertextDoesNotOpen() {
        val key = Crypto.pairKey(ByteArray(32) { it.toByte() })
        val sealed = Crypto.encrypt(key, 1, 2, 3, "hello".toByteArray())
        for (i in sealed.indices) {
            val bad = sealed.copyOf()
            bad[i] = (bad[i].toInt() xor 1).toByte()
            assertNull("flipping bit 0 of byte $i still opened", Crypto.decrypt(key, 1, 2, 3, bad))
        }
    }

    /**
     * GCM dies if a nonce ever repeats under one key: it leaks the authentication key.
     * The pair key is the same in both directions, so the direction must be part of the nonce.
     */
    @Test fun nonceNeverRepeatsAcrossDirectionsOrCounters() {
        val key = Crypto.pairKey(ByteArray(32) { it.toByte() })
        val plain = ByteArray(16)
        val seen = HashSet<String>()
        for (counter in 1..64) {
            for ((from, to) in listOf(0x1111 to 0x2222, 0x2222 to 0x1111)) {
                val c = Crypto.toHex(Crypto.encrypt(key, from, to, counter, plain))
                assertTrue("identical ciphertext means a repeated nonce: $from->$to #$counter", seen.add(c))
            }
        }
    }

    @Test fun fingerprintIsStableAndFormatted() {
        val pub = Crypto.publicKey(Crypto.hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        val f = Crypto.fingerprint(pub)
        assertEquals(Crypto.fingerprint(pub), f)
        assertTrue("looks like 'ABCD EF01', was '$f'", Regex("^[0-9A-F]{4} [0-9A-F]{4}$").matches(f))
        assertFalse(f == Crypto.fingerprint(Crypto.publicKey(Crypto.newPrivateKey())))
    }

    @Test fun hexRoundTrips() {
        val b = ByteArray(256) { it.toByte() }
        assertArrayEquals(b, Crypto.hex(Crypto.toHex(b)))
    }
}
