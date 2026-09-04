package com.sotto.carry

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * The carry network: messages this phone holds for other people, and the meetings in which it
 * trades them. A message no longer has to cross the whole distance while everyone is in range
 * at once — it rides along in whoever happens to be walking that way.
 *
 * It runs over Bluetooth only. Sound stays what it always was: instant, local and silent for
 * whoever is in earshot right now. Everything here happens on the main thread, so the store and
 * the sync engine need no locking; only the file writing goes elsewhere.
 */
class CarryEngine(
    private val context: Context,
    private val self: Long,
    private val nextSeq: () -> Int,
    private val transport: Transport,
    private val events: Events,
) {
    interface Transport {
        /** Sends one sync frame to one phone. False if it is not reachable right now. */
        fun sendTo(peer: Long, frame: ByteArray): Boolean
        /** Phones in radio range, by 64-bit id. */
        fun peersInRange(): List<Long>
    }

    interface Events {
        /** A message carried here that this phone had not seen. */
        fun onCarried(bundle: Bundle)
        /** One of our own private messages reached its person. */
        fun onDelivered(key: BundleKey, hops: Int, minutes: Int)
        /** One of our own messages was handed to another phone; [count] phones so far. */
        fun onHanded(key: BundleKey, count: Int)
        /** The estimated number of phones one of our own messages has reached. */
        fun onReach(key: BundleKey, phones: Int)
        /** Something changed that the screen shows: how much is held, and how many bytes. */
        fun onStoreChanged(held: Int, bytes: Long)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "sotto-carry-io") }
    private val file = File(context.filesDir, "carry.bin")

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    val store = Store(self, ::nowSeconds)

    private val sync = Sync(self, store, ::nowSeconds, { peer, frame ->
        transport.sendTo(peer, frame).also { if (!it) Log.d(TAG, "sync frame to ${peer.toString(16)} went nowhere") }
    }, object : Sync.Events {
        override fun onBundle(bundle: Bundle, peer: Long) {
            if (bundle.kind != Bundle.KIND_PROFILE) Log.i(TAG, "carried in: kind ${bundle.kind}, ${bundle.hops} hops")
            events.onCarried(bundle)
            saveSoon()
        }
        override fun onDelivered(key: BundleKey, hops: Int, minutes: Int) { events.onDelivered(key, hops, minutes); saveSoon() }
        override fun onHanded(key: BundleKey, peer: Long, count: Int) { events.onHanded(key, count); saveSoon() }
        override fun onReach(key: BundleKey, phones: Int) { events.onReach(key, phones); saveSoon() }
        override fun onSyncDone(peer: Long, received: Int, sent: Int) {
            if (received > 0 || sent > 0) Log.i(TAG, "met ${peer.toString(16)}: took $received, gave $sent")
            events.onStoreChanged(store.size, store.bytes)
        }
    })

    /** Whether this phone carries messages for other people at all. */
    var carrying = true

    private var started = false
    private var saveQueued = false

    private val upkeep = object : Runnable {
        override fun run() {
            if (store.expire().isNotEmpty()) { events.onStoreChanged(store.size, store.bytes); saveSoon() }
            if (carrying) for (peer in transport.peersInRange()) if (sync.due(peer)) sync.start(peer)
            main.postDelayed(this, UPKEEP_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        io.execute {
            val data = runCatching { if (file.exists()) file.readBytes() else null }.getOrNull()
            main.post {
                data?.let { store.import(it); Log.i(TAG, "carrying ${store.size} messages, ${store.bytes / 1024} KB") }
                events.onStoreChanged(store.size, store.bytes)
            }
        }
        main.postDelayed(upkeep, 4_000)
    }

    fun stop() {
        started = false
        main.removeCallbacks(upkeep)
        save()
    }

    /** A phone came into range: trade with it if enough time has passed. */
    fun onPeerSeen(peer: Long) {
        if (!started || !carrying || peer == self) return
        if (sync.due(peer)) sync.start(peer)
    }

    fun isSyncFrame(frame: ByteArray) = sync.isSyncFrame(frame)

    /** A sync frame arrived over the radio, from the device at [link]. */
    fun onFrame(frame: ByteArray, link: String? = null) {
        if (!started) return
        sync.onFrame(frame, link)
    }

    // ---- putting things into the network ----------------------------------------------------

    /**
     * A room message: spreads to everyone, for a day.
     *
     * [seq] is the message's own sequence, not a fresh one. The same number names this message
     * on the speaker and in the network, which is what lets a phone that hears it both ways show
     * it once, and what lets the sender's tile find its own bundle again to report on it.
     */
    fun postRoom(seq: Int, text: ByteArray): BundleKey =
        post(Bundle(self, seq, Bundle.KIND_ROOM, nowSeconds(), ROOM_TTL_MIN, 0L, 0, 0, text))

    /** A private message, already sealed: a limited number of copies, for three days. */
    fun postPrivate(seq: Int, dest: Long, sealed: ByteArray): BundleKey =
        post(Bundle(self, seq, Bundle.KIND_PRIVATE, nowSeconds(), PRIVATE_TTL_MIN, dest, 0, SPRAY_COPIES, sealed))

    /** Who this phone is, so someone who has never met it can still write to it. */
    fun postProfile(name: String, publicKey: ByteArray): BundleKey =
        post(Bundle(self, nextSeq(), Bundle.KIND_PROFILE, nowSeconds(), PROFILE_TTL_MIN, 0L, 0, 0, Bundle.profilePayload(name, publicKey)))

    /** A private message reached us: tell the sender, and let the carriers drop their copies. */
    fun acknowledge(b: Bundle) {
        val r = sync.receiptFor(b, nextSeq())
        store.receipt(b.key, b.expiresAt)
        post(r)
    }

    private fun post(b: Bundle): BundleKey {
        store.accept(b, null)
        saveSoon()
        events.onStoreChanged(store.size, store.bytes)
        // Anyone already in range should have it now rather than at the next upkeep tick.
        if (carrying) for (peer in transport.peersInRange()) if (sync.due(peer)) sync.start(peer)
        return b.key
    }

    // ---- what the screen asks -----------------------------------------------------------------

    fun entry(key: BundleKey): Store.Entry? = store[key]
    fun handedCount(key: BundleKey): Int = store[key]?.handedTo?.size ?: 0
    fun reachEstimate(key: BundleKey): Int = store[key]?.sketch?.estimate() ?: 0
    fun delivered(key: BundleKey): Triple<Int, Int, Long>? = store[key]?.delivered

    /** Newest profile held for [id], as name and public key. */
    fun profileOf(id: Long): Pair<String, ByteArray>? =
        store.all().firstOrNull { it.bundle.kind == Bundle.KIND_PROFILE && it.bundle.origin == id }
            ?.let { Bundle.parseProfile(it.bundle.payload) }

    fun knownProfiles(): List<Pair<Long, Pair<String, ByteArray>>> =
        store.all().filter { it.bundle.kind == Bundle.KIND_PROFILE && it.bundle.origin != self }
            .mapNotNull { e -> Bundle.parseProfile(e.bundle.payload)?.let { e.bundle.origin to it } }

    val held: Int get() = store.size
    val heldBytes: Long get() = store.bytes

    fun forget() {
        for (k in store.all().map { it.bundle.key }) store.remove(k)
        save()
        events.onStoreChanged(store.size, store.bytes)
    }

    // ---- keeping it on disk -------------------------------------------------------------------

    private fun saveSoon() {
        if (saveQueued) return
        saveQueued = true
        main.postDelayed({ saveQueued = false; save() }, 3_000)
    }

    private fun save() {
        // export() walks the whole store and can build megabytes; the snapshot is taken here on
        // the main thread because the store has no locking, but nothing else happens here.
        val data = runCatching { store.export() }.getOrNull() ?: return
        io.execute {
            runCatching {
                val tmp = File(file.parentFile, "carry.tmp")
                java.io.FileOutputStream(tmp).use { out ->
                    out.write(data)
                    out.flush()
                    runCatching { out.fd.sync() }   // the rename must not overtake the bytes
                }
                if (!tmp.renameTo(file)) tmp.delete()
            }.onFailure { Log.w(TAG, "could not save what we carry", it) }
        }
    }

    companion object {
        private const val TAG = "SottoCarry"
        const val ROOM_TTL_MIN = 24 * 60
        const val PRIVATE_TTL_MIN = 72 * 60
        const val PROFILE_TTL_MIN = 7 * 24 * 60
        /** Copies a private message may spawn: it halves at each meeting until one is left. */
        const val SPRAY_COPIES = 16
        private const val UPKEEP_MS = 30_000L
    }
}
