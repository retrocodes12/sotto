package com.sotto.carry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CarryTest {
    private var clock = 1_000_000L
    private fun now() = clock

    /** A phone with a store, a sync engine, and a record of what happened to it. */
    inner class Node(val id: Long) : Sync.Events {
        val store = Store(id, ::now)
        val outbox = ArrayList<Pair<Long, ByteArray>>()
        var radioWorks = true
        val sync = Sync(id, store, ::now, { peer, f -> if (radioWorks) { outbox.add(peer to f); true } else false }, this)
        val received = ArrayList<Bundle>()
        val delivered = ArrayList<Triple<BundleKey, Int, Int>>()
        val handed = HashMap<BundleKey, Int>()
        val reach = HashMap<BundleKey, Int>()
        var seq = 0
        override fun onBundle(bundle: Bundle, peer: Long) { received.add(bundle) }
        override fun onDelivered(key: BundleKey, hops: Int, minutes: Int) { delivered.add(Triple(key, hops, minutes)) }
        override fun onHanded(key: BundleKey, peer: Long, count: Int) { handed[key] = count }
        override fun onReach(key: BundleKey, phones: Int) { reach[key] = phones }
        override fun onSyncDone(peer: Long, received: Int, sent: Int) {}
        fun room(text: String) = Bundle(id, ++seq, Bundle.KIND_ROOM, now(), 24 * 60, 0, 0, 0, text.toByteArray()).also { store.accept(it, null) }
        fun private(to: Long, text: String, copies: Int = 16) = Bundle(id, ++seq, Bundle.KIND_PRIVATE, now(), 72 * 60, to, 0, copies, text.toByteArray()).also { store.accept(it, null) }
    }

    /** Runs a meeting between two nodes to completion, shuttling frames until both are quiet. */
    private fun meet(a: Node, b: Node) {
        a.sync.start(b.id); b.sync.start(a.id)
        var guard = 0
        while ((a.outbox.isNotEmpty() || b.outbox.isNotEmpty()) && guard++ < 10_000) {
            a.outbox.removeFirstOrNull()?.let { (to, f) -> if (to == b.id) b.sync.onFrame(f) }
            b.outbox.removeFirstOrNull()?.let { (to, f) -> if (to == a.id) a.sync.onFrame(f) }
        }
    }

    @Test fun bundleCodecRoundTrips() {
        val b = Bundle(0x1122334455667788L, 42, Bundle.KIND_PRIVATE, 1_700_000_000L, 4320, -5L, 7, 3, "hello".toByteArray())
        val d = Bundle.decode(b.encode())!!
        assertEquals(b.origin, d.origin); assertEquals(b.seq, d.seq); assertEquals(b.kind, d.kind)
        assertEquals(b.created, d.created); assertEquals(b.ttlMinutes, d.ttlMinutes); assertEquals(b.dest, d.dest)
        assertEquals(7, d.hops); assertEquals(3, d.copies); assertEquals("hello", String(d.payload))
        assertNull(Bundle.decode(b.encode().copyOf(20)))
    }

    @Test fun receiptAndProfilePayloadsRoundTrip() {
        val k = BundleKey(99L, 7)
        val (key, hops, minutes) = Bundle.parseReceipt(Bundle.receiptPayload(k, 5, 130))!!
        assertEquals(k, key); assertEquals(5, hops); assertEquals(130, minutes)
        val pub = ByteArray(32) { it.toByte() }
        val (name, key2) = Bundle.parseProfile(Bundle.profilePayload("Priya", pub))!!
        assertEquals("Priya", name); assertTrue(pub.contentEquals(key2))
    }

    @Test fun sketchCountsDistinctPhonesRoughly() {
        val key = BundleKey(1L, 1)
        val s = Sketch()
        for (phone in 1L..60L) s.mark(phone * 7919, key)
        val est = s.estimate()
        assertTrue("estimate $est should be near 60", est in 45..75)
        val t = Sketch(); for (phone in 61L..80L) t.mark(phone * 7919, key)
        s.merge(t)
        assertTrue("merged ${s.estimate()} should be near 80", s.estimate() in 62..100)
    }

    @Test fun roomMessageSpreadsAcrossAChainOfMeetings() {
        val a = Node(1); val b = Node(2); val c = Node(3)
        val m = a.room("meet at the gate")
        meet(a, b)
        assertTrue(b.store.contains(m.key)); assertEquals(1, b.received.size)
        meet(b, c)
        assertTrue(c.store.contains(m.key))
        assertEquals(2, c.store[m.key]!!.bundle.hops)
        assertEquals("meet at the gate", String(c.received[0].payload))
        assertEquals(1, a.handed[m.key])
    }

    @Test fun meetingAgainSendsNothingTwice() {
        val a = Node(1); val b = Node(2)
        a.room("x")
        meet(a, b); val once = b.received.size
        meet(a, b)
        assertEquals(once, b.received.size)
    }

    @Test fun sprayHalvesCopiesAndDeliversToTheDestination() {
        val a = Node(1); val b = Node(2); val c = Node(3); val d = Node(4)
        val m = a.private(c.id, "secret", copies = 8)
        meet(a, b)
        assertEquals(4, a.store[m.key]!!.copies); assertEquals(4, b.store[m.key]!!.copies)
        meet(b, d)   // d is not the destination: gets half of b's
        assertEquals(2, b.store[m.key]!!.copies); assertEquals(2, d.store[m.key]!!.copies)
        meet(d, c)   // c is the destination: always gets it
        assertTrue(c.store.contains(m.key))
        assertEquals("secret", String(c.received.first { it.key == m.key }.payload))
    }

    @Test fun singleCopyOnlyGoesToTheDestination() {
        val a = Node(1); val b = Node(2); val c = Node(3)
        val m = a.private(c.id, "one", copies = 1)
        meet(a, b)
        assertFalse("a carrier must not receive the last copy", b.store.contains(m.key))
        meet(a, c)
        assertTrue(c.store.contains(m.key))
    }

    @Test fun receiptFlowsBackAndVaccinatesCarriers() {
        val a = Node(1); val b = Node(2); val c = Node(3)
        val m = a.private(c.id, "ping", copies = 4)
        meet(a, b); meet(b, c)
        assertTrue(c.store.contains(m.key))
        clock += 25 * 60
        val r = c.sync.receiptFor(c.store[m.key]!!.bundle, ++c.seq)
        c.store.receipt(m.key, m.expiresAt); c.store.accept(r, null)
        meet(c, b)
        assertFalse("carrier dropped it", b.store.contains(m.key))
        assertEquals(Store.Verdict.TOMBSTONE, b.store.accept(m, c.id))
        meet(b, a)
        assertEquals(1, a.delivered.size)
        assertEquals(m.key, a.delivered[0].first); assertEquals(25, a.delivered[0].third)
        assertNotNull(a.store[m.key]?.delivered)
    }

    /**
     * The tile says "reached about N phones", and the whole point is that N counts phones the
     * sender never met. So the message goes to exactly one phone, spreads from there, and the
     * sender meets that one phone again: it should come back knowing about all of them.
     *
     * The previous version of this test handed the message to all twelve directly and then read
     * `a.reach[key] ?: a.store[key]!!.sketch.estimate()` -- the sender's own sketch, which of
     * course already knew about the twelve phones the sender had just handed it to. It passed
     * whether or not a single sketch ever made the return journey.
     */
    @Test fun reachEstimateReturnsToTheOrigin() {
        val a = Node(1); val hub = Node(2); val rest = (3L..13L).map { Node(it) }
        val m = a.room("hello all")
        meet(a, hub)                                                    // one handover, and that is all a sees
        for (o in rest) meet(hub, o)                                    // it spreads out of a's sight
        for (i in 0 until rest.size - 1) meet(rest[i], rest[i + 1])     // they compare notes
        assertEquals("a should still only know about itself and the one phone it met", 2, a.store[m.key]!!.sketch.estimate())

        clock += Sync.INTERVAL_S + 1
        meet(a, hub)                                                    // and a meets that phone again

        val est = a.reach[m.key]
        assertNotNull("the origin was never told how far its own message got", est)
        assertTrue("reach $est should be most of the thirteen, not the two a saw", est!! >= 8)
    }

    @Test fun quotaLimitsOneOriginsRoomMessages() {
        val a = Node(1); val b = Node(2)
        repeat(25) { a.room("spam $it") }
        meet(a, b)
        assertEquals(Store.QUOTA_PER_WINDOW, b.store.size)
    }

    @Test fun expiryAndEvictionKeepOurOwnLongest() {
        val a = Node(1)
        val old = Bundle(9L, 1, Bundle.KIND_ROOM, now(), 1, 0, 0, 0, "old".toByteArray())
        a.store.accept(old, 5L)
        clock += 120
        assertEquals(listOf(old.key), a.store.expire())
        val small = Store(1L, ::now, maxBundles = 3)
        val mine = Bundle(1L, 1, Bundle.KIND_ROOM, now() - 100, 600, 0, 0, 0, "mine".toByteArray())
        small.accept(mine, null)
        for (i in 2..5) small.accept(Bundle(7L, i, Bundle.KIND_ROOM, now() - 50 + i, 600, 0, 0, 0, "x".toByteArray()), 7L)
        assertTrue(small.size <= 3); assertTrue("own bundle survives eviction", small.contains(mine.key))
    }

    @Test fun profilesKeepOnlyTheNewest() {
        val s = Store(1L, ::now)
        val p1 = Bundle(2L, 1, Bundle.KIND_PROFILE, now(), 600, 0, 0, 0, Bundle.profilePayload("A", ByteArray(32)))
        val p2 = Bundle(2L, 2, Bundle.KIND_PROFILE, now(), 600, 0, 0, 0, Bundle.profilePayload("B", ByteArray(32)))
        assertEquals(Store.Verdict.NEW, s.accept(p2, 3L))
        assertEquals(Store.Verdict.DUPLICATE, s.accept(p1, 3L))
        assertEquals(1, s.size)
    }


    // ---- what the carried network refuses ---------------------------------------------------

    /**
     * The gate between a radio frame and this engine. It compared an unsigned first byte against
     * a range built from signed byte constants, so it was never once true: every sync frame went
     * to the sound parser instead, and the whole carry network was inert on the phone while
     * passing every test in this file, because the tests call onFrame directly.
     */
    @Test fun everySyncFrameIsRecognisedAsOne() {
        val a = Node(1); val b = Node(2)
        a.room("hello"); b.room("hi")
        meet(a, b)
        val frames = a.outbox.map { it.second } + b.outbox.map { it.second }
        val tags = listOf(Sync.TAG_HAVE, Sync.TAG_WANT, Sync.TAG_BUNDLE, Sync.TAG_SKETCH, Sync.TAG_DONE)
        for (tag in tags) {
            val f = ByteBuffer.allocate(11).put(tag).putLong(7L).putShort(0).array()
            assertTrue("tag ${tag.toInt() and 0xFF} was not taken for a sync frame", a.sync.isSyncFrame(f))
        }
        for (f in frames) assertTrue("a frame we just sent is not one of ours", a.sync.isSyncFrame(f))
        assertFalse(a.sync.isSyncFrame(byteArrayOf(0xA1.toByte(), 0, 1, 2, 3)))   // a text frame
        assertFalse(a.sync.isSyncFrame("plain words".toByteArray()))
        assertFalse(a.sync.isSyncFrame(ByteArray(0)))
    }

    /** Frames come off a radio anyone can transmit on; a short one must not take the app down. */
    @Test fun aTruncatedSyncFrameIsSurvivable() {
        val a = Node(1)
        a.room("something to offer")
        for (tag in listOf(Sync.TAG_HAVE, Sync.TAG_WANT, Sync.TAG_BUNDLE, Sync.TAG_SKETCH, Sync.TAG_DONE)) {
            for (n in 0..48) {
                val f = ByteArray(n)
                if (n > 0) f[0] = tag
                if (n >= 9) { val bb = ByteBuffer.wrap(f); bb.position(1); bb.putLong(2L) }
                a.sync.onFrame(f)
            }
        }
        // and a well-formed header claiming a count it does not carry
        a.sync.onFrame(ByteBuffer.allocate(11).put(Sync.TAG_HAVE).putLong(2L).putShort(9999).array())
        a.sync.onFrame(ByteBuffer.allocate(11).put(Sync.TAG_WANT).putLong(2L).putShort(9999).array())
        a.sync.onFrame(ByteBuffer.allocate(11).put(Sync.TAG_SKETCH).putLong(2L).putShort(9999).array())
    }

    /**
     * A receipt deletes a message from every phone that sees it, and nobody signs one. Taken
     * from a stranger it is a way to erase anything whose key you have overheard in a HAVE.
     */
    @Test fun aForgedReceiptCannotEraseAMessage() {
        val a = Node(1); val carrier = Node(3)
        val m = a.private(2L, "for two only")
        meet(a, carrier)
        assertTrue("the carrier should be holding it", carrier.store.contains(m.key))

        val forged = Bundle(99L, 1, Bundle.KIND_RECEIPT, now(), 600, a.id, 0, 0, Bundle.receiptPayload(m.key, 2, 5))
        carrier.sync.onFrame(ByteBuffer.allocate(9 + forged.encode().size).put(Sync.TAG_BUNDLE).putLong(99L).put(forged.encode()).array())
        assertTrue("a stranger erased a message from a carrier", carrier.store.contains(m.key))

        // the real recipient still can
        val real = Bundle(2L, 1, Bundle.KIND_RECEIPT, now(), 600, a.id, 0, 0, Bundle.receiptPayload(m.key, 2, 5))
        carrier.sync.onFrame(ByteBuffer.allocate(9 + real.encode().size).put(Sync.TAG_BUNDLE).putLong(2L).put(real.encode()).array())
        assertFalse("the recipient's own receipt did not vaccinate the carrier", carrier.store.contains(m.key))
    }

    /** And it cannot make the sender's tile claim a delivery that never happened. */
    @Test fun aForgedReceiptCannotClaimDelivery() {
        val a = Node(1)
        val m = a.private(2L, "for two only")
        val forged = Bundle(99L, 1, Bundle.KIND_RECEIPT, now(), 600, a.id, 0, 0, Bundle.receiptPayload(m.key, 9, 1))
        a.sync.onFrame(ByteBuffer.allocate(9 + forged.encode().size).put(Sync.TAG_BUNDLE).putLong(99L).put(forged.encode()).array())
        assertNull(a.store[m.key]?.delivered)
        assertTrue(a.delivered.isEmpty())
    }

    /** Creation time and lifetime are both written by the sender. Neither buys immortality. */
    @Test fun aBundleCannotOutliveWhatItsKindAllows() {
        val s = Store(1L, ::now)
        val greedy = Bundle(2L, 1, Bundle.KIND_ROOM, now(), 65535, 0, 0, 0, "stay".toByteArray())
        assertEquals(Store.Verdict.NEW, s.accept(greedy, 3L))
        assertTrue("a day is the most a room message gets", s[greedy.key]!!.expiresAt <= now() + 24 * 3600 + 1)
        clock += 25 * 3600
        assertEquals(listOf(greedy.key), s.expire())
    }

    /** A date in the future is not a longer life; it is a bundle nobody takes. */
    @Test fun aBundleDatedInTheFutureIsRefused() {
        val s = Store(1L, ::now)
        val ahead = Bundle(2L, 1, Bundle.KIND_ROOM, now() + 400L * 86400, 600, 0, 0, 0, "stay".toByteArray())
        assertEquals(Store.Verdict.EXPIRED, s.accept(ahead, 3L))
        // a little skew is another phone's clock, not an attack
        val slightly = Bundle(2L, 2, Bundle.KIND_ROOM, now() + 60, 600, 0, 0, 0, "fine".toByteArray())
        assertEquals(Store.Verdict.NEW, s.accept(slightly, 3L))
    }

    /**
     * The deadline has to come from when the message was written, not from when this phone
     * happened to receive it: measured from arrival, every hop restarts the clock and a message
     * that should live a day lives as long as anyone keeps passing it on.
     */
    @Test fun carryingAMessageDoesNotExtendItsLife() {
        val born = now()
        val chain = (1..6).map { Node(it.toLong()) }
        val m = chain[0].room("passed along")
        for (i in 0 until chain.size - 1) {
            clock += 4 * 3600            // four hours between each meeting: 20 in all, inside the day
            meet(chain[i], chain[i + 1])
        }
        val last = chain.last().store[m.key]
        assertNotNull("it should have travelled the whole chain", last)
        assertTrue(
            "the last carrier expires it ${(last!!.expiresAt - born) / 3600} h after it was written",
            last.expiresAt <= born + 24 * 3600 + 1,
        )
    }

    /** A private message that claimed to flood would be handed to everyone in the district. */
    @Test fun aPrivateBundleCannotClaimToFlood() {
        val s = Store(1L, ::now)
        val sneaky = Bundle(2L, 1, Bundle.KIND_PRIVATE, now(), 600, 5L, 0, 0, "read me".toByteArray())
        s.accept(sneaky, 3L)
        assertEquals(1, s[sneaky.key]!!.copies)
        assertFalse("it should not be offered to phones that are not its destination", 9L in s.offerable(9L).map { it.bundle.dest })
        val greedy = Bundle(2L, 2, Bundle.KIND_PRIVATE, now(), 600, 5L, 0, 250, "read me".toByteArray())
        s.accept(greedy, 3L)
        assertEquals(Bundle.MAX_COPIES, s[greedy.key]!!.copies)
    }

    /**
     * The per-origin quota is keyed on an identity anyone can mint, so it stops nobody. This one
     * is keyed on the phone actually in range, which is the thing an attacker cannot clone.
     */
    @Test fun onePhoneInRangeCannotFillTheStore() {
        val s = Store(1L, ::now)
        var taken = 0
        // Every bundle claims a new origin AND a new sender id, which is all an attacker needs
        // to walk through a quota keyed on either. They all arrive on one radio.
        for (i in 1..500) {
            val b = Bundle(1000L + i, 1, Bundle.KIND_PRIVATE, now(), 600, 7L, 0, 4, ByteArray(9_000))
            if (s.accept(b, 2000L + i, "AA:BB:CC:DD:EE:FF") == Store.Verdict.NEW) taken++ else break
        }
        assertTrue("took $taken bundles from one radio", taken in 50..140)
        assertTrue(s.bytes <= Store.INTAKE_BYTES_PER_WINDOW)
        // a different phone gets its own budget, so an honest one is not punished for the flood
        val honest = Bundle(77L, 1, Bundle.KIND_ROOM, now(), 600, 0, 0, 0, "hello".toByteArray())
        assertEquals(Store.Verdict.NEW, s.accept(honest, 5L, "11:22:33:44:55:66"))
    }

    /** Eviction orders on when we took a message in, not on the date its sender wrote in it. */
    @Test fun aFutureDateDoesNotSurviveEviction() {
        val s = Store(1L, ::now, maxBundles = 4)
        val mine = Bundle(1L, 1, Bundle.KIND_ROOM, now(), 600, 0, 0, 0, "mine".toByteArray())
        s.accept(mine, null)
        val future = Bundle(2L, 1, Bundle.KIND_ROOM, now() + 300L * 86400, 600, 0, 0, 0, "later".toByteArray())
        s.accept(future, 3L)
        for (i in 1..6) { clock += 1; s.accept(Bundle(9L, i, Bundle.KIND_ROOM, now(), 600, 0, 0, 0, "fill".toByteArray()), 3L) }
        assertTrue("our own message was evicted", s.contains(mine.key))
        assertFalse("a bundle dated in the future outlasted everything", s.contains(future.key))
    }

    /**
     * A private message has a fixed number of copies in the whole network. Halving that budget
     * for a frame that never left the radio does not fail loudly; it just quietly reaches fewer
     * people, and there is no way to tell afterwards that it happened.
     */
    @Test fun aHandoverThatNeverLeftDoesNotSpendCopies() {
        val a = Node(1); val b = Node(2)
        val m = a.private(9L, "for someone else", copies = 16)
        assertEquals(16, a.store[m.key]!!.copies)

        a.sync.start(b.id)
        b.sync.onFrame(a.outbox.removeAt(0).second)   // not removeFirst(): that is Java 21, absent below API 35
        val want = b.outbox.first { it.second[0] == Sync.TAG_WANT }.second

        a.radioWorks = false
        a.sync.onFrame(want)
        assertEquals("copies vanished with a frame that never left", 16, a.store[m.key]!!.copies)
        assertFalse(b.id in a.store[m.key]!!.handedTo)

        a.radioWorks = true
        a.sync.onFrame(want)
        assertEquals("a handover that did leave should spend half", 8, a.store[m.key]!!.copies)
        assertTrue(b.id in a.store[m.key]!!.handedTo)
    }

    @Test fun exportImportKeepsEverything() {
        val a = Node(1)
        val m = a.private(2L, "keep", copies = 6)
        a.store[m.key]!!.handedTo.add(4L); a.store[m.key]!!.copies = 3
        val r = Store(1L, ::now); r.import(a.store.export())
        assertEquals(1, r.size); assertEquals(3, r[m.key]!!.copies); assertTrue(4L in r[m.key]!!.handedTo)
        assertEquals("keep", String(r[m.key]!!.bundle.payload))
    }
}
