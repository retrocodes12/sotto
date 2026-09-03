package com.sotto

import android.app.Application
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

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
) {
    enum class Kind { RX, TX, INFO }

    fun with(
        text: String = this.text, image: Bitmap? = this.image, progress: String? = this.progress,
        bytes: Int = this.bytes, fraction: Float? = this.fraction,
    ) = LogEntry(id, time, kind, text, protocol, bytes, image, progress, fraction)
}

/** Owns the [SoundLink] and exposes everything the UI shows as Compose state. */
class MainViewModel(app: Application) : AndroidViewModel(app), SoundLink.Callbacks {

    private val main = Handler(Looper.getMainLooper())
    private val link = SoundLink(app, this)
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
    var protocolId by mutableIntStateOf(Modem.DEFAULT_PROTOCOL_ID)
    /** Auto: Fast for text, Near for photos. Off: whatever [protocolId] says, for everything. */
    var autoProtocol by mutableStateOf(true)
    var txVolume by mutableIntStateOf(DEFAULT_TX_VOLUME)

    val textProtocolId: Int get() = if (autoProtocol) Modem.DEFAULT_PROTOCOL_ID else protocolId
    val photoProtocolId: Int get() = if (autoProtocol) Modem.NEAR_PROTOCOL_ID else protocolId
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
    private val control = LinkedBlockingQueue<Transfer.Frame>()
    private var nextLogId = 1L

    val draftBytes: Int
        get() = draft.toByteArray(Charsets.UTF_8).size

    val busy: Boolean
        get() = transmitting || burstSending || transferring

    val canSend: Boolean
        get() = draftBytes in 1..MAX_BYTES && !busy

    /** Amplitude actually handed to the modem: ggwave's multi-tone sum clips above 25. */
    val effectiveTxVolume: Int
        get() = if (protocolId >= Modem.SOTTO_ID_BASE) txVolume else minOf(txVolume, Modem.GGWAVE_MAX_VOLUME)

    // ---- listening ----------------------------------------------------------------------

    fun setListening(on: Boolean) {
        wantListening = on
        status = null
        if (on) link.startListening() else link.stopListening()
    }

    /** The conversation screen calls this once the mic permission exists: listening is the default. */
    fun ensureListening() {
        if (!wantListening && captureSource == null) setListening(true)
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
        }

    /** Android blocks background mic access anyway; stop cleanly and resume on return. */
    fun onForeground() {
        inForeground = true
        if (wantListening) link.startListening()
        if (SystemClock.elapsedRealtime() - lastUpdateCheck > UPDATE_CHECK_EVERY_MS) checkForUpdates(manual = false)
    }

    // ---- updates -------------------------------------------------------------------------

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
        link.stopListening()
    }

    fun refreshMediaVolume() {
        mediaVolume = link.mediaVolumeFraction()
    }

    fun showVolumePanel() = link.showVolumePanel()

    // ---- sending ------------------------------------------------------------------------

    fun send() {
        if (!canSend) return
        val text = draft
        val bytes = text.toByteArray(Charsets.UTF_8)
        val pid = textProtocolId
        val vol = effectiveVolumeFor(pid)
        status = null
        link.post {
            val ok = link.transmit(bytes, pid, vol)
            main.post {
                if (ok) { addLog(LogEntry.Kind.TX, text, pid, bytes.size); if (draft == text) draft = "" }
                else status = "${Modem.protocolName(pid)} refused to encode ${bytes.size} bytes"
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

    override fun onMessage(payload: ByteArray, protocolId: Int) {
        main.post {
            if (Transfer.isTransferFrame(payload)) {
                Transfer.parse(payload)?.let { onTransferFrame(it, protocolId) }
                return@post
            }
            val text = String(payload, Charsets.UTF_8)
            Log.i(TAG, "rx ${Modem.protocolName(protocolId)} ${payload.size} B: $text")
            trackBurst(text)
            addLog(LogEntry.Kind.RX, text, protocolId, payload.size)
        }
    }

    // ---- transfers: receiving ----------------------------------------------------------

    private fun onTransferFrame(f: Transfer.Frame, protocolId: Int) {
        when (f) {
            is Transfer.Frame.Req, is Transfer.Frame.Done -> { control.offer(f); return }
            else -> {}
        }
        val total = when (f) { is Transfer.Frame.Data -> f.total; is Transfer.Frame.End -> f.total; else -> return }
        val x = incoming.getOrPut(f.id) {
            Transfer.Incoming(f.id, total).also {
                it.logId = addLog(LogEntry.Kind.RX, "incoming photo", protocolId, 0, progress = "receiving, 0 of $total", fraction = 0f)
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
        link.post { SystemClock.sleep(REPLY_DELAY_MS); link.transmit(reply, protocolId, vol) }
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
        val assembled = Transfer.assemble(x.parts.map { it!! })
        if (assembled == null) {
            updateLog(x.logId) { it.with(text = "transfer failed to assemble", progress = null) }
            return
        }
        val (kind, content) = assembled
        Log.i(TAG, "transfer ${x.id} complete: kind $kind, ${content.size} B")
        when (kind) {
            Transfer.KIND_JPEG -> {
                val bmp = BitmapFactory.decodeByteArray(content, 0, content.size)
                updateLog(x.logId) { it.with(text = if (bmp != null) "" else "photo could not be decoded", image = bmp, progress = null, fraction = null, bytes = content.size) }
            }
            else -> updateLog(x.logId) { it.with(text = String(content, Charsets.UTF_8), progress = null, fraction = null, bytes = content.size) }
        }
        // the sender may finish its pass later; DONE goes out when its END arrives, and once now
        val pid = protocolId
        link.post { SystemClock.sleep(REPLY_DELAY_MS); link.transmit(Transfer.doneFrame(x.id), pid, effectiveVolumeFor(pid)) }
    }

    // ---- transfers: sending ------------------------------------------------------------

    /** Downscales and JPEG-compresses the picked photo, then sends it on the current protocol. */
    fun sendPhoto(uri: Uri) {
        if (busy) return
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

    private fun startTransfer(kind: Int, content: ByteArray, label: String, preview: Bitmap?) {
        val pid = photoProtocolId
        val id = Random.nextInt(256)
        val chunks = Transfer.chunks(id, kind, content)
        if (chunks == null) { status = "Too large to send (${content.size} B)"; return }
        val vol = effectiveVolumeFor(pid)
        val perChunk = Modem.airtime(pid, Transfer.MAX_FRAME) ?: 3f
        val logId = addLog(LogEntry.Kind.TX, "", pid, content.size, image = preview,
            progress = "sending, about ${(chunks.size * (perChunk + 0.4f)).toInt()} s", fraction = 0f)
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
            while (round < MAX_ROUNDS) {
                for (seq in pass) {
                    link.transmit(chunks[seq], pid, vol)
                    sent++
                    val n = sent
                    main.post { updateLog(logId) { it.with(progress = "sending, $n of ${chunks.size}" + if (round > 0) ", round ${round + 1}" else "", fraction = minOf(1f, n.toFloat() / chunks.size)) } }
                }
                var reply: Transfer.Frame? = null
                for (attempt in 0 until END_ATTEMPTS) {   // repeat END if no reply is heard
                    link.transmit(Transfer.endFrame(id, chunks.size), pid, vol)
                    reply = control.poll(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    while (reply != null && reply.id != id) reply = control.poll(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    if (reply != null) break
                    Log.i(TAG, "transfer $id: no reply to END, attempt ${attempt + 1}")
                }
                when (reply) {
                    null -> { outcome = "no reply from the receiver after round ${round + 1}"; break }
                    is Transfer.Frame.Done -> { outcome = "delivered"; break }
                    is Transfer.Frame.Req -> {
                        pass = reply.missing.filter { it < chunks.size }
                        round++
                        if (pass.isEmpty()) { outcome = "delivered"; break }
                        SystemClock.sleep(REPLY_DELAY_MS)   // let the receiver's post-transmit mute lapse
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
            }
        }
    }

    override fun onTransmitting(active: Boolean) {
        main.post { transmitting = active }
    }

    override fun onCleared() {
        cancelBurst()
        link.close()
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun addLog(kind: LogEntry.Kind, text: String, protocolId: Int, bytes: Int, image: Bitmap? = null, progress: String? = null, fraction: Float? = null): Long {
        val id = nextLogId++
        log.add(0, LogEntry(id, clock.format(Date()), kind, text, Modem.protocolName(protocolId), bytes, image, progress, fraction))
        while (log.size > MAX_LOG) log.removeAt(log.size - 1)
        return id
    }

    private fun updateLog(id: Long, change: (LogEntry) -> LogEntry) {
        val i = log.indexOfFirst { it.id == id }
        if (i >= 0) log[i] = change(log[i])
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

    companion object {
        private const val TAG = "Sotto"
        const val MAX_BYTES = 100
        const val DEFAULT_TX_VOLUME = 100
        const val BURST_COUNT = 10
        const val BURST_GAP_MS = 2000L
        const val BURST_PAYLOAD_BYTES = 20
        private const val BURST_NEW_AFTER_MS = 60_000L
        private const val PHOTO_MAX_SIDE = 160
        private const val PHOTO_QUALITY = 45
        private const val PHOTO_MAX_BYTES = 8_000
        private const val REPLY_DELAY_MS = 700L
        private const val REPLY_TIMEOUT_MS = 9_000L
        private const val REPLY_RETRY_MS = 4_000L
        private const val MAX_REPLY_ATTEMPTS = 3
        private const val END_ATTEMPTS = 2
        private const val MAX_ROUNDS = 6
        private const val UPDATE_CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
        private const val MAX_LOG = 200
        private val BURST_REGEX = Regex("^TB(\\d\\d)/(\\d\\d):")

        /** Fixed 20-byte payload: "TB01/10:" + 12 filler bytes. */
        fun burstPayload(seq: Int): ByteArray =
            String.format(Locale.US, "TB%02d/%02d:ABCDEFGHIJKL", seq, BURST_COUNT).toByteArray(Charsets.US_ASCII)
    }
}
