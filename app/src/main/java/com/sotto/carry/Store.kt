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
    class Entry(
        val bundle: Bundle,
        val sketch: Sketch,
        val receivedAt: Long,
        val from: Long?,
        /**
         * When this phone will drop it. Not bundle.expiresAt: the header's creation time and
         * lifetime are both written by whoever sent it, so a hostile bundle could claim to have
         * been made in the future and never expire. This is that claim, held to what its kind
         * is allowed and measured from when we actually took it in.
         */
        val expiresAt: Long,
    ) {
        /** Phones this copy has been handed to; for our own bundles that is the count on the tile. */
        val handedTo = HashSet<Long>()
        var copies = Bundle.legalCopies(bundle.kind, bundle.copies)
        var delivered: Triple<Int, Int, Long>? = null   // hops, minutes, when (own bundles only)
    }

    enum class Verdict { NEW, DUPLICATE, EXPIRED, TOMBSTONE, QUOTA, TOO_BIG, REPLACED }

    private val entries = LinkedHashMap<BundleKey, Entry>()
    private val tombstones = HashMap<BundleKey, Long>()
    private val quota = HashMap<Long, Pair<Long, Int>>()   // origin -> window start, count
    private val intake = HashMap<String, Pair<Long, Long>>() // radio link -> window start, bytes taken
    private val profiles = HashMap<Long, Int>()             // origin -> newest profile seq held
    var bytes = 0L
        private set

    val size: Int get() = entries.size
    fun all(): Collection<Entry> = entries.values
    operator fun get(key: BundleKey): Entry? = entries[key]
    fun contains(key: BundleKey) = entries.containsKey(key)

    /** Takes a bundle in from [from] (null when it is our own). */
    fun accept(b: Bundle, from: Long?, link: String? = null): Verdict {
        val t = now()
        if (b.payload.size > Bundle.MAX_PAYLOAD) return Verdict.TOO_BIG
        // A carried network has no shared clock, so a deadline has to come from somewhere. It
        // cannot come from when WE first saw it -- every hop would then start the clock again
        // and the message would live for ever. So it comes from the sender's creation time,
        // held to what the kind allows, with anything claiming to be from the future refused
        // outright. Phones keep time automatically; one that does not is the case this trades
        // away, and it only costs that phone the ability to hand its own messages on.
        if (b.created > t + CLOCK_SKEW_S) return Verdict.EXPIRED
        val expiresAt = minOf(b.expiresAt, b.created + Bundle.maxTtlMinutes(b.kind) * 60L)
        if (expiresAt <= t) return Verdict.EXPIRED
        if (tombstones.containsKey(b.key)) return Verdict.TOMBSTONE
        val old = entries[b.key]
        if (old != null) {
            if (from != null) old.sketch.mark(from, b.key)   // they hold it too
            return Verdict.DUPLICATE
        }
        var verdict = Verdict.NEW
        // The per-origin quota below is keyed on an identity anyone can mint, so it only slows
        // an honest phone down. This one is keyed on the radio the bytes actually arrived on --
        // the Bluetooth address of the phone in range, not the sender id written in the header,
        // which the sender chooses and can vary per frame.
        val budgetKey = link ?: from?.toString()
        if (from != null && budgetKey != null) {
            val (started, taken) = intake[budgetKey] ?: (t to 0L)
            val window = if (t - started > INTAKE_WINDOW_S) t to 0L else started to taken
            val size = b.payload.size + Bundle.HEADER
            if (window.second + size > INTAKE_BYTES_PER_WINDOW) return Verdict.QUOTA
            intake[budgetKey] = window.first to window.second + size
        }
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
        val e = Entry(b, Sketch(), t, from, expiresAt)
        e.sketch.mark(self, b.key)
        from?.let { e.sketch.mark(it, b.key) }
        entries[b.key] = e
        bytes += b.payload.size + Bundle.HEADER
        evictIfNeeded()
        return verdict
    }

    /** A receipt says the target reached its person: carriers drop it and never take it again. */
    fun receipt(target: BundleKey, expiresAt: Long): Entry? {
        // The lifetime comes off a bundle from the air, so it is held to the longest a message
        // of any kind may live. A tombstone is a refusal to ever take something again; it must
        // not be possible to make one permanent.
        tombstones[target] = minOf(expiresAt, now() + Bundle.maxTtlMinutes(Bundle.KIND_PROFILE) * 60L)
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
        val gone = entries.values.filter { it.expiresAt <= t }.map { it.bundle.key }
        for (k in gone) remove(k)
        tombstones.entries.removeAll { it.value <= t }
        // These are keyed by whoever we have met, so without pruning they are a slow leak that
        // a phone minting identities could turn into a fast one.
        quota.entries.removeAll { t - it.value.first > QUOTA_WINDOW_S }
        intake.entries.removeAll { t - it.value.first > INTAKE_WINDOW_S }
        return gone
    }

    private fun evictIfNeeded() {
        if (entries.size <= maxBundles && bytes <= maxBytes) return
        // Oldest first, but what is ours or for us goes last.
        // Ordered by when WE took it in, not by the creation time in the header: that is written
        // by the sender, so sorting on it would let a flood of freshly-dated bundles push out
        // everything real.
        val order = entries.values.sortedWith(compareBy({ it.bundle.origin == self || it.bundle.dest == self }, { it.receivedAt }))
        for (e in order) {
            if (entries.size <= maxBundles && bytes <= maxBytes) break
            remove(e.bundle.key)
        }
    }

    /**
     * What to tell [peer] we are holding. Deliberately wider than what we would hand over: a
     * message they already have is exactly the one whose reach sketch we want back from them,
     * and advertising only what we would give meant the sender never heard anything about its
     * own message after the first handover. They ask for what they lack, so offering more
     * costs a few bytes and nothing else.
     */
    fun advertisable(peer: Long): List<Entry> {
        val t = now()
        return entries.values.filter { it.expiresAt > t }
    }

    /**
     * What to offer [peer]: not what they gave us, not what we already handed them, and a
     * spray bundle only while it still has copies to give, unless the peer is its destination.
     */
    fun offerable(peer: Long): List<Entry> = entries.values.filter { e ->
        val b = e.bundle
        if (e.from == peer || peer in e.handedTo) return@filter false
        if (e.expiresAt <= now()) return@filter false
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

    /** Puts back what [copiesToGive] took, for a handover that never left the radio. */
    fun returnCopies(e: Entry, peer: Long, given: Int) {
        if (e.bundle.floods || e.bundle.dest == peer) return   // those branches take nothing
        e.copies += given
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
                val size = d.readInt()
                if (size < 0 || size > Bundle.HEADER + Bundle.MAX_PAYLOAD) return   // the file is not ours
                val enc = ByteArray(size).also { d.readFully(it) }
                val bits = ByteArray(Sketch.SIZE).also { d.readFully(it) }
                val receivedAt = d.readLong(); val from = d.readLong().takeIf { it != 0L }; val copies = d.readInt()
                val handed = (0 until d.readInt()).map { d.readLong() }
                val delivered = if (d.readBoolean()) Triple(d.readInt(), d.readInt(), d.readLong()) else null
                val b = Bundle.decode(enc) ?: return@repeat
                if (b.expiresAt <= now()) return@repeat
                if (entries.containsKey(b.key)) return@repeat
                val expiresAt = minOf(b.expiresAt, b.created + Bundle.maxTtlMinutes(b.kind) * 60L)
                val e = Entry(b, Sketch(bits), receivedAt, from, expiresAt)
                    .also { it.copies = Bundle.legalCopies(b.kind, copies); it.handedTo.addAll(handed); it.delivered = delivered }
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
        /** What one phone in range may hand this one in an hour. An honest meeting is far under. */
        /** How far ahead of us another phone's clock may be before we stop believing its dates. */
        const val CLOCK_SKEW_S = 3600L
        const val INTAKE_WINDOW_S = 3600L
        const val INTAKE_BYTES_PER_WINDOW = 1_000_000L
    }
}
