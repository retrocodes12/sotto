package com.sotto

import com.sotto.carry.Bundle
import com.sotto.carry.Store
import com.sotto.carry.Sync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Three families of frame share one radio and one microphone, and three separate checks decide
 * which is which: [Transfer.isTransferFrame], [Sync.isSyncFrame], and whatever [Wire.parse]
 * makes of the rest.
 *
 * The carry network shipped inert for a release because one of those three answered "not mine"
 * to every frame it owned, and the frames fell through to the text parser and were drawn in the
 * conversation as binary. Nothing noticed, because every test called the handlers directly and
 * none of them crossed the junction.
 *
 * So this is the junction, tested as a whole: every frame the app can produce is claimed by
 * exactly one of the three. Not zero, which is the bug that shipped. Not two, which would send
 * the same bytes down two paths.
 */
class RoutingTest {

    private val sync = Sync(1L, Store(1L, { 1_000_000L }), { 1_000_000L }, { _, _ -> true }, object : Sync.Events {
        override fun onBundle(bundle: Bundle, peer: Long) {}
        override fun onDelivered(key: com.sotto.carry.BundleKey, hops: Int, minutes: Int) {}
        override fun onHanded(key: com.sotto.carry.BundleKey, peer: Long, count: Int) {}
        override fun onReach(key: com.sotto.carry.BundleKey, phones: Int) {}
        override fun onSyncDone(peer: Long, received: Int, sent: Int) {}
    })

    private enum class Route { TRANSFER, SYNC, WIRE, NOBODY }

    /** Exactly what MainViewModel does with a frame off the radio or the microphone. */
    private fun routeOf(frame: ByteArray): Route = when {
        sync.isSyncFrame(frame) -> Route.SYNC
        Transfer.isTransferFrame(frame) -> Route.TRANSFER
        Wire.parse(frame) !is Wire.Parsed.Plain -> Route.WIRE
        else -> Route.NOBODY
    }

    /** Every kind of frame the app can put on the air, with the route each one belongs to. */
    private fun everyFrame(): List<Triple<String, ByteArray, Route>> {
        val out = ArrayList<Triple<String, ByteArray, Route>>()

        out += Triple("TEXT", Wire.text(0x1234, 7, 3, "hello"), Route.WIRE)
        out += Triple("RELAY", Wire.relay(0x1234, 7, 2, 0x5678, "hello"), Route.WIRE)
        out += Triple("HELLO", Wire.hello(0x1234, "Priya"), Route.WIRE)
        out += Triple("HERE", Wire.here(0x1234), Route.WIRE)
        out += Triple("KEY", Wire.key(0x1234, 0x5678, ByteArray(32) { it.toByte() }), Route.WIRE)
        out += Triple("PRIVATE", Wire.private(0x1234, 0x5678, 7, 3, 99, ByteArray(48)), Route.WIRE)
        out += Triple("ACK", Wire.ack(0x1234, 0x5678, 7, 3), Route.WIRE)
        out += Triple("PROBE", Wire.probe(0x1234, 7), Route.WIRE)
        out += Triple("REPLY", Wire.probeReply(0x1234, 0x5678, 7, 30), Route.WIRE)
        out += Triple("CARD", Wire.card(0x1234, 7, Wire.CARD_LINK, listOf("example.com")), Route.WIRE)

        val chunks = Transfer.chunks(9, Transfer.KIND_JPEG, 0x1234, 7, 3, ByteArray(4000))!!
        out += Triple("DATA first", chunks.first(), Route.TRANSFER)
        out += Triple("DATA last", chunks.last(), Route.TRANSFER)
        out += Triple("END", Transfer.endFrame(9, chunks.size), Route.TRANSFER)
        out += Triple("REQ", Transfer.reqFrame(9, listOf(0, 5, 17)), Route.TRANSFER)
        out += Triple("DONE", Transfer.doneFrame(9), Route.TRANSFER)

        // Sync frames are built inside Sync, so they are collected from a real exchange rather
        // than hand-rolled here: a frame this test invented could differ from a frame it sends.
        val sent = ArrayList<ByteArray>()
        val store = Store(2L, { 1_000_000L })
        val talker = Sync(2L, store, { 1_000_000L }, { _, f -> sent.add(f); true }, object : Sync.Events {
            override fun onBundle(bundle: Bundle, peer: Long) {}
            override fun onDelivered(key: com.sotto.carry.BundleKey, hops: Int, minutes: Int) {}
            override fun onHanded(key: com.sotto.carry.BundleKey, peer: Long, count: Int) {}
            override fun onReach(key: com.sotto.carry.BundleKey, phones: Int) {}
            override fun onSyncDone(peer: Long, received: Int, sent: Int) {}
        })
        val b = Bundle(2L, 1, Bundle.KIND_ROOM, 1_000_000L, 600, 0, 0, 0, "carried".toByteArray())
        store.accept(b, null)
        talker.start(3L)                                   // HAVE
        val theirWant = ByteBuffer.allocate(11 + 12).put(Sync.TAG_WANT).putLong(3L).putShort(1)
            .put(b.key.encode()).array()
        talker.onFrame(theirWant)                          // BUNDLE, then DONE
        // A HAVE from the other side, not our own: onFrame ignores a frame carrying our own id.
        val theirHave = ByteBuffer.allocate(11 + 13).put(Sync.TAG_HAVE).putLong(3L).putShort(1)
            .put(b.key.encode()).put(b.kind.toByte()).array()
        talker.onFrame(theirHave)                          // WANT, and SKETCH for what we share
        for ((i, f) in sent.withIndex()) out += Triple("sync frame $i (tag ${f[0].toInt() and 0xFF})", f, Route.SYNC)

        return out
    }

    @Test fun everyFrameTheAppSendsGoesExactlyOneWay() {
        val frames = everyFrame()
        assertTrue("the exchange should have produced HAVE, WANT, BUNDLE, SKETCH and DONE",
            frames.count { it.third == Route.SYNC } >= 5)
        for ((name, frame, expected) in frames) {
            assertEquals("$name (${frame.size} B, first byte ${frame[0].toInt() and 0xFF}) went the wrong way", expected, routeOf(frame))
        }
    }

    /** All five sync tags, since the exchange above may not produce every one of them. */
    @Test fun everySyncTagIsClaimedByTheCarryNetwork() {
        for (tag in listOf(Sync.TAG_HAVE, Sync.TAG_WANT, Sync.TAG_BUNDLE, Sync.TAG_SKETCH, Sync.TAG_DONE)) {
            val frame = ByteBuffer.allocate(11).put(tag).putLong(2L).putShort(0).array()
            assertEquals("tag ${tag.toInt() and 0xFF}", Route.SYNC, routeOf(frame))
        }
    }

    /** And the three families must not overlap: no frame may answer to two checks. */
    @Test fun theThreeFamiliesDoNotOverlap() {
        for ((name, frame, _) in everyFrame()) {
            val claims = listOfNotNull(
                "sync".takeIf { sync.isSyncFrame(frame) },
                "transfer".takeIf { Transfer.isTransferFrame(frame) },
                "wire".takeIf { Wire.parse(frame) !is Wire.Parsed.Plain },
            )
            assertEquals("$name is claimed by $claims", 1, claims.size)
        }
    }

    /** Text from another app is nobody's frame, and must stay that way. */
    @Test fun somebodyElsesBytesAreNobodysFrame() {
        for (s in listOf("hello from ggwave", "", "1234", "a message with a 0xA1 in it: ¡")) {
            assertEquals(s, Route.NOBODY, routeOf(s.toByteArray()))
        }
    }
}
