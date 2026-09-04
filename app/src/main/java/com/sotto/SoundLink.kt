package com.sotto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Half-duplex audio link.
 *
 * A dedicated capture thread reads 1024-sample frames from AudioRecord and feeds them to the
 * modem decoders. A single-threaded transmit executor encodes messages and plays them through
 * AudioTrack. Decoding is muted from the moment playback starts until [RESUME_GUARD_MS] after
 * it ends, so a phone never decodes its own transmission. Capture keeps running while muted so
 * the record buffer does not fill up with stale audio.
 *
 * All callbacks arrive on the capture or transmit thread, never the main thread.
 */
class SoundLink(private val context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        /** Roughly 12 times a second while listening. [rms] is linear, 0..1. */
        fun onLevel(rms: Float)
        fun onMessage(payload: ByteArray, protocolId: Int, snrDb: Float)
        /** Capture is up; [sourceName] is the AudioRecord source that worked. */
        fun onCaptureStarted(sourceName: String)
        /** Capture ended. [reason] is null for a requested stop, otherwise a user-readable error. */
        fun onCaptureStopped(reason: String?)
        fun onTransmitting(active: Boolean)
    }

    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val modem = Modem(SAMPLE_RATE)
    private val txExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "sotto-tx") }

    private val decodeMuted = AtomicBoolean(false)
    /** Set after each transmission so the capture thread clears half-read frames once it decodes again. */
    private val needsReset = AtomicBoolean(false)
    /** Each capture thread gets its own flag, so a thread that outlives a stop can never be re-armed. */
    @Volatile private var run: AtomicBoolean? = null
    @Volatile private var captureThread: Thread? = null

    /** Call from the main thread. Any previous capture thread is stopped and joined first. */
    fun startListening() {
        if (!stopListening()) {
            // The old thread is still inside a read. Starting a second one would give two
            // capture threads the same lock-free native decoders.
            Log.w(TAG, "previous capture thread has not stopped; not starting another")
            return
        }
        val flag = AtomicBoolean(true)
        run = flag
        captureThread = Thread({ captureLoop(flag) }, "sotto-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Stops the capture thread and waits for it. It exits within one frame read (about 21 ms).
     * Returns false if it had not stopped in time, which matters to [close]: freeing the native
     * modem while that thread is still inside a decode is a crash in C++.
     */
    fun stopListening(): Boolean {
        run?.set(false)
        val t = captureThread
        captureThread = null
        run = null
        if (t == null || t === Thread.currentThread()) return true
        // This is called from the main thread on every listen toggle, so the wait has to stay
        // well inside the ANR window. The loop exits within one frame read, about 21 ms.
        t.join(1000)
        return !t.isAlive
    }

    /** Runs [task] on the transmit thread. Call [transmit] from inside it. */
    fun post(task: () -> Unit) {
        txExecutor.execute(task)
    }

    /**
     * Encodes and plays [payload], blocking the transmit thread until playback has drained and
     * decoding is enabled again. Returns false if the modem refused to encode.
     */
    /** When this phone last played anything, for presence chirps. */
    @Volatile var lastTransmitAt: Long = 0L
        private set

    fun transmit(payload: ByteArray, protocolId: Int, volume: Int): Boolean {
        val wave = modem.encode(payload, protocolId, volume) ?: return false
        // Media volume at zero produces a waveform of digital silence, which used to be logged
        // as a message sent. Nothing left the phone.
        if (mediaVolumeFraction() <= 0f) {
            Log.w(TAG, "media volume is zero; nothing would leave the speaker")
            return false
        }
        lastTransmitAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "tx protocol $protocolId, ${payload.size} B, amplitude $volume, ${wave.size} samples (${wave.size / 48} ms)")
        decodeMuted.set(true)
        callbacks.onTransmitting(true)
        var played = false
        try {
            playBlocking(wave)
            played = true
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
        } finally {
            SystemClock.sleep(RESUME_GUARD_MS)
            needsReset.set(true)
            decodeMuted.set(false)
            callbacks.onTransmitting(false)
        }
        return played   // a swallowed exception used to be reported as a message sent
    }

    /**
     * The device the tones must come out of. AudioTrack with USAGE_MEDIA follows the media route,
     * so with earbuds paired every message played into somebody's ears and the room heard nothing
     * -- while the app said it had been sent.
     */
    private fun builtinSpeaker(): android.media.AudioDeviceInfo? =
        runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        }.getOrNull()

    /** True when something is plugged in or paired that would otherwise take the sound. */
    fun outputIsElsewhere(): Boolean = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }.getOrDefault(false)

    /**
     * Listen before talking: block the calling (transmit) thread until no frame is being
     * received, up to [maxWaitMs]. Returns false if the band never went quiet.
     */
    fun waitUntilQuiet(maxWaitMs: Long): Boolean {
        val until = SystemClock.elapsedRealtime() + maxWaitMs
        var quietFor = 0L
        while (SystemClock.elapsedRealtime() < until) {
            if (run?.get() == true && modem.receiving) quietFor = 0 else quietFor += 50
            if (quietFor >= 250) return true
            SystemClock.sleep(50)
        }
        return false
    }

    /** Media (STREAM_MUSIC) volume as a fraction of its maximum. */
    fun mediaVolumeFraction(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 1f
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun showVolumePanel() {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI
        )
    }

    /** Stops capture, then frees the modem on the transmit thread once any in-flight transmit ends. */
    fun close() {
        val stopped = stopListening()
        // If the capture thread would not stop, leak the native modem rather than free it under
        // that thread's feet. The process is going away anyway.
        if (stopped) txExecutor.execute { modem.close() } else Log.w(TAG, "capture thread still running; leaving the modem alone")
        txExecutor.shutdown()
    }

    // ---- capture -------------------------------------------------------------------------

    private fun captureLoop(flag: AtomicBoolean) {
        val opened = openRecorder()
        if (opened == null) {
            flag.set(false)
            callbacks.onCaptureStopped("Could not open the microphone at 48 kHz mono")
            return
        }
        val (record, sourceName) = opened
        val effects = disableEffects(record.audioSessionId)
        val frame = ShortArray(Modem.SAMPLES_PER_FRAME)
        var reason: String? = null
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                reason = "Microphone is busy (another app may be recording)"
                return
            }
            callbacks.onCaptureStarted(sourceName)

            var reads = 0
            var emptyReads = 0
            var silentReads = 0
            var energy = 0.0
            var energyCount = 0
            while (flag.get()) {
                val n = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION) {
                        reason = "Microphone stopped delivering audio (error $n)"
                        break
                    }
                    // Any other non-positive return comes back immediately, so looping straight
                    // round would spin a core flat out and drain the battery in silence. Give it
                    // a few frames' grace, and give up if it never recovers.
                    if (++emptyReads > MAX_EMPTY_READS) {
                        reason = "Microphone stopped delivering audio"
                        break
                    }
                    SystemClock.sleep(20)
                    continue
                }
                emptyReads = 0

                var sum = 0.0
                for (i in 0 until n) {
                    val v = frame[i].toDouble()
                    sum += v * v
                }
                // A real microphone never delivers exactly zero for long: there is always some
                // dither. A stream of true silence means something else has taken the mic (a
                // call, another recorder) or the system has muted us, and the UI would otherwise
                // go on saying "listening" for as long as the user left it.
                if (sum == 0.0) {
                    if (++silentReads > MAX_SILENT_READS) {
                        reason = "The microphone is delivering silence. Another app may be using it."
                        break
                    }
                } else {
                    silentReads = 0
                }

                energy += sum
                energyCount += n
                if (++reads % LEVEL_EVERY_READS == 0) {
                    callbacks.onLevel((sqrt(energy / energyCount) / 32768.0).toFloat())
                    energy = 0.0
                    energyCount = 0
                }

                if (!decodeMuted.get()) {
                    if (needsReset.getAndSet(false)) modem.reset()
                    var payload = modem.decode(frame, n)
                    while (payload != null) {
                        callbacks.onMessage(payload, modem.lastRxProtocolId, modem.lastRxSnr)
                        payload = modem.takePending()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            reason = "Capture failed: ${e.message}"
        } finally {
            runCatching { record.stop() }
            record.release()
            effects.forEach { runCatching { it.release() } }
            flag.set(false)
            callbacks.onCaptureStopped(reason)
        }
    }

    /** Tries UNPROCESSED (when the device advertises it), then VOICE_RECOGNITION, then MIC. */
    private fun openRecorder(): Pair<AudioRecord, String>? {
        // The permission can be taken away while the app runs; capture then stops cleanly
        // instead of throwing on the capture thread.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "microphone permission is gone")
            return null
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "48 kHz mono capture unsupported (minBuf=$minBuf)")
            return null
        }
        val bufferBytes = max(minBuf * 4, Modem.SAMPLES_PER_FRAME * 2 * 8)

        val unprocessed = audioManager.getProperty(
            AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
        ) == "true"
        val candidates = buildList {
            if (unprocessed) add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED")
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION")
            add(MediaRecorder.AudioSource.MIC to "MIC")
        }
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        for ((source, name) in candidates) {
            val record = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord($name) failed: $e")
                continue
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "capture source $name, buffer $bufferBytes bytes (min $minBuf)")
                return record to name
            }
            record.release()
        }
        return null
    }

    /** Explicitly switches off AEC, NS and AGC on the capture session where the device has them. */
    private fun disableEffects(sessionId: Int): List<AudioEffect> {
        val effects = ArrayList<AudioEffect>(3)
        fun off(name: String, available: Boolean, create: () -> AudioEffect?) {
            if (!available) return
            try {
                val fx = create() ?: return
                fx.setEnabled(false)
                effects += fx
                Log.i(TAG, "$name disabled")
            } catch (e: Exception) {
                Log.w(TAG, "$name: $e")
            }
        }
        off("AcousticEchoCanceler", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sessionId) }
        off("NoiseSuppressor", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }
        off("AutomaticGainControl", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sessionId) }
        return effects
    }

    // ---- playback ------------------------------------------------------------------------

    private fun playBlocking(wave: ShortArray) {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuf * 4, SAMPLE_RATE / 4 * 2))
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack failed to initialise")
        }
        // Send it out of the phone's own speaker whatever else is connected. A message meant for
        // the room is not a message for your headphones.
        builtinSpeaker()?.let { speaker ->
            if (!track.setPreferredDevice(speaker)) Log.w(TAG, "could not pin playback to the speaker")
        }

        // Trailing silence so the end marker has left the DAC before stop().
        val tail = SAMPLE_RATE / 10
        val totalFrames = wave.size + tail
        try {
            track.play()
            var off = 0
            while (off < wave.size) {
                val w = track.write(wave, off, wave.size - off, AudioTrack.WRITE_BLOCKING)
                if (w < 0) throw IllegalStateException("AudioTrack write error $w")
                off += w
            }
            track.write(ShortArray(tail), 0, tail, AudioTrack.WRITE_BLOCKING)

            // write() returns once the data is queued; wait until it has actually been played.
            val deadline = SystemClock.elapsedRealtime() + totalFrames * 1000L / SAMPLE_RATE + 1000
            while (track.playbackHeadPosition < totalFrames && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(10)
            }
            track.stop()
        } finally {
            track.release()
        }
    }

    companion object {
        private const val TAG = "SoundLink"
        const val SAMPLE_RATE = Modem.SAMPLE_RATE
        const val RESUME_GUARD_MS = 300L
        private const val MAX_EMPTY_READS = 250     // about five seconds of nothing
        private const val MAX_SILENT_READS = 1400   // about thirty seconds of exact digital silence
        private const val LEVEL_EVERY_READS = 4
    }
}
