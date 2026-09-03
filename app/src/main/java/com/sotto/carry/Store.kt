package com.sotto.carry

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * What this phone holds for the network: its own messages, the ones it carries for others,
 * and the ids of ones it must not accept again. Pure Kotlin so it can be tested on a laptop.
 */
class Store(
    private val self: Long,
    private val now: () -> Long,
    private val maxBundles: Int = 2000,
    private val maxBytes: Int = 5_000_000,
) {
    class Entry(val bundle: Bundle, val sketch: Sketch, val receivedAt: Long, val from: Long?) {
        /** Phones this copy has been handed to; for our own bundles that is the count on the tile. */
        val handedTo = HashSet<Long>()
        var copies = bundle.copies
        var delivered: Triple<Int, Int, Long>? = null   // hops, minutes, when (own bundles only)
    }

    enum class Verdict { NEW, DUPLICATE, EXPIRED, TOMBSTONE, QUOTA, TOO_BIG, REPLACED }

    private val entries = LinkedHashMap<BundleKey, Entry>()
    private val tombstones = HashMap<BundleKey, Long>()
    private val quota = HashMap<Long, Pair<Long, Int>>()   // origin -> window start, count
    private val profiles = HashMap<Long, Int>()             // origin -> newest profile seq held
    var bytes = 0L
        private set

    val size: Int get() = entries.size
    fun all(): Collection<Entry> = entries.values
    operator fun get(key: BundleKey): Entry? = entries[key]
    fun contains(key: BundleKey) = entries.containsKey(key)

    /** Takes a bundle in from [from] (null when it is our own). */
    fun accept(b: Bundle, from: Long?): Verdict {
        val t = now()
        if (b.expiresAt <= t) return Verdict.EXPIRED
        if (b.payload.size > Bundle.MAX_PAYLOAD) return Verdict.TOO_BIG
        if (tombstones.containsKey(b.key)) return Verdict.TOMBSTONE
        val old = entries[b.key]
        if (old != null) {
            if (from != null) old.sketch.mark(from, b.key)   // they hold it too
            return Verdict.DUPLICATE
        }
        var verdict = Verdict.NEW
        if (b.kind == Bundle.KIND_PROFILE) {
            val newest = profiles[b.origin]
            if (newest != null && newest >= b.seq) return Verdict.DUPLICATE
            if (newest != null) { remove(BundleKey(b.origin, newest)); verdict = Verdict.REPLACED }
            profiles[b.origin] = b.seq
        } else if (from != null && b.origin != self && (b.kind == Bundle.KIND_ROOM)) {
            val (start, count) = quota[b.origin] ?: (t to 0)
            if (t - start > QUOTA_WINDOW_S) quota[b.origin] = t to 1
            else if (count >= QUOTA_PER_WINDOW) return Verdict.QUOTA
            else quota[b.origin] = start to count + 1
        }
        val e = Entry(b, Sketch(), t, from)
        e.sketch.mark(self, b.key)
        from?.let { e.sketch.mark(it, b.key) }
        entries[b.key] = e
        bytes += b.payload.size + Bundle.HEADER
        evictIfNeeded()
        return verdict
    }

    /** A receipt says the target reached its person: carriers drop it and never take it again. */
    fun receipt(target: BundleKey, expiresAt: Long): Entry? {
        tombstones[target] = expiresAt
        return remove(target)
    }

    fun remove(key: BundleKey): Entry? {
        val e = entries.remove(key) ?: return null
        bytes -= e.bundle.payload.size + Bundle.HEADER
        if (e.bundle.kind == Bundle.KIND_PROFILE && profiles[key.origin] == key.seq) profiles.remove(key.origin)
        return e
    }

    /** Drops what has run out. Returns the keys removed. */
    fun expire(): List<BundleKey> {
        val t = now()
        val gone = entries.values.filter { it.bundle.expiresAt <= t }.map { it.bundle.key }
        for (k in gone) remove(k)
        tombstones.entries.removeAll { it.value <= t }
        return gone
    }

    private fun evictIfNeeded() {
        if (entries.size <= maxBundles && bytes <= maxBytes) return
        // Oldest first, but what is ours or for us goes last.
        val order = entries.values.sortedWith(compareBy({ it.bundle.origin == self || it.bundle.dest == self }, { it.bundle.created }))
        for (e in order) {
            if (entries.size <= maxBundles && bytes <= maxBytes) break
            remove(e.bundle.key)
        }
    }

    /**
     * What to offer [peer]: not what they gave us, not what we already handed them, and a
     * spray bundle only while it still has copies to give, unless the peer is its destination.
     */
    fun offerable(peer: Long): List<Entry> = entries.values.filter { e ->
        val b = e.bundle
        if (e.from == peer || peer in e.handedTo) return@filter false
        if (b.expiresAt <= now()) return@filter false
        if (b.floods) true else b.dest == peer || e.copies > 1
    }

    /** The copies to hand [peer] for [e], halving a spray budget; null means nothing to give. */
    fun copiesToGive(e: Entry, peer: Long): Int? {
        val b = e.bundle
        if (b.floods) return 0
        if (b.dest == peer) return 1
        if (e.copies <= 1) return null
        val give = e.copies / 2
        e.copies -= give
        return give
    }

    fun markHanded(key: BundleKey, peer: Long) { entries[key]?.handedTo?.add(peer) }

    // ---- persistence: a small binary format, no Android involved ----

    fun export(): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeInt(FORMAT)
        d.writeInt(entries.size)
        for (e in entries.values) {
            val enc = e.bundle.encode()
            d.writeInt(enc.size); d.write(enc)
            d.write(e.sketch.bits)
            d.writeLong(e.receivedAt); d.writeLong(e.from ?: 0L); d.writeInt(e.copies)
            d.writeInt(e.handedTo.size); for (h in e.handedTo) d.writeLong(h)
            val del = e.delivered
            d.writeBoolean(del != null)
            if (del != null) { d.writeInt(del.first); d.writeInt(del.second); d.writeLong(del.third) }
        }
        d.writeInt(tombstones.size)
        for ((k, exp) in tombstones) { d.write(k.encode()); d.writeLong(exp) }
        return out.toByteArray()
    }

    fun import(data: ByteArray) {
        runCatching {
            val d = DataInputStream(data.inputStream())
            if (d.readInt() != FORMAT) return
            val n = d.readInt()
            repeat(n) {
                val enc = ByteArray(d.readInt()).also { d.readFully(it) }
                val bits = ByteArray(Sketch.SIZE).also { d.readFully(it) }
                val receivedAt = d.readLong(); val from = d.readLong().takeIf { it != 0L }; val copies = d.readInt()
                val handed = (0 until d.readInt()).map { d.readLong() }
                val delivered = if (d.readBoolean()) Triple(d.readInt(), d.readInt(), d.readLong()) else null
                val b = Bundle.decode(enc) ?: return@repeat
                if (b.expiresAt <= now()) return@repeat
                val e = Entry(b, Sketch(bits), receivedAt, from).also { it.copies = copies; it.handedTo.addAll(handed); it.delivered = delivered }
                entries[b.key] = e
                bytes += b.payload.size + Bundle.HEADER
                if (b.kind == Bundle.KIND_PROFILE) profiles[b.origin] = maxOf(profiles[b.origin] ?: 0, b.seq)
            }
            val tn = d.readInt()
            repeat(tn) { val k = ByteArray(12).also { d.readFully(it) }; tombstones[BundleKey.decode(k)] = d.readLong() }
        }
    }

    companion object {
        private const val FORMAT = 1
        const val QUOTA_WINDOW_S = 3600L
        const val QUOTA_PER_WINDOW = 20
    }
}
