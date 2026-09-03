package com.sotto

import android.content.Context
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
class SoundLink(context: Context, private val callbacks: Callbacks) {

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
        stopListening()
        val flag = AtomicBoolean(true)
        run = flag
        captureThread = Thread({ captureLoop(flag) }, "sotto-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Stops the capture thread and waits for it. It exits within one frame read (about 21 ms). */
    fun stopListening() {
        run?.set(false)
        captureThread?.let { if (it !== Thread.currentThread()) it.join(1000) }
        captureThread = null
        run = null
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
        lastTransmitAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "tx protocol $protocolId, ${payload.size} B, amplitude $volume, ${wave.size} samples (${wave.size / 48} ms)")
        decodeMuted.set(true)
        callbacks.onTransmitting(true)
        try {
            playBlocking(wave)
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
        } finally {
            SystemClock.sleep(RESUME_GUARD_MS)
            needsReset.set(true)
            decodeMuted.set(false)
            callbacks.onTransmitting(false)
        }
        return true
    }

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
        stopListening()
        txExecutor.execute { modem.close() }
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
            var energy = 0.0
            var energyCount = 0
            while (flag.get()) {
                val n = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION) {
                        reason = "Microphone stopped delivering audio (error $n)"
                        break
                    }
                    continue
                }

                var sum = 0.0
                for (i in 0 until n) {
                    val v = frame[i].toDouble()
                    sum += v * v
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
        private const val LEVEL_EVERY_READS = 4
    }
}
