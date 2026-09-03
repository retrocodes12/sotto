package com.sotto

import android.app.Application
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class LogEntry(val time: String, val kind: Kind, val text: String, val protocol: String, val bytes: Int) {
    enum class Kind { RX, TX, INFO }
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
    var txVolume by mutableIntStateOf(DEFAULT_TX_VOLUME)
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

    val draftBytes: Int
        get() = draft.toByteArray(Charsets.UTF_8).size

    val busy: Boolean
        get() = transmitting || burstSending

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

    /** Android blocks background mic access anyway; stop cleanly and resume on return. */
    fun onForeground() {
        inForeground = true
        if (wantListening) link.startListening()
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
        val pid = protocolId
        val vol = effectiveTxVolume
        status = null
        link.post {
            val ok = link.transmit(bytes, pid, vol)
            main.post {
                if (ok) addLog(LogEntry.Kind.TX, text, pid, bytes.size)
                else status = "${Modem.protocolName(pid)} refused to encode ${bytes.size} bytes"
            }
        }
    }

    fun startBurst() {
        if (busy) return
        burstSending = true
        burstSent = 0
        burstCancel.set(false)
        val pid = protocolId
        val vol = effectiveTxVolume
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
            val text = String(payload, Charsets.UTF_8)
            Log.i(TAG, "rx ${Modem.protocolName(protocolId)} ${payload.size} B: $text")
            trackBurst(text)
            addLog(LogEntry.Kind.RX, text, protocolId, payload.size)
        }
    }

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

    private fun addLog(kind: LogEntry.Kind, text: String, protocolId: Int, bytes: Int) {
        log.add(0, LogEntry(clock.format(Date()), kind, text, Modem.protocolName(protocolId), bytes))
        while (log.size > MAX_LOG) log.removeAt(log.size - 1)
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
        private const val BURST_NEW_AFTER_MS = 20_000L
        private const val MAX_LOG = 200
        private val BURST_REGEX = Regex("^TB(\\d\\d)/(\\d\\d):")

        /** Fixed 20-byte payload: "TB01/10:" + 12 filler bytes. */
        fun burstPayload(seq: Int): ByteArray =
            String.format(Locale.US, "TB%02d/%02d:ABCDEFGHIJKL", seq, BURST_COUNT).toByteArray(Charsets.US_ASCII)
    }
}
