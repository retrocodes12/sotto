package com.sotto

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sotto.carry.Bundle
import com.sotto.carry.BundleKey
import com.sotto.carry.CarryEngine
import com.sotto.carry.Ids
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Compose state that is also written to disk, so a setting the user changed stays changed. */
private class Remembered<T>(
    private val get: (String, T) -> T,
    private val put: (String, T) -> Unit,
    private val name: String,
    fallback: T,
) {
    private val state = androidx.compose.runtime.mutableStateOf(get(name, fallback))
    operator fun getValue(owner: Any?, property: kotlin.reflect.KProperty<*>): T = state.value
    operator fun setValue(owner: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
        state.value = value
        put(name, value)
    }
}

class LogEntry(
    val id: Long,
    val time: String,
    val kind: Kind,
    val text: String,
    val protocol: String,
    val bytes: Int,
    val image: Bitmap? = null,
    val progress: String? = null,
    val fraction: Float? = null,
    val senderId: Int? = null,
    val via: Int? = null,
    /** The other person of a private chat; null for the room. */
    val peer: Int? = null,
    /** Sequence number of a sent private message, until its receipt arrives. */
    val seq: Int? = null,
    val delivered: Boolean = false,
    /** The photo's JPEG bytes, kept until History has written them to a file. */
    val imageBytes: ByteArray? = null,
    /** A shared link, Wi-Fi network or contact: kind and its fields. */
    val card: Card? = null,
    /** The carried message this tile belongs to, if it went into the network. */
    val bundleSeq: Int? = null,
    /** Phones this one handed it to, the reach estimate, and how it was delivered. */
    val handed: Int = 0,
    val reach: Int = 0,
    val carriedHops: Int = 0,
    val deliveredAfter: Pair<Int, Int>? = null,   // hops, minutes
) {
    enum class Kind { RX, TX, INFO }

    class Card(val kind: Int, val fields: List<String>)

    fun with(
        text: String = this.text, image: Bitmap? = this.image, progress: String? = this.progress,
        bytes: Int = this.bytes, fraction: Float? = this.fraction, senderId: Int? = this.senderId, via: Int? = this.via,
        delivered: Boolean = this.delivered, imageBytes: ByteArray? = this.imageBytes, protocol: String = this.protocol,
        handed: Int = this.handed, reach: Int = this.reach, deliveredAfter: Pair<Int, Int>? = this.deliveredAfter,
    ) = LogEntry(id, time, kind, text, protocol, bytes, image, progress, fraction, senderId, via, peer, seq, delivered, imageBytes, card,
        bundleSeq, handed, reach, carriedHops, deliveredAfter)
}

/** Owns the [SoundLink] and exposes everything the UI shows as Compose state. */
class MainViewModel(app: Application) : AndroidViewModel(app), SoundLink.Callbacks {

    private val main = Handler(Looper.getMainLooper())
    private val link = SoundLink(app, this)
    val identity = IdentityStore(app)

    private fun remembered(name: String, fallback: Boolean) =
        Remembered(identity::flag, identity::setFlag, name, fallback)

    private fun counted(name: String, fallback: Int) =
        Remembered(identity::number, identity::setNumber, name, fallback)

    /**
     * The radio, beside the speaker. It carries exactly the same frames, so names, private
     * chats, receipts, relaying and photo transfers work over it without knowing it exists.
     * Everything falls back to sound the moment no phone is in Bluetooth range.
     */
    private val ble = BleLink(app, object : BleLink.Callbacks {
        override fun onBleFrame(payload: ByteArray, link: String) {
            // The carry network's own frames never reach the sound world's parser.
            if (carry.isSyncFrame(payload)) main.post { carry.onFrame(payload, link) }
            else onMessage(payload, BleLink.PROTOCOL_ID, 0f)
        }
        override fun onBlePresence(id64: Long, id16: Int, rssi: Int) {
            main.post {
                if (id16 != identity.id) { noteSender(id16, BleLink.PROTOCOL_ID); identity.learnWideId(id64) }
                carry.onPeerSeen(id64)
            }
        }
        override fun onBleState(running: Boolean, peers: Int, note: String?) {
            main.post { bleRunning = running; blePeers = peers; bleNote = note }
        }
    })

    /**
     * Messages this phone holds for other people. A message no longer has to cross the whole
     * distance while everyone is in range at once: it rides along with whoever is walking that
     * way, and arrives minutes or hours later somewhere the sender could never have reached.
     */
    val carry: CarryEngine = CarryEngine(app, identity.id64, identity::nextSeq, object : CarryEngine.Transport {
        override fun sendTo(peer: Long, frame: ByteArray): Boolean =
            bluetoothOn && ble.sendTo(peer, frame) { }
        override fun peersInRange(): List<Long> = if (bluetoothOn) ble.nearbyIds64(PRESENT_FOR_MS) else emptyList()
    }, object : CarryEngine.Events {
        override fun onCarried(bundle: Bundle) { onCarriedBundle(bundle) }
        override fun onDelivered(key: BundleKey, hops: Int, minutes: Int) {
            logFor(key)?.let { id -> updateLog(id) { it.with(delivered = true, deliveredAfter = hops to minutes) } }
        }
        override fun onHanded(key: BundleKey, count: Int) { logFor(key)?.let { id -> updateLog(id) { it.with(handed = count) } } }
        override fun onReach(key: BundleKey, phones: Int) { logFor(key)?.let { id -> updateLog(id) { it.with(reach = phones) } } }
        override fun onStoreChanged(held: Int, bytes: Long) { carryHeld = held; carryBytes = bytes }
    })

    var carryHeld by mutableIntStateOf(0)
        private set
    var carryBytes by mutableStateOf(0L)
        private set
    var carryOn by remembered("carry", true)
        private set

    fun setCarry(on: Boolean) {
        carryOn = on
        carry.carrying = on
        if (!on) carry.forget()
    }

    init {
        // The engine is built before the setting is read, so tell it what the user actually chose.
        carry.carrying = carryOn
    }

    /** The tile that belongs to one of our own carried messages. */
    private fun logFor(key: BundleKey): Long? =
        if (key.origin != identity.id64) null else log.firstOrNull { it.bundleSeq == key.seq }?.id

    /** The user's choice. Off keeps every frame on the speaker. */
    var bluetoothOn by remembered("bluetooth", true)
        private set
    var bleRunning by mutableStateOf(false)
        private set
    var blePeers by mutableIntStateOf(0)
        private set
    var bleNote by mutableStateOf<String?>(null)
        private set

    val bluetoothAvailable: Boolean get() = ble.hasHardware()

    /** True when the radio would actually carry a frame right now. */
    private fun bleReady(): Boolean = bluetoothOn && ble.canSend()

    fun setBluetooth(on: Boolean) {
        bluetoothOn = on
        if (on) startBle() else { ble.stop(); blePeers = 0 }
    }

    /** Called once the permissions exist. Safe to call again. */
    fun startBle() {
        if (!bluetoothOn || !ble.hasHardware()) return
        if (!wantListening && listeningDecided) return   // off means off, radio included
        if (ble.missingPermissions().isNotEmpty()) { bleNote = "Bluetooth permission not granted"; return }
        ble.start(identity.id, identity.id64)
        publishProfile()
    }

    fun blePermissionsNeeded(): List<String> = if (bluetoothOn && ble.hasHardware()) ble.missingPermissions() else emptyList()

    /**
     * One frame out on the best transport. The radio takes it immediately when a phone is in
     * range; otherwise it joins the sound thread's queue with the pauses that channel needs.
     */
    private fun dispatch(frame: ByteArray, protocolId: Int, volume: Int, delayMs: Long = 0L, quiet: Boolean = true) {
        if (bleReady() && ble.send(frame) { ok -> if (!ok) overTheSpeaker(frame, protocolId, volume, delayMs, quiet) }) return
        overTheSpeaker(frame, protocolId, volume, delayMs, quiet)
    }

    /** The speaker path, used directly and as the radio's fallback. */
    private fun overTheSpeaker(frame: ByteArray, protocolId: Int, volume: Int, delayMs: Long = 0L, quiet: Boolean = true) {
        val pid = overSpeaker(protocolId)
        link.post {
            if (delayMs > 0) SystemClock.sleep(delayMs)
            if (quiet) link.waitUntilQuiet(RELAY_QUIET_WAIT_MS)
            link.transmit(frame, pid, overSpeakerVolume(pid, volume))
        }
    }

    /**
     * A reply to something heard over the radio carries the radio's protocol id, which the
     * modem knows nothing about. If the radio is gone by the time we answer, the frame still
     * has to leave through the speaker, so it needs a real protocol.
     */
    private fun overSpeaker(protocolId: Int): Int =
        if (protocolId == BleLink.PROTOCOL_ID) textProtocolId else protocolId

    private fun overSpeakerVolume(pid: Int, volume: Int): Int =
        if (pid >= Modem.SOTTO_ID_BASE) volume else minOf(volume, Modem.GGWAVE_MAX_VOLUME)
    private val helloSentAt = HashMap<Int, Long>()

    /** Repeat what this phone hears so phones out of earshot of the sender still get it. */
    var relayForOthers by remembered("relay", true)

    /** Keep the microphone open with the screen off, behind a quiet notification. */
    var listenInBackground by remembered("background", true)
        private set

    fun keepListeningInBackground(on: Boolean) {
        listenInBackground = on
        if (!on) {
            ListenService.stop(getApplication())
            if (!inForeground) { link.stopListening(); ble.stop() }
        } else if (wantListening && !ListenService.start(getApplication())) {
            listenInBackground = false
            status = "Android would not let Sotto listen in the background."
        }
    }

    /** A link, Wi-Fi network or contact, always on Near: one frame, arm's length, audible. */
    fun sendCard(kind: Int, fields: List<String>) {
        if (transferring || burstSending) { status = "Wait, still sending."; return }
        val clean = fields.map { it.trim().replace('\u001F', ' ') }
        val frame = Wire.card(identity.id, identity.nextSeq(), kind, clean)
        if (frame.size > Transfer.MAX_FRAME) { status = "That is too long to fit in one card."; return }
        val pid = Modem.NEAR_PROTOCOL_ID
        val vol = effectiveVolumeFor(pid)
        status = null
        // Deliberately not over the radio: a card is the "hold the phones together" gesture, and
        // a Wi-Fi password should not carry to every phone within thirty metres.
        link.post {
            val ok = link.transmit(frame, pid, vol)
            main.post { if (ok) addLog(LogEntry.Kind.TX, cardTitle(kind, clean), pid, frame.size, card = LogEntry.Card(kind, clean)) }
        }
    }

    private fun cardTitle(kind: Int, f: List<String>) = when (kind) {
        Wire.CARD_LINK -> f.getOrElse(0) { "" }
        Wire.CARD_WIFI -> "Wi-Fi: ${f.getOrElse(0) { "" }}"
        else -> f.getOrElse(0) { "" }
    }

    /** Reach test: three probes, everyone answers with how loudly each arrived. */
    class Reach(val started: Long) {
        var probesSent by mutableIntStateOf(0)
        var running by mutableStateOf(true)
        /** Sequence numbers this test used; replies to older probes are ignored. */
        val seqs = HashSet<Int>()
        /** peer -> SNRs in dB: theirs of our probe, and ours of their reply. */
        val heard = mutableStateMapOf<Int, List<Int>>()
    }
    var reach by mutableStateOf<Reach?>(null)
        private set
    private var lastSnrDb = 0f
    private var reachSeq = 0

    fun startReach() {
        if (busy || reach?.running == true) return
        val r = Reach(SystemClock.elapsedRealtime())
        reach = r
        val pid = textProtocolId
        val vol = effectiveVolumeFor(pid)
        val base = (Random.nextInt(0, 80) * 3) and 0xFF
        link.post {
            for (k in 0 until REACH_PROBES) {
                val seq = (base + k) and 0xFF
                main.post { reachSeq = seq; r.seqs.add(seq) }
                link.waitUntilQuiet(RELAY_QUIET_WAIT_MS)
                link.transmit(Wire.probe(identity.id, seq), pid, vol)
                main.post { r.probesSent = k + 1 }
                SystemClock.sleep(REACH_GAP_MS)
            }
            main.post { r.running = false; Log.i(TAG, "reach finished: ${r.heard.map { "${IdentityStore.tagOf(it.key)}=${it.value}" }}") }
        }
    }

    fun dismissReach() { reach = null }

    /** Bar 0..1 and a verdict for a peer's SNR list. */
    fun reachVerdict(snrs: List<Int>): Pair<Float, String> {
        if (snrs.isEmpty()) return 0f to "no answer"
        val snr = snrs.sorted()[snrs.size / 2]
        val bar = ((snr - 8f) / 27f).coerceIn(0.05f, 1f)
        val text = when {
            snr >= 28 -> "strong, $snr dB. Silent messages will be fine."
            snr >= 20 -> "good, $snr dB. Silent is fine; photos may need a step closer."
            snr >= 14 -> "at the edge, $snr dB. Silent will drop some; audible would be safer."
            else -> "weak, $snr dB. Move closer or switch to audible."
        }
        return bar to text
    }

    /** Keys offered for someone we already hold a key for, until the user accepts one. */
    val pendingKeys = mutableStateMapOf<Int, ByteArray>()

    fun acceptNewKey(peer: Int) {
        val pub = pendingKeys.remove(peer) ?: return
        if (identity.learnPublicKey(peer, pub)) {
            addLog(LogEntry.Kind.INFO, "Now using ${identity.nameFor(peer)}'s new key.", textProtocolId, 0, peer = peer)
            keySentAt.remove(peer)
            sendKey(peer)
        }
    }

    fun rejectNewKey(peer: Int) { pendingKeys.remove(peer) }

    /** Fingerprint of a peer's key, for reading out loud across the room. */
    fun fingerprintOf(peer: Int): String? = identity.peerPubs[peer]?.let { Crypto.fingerprint(it) }
    val myFingerprint: String get() = Crypto.fingerprint(identity.publicKey)

    /** Private chats. [openChat] is the peer whose chat is on screen; null is the room. */
    var openChat by mutableStateOf<Int?>(null)
        private set
    val unread = mutableStateMapOf<Int, Int>()
    private val keySentAt = HashMap<Int, Long>()
    /**
     * Whether the hand-written X25519 gives the RFC 7748 answer on this device. Checked off the
     * main thread -- it is another full ladder, and nothing needs it until the user opens a
     * private chat. Assumed good until proven otherwise, then latched false.
     */
    var cryptoOk by mutableStateOf(true)
        private set

    init {
        Thread({
            val ok = Crypto.selfTest()
            if (!ok) Log.e(TAG, "X25519 self-test failed; private chat disabled")
            main.post { cryptoOk = ok }
        }, "sotto-selftest").start()
    }

    /** Unread room messages. The room has no peer id, so it gets a key of its own. */
    val roomUnread: Int get() = unread[ROOM] ?: 0

    fun openChat(peer: Int?) {
        openChat = peer
        unread.remove(peer ?: ROOM)
        if (peer != null && !identity.peerKeys.containsKey(peer)) sendKey(peer)
    }

    /** A room message arrived. If the user is reading a private chat, they cannot see it. */
    private fun noteRoomUnread() {
        if (openChat != null) unread[ROOM] = (unread[ROOM] ?: 0) + 1
    }

    /** People to offer chats with: anyone heard, most recent first. */
    val chatPeers: List<Int> get() { clockTick; return identity.contacts.entries.sortedByDescending { it.value.lastHeard }.map { it.key } }

    fun hasKeyFor(peer: Int) = identity.peerKeys.containsKey(peer)

    /** Ticks every few seconds so "nearby" counts and "x min ago" stay current on screen. */
    var clockTick by mutableIntStateOf(0)
        private set
    val nearby: List<Int> get() { clockTick; return (identity.nearby(PRESENT_FOR_MS) + ble.nearbyIds(PRESENT_FOR_MS)).distinct() }

    /** Ids the radio can see right now, so the UI can say how someone is reachable. */
    val bleNearby: List<Int> get() { clockTick; return if (bluetoothOn) ble.nearbyIds(PRESENT_FOR_MS) else emptyList() }
    val farther: List<Int> get() { clockTick; return identity.farther(PRESENT_FOR_MS) }
    private var announced = false
    private val presence = object : Runnable {
        override fun run() {
            clockTick++
            maybeChirp()
            abandonStaleTransfers()
            main.postDelayed(this, PRESENCE_TICK_MS)
        }
    }

    /** Drops transfers whose sender has clearly given up, so their id can be reused. */
    private fun abandonStaleTransfers() {
        val now = SystemClock.elapsedRealtime()
        val stale = incoming.values.filter { !it.complete && now - it.lastAt > INCOMING_ABANDON_MS }
        for (x in stale) {
            incoming.remove(x.id)
            if (x.logId != 0L) updateLog(x.logId) { it.with(text = "photo did not finish", progress = null, fraction = null) }
        }
    }

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "sotto-history") }
    private var saveQueued = false
    /** False until what was on disk is back in memory. Saving before that erases it. */
    private var historyReady = false
    private val saveNow = Runnable {
        saveQueued = false
        val snapshot = log.toList()
        io.execute { runCatching { History.save(getApplication(), snapshot) }.onFailure { Log.w(TAG, "history save failed", it) } }
    }

    /**
     * Coalesces bursts of changes into one write a second later -- but never before the restore
     * has landed. A message arriving in that first second used to trigger a save of a log holding
     * only that message, and the save deletes every photo file no entry refers to: the entire
     * picture archive, gone, for having received one message at the wrong moment.
     */
    private fun scheduleSave() {
        if (saveQueued || !historyReady) return
        saveQueued = true
        main.postDelayed(saveNow, 1000)
    }

    fun clearHistory() {
        log.clear()
        unread.clear()
        io.execute { History.clear(getApplication()) }
    }


    /** (sender, seq) pairs heard recently: shown once, repeated at most once. */
    private val seen = LinkedHashMap<Long, Long>()
    private val pendingRelays = HashMap<Long, AtomicBoolean>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** What the user asked for. The switch reflects this; [captureSource] says whether it is up. */
    var wantListening by mutableStateOf(false)
        private set
    var captureSource by mutableStateOf<String?>(null)
        private set
    var micLevel by mutableFloatStateOf(0f)
        private set
    var transmitting by mutableStateOf(false)
        private set
    var protocolId by counted("protocol", Modem.DEFAULT_PROTOCOL_ID)
    /** Auto: silent ultrasound (or Fast) for text, Near for photos. Off: [protocolId] for everything. */
    var autoProtocol by remembered("autoProtocol", true)
    /** The point of the app: messages nobody can hear. Off trades silence for range. */
    var silentText by remembered("silent", true)
    var txVolume by counted("volume", DEFAULT_TX_VOLUME)

    val textProtocolId: Int
        get() = if (!autoProtocol) protocolId else if (silentText) Modem.ULTRASOUND_PROTOCOL_ID else Modem.DEFAULT_PROTOCOL_ID
    val photoProtocolId: Int get() = if (autoProtocol) Modem.NEAR_PROTOCOL_ID else protocolId

    /** Seconds of audio the current draft would take. */
    val draftSeconds: Float? get() = if (draftBytes == 0) null else Modem.airtime(textProtocolId, draftBytes + if (openChat == null) 5 else 23)
    var draft by mutableStateOf("")
    var mediaVolume by mutableFloatStateOf(1f)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    val log = mutableStateListOf<LogEntry>()

    var burstSending by mutableStateOf(false)
        private set
    var burstSent by mutableIntStateOf(0)
        private set
    var burstReceived by mutableIntStateOf(0)
        private set
    var burstExpected by mutableIntStateOf(0)
        private set
    private var burstLastSeq = 0
    private var burstLastAt = 0L
    private val burstSeen = HashSet<Int>()
    private val burstCancel = AtomicBoolean(false)

    private var inForeground = false

    /** Updates from GitHub releases. */
    var update by mutableStateOf<Updates.Release?>(null)
        private set
    var updateChecking by mutableStateOf(false)
        private set
    var updateProgress by mutableIntStateOf(-1)   // -1 idle, 0..100 downloading, 100 ready
        private set
    var updateNote by mutableStateOf<String?>(null)
        private set
    private var lastUpdateCheck = 0L

    /** Photo and long-content transfers. */
    var transferring by mutableStateOf(false)
        private set
    private val incoming = HashMap<Int, Transfer.Incoming>()
    /**
     * Replies to a photo this phone is sending. Bounded, and only ever drained while a transfer
     * is running: an unbounded queue here would grow for the life of the process on the REQ and
     * DONE frames of everybody else's transfers, which anyone in range can also simply make up.
     */
    private val control = LinkedBlockingQueue<Transfer.Frame>(64)
    // Started from the clock, not from 1: ids are also photo filenames, and until the restore
    // lands we do not know what is already taken. Counting up from one collided with them.
    private var nextLogId = System.currentTimeMillis()

    val draftBytes: Int
        get() = draft.toByteArray(Charsets.UTF_8).size

    /** A message handed to the transmit thread and not yet played. */
    var sending by mutableStateOf(false)
        private set

    val busy: Boolean
        get() = transmitting || burstSending || transferring || sending

    val canSend: Boolean
        get() = draftBytes in 1..MAX_BYTES && !busy && (openChat?.let { hasKeyFor(it) } ?: true)

    /** Amplitude actually handed to the modem: ggwave's multi-tone sum clips above 25. */
    val effectiveTxVolume: Int
        get() = if (protocolId >= Modem.SOTTO_ID_BASE) txVolume else minOf(txVolume, Modem.GGWAVE_MAX_VOLUME)

    // ---- listening ----------------------------------------------------------------------

    private var listeningDecided = false

    /**
     * The master switch. It has to move every layer, not just the microphone: with only the
     * microphone stopped the phone went on advertising its identity over Bluetooth and scanning
     * for other phones indefinitely, which is a battery cost the user did not ask for and, worse,
     * a beacon they believe they switched off.
     */
    fun setListening(on: Boolean) {
        listeningDecided = true
        wantListening = on
        status = null
        if (on) {
            link.startListening()
            startBle()
            carry.start()   // idempotent; the service can restart the process without an activity
            // The system can refuse a microphone service started from the background. Say so
            // rather than leaving the switch looking on while nothing listens behind it.
            if (listenInBackground && !ListenService.start(getApplication())) {
                listenInBackground = false
                status = "Android would not let Sotto listen in the background. Reopen the app and turn it on again."
            }
        } else {
            link.stopListening()
            ble.stop()
            blePeers = 0
            ListenService.stop(getApplication())
        }
    }

    /** The conversation screen calls this once the mic permission exists: listening is the default. */
    fun ensureListening() {
        if (!listeningDecided && !wantListening && captureSource == null) setListening(true)
        if (!announced && identity.name.isNotEmpty()) {
            announced = true
            main.postDelayed({ announce() }, 2500)
        }
    }

    /** One hello when the app opens, so others learn we are here and we learn who answers. */
    private fun announce() {
        if (identity.name.isEmpty() || busy || !wantListening) return
        val frame = Wire.hello(identity.id, identity.name)
        val pid = textProtocolId
        val vol = effectiveVolumeFor(pid)
        dispatch(frame, pid, vol)
    }

    /** A three-byte "here" when this phone has been quiet for a while, so it stays counted. */
    private fun maybeChirp() {
        if (!inForeground || !wantListening || busy || identity.name.isEmpty()) return
        // The radio advertises us continuously, so a chirp is only wasted airtime when every
        // phone we know of can see us that way.
        if (bleReady() && identity.nearby(PRESENT_FOR_MS).all { it in ble.nearbyIds(PRESENT_FOR_MS) }) return
        val idle = SystemClock.elapsedRealtime() - link.lastTransmitAt
        if (idle < CHIRP_AFTER_IDLE_MS + Random.nextLong(0, CHIRP_JITTER_MS)) return
        val frame = Wire.here(identity.id)
        val pid = textProtocolId
        val vol = effectiveVolumeFor(pid)
        dispatch(frame, pid, vol)
    }

    /** One line for the header. */
    val statusLine: String
        get() = when {
            transferring && transmitting -> "sending photo"
            transferring -> "sending photo, waiting"
            transmitting -> "sending"
            burstSending -> "range test"
            !wantListening -> "paused"
            captureSource == null -> "starting the mic"
            micLevel > 0.05f -> "listening, loud room"
            else -> "listening"
        } + presenceSuffix()

    private fun presenceSuffix(): String {
        if (bleRunning && blePeers > 0 && nearby.isEmpty()) return " · Bluetooth"
        val n = nearby.size
        val f = farther.size
        if (n == 0 && f == 0) return ""
        val names = nearby.take(2).mapNotNull { identity.nameFor(it) }
        val who = if (n in 1..2 && names.size == n) names.joinToString(", ") else "$n nearby"
        return " · " + who + (if (f > 0) " · $f farther" else "") + (if (bleRunning && blePeers > 0) " · Bluetooth" else "")
    }

    /** Android blocks background mic access anyway; stop cleanly and resume on return. */
    fun onForeground() {
        inForeground = true
        startBle()
        carry.start()
        if (wantListening && captureSource == null) link.startListening()
        if (autoUpdateCheck && SystemClock.elapsedRealtime() - lastUpdateCheck > UPDATE_CHECK_EVERY_MS) checkForUpdates(manual = false)
    }

    // ---- updates -------------------------------------------------------------------------

    /**
     * Whether to ask GitHub for a new version by itself. It is the only network request this app
     * makes, and it happens on almost every cold start -- which for an app whose whole claim is
     * that it needs no server is a thing the user should be able to say no to. Checking by hand
     * still works with this off.
     */
    var autoUpdateCheck by remembered("autoUpdate", true)

    fun checkForUpdates(manual: Boolean) {
        if (updateChecking) return
        updateChecking = true
        if (manual) updateNote = null
        lastUpdateCheck = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            val r = Updates.latest()
            updateChecking = false
            when {
                r == null -> if (manual) updateNote = "Could not reach the release feed. Try again on Wi-Fi."
                Updates.isNewer(r.version, BuildConfig.VERSION_NAME) -> {
                    update = r
                    if (Updates.cachedApk(getApplication(), r.version) != null) updateProgress = 100
                }
                else -> if (manual) updateNote = "You have the latest version."
            }
        }
    }

    /** Downloads the update if needed, then opens the system installer. */
    fun installUpdate() {
        val r = update ?: return
        val app = getApplication<Application>()
        val cached = Updates.cachedApk(app, r.version)
        if (cached != null) {
            if (!Updates.install(app, cached)) updateNote = "Allow Sotto to install updates, then tap again."
            return
        }
        if (updateProgress in 0..99) return
        updateProgress = 0
        updateNote = null
        viewModelScope.launch {
            val file = Updates.download(app, r) { p -> updateProgress = p }
            if (file == null) { updateProgress = -1; updateNote = "Download failed. Try again."; return@launch }
            updateProgress = 100
            if (!Updates.install(app, file)) updateNote = "Allow Sotto to install updates, then tap again."
        }
    }

    fun onBackground() {
        inForeground = false
        if (!listenInBackground) { link.stopListening(); ble.stop(); blePeers = 0 }
    }

    /** Notifies about a message that arrived while the app was not on screen. */
    private fun notifyMessage(title: String, text: String, id: Int) {
        if (inForeground) return
        val app = getApplication<Application>()
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, SottoApplication.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { app.getSystemService(NotificationManager::class.java).notify(1000 + (id and 0xFFF), n) }
    }

    fun refreshMediaVolume() {
        mediaVolume = link.mediaVolumeFraction()
    }

    fun showVolumePanel() = link.showVolumePanel()

    // ---- sending ------------------------------------------------------------------------

    fun send() {
        if (!canSend) return
        val text = draft
        val peer = openChat
        val seq = identity.nextSeq()
        val bytes = if (peer == null) {
            Wire.text(identity.id, seq, HOP_BUDGET, text)
        } else {
            val key = identity.peerKeys[peer] ?: run { status = "Still waiting for ${identity.nameFor(peer)}'s key."; sendKey(peer); return }
            val ctr = identity.nextCounter(peer)
            Wire.private(identity.id, peer, seq, HOP_BUDGET, ctr, Crypto.encrypt(key, identity.id, peer, ctr, text.toByteArray(Charsets.UTF_8)))
        }
        val pid = textProtocolId
        val vol = effectiveVolumeFor(pid)
        status = null
        sending = true
        draft = ""
        val carriedSeq = intoTheNetwork(text, peer, seq)
        // The tile goes up now, not when the speaker gets round to it. The transmit thread can
        // be busy repeating somebody else's message for the better part of half a minute, and
        // until it was free the sender saw an emptied box and no message anywhere.
        val logId = addLog(
            LogEntry.Kind.TX, text, pid, bytes.size, peer = peer,
            seq = if (peer != null) seq else null, bundleSeq = carriedSeq, progress = "sending",
        )
        val radioAttempt = bleReady() && ble.send(bytes) { ok ->
            main.post {
                sending = false
                if (ok) {
                    updateLog(logId) { it.with(protocol = Modem.protocolName(BleLink.PROTOCOL_ID), progress = null) }
                } else {
                    Log.i(TAG, "Bluetooth did not carry it; falling back to the speaker")
                    updateLog(logId) { it.with(protocol = Modem.protocolName(pid), progress = null) }
                    overTheSpeaker(bytes, pid, vol, quiet = false)
                }
            }
        }
        if (radioAttempt) return
        link.post {
            val ok = link.transmit(bytes, pid, vol)
            main.post {
                sending = false
                if (ok) updateLog(logId) { it.with(progress = null) }
                else {
                    updateLog(logId) { it.with(progress = "not sent") }
                    status = "Nothing left the speaker. Check the media volume and that nothing is plugged in."
                    if (draft.isEmpty()) draft = text
                }
            }
        }
    }

    fun startBurst() {
        if (busy) return
        burstSending = true
        burstSent = 0
        burstCancel.set(false)
        val pid = textProtocolId
        val vol = effectiveVolumeFor(pid)
        addLog(LogEntry.Kind.INFO, "Test burst started: $BURST_COUNT x ${BURST_PAYLOAD_BYTES} bytes, ${BURST_GAP_MS / 1000} s gap", pid, 0)
        link.post {
            var sent = 0
            for (i in 1..BURST_COUNT) {
                if (burstCancel.get()) break
                val ok = link.transmit(burstPayload(i), pid, vol)
                if (ok) sent++
                main.post { burstSent = i }
                if (i < BURST_COUNT) waitUnlessCancelled(BURST_GAP_MS)
            }
            val done = sent
            main.post {
                burstSending = false
                addLog(LogEntry.Kind.INFO, "Test burst finished: $done of $BURST_COUNT sent", pid, 0)
            }
        }
    }

    fun cancelBurst() {
        burstCancel.set(true)
    }

    fun resetBurstCounter() {
        burstReceived = 0
        burstExpected = 0
        burstLastSeq = 0
        burstLastAt = 0L
        burstSeen.clear()
    }

    private fun waitUnlessCancelled(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < until && !burstCancel.get()) SystemClock.sleep(50)
    }

    // ---- SoundLink callbacks (worker threads) -------------------------------------------

    override fun onLevel(rms: Float) {
        main.post { micLevel = rms }
    }

    override fun onMessage(payload: ByteArray, protocolId: Int, snrDb: Float) {
        main.post {
            lastSnrDb = snrDb
            if (Transfer.isTransferFrame(payload)) {
                Transfer.parse(payload)?.let { onTransferFrame(it, protocolId) }
                return@post
            }
            when (val m = Wire.parse(payload)) {
                is Wire.Parsed.Text -> {
                    Log.i(TAG, "rx ${Modem.protocolName(protocolId)} ${payload.size} B from ${IdentityStore.tagOf(m.id)} seq ${m.seq} hops ${m.hops}${m.via?.let { " via ${IdentityStore.tagOf(it)}" } ?: ""}: ${m.text}")
                    if (m.id == identity.id) return@post              // our own message, back from a relay
                    noteSender(m.id, protocolId, direct = m.via == null)
                    m.via?.let { identity.heard(it, direct = true) }
                    val key = seenKey(m.id, m.seq, transfer = false)
                    if (markSeen(key)) {                               // heard again: someone else repeated it
                        pendingRelays[key]?.set(true)
                        return@post
                    }
                    trackBurst(m.text)
                    addLog(LogEntry.Kind.RX, m.text, protocolId, payload.size, senderId = m.id, via = m.via)
                    noteRoomUnread()
                    notifyMessage(identity.nameFor(m.id) ?: "Sotto", m.text, m.id)
                    if (relayForOthers && m.hops > 0) scheduleRelay(key, Wire.relay(m.id, m.seq, m.hops - 1, identity.id, m.text), protocolId)
                }
                is Wire.Parsed.Hello -> {
                    Log.i(TAG, "rx hello from ${IdentityStore.tagOf(m.id)}: ${m.name}")
                    val known = identity.contacts[m.id]?.name?.isNotEmpty() == true
                    identity.heard(m.id, m.name.ifEmpty { null })
                    if (!known) addLog(LogEntry.Kind.INFO, "${identity.nameFor(m.id)} is here", protocolId, 0)
                    sendHello(m.id, protocolId)
                }
                is Wire.Parsed.Here -> {
                    if (m.id != identity.id) noteSender(m.id, protocolId)
                }
                is Wire.Parsed.Card -> {
                    if (m.from == identity.id) return@post
                    val key = seenKey(m.from, m.seq, transfer = false)
                    if (markSeen(key)) return@post
                    noteSender(m.from, protocolId)
                    addLog(LogEntry.Kind.RX, cardTitle(m.kind, m.fields), protocolId, payload.size, senderId = m.from, card = LogEntry.Card(m.kind, m.fields))
                    notifyMessage(identity.nameFor(m.from) ?: "Sotto", "shared " + when (m.kind) { Wire.CARD_LINK -> "a link"; Wire.CARD_WIFI -> "a Wi-Fi network"; else -> "a contact" }, m.from)
                }
                is Wire.Parsed.Probe -> {
                    if (m.from == identity.id) return@post
                    noteSender(m.from, protocolId)
                    val snr = lastSnrDb.toInt().coerceIn(0, 255)
                    if (protocolId == BleLink.PROTOCOL_ID) return@post   // the reach test measures the room, not the radio
                    val frame = Wire.probeReply(identity.id, m.from, m.seq, snr)
                    val vol = effectiveVolumeFor(protocolId)
                    val wait = REPLY_DELAY_MS + Random.nextLong(0, 1500)
                    overTheSpeaker(frame, protocolId, vol, delayMs = wait)
                }
                is Wire.Parsed.ProbeReply -> {
                    if (m.from == identity.id || m.to != identity.id) return@post
                    noteSender(m.from, protocolId)
                    reach?.let { r ->
                        if (m.seq !in r.seqs || protocolId == BleLink.PROTOCOL_ID) return@let
                        val ours = lastSnrDb.toInt().coerceIn(0, 255)
                        r.heard[m.from] = (r.heard[m.from] ?: emptyList()) + listOf(m.snrDb, ours)
                        Log.i(TAG, "reach reply from ${IdentityStore.tagOf(m.from)}: they heard ${m.snrDb} dB, we hear $ours dB")
                    }
                }
                is Wire.Parsed.Ack -> {
                    if (m.from == identity.id) return@post
                    val key = ackKey(m.from, m.to, m.seq)
                    if (markSeen(key)) { pendingRelays[key]?.set(true); return@post }
                    noteSender(m.from, protocolId)
                    // the target has it: any repeat of the message itself still pending here is pointless
                    pendingRelays[seenKey(m.to, m.seq, transfer = false)]?.set(true)
                    if (m.to == identity.id) {
                        Log.i(TAG, "receipt from ${IdentityStore.tagOf(m.from)} for seq ${m.seq}")
                        val i = log.indexOfFirst { it.kind == LogEntry.Kind.TX && it.peer == m.from && it.seq == m.seq }
                        if (i >= 0) updateLog(log[i].id) { it.with(delivered = true) }
                    } else if (relayForOthers && m.hops > 0) {
                        scheduleRelay(key, Wire.relayAck(m.raw), protocolId)
                    }
                }
                is Wire.Parsed.Key -> {
                    if (m.from == identity.id) return@post
                    noteSender(m.from, protocolId)
                    if (m.to != identity.id) return@post
                    when {
                        identity.samePublicKey(m.from, m.publicKey) -> sendKey(m.from)
                        identity.peerKeys.containsKey(m.from) -> {
                            // A different key for someone we already know: could be a reinstall, could be
                            // a phone in earshot pretending. Hold it until the user accepts it.
                            pendingKeys[m.from] = m.publicKey
                            Log.w(TAG, "key from ${IdentityStore.tagOf(m.from)} differs from the one we hold; waiting for the user")
                            addLog(LogEntry.Kind.INFO, "${identity.nameFor(m.from)}'s key changed. Open the chat to compare fingerprints before accepting it.", protocolId, 0)
                        }
                        identity.learnPublicKey(m.from, m.publicKey) -> {
                            Log.i(TAG, "key from ${IdentityStore.tagOf(m.from)}")
                            sendKey(m.from)   // answer with ours unless we just did
                        }
                        else -> Log.w(TAG, "key from ${IdentityStore.tagOf(m.from)} rejected: no usable secret")
                    }
                }
                is Wire.Parsed.Private -> {
                    if (m.from == identity.id) return@post
                    val key = seenKey(m.from, m.seq, transfer = false)
                    val dup = markSeen(key)
                    if (dup) { pendingRelays[key]?.set(true); return@post }
                    noteSender(m.from, protocolId, direct = true)
                    if (m.to == identity.id) {
                        if (!identity.isFreshCounter(m.from, m.counter)) { Log.w(TAG, "replayed private message from ${IdentityStore.tagOf(m.from)} ignored"); return@post }
                        val k = identity.peerKeys[m.from]
                        val plain = k?.let { Crypto.decrypt(it, m.from, identity.id, m.counter, m.sealed) }
                        if (plain == null) {
                            Log.w(TAG, "private message from ${IdentityStore.tagOf(m.from)} could not be read")
                            addLog(LogEntry.Kind.INFO, "A private message from ${identity.nameFor(m.from)} could not be read. Their key may have changed.", protocolId, 0, peer = m.from)
                            sendKey(m.from)
                        } else {
                            identity.recordCounter(m.from, m.counter)
                            val text = String(plain, Charsets.UTF_8)
                            Log.i(TAG, "rx private ${payload.size} B from ${IdentityStore.tagOf(m.from)}")
                            addLog(LogEntry.Kind.RX, text, protocolId, payload.size, senderId = m.from, peer = m.from)
                            if (openChat != m.from) unread[m.from] = (unread[m.from] ?: 0) + 1
                            notifyMessage("${identity.nameFor(m.from)} · private", text, m.from)
                            sendAck(m.from, m.seq, protocolId)
                        }
                    } else if (relayForOthers && m.hops > 0) {
                        scheduleRelay(key, Wire.relayPrivate(m.raw), protocolId)   // not for us: repeat it unread
                    }
                }
                is Wire.Parsed.Plain -> {
                    Log.i(TAG, "rx ${Modem.protocolName(protocolId)} ${payload.size} B plain: ${m.text}")
                    trackBurst(m.text)
                    addLog(LogEntry.Kind.RX, m.text, protocolId, payload.size)
                    noteRoomUnread()
                }
            }
        }
    }

    // ---- the carry network -----------------------------------------------------------------

    /**
     * Hands a message to the carry network as well as playing it live. A room message spreads
     * to everyone; a private one is sealed to its person and given a budget of copies. Returns
     * the sequence the tile follows, or null when it could not be carried.
     */
    private fun intoTheNetwork(text: String, peer: Int?, seq: Int): Int? {
        if (!carryOn) return null
        if (peer == null) { carry.postRoom(seq, text.toByteArray(Charsets.UTF_8)); return seq }
        val dest = identity.wideId(peer) ?: return null   // nobody has told us their full id yet
        val key = identity.peerKeys[peer] ?: return null
        // The nonce counter comes from the same place the sound path takes it, and travels with
        // the message: the bundle's own sequence cannot serve, because it names the message
        // rather than the pair, and two counters feeding one nonce is a repeated nonce.
        val counter = identity.nextCounter(peer)
        val sealed = runCatching { Crypto.encrypt(key, identity.id, peer, counter, text.toByteArray(Charsets.UTF_8)) }.getOrNull() ?: return null
        carry.postPrivate(seq, dest, Bundle.sealedWithCounter(counter, sealed))
        return seq
    }

    /** A message someone carried here. */
    private fun onCarriedBundle(b: Bundle) {
        val from16 = Ids.id16(b.origin)
        when (b.kind) {
            Bundle.KIND_PROFILE -> {
                val (name, pub) = Bundle.parseProfile(b.payload) ?: return
                // A profile says "this is my name and this is my key", and nothing signs it. But
                // it does not need a signature: an id IS the hash of the public key, so a profile
                // whose key does not hash to its own origin is simply a lie, and one that does
                // could only have been made by whoever holds that key. Without this check anyone
                // could publish themselves as anybody, and the network would carry it for a week.
                if (Ids.id64(pub) != b.origin) {
                    Log.w(TAG, "profile claiming ${b.origin.toString(16)} carries a key that hashes elsewhere; dropped")
                    return
                }
                identity.learnWideId(b.origin)
                if (from16 != identity.id) {
                    val known = identity.contacts[from16]?.name?.isNotEmpty() == true
                    identity.heard(from16, name.ifEmpty { null }, direct = false)
                    // Their key arriving this way is what lets you write privately to someone
                    // you have never stood next to.
                    if (!identity.peerKeys.containsKey(from16)) identity.learnPublicKey(from16, pub)
                    if (!known && name.isNotEmpty()) addLog(LogEntry.Kind.INFO, "$name is on the network", BleLink.PROTOCOL_ID, 0)
                }
            }
            Bundle.KIND_ROOM -> {
                if (from16 == identity.id) return
                if (markSeen(seenKey(from16, b.seq and 0xFF, transfer = false))) return   // already heard it live
                identity.learnWideId(b.origin)
                identity.heard(from16, direct = false)
                val text = String(b.payload, Charsets.UTF_8)
                addLog(LogEntry.Kind.RX, text, BleLink.PROTOCOL_ID, b.payload.size, senderId = from16, carriedHops = b.hops)
                noteRoomUnread()
                notifyMessage(identity.nameFor(from16) ?: "Sotto", text, from16)
            }
            Bundle.KIND_PRIVATE -> {
                if (b.dest != identity.id64 || from16 == identity.id) return   // just carrying it
                if (markSeen(seenKey(from16, b.seq and 0xFF, transfer = false))) { carry.acknowledge(b); return }
                identity.learnWideId(b.origin)
                val counter = Bundle.counterOf(b.payload) ?: return
                if (!identity.isFreshCounter(from16, counter)) {
                    Log.w(TAG, "replayed carried message from ${IdentityStore.tagOf(from16)} ignored")
                    return
                }
                val key = identity.peerKeys[from16]
                val plain = key?.let { Crypto.decrypt(it, from16, identity.id, counter, Bundle.sealedOf(b.payload)) }
                if (plain == null) {
                    addLog(LogEntry.Kind.INFO, "A carried private message from ${identity.nameFor(from16)} could not be read.", BleLink.PROTOCOL_ID, 0, peer = from16)
                    return
                }
                identity.recordCounter(from16, counter)
                identity.heard(from16, direct = false)
                val text = String(plain, Charsets.UTF_8)
                addLog(LogEntry.Kind.RX, text, BleLink.PROTOCOL_ID, b.payload.size, senderId = from16, peer = from16, carriedHops = b.hops)
                if (openChat != from16) unread[from16] = (unread[from16] ?: 0) + 1
                notifyMessage("${identity.nameFor(from16)} · private", text, from16)
                carry.acknowledge(b)   // tells the sender, and lets every carrier drop its copy
            }
        }
    }

    /**
     * Publishes who this phone is, so someone who has never met it can still write to it.
     *
     * Only when there is something new to say. Every profile floods the whole network for a
     * week, and this ran on every start of the radio -- so simply opening the app put another
     * copy of the same name and the same key into everybody's store, week after week.
     */
    private fun publishProfile(force: Boolean = false) {
        if (!carryOn || identity.name.isEmpty()) return
        val fingerprint = identity.name.hashCode() * 31 + identity.publicKey.contentHashCode()
        val age = System.currentTimeMillis() - identity.number("profileAt", 0).toLong() * 1000
        if (!force && fingerprint == identity.number("profileOf", 0) && age < PROFILE_REPUBLISH_MS) return
        carry.postProfile(identity.name, identity.publicKey)
        identity.setNumber("profileOf", fingerprint)
        identity.setNumber("profileAt", (System.currentTimeMillis() / 1000).toInt())
    }

    // ---- identity ------------------------------------------------------------------------

    /** A frame from [id] arrived. Unknown senders get our name, which invites theirs. */
    private fun noteSender(id: Int, protocolId: Int, direct: Boolean = true) {
        val known = identity.contacts[id]?.name?.isNotEmpty() == true
        identity.heard(id, direct = direct)
        if (!known && direct) sendHello(id, protocolId)
    }

    /** Plays our name for [forId], at most once a minute per phone, after a short pause. */
    private fun sendHello(forId: Int, protocolId: Int) {
        if (identity.name.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - (helloSentAt[forId] ?: 0L) < HELLO_EVERY_MS) return
        helloSentAt[forId] = now
        val frame = Wire.hello(identity.id, identity.name)
        val pid = protocolId
        val vol = effectiveVolumeFor(pid)
        val wait = REPLY_DELAY_MS + Random.nextLong(0, 900)
        dispatch(frame, pid, vol, delayMs = wait, quiet = false)
    }

    /** "Got it": a seven-byte receipt to the sender of a private message, after a short pause. */
    private fun sendAck(to: Int, seq: Int, protocolId: Int) {
        val frame = Wire.ack(identity.id, to, seq, HOP_BUDGET)
        val vol = effectiveVolumeFor(protocolId)
        dispatch(frame, protocolId, vol, delayMs = REPLY_DELAY_MS + Random.nextLong(0, 400))
    }

    /** Plays our public key for [peer], at most once a minute; they answer with theirs. */
    private fun sendKey(peer: Int) {
        if (!cryptoOk) return
        val now = SystemClock.elapsedRealtime()
        if (now - (keySentAt[peer] ?: 0L) < HELLO_EVERY_MS) return
        keySentAt[peer] = now
        val frame = Wire.key(identity.id, peer, identity.publicKey)
        val pid = textProtocolId
        val vol = effectiveVolumeFor(pid)
        dispatch(frame, pid, vol, delayMs = REPLY_DELAY_MS + Random.nextLong(0, 600))
    }

    /** Settings: a new name is announced to everyone we know next time they speak. */
    fun setName(name: String) {
        identity.rename(name)
        helloSentAt.clear()
        publishProfile(force = true)
    }

    // ---- relaying ------------------------------------------------------------------------

    private fun seenKey(sender: Int, seq: Int, transfer: Boolean): Long = (sender.toLong() shl 10) or (seq.toLong() shl 2) or (if (transfer) 1L else 0L)

    /** Receipts are keyed by acker, recipient and sequence: two senders may share a sequence number. */
    private fun ackKey(from: Int, to: Int, seq: Int): Long = (from.toLong() shl 26) or (to.toLong() shl 10) or (seq.toLong() shl 2) or 2L

    /** Records the key; true if it had been heard within the last few minutes already. */
    private fun markSeen(key: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val it = seen.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > SEEN_FOR_MS) it.remove()
        val dup = seen.containsKey(key)
        seen[key] = now
        return dup
    }

    /**
     * Flooding with suppression: repeat after a random pause unless another phone repeats
     * first, and only once the band is quiet.
     */
    /**
     * Repeats a frame for the phones the sender could not reach.
     *
     * A relay is the one place where picking a single transport is wrong: whoever this is being
     * repeated for is, by definition, somewhere the sender's own transmission did not arrive, and
     * this phone cannot know which of the two media they are on. So it goes over the radio when
     * the radio has peers, and over the speaker as well whenever some phone it knows of is not
     * visible on the radio. When everyone in the room is on the radio, the airtime is saved.
     */
    private fun scheduleRelay(key: Long, frame: ByteArray, protocolId: Int) {
        val cancelled = AtomicBoolean(false)
        pendingRelays[key] = cancelled
        val wait = RELAY_MIN_WAIT_MS + Random.nextLong(0, RELAY_JITTER_MS)
        val vol = effectiveVolumeFor(overSpeaker(protocolId))
        val radioPeers = if (bluetoothOn) ble.nearbyIds(PRESENT_FOR_MS).toSet() else emptySet()
        val someoneOffRadio = identity.nearby(PRESENT_FOR_MS).any { it !in radioPeers } || radioPeers.isEmpty()
        link.post {
            SystemClock.sleep(wait)
            if (cancelled.get()) { Log.i(TAG, "relay suppressed, someone else repeated it"); return@post }

            val onRadio = bleReady() && ble.send(frame) { ok ->
                if (!ok && !cancelled.get()) overTheSpeaker(frame, protocolId, vol, quiet = false)
            }
            if (!someoneOffRadio && onRadio) {
                Log.i(TAG, "relayed ${frame.size} B over Bluetooth")
                main.post { pendingRelays.remove(key) }
                return@post
            }

            link.waitUntilQuiet(RELAY_QUIET_WAIT_MS)
            if (cancelled.get()) return@post
            Log.i(TAG, "relayed ${frame.size} B by sound${if (onRadio) " and Bluetooth" else ""}")
            link.transmit(frame, overSpeaker(protocolId), vol)
            main.post { pendingRelays.remove(key) }
        }
    }

    /** Introduce this phone now, on the text protocol. */
    fun sayHello() {
        if (identity.name.isEmpty() || busy) return
        helloSentAt.clear()
        val frame = Wire.hello(identity.id, identity.name)
        val pid = textProtocolId
        val vol = effectiveVolumeFor(pid)
        dispatch(frame, pid, vol, quiet = false)
        addLog(LogEntry.Kind.INFO, "You said hello as ${identity.name} ${identity.tag}", pid, 0)
    }

    // ---- transfers: receiving ----------------------------------------------------------

    private fun onTransferFrame(f: Transfer.Frame, protocolId: Int) {
        when (f) {
            // Only while we are the one sending; otherwise it is somebody else's conversation.
            is Transfer.Frame.Req, is Transfer.Frame.Done -> { if (transferring) control.offer(f); return }
            else -> {}
        }
        val total = when (f) { is Transfer.Frame.Data -> f.total; is Transfer.Frame.End -> f.total; else -> return }
        // A finished or differently-sized entry under this id belongs to an older transfer.
        incoming[f.id]?.let { old -> if (old.total != total || (old.complete && f is Transfer.Frame.Data && f.seq == 0)) incoming.remove(f.id) }
        // An END for a transfer nothing has arrived for is three bytes anybody can transmit, and
        // answering it would put a photo tile in the history and send back a full frame asking
        // for all 255 chunks. A transfer begins when its first chunk does, not when someone
        // says it ended.
        if (f is Transfer.Frame.End && !incoming.containsKey(f.id)) return
        val x = incoming.getOrPut(f.id) {
            val ownPhoto = f is Transfer.Frame.Data && f.seq == 0 && f.bytes.size >= 5 &&
                (((f.bytes[3].toInt() and 0xFF) shl 8) or (f.bytes[4].toInt() and 0xFF)) == identity.id
            Transfer.Incoming(f.id, total).also {
                if (ownPhoto) it.complete = true   // our own photo coming back through a relay: answer DONE, show nothing
                else it.logId = addLog(LogEntry.Kind.RX, "incoming photo", protocolId, 0, progress = "receiving, 0 of $total", fraction = 0f)
            }
        }
        x.lastAt = SystemClock.elapsedRealtime()
        if (f is Transfer.Frame.Data && !x.complete && f.seq < x.total) {
            x.parts[f.seq] = f.bytes
            updateLog(x.logId) { it.with(progress = "receiving, ${x.received} of ${x.total}", fraction = x.received.toFloat() / x.total) }
            if (x.received == x.total) finishIncoming(x, protocolId)
        }
        if (f is Transfer.Frame.End) {
            Log.i(TAG, "transfer ${x.id}: END received, ${if (x.complete) "sending DONE" else "requesting ${x.missing.size} chunks"}")
            x.replyAttempt = 0
            sendReply(x, protocolId)
        }
    }

    /**
     * Answers the sender after its END: DONE, or the missing list. Gives the sender's
     * decoder time to come back first, then, if nothing arrives for a while, repeats the
     * request a couple of times in case the sender missed it.
     */
    private fun sendReply(x: Transfer.Incoming, protocolId: Int) {
        val reply = if (x.complete) Transfer.doneFrame(x.id) else Transfer.reqFrame(x.id, x.missing)
        val vol = effectiveVolumeFor(protocolId)
        val attempt = x.replyAttempt
        val stampBefore = x.lastAt
        dispatch(reply, protocolId, vol, delayMs = if (bleReady()) 0L else REPLY_DELAY_MS, quiet = false)
        if (x.complete || attempt >= MAX_REPLY_ATTEMPTS - 1) return
        main.postDelayed({
            if (!x.complete && x.lastAt == stampBefore) {   // nothing new arrived since the request
                x.replyAttempt = attempt + 1
                Log.i(TAG, "transfer ${x.id}: no resend heard, repeating the request (attempt ${attempt + 2})")
                sendReply(x, protocolId)
            }
        }, REPLY_RETRY_MS)
    }

    private fun finishIncoming(x: Transfer.Incoming, protocolId: Int) {
        x.complete = true
        main.postDelayed({ if (incoming[x.id] === x) incoming.remove(x.id) }, INCOMING_FORGET_MS)
        val assembled = Transfer.assemble(x.parts.map { it!! })
        if (assembled == null) {
            updateLog(x.logId) { it.with(text = "transfer failed to assemble", progress = null) }
            return
        }
        val kind = assembled.kind
        val content = assembled.content
        Log.i(TAG, "transfer ${x.id} complete: kind $kind, ${content.size} B, from ${IdentityStore.tagOf(assembled.sender)} seq ${assembled.msgSeq} hops ${assembled.hops}")
        if (assembled.sender == identity.id) { log.removeAll { it.id == x.logId }; return }   // our own photo, forwarded back
        if (assembled.sender != 0) { noteSender(assembled.sender, protocolId, direct = assembled.hops == HOP_BUDGET); updateLog(x.logId) { it.with(senderId = assembled.sender) } }
        val key = seenKey(assembled.sender, assembled.msgSeq, transfer = true)
        if (markSeen(key)) { log.removeAll { it.id == x.logId }; return }   // already had this photo via another path
        if (relayForOthers && assembled.hops > 0 && kind == Transfer.KIND_JPEG) {
            main.postDelayed({
                if (!busy) {
                    Log.i(TAG, "forwarding photo from ${IdentityStore.tagOf(assembled.sender)}, ${assembled.hops - 1} hops left")
                    startTransfer(kind, content, "", null, origin = assembled.sender, msgSeq = assembled.msgSeq, hops = assembled.hops - 1)
                }
            }, RELAY_MIN_WAIT_MS + Random.nextLong(0, RELAY_JITTER_MS))
        }
        when (kind) {
            Transfer.KIND_JPEG -> {
                val bmp = BitmapFactory.decodeByteArray(content, 0, content.size)
                updateLog(x.logId) { it.with(text = if (bmp != null) "" else "photo could not be decoded", image = bmp, progress = null, fraction = null, bytes = content.size, imageBytes = content) }
                notifyMessage(identity.nameFor(assembled.sender) ?: "Sotto", "sent a photo", assembled.sender)
            }
            else -> updateLog(x.logId) { it.with(text = String(content, Charsets.UTF_8), progress = null, fraction = null, bytes = content.size) }
        }
        // the sender may finish its pass later; DONE goes out when its END arrives, and once now
        val pid = protocolId
        dispatch(Transfer.doneFrame(x.id), pid, effectiveVolumeFor(pid), delayMs = if (bleReady()) 0L else REPLY_DELAY_MS, quiet = false)
    }

    // ---- transfers: sending ------------------------------------------------------------

    /** Downscales and JPEG-compresses the picked photo, then sends it on the current protocol. */
    fun sendPhoto(uri: Uri) {
        if (transferring || burstSending) { status = "Wait, still sending."; return }
        val app = getApplication<Application>()
        val bitmap = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            Log.w(TAG, "photo decode failed", e); null
        }
        if (bitmap == null) { status = "Could not read that photo"; return }
        val scale = PHOTO_MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        var quality = PHOTO_QUALITY
        var jpeg: ByteArray
        do {
            val out = java.io.ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, quality, out)
            jpeg = out.toByteArray()
            quality -= 10
        } while (jpeg.size > PHOTO_MAX_BYTES && quality >= 10)
        startTransfer(Transfer.KIND_JPEG, jpeg, "photo ${small.width}×${small.height}", small)
    }

    private fun startTransfer(
        kind: Int, content: ByteArray, label: String, preview: Bitmap?,
        origin: Int = identity.id, msgSeq: Int = identity.nextSeq(), hops: Int = HOP_BUDGET,
    ) {
        val pid = photoProtocolId
        val id = Random.nextInt(256)
        val chunks = Transfer.chunks(id, kind, origin, msgSeq, hops, content)
        if (chunks == null) { status = "Too large to send (${content.size} B)"; return }
        val vol = effectiveVolumeFor(pid)
        val overRadio = bleReady()
        val perChunk = if (overRadio) 0.06f else (Modem.airtime(pid, Transfer.MAX_FRAME) ?: 3f)
        val forwarded = origin != identity.id
        val logId = addLog(if (forwarded) LogEntry.Kind.INFO else LogEntry.Kind.TX, if (forwarded) "Forwarding ${identity.nameFor(origin)}'s photo for others" else "", pid, content.size, image = preview,
            progress = "sending, about ${(chunks.size * (perChunk + 0.4f)).toInt()} s", fraction = 0f, imageBytes = if (forwarded) null else content)
        transferring = true
        status = null
        if (!wantListening) setListening(true)   // needed to hear the receiver's requests
        control.clear()
        Log.i(TAG, "transfer $id: ${content.size} B in ${chunks.size} chunks on ${Modem.protocolName(pid)}")

        link.post {
            var pass = chunks.indices.toList()
            var sent = 0
            var outcome = "no reply from the receiver"
            var round = 0
            var heardOnRadio = false   // true once this transfer has had an answer back
            while (round < MAX_ROUNDS) {
                for (seq in pass) {
                    val chunk = chunks[seq]
                    // No speaker fallback in the callback here. This loop is itself running on
                    // the transmit thread, so overTheSpeaker would queue behind it and every
                    // failed chunk would play out loud after the transfer had already finished.
                    // A chunk the radio drops is simply one the receiver will ask for, and the
                    // next round sends it -- by sound, if the radio has gone by then.
                    if (!(bleReady() && ble.send(chunk) { })) link.transmit(chunk, pid, vol)
                    sent++
                    val n = sent
                    main.post { updateLog(logId) { it.with(progress = "sending, $n of ${chunks.size}" + if (round > 0) ", round ${round + 1}" else "", fraction = minOf(1f, n.toFloat() / chunks.size)) } }
                }
                var reply: Transfer.Frame? = null
                for (attempt in 0 until END_ATTEMPTS) {   // repeat END if no reply is heard
                    // read each time: the radio may come or go in the middle of a photo
                    val onRadio = bleReady()
                    // A cold Bluetooth connection takes a few seconds to open, so the first
                    // attempt of a transfer waits as long as the sound path would.
                    val timeout = if (!onRadio) REPLY_TIMEOUT_MS
                        else if (heardOnRadio) BLE_REPLY_TIMEOUT_MS else REPLY_TIMEOUT_MS
                    val endFrame = Transfer.endFrame(id, chunks.size)
                    if (!(onRadio && ble.send(endFrame) { ok -> if (!ok) overTheSpeaker(endFrame, pid, vol, quiet = false) })) link.transmit(endFrame, pid, vol)
                    reply = control.poll(timeout, TimeUnit.MILLISECONDS)
                    while (reply != null && reply.id != id) reply = control.poll(timeout, TimeUnit.MILLISECONDS)
                    if (reply != null) break
                    Log.i(TAG, "transfer $id: no reply to END, attempt ${attempt + 1}")
                }
                when (reply) {
                    null -> { outcome = "no reply from the receiver after round ${round + 1}"; break }
                    is Transfer.Frame.Done -> { heardOnRadio = true; outcome = "delivered"; break }
                    is Transfer.Frame.Req -> {
                        heardOnRadio = true
                        pass = reply.missing.filter { it < chunks.size }
                        round++
                        if (pass.isEmpty()) { outcome = "delivered"; break }
                        if (!bleReady()) SystemClock.sleep(REPLY_DELAY_MS)   // let the receiver's post-transmit mute lapse
                    }
                    else -> {}
                }
                if (round >= MAX_ROUNDS) outcome = "gave up after $MAX_ROUNDS rounds"
            }
            val done = outcome
            main.post {
                transferring = false
                updateLog(logId) { it.with(progress = if (done == "delivered") null else done, fraction = null) }
                Log.i(TAG, "transfer $id: $done")
            }
        }
    }

    private fun effectiveVolumeFor(pid: Int): Int =
        if (pid >= Modem.SOTTO_ID_BASE) txVolume else minOf(txVolume, Modem.GGWAVE_MAX_VOLUME)

    override fun onCaptureStarted(sourceName: String) {
        main.post { captureSource = sourceName }
    }

    override fun onCaptureStopped(reason: String?) {
        main.post {
            captureSource = null
            micLevel = 0f
            if (reason != null) {
                wantListening = false
                status = reason
                ListenService.stop(getApplication())
            }
        }
    }

    override fun onTransmitting(active: Boolean) {
        main.post { transmitting = active }
    }

    override fun onCleared() {
        cancelBurst()
        carry.stop()
        ble.close()
        link.close()
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun addLog(kind: LogEntry.Kind, text: String, protocolId: Int, bytes: Int, image: Bitmap? = null, progress: String? = null, fraction: Float? = null, senderId: Int? = null, via: Int? = null, peer: Int? = null, seq: Int? = null, imageBytes: ByteArray? = null, card: LogEntry.Card? = null, bundleSeq: Int? = null, carriedHops: Int = 0): Long {
        val id = nextLogId++
        log.add(0, LogEntry(id, clock.format(Date()), kind, text, Modem.protocolName(protocolId), bytes, image, progress, fraction, senderId, via, peer, seq, imageBytes = imageBytes, card = card, bundleSeq = bundleSeq, carriedHops = carriedHops))
        while (log.size > MAX_LOG) log.removeAt(log.size - 1)
        scheduleSave()
        return id
    }

    private fun updateLog(id: Long, change: (LogEntry) -> LogEntry) {
        val i = log.indexOfFirst { it.id == id }
        if (i >= 0) { log[i] = change(log[i]); scheduleSave() }
    }

    /**
     * Counts burst messages on the receiving side. A new burst starts when the sequence number
     * goes backwards or when nothing burst-shaped arrived for a while, so a lost first message
     * does not break the count.
     */
    private fun trackBurst(text: String) {
        val m = BURST_REGEX.find(text) ?: return
        val seq = m.groupValues[1].toInt()
        val total = m.groupValues[2].toInt()
        val now = SystemClock.elapsedRealtime()
        if (burstExpected == 0 || seq <= burstLastSeq || now - burstLastAt > BURST_NEW_AFTER_MS) {
            resetBurstCounter()
            burstExpected = total
        }
        if (burstSeen.add(seq)) burstReceived++
        burstLastSeq = seq
        burstLastAt = now
        Log.i(TAG, "burst seq $seq: received $burstReceived / $burstExpected")
    }

    init {   // last: every property above is initialised by now
        io.execute {
            val restored = runCatching { History.load(app) }.getOrDefault(emptyList())
            main.post {
                log.addAll(restored)   // newest first already; anything logged meanwhile stays on top
                nextLogId = maxOf(nextLogId, (restored.maxOfOrNull { it.id } ?: 0L) + 1)
                historyReady = true
                scheduleSave()
            }
        }
        main.postDelayed(presence, PRESENCE_TICK_MS)
    }

    companion object {
        private const val TAG = "Sotto"
        const val MAX_BYTES = 100
        const val DEFAULT_TX_VOLUME = 100
        /** The room's slot in the unread map. Not a real id: ids are 16 bit and this is not. */
        private const val ROOM = -1
        /** A profile lives a week; republishing one that has not changed is pure noise. */
        private const val PROFILE_REPUBLISH_MS = 3L * 24 * 60 * 60 * 1000
        const val BURST_COUNT = 10
        const val BURST_GAP_MS = 2000L
        const val BURST_PAYLOAD_BYTES = 20
        private const val BURST_NEW_AFTER_MS = 60_000L
        private const val PHOTO_MAX_SIDE = 160
        private const val PHOTO_QUALITY = 45
        private const val PHOTO_MAX_BYTES = 8_000
        private const val INCOMING_FORGET_MS = 20_000L
        private const val INCOMING_ABANDON_MS = 120_000L
        private const val REPLY_DELAY_MS = 700L
        private const val REPLY_TIMEOUT_MS = 9_000L
        private const val BLE_REPLY_TIMEOUT_MS = 2_500L
        private const val REPLY_RETRY_MS = 4_000L
        private const val MAX_REPLY_ATTEMPTS = 3
        private const val END_ATTEMPTS = 2
        private const val MAX_ROUNDS = 6
        private const val UPDATE_CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
        private const val HELLO_EVERY_MS = 60_000L
        private const val HOP_BUDGET = 2
        private const val SEEN_FOR_MS = 10 * 60 * 1000L
        private const val RELAY_MIN_WAIT_MS = 1_000L
        private const val RELAY_JITTER_MS = 2_000L
        private const val RELAY_QUIET_WAIT_MS = 6_000L
        const val REACH_PROBES = 3
        private const val REACH_GAP_MS = 6_500L
        private const val PRESENCE_TICK_MS = 5_000L
        private const val PRESENT_FOR_MS = 3 * 60 * 1000L
        private const val CHIRP_AFTER_IDLE_MS = 75_000L
        private const val CHIRP_JITTER_MS = 20_000L
        private const val MAX_LOG = 500
        private val BURST_REGEX = Regex("^TB(\\d\\d)/(\\d\\d):")

        /** Fixed 20-byte payload: "TB01/10:" + 12 filler bytes. */
        fun burstPayload(seq: Int): ByteArray =
            String.format(Locale.US, "TB%02d/%02d:ABCDEFGHIJKL", seq, BURST_COUNT).toByteArray(Charsets.US_ASCII)
    }
}
