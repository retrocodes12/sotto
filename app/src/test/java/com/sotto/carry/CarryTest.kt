package com.sotto.carry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarryTest {
    private var clock = 1_000_000L
    private fun now() = clock

    /** A phone with a store, a sync engine, and a record of what happened to it. */
    inner class Node(val id: Long) : Sync.Events {
        val store = Store(id, ::now)
        val outbox = ArrayList<Pair<Long, ByteArray>>()
        val sync = Sync(id, store, ::now, { peer, f -> outbox.add(peer to f) }, this)
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

    @Test fun reachEstimateReturnsToTheOrigin() {
        val a = Node(1); val others = (2L..13L).map { Node(it) }
        val m = a.room("hello all")
        for (o in others) meet(a, o)                       // everyone gets it directly from a
        for (i in 0 until others.size - 1) meet(others[i], others[i + 1])   // they compare notes
        meet(a, others.last())
        val est = a.reach[m.key] ?: a.store[m.key]!!.sketch.estimate()
        assertTrue("reach $est should be about 13", est in 9..20)
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

    @Test fun exportImportKeepsEverything() {
        val a = Node(1)
        val m = a.private(2L, "keep", copies = 6)
        a.store[m.key]!!.handedTo.add(4L); a.store[m.key]!!.copies = 3
        val r = Store(1L, ::now); r.import(a.store.export())
        assertEquals(1, r.size); assertEquals(3, r[m.key]!!.copies); assertTrue(4L in r[m.key]!!.handedTo)
        assertEquals("keep", String(r[m.key]!!.bundle.payload))
    }
}
