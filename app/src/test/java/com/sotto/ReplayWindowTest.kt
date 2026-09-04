package com.sotto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window that decides whether a private message is new or a recording of an old one. It is
 * the only thing standing between someone with a microphone and replaying yesterday's messages
 * back at you, and it has to do that while still accepting a genuine message that spent two
 * days in a stranger's pocket and arrives after newer ones.
 */
class ReplayWindowTest {

    /** Runs a sequence of counters through the window, returning which were accepted. */
    private fun feed(vararg counters: Int): List<Int> {
        var high = 0
        var mask = 0L
        val taken = ArrayList<Int>()
        for (c in counters) {
            if (ReplayWindow.isFresh(high, mask, c)) {
                taken.add(c)
                val next = ReplayWindow.accept(high, mask, c)
                high = next.first; mask = next.second
            }
        }
        return taken
    }

    @Test fun aRisingSequenceIsAllAccepted() {
        assertEquals((1..200).toList(), feed(*(1..200).toList().toIntArray()))
    }

    @Test fun nothingIsAcceptedTwice() {
        assertEquals(listOf(1, 2, 3), feed(1, 2, 3, 3, 2, 1, 3))
    }

    /** The whole reason for a window: sound arrives now, a carried copy arrives on Thursday. */
    @Test fun outOfOrderIsStillAccepted() {
        assertEquals(listOf(10, 4, 7, 11, 5), feed(10, 4, 7, 11, 5, 10, 4))
    }

    @Test fun anythingOlderThanTheWindowIsRefused() {
        var high = 0; var mask = 0L
        ReplayWindow.accept(0, 0L, 1000).let { high = it.first; mask = it.second }
        assertTrue("one inside the window", ReplayWindow.isFresh(high, mask, 1000 - ReplayWindow.SIZE + 1))
        assertFalse("one exactly at the edge", ReplayWindow.isFresh(high, mask, 1000 - ReplayWindow.SIZE))
        assertFalse("one long past", ReplayWindow.isFresh(high, mask, 1))
    }

    /** A big jump forward must clear the map rather than shift stale bits into it. */
    @Test fun aJumpBeyondTheWindowStartsClean() {
        val (high, mask) = ReplayWindow.accept(10, 0b1111L, 10 + ReplayWindow.SIZE + 5)
        assertEquals(10 + ReplayWindow.SIZE + 5, high)
        assertEquals(1L, mask)
        assertFalse("the old counters are simply out of reach now", ReplayWindow.isFresh(high, mask, 10))
    }

    @Test fun zeroAndNegativeCountersAreRefused() {
        assertFalse(ReplayWindow.isFresh(5, 0L, 0))
        assertFalse(ReplayWindow.isFresh(5, 0L, -1))
        assertFalse(ReplayWindow.isFresh(0, 0L, Int.MIN_VALUE))
        assertEquals(5 to 0L, ReplayWindow.accept(5, 0L, 0))
    }

    /** The top bit of the mask is the counter exactly SIZE-1 back; it must not be lost. */
    @Test fun theFarEdgeOfTheWindowIsRemembered() {
        var high = 100; var mask = 0L
        val edge = 100 - ReplayWindow.SIZE + 1
        assertTrue(ReplayWindow.isFresh(high, mask, edge))
        ReplayWindow.accept(high, mask, edge).let { high = it.first; mask = it.second }
        assertFalse("accepted the same far-edge counter twice", ReplayWindow.isFresh(high, mask, edge))
        assertEquals(100, high)
    }

    /** Every counter, offered in a shuffled order, is accepted exactly once. */
    @Test fun everyCounterIsTakenExactlyOnce() {
        val order = (1..40).toList().let { it.reversed().zip(it).flatMap { (a, b) -> listOf(a, b) } }
        val taken = feed(*order.toIntArray())
        assertEquals("each accepted once", taken.toSet().size, taken.size)
        assertEquals("all forty arrived", (1..40).toSet(), taken.toSet())
    }
}
