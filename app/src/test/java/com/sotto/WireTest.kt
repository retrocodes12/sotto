package com.sotto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Wire.parse is fed by the microphone and by Bluetooth, so every byte it sees is written by
 * whoever is in the room. It has to survive all of them: the contract these tests pin is that
 * parse never throws, whatever it is handed.
 */
class WireTest {

    private fun textOf(p: Wire.Parsed) = p as Wire.Parsed.Text

    @Test fun textRoundTrips() {
        val m = textOf(Wire.parse(Wire.text(SOME_ID, 42, 3, "hello there")))
        assertEquals(SOME_ID, m.id); assertEquals(42, m.seq); assertEquals(3, m.hops)
        assertEquals("hello there", m.text); assertEquals(null, m.via)
    }

    @Test fun relayRoundTripsAndKeepsTheVia() {
        val m = textOf(Wire.parse(Wire.relay(0x1234, 9, 2, 0xABCD, "passed along")))
        assertEquals(0x1234, m.id); assertEquals(9, m.seq); assertEquals(2, m.hops)
        assertEquals(0xABCD, m.via); assertEquals("passed along", m.text)
    }

    @Test fun helloRoundTrips() {
        val h = Wire.parse(Wire.hello(0x1234, "Sohil")) as Wire.Parsed.Hello
        assertEquals(0x1234, h.id); assertEquals("Sohil", h.name)
    }

    @Test fun hereRoundTrips() {
        assertEquals(0xBEEF, (Wire.parse(Wire.here(0xBEEF)) as Wire.Parsed.Here).id)
    }

    @Test fun keyRoundTrips() {
        val pub = ByteArray(32) { (it * 7).toByte() }
        val k = Wire.parse(Wire.key(0x1111, 0x2222, pub)) as Wire.Parsed.Key
        assertEquals(0x1111, k.from); assertEquals(0x2222, k.to); assertArrayEquals(pub, k.publicKey)
    }

    @Test fun privateRoundTrips() {
        val sealed = ByteArray(40) { it.toByte() }
        val p = Wire.parse(Wire.private(0x1111, 0x2222, 5, 4, 0x0BADF00D, sealed)) as Wire.Parsed.Private
        assertEquals(0x1111, p.from); assertEquals(0x2222, p.to); assertEquals(5, p.seq); assertEquals(4, p.hops)
        assertEquals(0x0BADF00D, p.counter); assertArrayEquals(sealed, p.sealed)
    }

    /** A 32-bit counter must survive the top bit being set; it is packed by hand. */
    @Test fun privateCounterSurvivesTheTopBit() {
        for (c in listOf(0, 1, 0x7FFFFFFF, -1, Int.MIN_VALUE, 0xFFFFFF00.toInt())) {
            val p = Wire.parse(Wire.private(1, 2, 0, 0, c, ByteArray(1))) as Wire.Parsed.Private
            assertEquals("counter $c", c, p.counter)
        }
    }

    @Test fun ackRoundTrips() {
        val a = Wire.parse(Wire.ack(0x1111, 0x2222, 8, 3)) as Wire.Parsed.Ack
        assertEquals(0x1111, a.from); assertEquals(0x2222, a.to); assertEquals(8, a.seq); assertEquals(3, a.hops)
    }

    @Test fun probeAndReplyRoundTrip() {
        assertEquals(7, (Wire.parse(Wire.probe(0x1234, 7)) as Wire.Parsed.Probe).seq)
        val r = Wire.parse(Wire.probeReply(0x1111, 0x2222, 7, 200)) as Wire.Parsed.ProbeReply
        assertEquals(200, r.snrDb)
        assertEquals(255, (Wire.parse(Wire.probeReply(1, 2, 0, 9999)) as Wire.Parsed.ProbeReply).snrDb)
        assertEquals(0, (Wire.parse(Wire.probeReply(1, 2, 0, -50)) as Wire.Parsed.ProbeReply).snrDb)
    }

    @Test fun cardRoundTrips() {
        val c = Wire.parse(Wire.card(0x1234, 1, Wire.CARD_WIFI, listOf("Home", "hunter2"))) as Wire.Parsed.Card
        assertEquals(Wire.CARD_WIFI, c.kind); assertEquals(listOf("Home", "hunter2"), c.fields)
    }

    @Test fun aNameIsCappedOnTheWire() {
        assertEquals(3 + IdentityStore.MAX_NAME, Wire.hello(1, "x".repeat(200)).size)
    }

    @Test fun textIsUtf8Clean() {
        val s = "नमस्ते 🌙 café"
        assertEquals(s, textOf(Wire.parse(Wire.text(1, 1, 1, s))).text)
    }

    @Test fun aPlainMessageFromAnotherAppIsNotOurs() {
        assertEquals("sent by ggwave", (Wire.parse("sent by ggwave".toByteArray()) as Wire.Parsed.Plain).text)
    }

    @Test fun relayingDecrementsOnlyTheHopByte() {
        val raw = Wire.private(0x1111, 0x2222, 5, 4, 99, ByteArray(20) { it.toByte() })
        val out = Wire.relayPrivate(raw)
        assertEquals(3, (Wire.parse(out) as Wire.Parsed.Private).hops)
        for (i in raw.indices) if (i != 6) assertEquals("byte $i changed", raw[i], out[i])
        assertEquals(4, (Wire.parse(raw) as Wire.Parsed.Private).hops)   // the original is untouched

        assertEquals(3, (Wire.parse(Wire.relayAck(Wire.ack(1, 2, 3, 4))) as Wire.Parsed.Ack).hops)
    }

    /** A hop count that has run out must not wrap round to 255 and live forever. */
    @Test fun relayingCannotResurrectADeadFrame() {
        assertEquals(0, (Wire.parse(Wire.relayPrivate(Wire.private(1, 2, 3, 0, 9, ByteArray(4)))) as Wire.Parsed.Private).hops)
        assertEquals(0, (Wire.parse(Wire.relayAck(Wire.ack(1, 2, 3, 0))) as Wire.Parsed.Ack).hops)
    }

    /** Every one of our tags, truncated to every shorter length, must still not throw. */
    @Test fun truncatedFramesOfEveryTagAreSurvivable() {
        val full = listOf(
            Wire.text(1, 2, 3, "hello"), Wire.relay(1, 2, 3, 4, "hello"), Wire.hello(1, "name"),
            Wire.here(1), Wire.key(1, 2, ByteArray(32)), Wire.private(1, 2, 3, 4, 5, ByteArray(30)),
            Wire.ack(1, 2, 3, 4), Wire.probe(1, 2), Wire.probeReply(1, 2, 3, 4),
            Wire.card(1, 2, 3, listOf("a", "b")),
        )
        for (f in full) for (n in 0..f.size) Wire.parse(f.copyOf(n))
    }

    /** Every tag byte at every short length, including the ones we do not define. */
    @Test fun everyTagAtEveryShortLengthIsSurvivable() {
        for (tag in 0..255) for (n in 0..48) {
            val p = ByteArray(n)
            if (n > 0) p[0] = tag.toByte()
            Wire.parse(p)
        }
    }

    /** Fuzz: whatever is in the air, parse returns rather than throws. */
    @Test fun parseNeverThrows() {
        val r = Random(20260904)
        val tags = byteArrayOf(
            0xA1.toByte(), 0xA2.toByte(), 0xA4.toByte(), 0xA9.toByte(), 0xAB.toByte(),
            0xAC.toByte(), 0xAD.toByte(), 0xAE.toByte(), 0xAF.toByte(), 0xB0.toByte(),
        )
        repeat(60_000) {
            val p = ByteArray(r.nextInt(80))
            r.nextBytes(p)
            // half the time force one of our tags, so the structured branches are hit too
            if (p.isNotEmpty() && r.nextBoolean()) p[0] = tags[r.nextInt(tags.size)]
            Wire.parse(p)
        }
    }

    /** A name arriving from the air must not be able to wreck the list it is drawn into. */
    @Test fun aHostileNameIsNeutralised() {
        val hostile = "‮gnitset\n\n\t" + "A".repeat(400)
        val name = (Wire.parse(Wire.hello(1, hostile)) as Wire.Parsed.Hello).name
        assertTrue("name is ${name.length} characters", name.length <= IdentityStore.MAX_NAME)
        assertTrue("control characters survived", name.none { it.code < 0x20 })
        assertTrue("a bidi override survived", name.none { it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 })
    }

    private companion object {
        const val SOME_ID = 0x7A2B
    }
}
