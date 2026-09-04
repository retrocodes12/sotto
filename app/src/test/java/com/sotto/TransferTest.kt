package com.sotto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Photos arrive in up to 255 pieces over a channel that drops them. This is the reassembly. */
class TransferTest {

    private fun bodyOf(frame: ByteArray) = frame.copyOfRange(4, frame.size)

    private fun roundTrip(content: ByteArray, kind: Int = Transfer.KIND_JPEG): Transfer.Assembled? {
        val cs = Transfer.chunks(7, kind, 0x1234, 9, 3, content)!!
        return Transfer.assemble(cs.map { bodyOf(it) })
    }

    @Test fun contentOfEverySizeComesBackWhole() {
        val r = Random(7)
        for (n in listOf(0, 1, 127, 128, 129, 136, 264, 265, 1000, 30_000, 34_672)) {
            val content = ByteArray(n).also { r.nextBytes(it) }
            val a = roundTrip(content)
            assertNotNull("$n bytes did not reassemble", a)
            assertArrayEquals("$n bytes came back wrong", content, a!!.content)
            assertEquals(Transfer.KIND_JPEG, a.kind)
            assertEquals(0x1234, a.sender)
            assertEquals(9, a.msgSeq)
            assertEquals(3, a.hops)
        }
    }

    @Test fun everyFrameFitsTheModem() {
        val cs = Transfer.chunks(1, Transfer.KIND_JPEG, 1, 1, 4, ByteArray(30_000))!!
        for (f in cs) assertTrue("a chunk is ${f.size} B, over the ${Transfer.MAX_FRAME} B limit", f.size <= Transfer.MAX_FRAME)
        assertTrue(Transfer.reqFrame(1, (0..254).toList()).size <= Transfer.MAX_FRAME)
        assertTrue(Transfer.endFrame(1, 200).size <= Transfer.MAX_FRAME)
    }

    /** More than 255 chunks cannot be numbered, so it must refuse rather than wrap round. */
    @Test fun contentTooLargeIsRefused() {
        // 128 bytes in the first chunk, then 136 in each of 254 more: 34,672 is the last that fits.
        assertNotNull(Transfer.chunks(1, Transfer.KIND_JPEG, 1, 1, 4, ByteArray(34_672)))
        assertNull(Transfer.chunks(1, Transfer.KIND_JPEG, 1, 1, 4, ByteArray(34_673)))
        assertNull(Transfer.chunks(1, Transfer.KIND_JPEG, 1, 1, 4, ByteArray(40_000)))
        assertNull(Transfer.chunks(1, Transfer.KIND_JPEG, 1, 1, 4, ByteArray(100_000)))
    }

    @Test fun framesRoundTrip() {
        val d = Transfer.parse(Transfer.chunks(9, Transfer.KIND_TEXT, 1, 2, 3, "hi".toByteArray())!![0]) as Transfer.Frame.Data
        assertEquals(9, d.id); assertEquals(0, d.seq); assertEquals(1, d.total)
        assertEquals(200, (Transfer.parse(Transfer.endFrame(9, 200)) as Transfer.Frame.End).total)
        assertEquals(listOf(1, 5, 250), (Transfer.parse(Transfer.reqFrame(9, listOf(1, 5, 250))) as Transfer.Frame.Req).missing)
        assertEquals(9, (Transfer.parse(Transfer.doneFrame(9)) as Transfer.Frame.Done).id)
    }

    /** A partial set must be refused, not assembled into a truncated photo. */
    @Test fun missingChunksAreNotAssembled() {
        val cs = Transfer.chunks(1, Transfer.KIND_JPEG, 1, 1, 4, ByteArray(1000) { it.toByte() })!!
        assertNull(Transfer.assemble(cs.dropLast(1).map { bodyOf(it) }))
        assertNull(Transfer.assemble(emptyList()))
        assertNull(Transfer.assemble(listOf(ByteArray(3))))     // shorter than the header
    }

    @Test fun ourTagsAreRecognisedAndOthersAreNot() {
        for (f in listOf(Transfer.endFrame(1, 2), Transfer.doneFrame(1), Transfer.reqFrame(1, listOf(0)))) {
            assertTrue(Transfer.isTransferFrame(f))
        }
        assertTrue(Transfer.isTransferFrame(Transfer.chunks(1, 1, 1, 1, 1, ByteArray(4))!![0]))
        assertFalse(Transfer.isTransferFrame(Wire.text(1, 1, 1, "hello")))
        assertFalse(Transfer.isTransferFrame(Wire.hello(1, "x")))
        assertFalse(Transfer.isTransferFrame("plain".toByteArray()))
        assertFalse(Transfer.isTransferFrame(ByteArray(0)))
    }

    /** Frames come off the air; parse must return rather than throw, whatever it is handed. */
    @Test fun parseNeverThrows() {
        val r = Random(99)
        repeat(40_000) {
            val p = ByteArray(r.nextInt(160))
            r.nextBytes(p)
            if (p.isNotEmpty() && r.nextBoolean()) p[0] = (0xA5 + r.nextInt(4)).toByte()
            Transfer.parse(p)
        }
        for (tag in 0xA5..0xA8) for (n in 0..12) {
            val p = ByteArray(n)
            if (n > 0) p[0] = tag.toByte()
            Transfer.parse(p)
        }
    }

    /** A REQ claiming more missing chunks than it carries must not read past its own frame. */
    @Test fun aLyingRequestIsRejected() {
        assertNull(Transfer.parse(byteArrayOf(0xA6.toByte(), 1, 200.toByte(), 0, 1, 2)))
        assertNotNull(Transfer.parse(byteArrayOf(0xA6.toByte(), 1, 3, 0, 1, 2)))
    }

    @Test fun incomingTracksWhatIsMissing() {
        val x = Transfer.Incoming(1, 4)
        assertEquals(listOf(0, 1, 2, 3), x.missing)
        x.parts[0] = ByteArray(1); x.parts[2] = ByteArray(1)
        assertEquals(2, x.received)
        assertEquals(listOf(1, 3), x.missing)
    }
}
