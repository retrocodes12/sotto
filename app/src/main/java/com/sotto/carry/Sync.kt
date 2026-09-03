package com.sotto.carry

import java.nio.ByteBuffer

/**
 * What two phones say to each other when they meet, so each ends up holding what the other
 * has. Every frame starts with a tag byte in the 0xC0 range and the sender's 64-bit id.
 *
 *   HAVE    C1 | from 8 | n 2 | (key 12, kind 1) * n     what I could give you
 *   WANT    C2 | from 8 | n 2 | key 12 * n               what I lack of that
 *   BUNDLE  C3 | from 8 | bundle                          one message, copies already set for you
 *   SKETCH  C4 | from 8 | n 2 | (key 12, bits 32) * n    reach sketches for what we both hold
 *   DONE    C5 | from 8
 *
 * Both sides send HAVE; each answers the other's HAVE with WANT, each WANT with BUNDLEs, then
 * SKETCHes for the overlap and DONE. Pure Kotlin: the caller supplies the transport.
 */
class Sync(
    private val self: Long,
    private val store: Store,
    private val now: () -> Long,
    private val send: (peer: Long, frame: ByteArray) -> Unit,
    private val events: Events,
) {
    interface Events {
        /** A bundle we did not have arrived from [peer]. */
        fun onBundle(bundle: Bundle, peer: Long)
        /** A receipt for one of our own bundles came back. */
        fun onDelivered(key: BundleKey, hops: Int, minutes: Int)
        /** One of our own bundles was handed to a new phone. */
        fun onHanded(key: BundleKey, peer: Long, count: Int)
        /** The reach estimate for one of our own bundles changed. */
        fun onReach(key: BundleKey, phones: Int)
        fun onSyncDone(peer: Long, received: Int, sent: Int)
    }

    private val lastSync = HashMap<Long, Long>()
    private val counters = HashMap<Long, IntArray>()   // peer -> received, sent this session

    /** True when it is worth talking to [peer] again. */
    fun due(peer: Long): Boolean = now() - (lastSync[peer] ?: 0L) >= INTERVAL_S

    /** Opens a sync with [peer] by offering what we hold. */
    fun start(peer: Long) {
        lastSync[peer] = now()
        counters[peer] = IntArray(2)
        sendHave(peer)
    }

    private fun sendHave(peer: Long) {
        val offer = store.offerable(peer).sortedByDescending { it.bundle.created }.take(MAX_HAVE)
        val bb = ByteBuffer.allocate(11 + offer.size * 13)
        bb.put(TAG_HAVE).putLong(self).putShort(offer.size.toShort())
        for (e in offer) { bb.put(e.bundle.key.encode()); bb.put(e.bundle.kind.toByte()) }
        send(peer, bb.array())
    }

    fun isSyncFrame(frame: ByteArray) = frame.isNotEmpty() && (frame[0].toInt() and 0xFF) in TAG_HAVE.toInt()..TAG_DONE.toInt()

    /** Feeds a frame that arrived by radio. Ignores anything that is not ours. */
    fun onFrame(frame: ByteArray) {
        if (frame.size < 9) return
        val tag = frame[0]
        val peer = ByteBuffer.wrap(frame, 1, 8).long
        if (peer == self) return
        if (!lastSync.containsKey(peer)) { lastSync[peer] = now(); counters[peer] = IntArray(2); if (tag == TAG_HAVE) sendHave(peer) }
        when (tag) {
            TAG_HAVE -> onHave(peer, frame)
            TAG_WANT -> onWant(peer, frame)
            TAG_BUNDLE -> onBundle(peer, frame)
            TAG_SKETCH -> onSketch(peer, frame)
            TAG_DONE -> { val c = counters[peer] ?: IntArray(2); events.onSyncDone(peer, c[0], c[1]) }
        }
    }

    private fun onHave(peer: Long, frame: ByteArray) {
        val bb = ByteBuffer.wrap(frame, 9, frame.size - 9)
        val n = bb.short.toInt() and 0xFFFF
        val want = ArrayList<BundleKey>()
        val shared = ArrayList<BundleKey>()
        for (i in 0 until n) {
            if (bb.remaining() < 13) break
            val kb = ByteArray(12).also { bb.get(it) }; bb.get()
            val key = BundleKey.decode(kb)
            if (store.contains(key)) shared.add(key) else if (want.size < MAX_WANT) want.add(key)
        }
        val wb = ByteBuffer.allocate(11 + want.size * 12)
        wb.put(TAG_WANT).putLong(self).putShort(want.size.toShort())
        for (k in want) wb.put(k.encode())
        send(peer, wb.array())
        if (shared.isNotEmpty()) sendSketches(peer, shared)
    }

    private fun onWant(peer: Long, frame: ByteArray) {
        val bb = ByteBuffer.wrap(frame, 9, frame.size - 9)
        val n = bb.short.toInt() and 0xFFFF
        var sent = 0
        for (i in 0 until n) {
            if (bb.remaining() < 12) break
            val key = BundleKey.decode(ByteArray(12).also { bb.get(it) })
            val e = store[key] ?: continue
            val copies = store.copiesToGive(e, peer) ?: continue
            val out = e.bundle.withHops(minOf(255, e.bundle.hops + 1), copies)
            val enc = out.encode()
            send(peer, ByteBuffer.allocate(9 + enc.size).put(TAG_BUNDLE).putLong(self).put(enc).array())
            store.markHanded(key, peer)
            e.sketch.mark(peer, key)
            sent++
            if (e.bundle.origin == self) events.onHanded(key, peer, e.handedTo.size)
        }
        counters[peer]?.let { it[1] += sent }
        send(peer, ByteBuffer.allocate(9).put(TAG_DONE).putLong(self).array())
    }

    private fun onBundle(peer: Long, frame: ByteArray) {
        val b = Bundle.decode(frame, 9) ?: return
        when (b.kind) {
            Bundle.KIND_RECEIPT -> {
                val (target, hops, minutes) = Bundle.parseReceipt(b.payload) ?: return
                if (target.origin == self) {
                    // our own message arrived: keep it so the tile can say "delivered", and
                    // stop it spreading further (the receipt vaccinates the carriers).
                    store[target]?.let { it.delivered = Triple(hops, minutes, now()); it.copies = 1 }
                    events.onDelivered(target, hops, minutes)
                } else {
                    store.receipt(target, b.expiresAt)   // a carrier: drop it and never take it again
                }
                if (store.accept(b, peer) == Store.Verdict.NEW) { counters[peer]?.let { it[0]++ } }
            }
            else -> if (store.accept(b, peer) == Store.Verdict.NEW) { counters[peer]?.let { it[0]++ }; events.onBundle(b, peer) }
        }
    }

    private fun sendSketches(peer: Long, keys: List<BundleKey>) {
        val take = keys.take(MAX_SKETCH)
        val bb = ByteBuffer.allocate(11 + take.size * 44)
        bb.put(TAG_SKETCH).putLong(self).putShort(take.size.toShort())
        for (k in take) { bb.put(k.encode()); bb.put(store[k]?.sketch?.bits ?: ByteArray(Sketch.SIZE)) }
        send(peer, bb.array())
    }

    private fun onSketch(peer: Long, frame: ByteArray) {
        val bb = ByteBuffer.wrap(frame, 9, frame.size - 9)
        val n = bb.short.toInt() and 0xFFFF
        for (i in 0 until n) {
            if (bb.remaining() < 44) break
            val key = BundleKey.decode(ByteArray(12).also { bb.get(it) })
            val bits = ByteArray(Sketch.SIZE).also { bb.get(it) }
            val e = store[key] ?: continue
            val before = e.sketch.estimate()
            e.sketch.merge(Sketch(bits))
            e.sketch.mark(peer, key)
            val after = e.sketch.estimate()
            if (e.bundle.origin == self && after != before) events.onReach(key, after)
        }
    }

    /** Builds a receipt for a private bundle that reached us. */
    fun receiptFor(b: Bundle, seq: Int): Bundle {
        val minutes = ((now() - b.created) / 60).toInt().coerceIn(0, 65535)
        val ttl = ((b.expiresAt - now()) / 60).toInt().coerceIn(30, 65535)
        return Bundle(self, seq, Bundle.KIND_RECEIPT, now(), ttl, b.origin, 0, 0, Bundle.receiptPayload(b.key, b.hops, minutes))
    }

    companion object {
        const val TAG_HAVE: Byte = 0xC1.toByte()
        const val TAG_WANT: Byte = 0xC2.toByte()
        const val TAG_BUNDLE: Byte = 0xC3.toByte()
        const val TAG_SKETCH: Byte = 0xC4.toByte()
        const val TAG_DONE: Byte = 0xC5.toByte()
        const val INTERVAL_S = 180L
        const val MAX_HAVE = 400
        const val MAX_WANT = 200
        const val MAX_SKETCH = 100
    }
}
