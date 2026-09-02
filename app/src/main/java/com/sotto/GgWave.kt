package com.sotto

/**
 * Thin Kotlin face over the JNI wrapper in cpp/ggwave_jni.cpp.
 *
 * [encode] is meant to be called from the transmit thread and [decode] from the capture
 * thread. The native side keeps separate TX and RX ggwave instances, so that pairing is safe
 * without locking.
 */
class GgWave(sampleRate: Int, samplesPerFrame: Int = SAMPLES_PER_FRAME) : AutoCloseable {

    private var handle: Long = nativeCreate(sampleRate, samplesPerFrame)

    init {
        check(handle != 0L) { "ggwave failed to initialise" }
    }

    /** 16-bit mono waveform for [payload], or null if ggwave rejected the request. */
    fun encode(payload: ByteArray, protocolId: Int, volume: Int): ShortArray? =
        nativeEncode(handle, payload, protocolId, volume)

    /** Feed [count] samples. Returns the payload when a whole message was decoded, else null. */
    fun decode(samples: ShortArray, count: Int): ByteArray? = nativeDecode(handle, samples, count)

    /** ggwave protocol id of the most recent successful [decode]. */
    val lastRxProtocolId: Int
        get() = nativeLastRxProtocolId(handle)

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    data class Protocol(val id: Int, val name: String)

    companion object {
        const val SAMPLE_RATE = 48_000
        const val SAMPLES_PER_FRAME = 1024

        /** GGWAVE_PROTOCOL_AUDIBLE_FAST */
        const val DEFAULT_PROTOCOL_ID = 1

        init {
            System.loadLibrary("sotto")
        }

        /**
         * Protocols the app can transmit with, in ggwave id order. Mono-tone ([MT]) protocols
         * are left out because ggwave only supports them with fixed-length payloads.
         */
        val protocols: List<Protocol> by lazy {
            (0 until nativeProtocolCount()).mapNotNull { id ->
                val raw = nativeProtocolName(id) ?: return@mapNotNull null
                if (raw.startsWith("[MT]")) return@mapNotNull null
                Protocol(id, prettyName(raw))
            }
        }

        fun protocolName(id: Int): String =
            protocols.firstOrNull { it.id == id }?.name ?: "protocol $id"

        private fun prettyName(raw: String): String = when {
            raw.startsWith("[U] ") -> "Ultrasound " + raw.removePrefix("[U] ")
            raw.startsWith("[DT] ") -> "Dual-tone " + raw.removePrefix("[DT] ")
            else -> "Audible $raw"
        }

        @JvmStatic private external fun nativeCreate(sampleRate: Int, samplesPerFrame: Int): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeEncode(handle: Long, payload: ByteArray, protocolId: Int, volume: Int): ShortArray?
        @JvmStatic private external fun nativeDecode(handle: Long, samples: ShortArray, count: Int): ByteArray?
        @JvmStatic private external fun nativeLastRxProtocolId(handle: Long): Int
        @JvmStatic private external fun nativeProtocolCount(): Int
        @JvmStatic private external fun nativeProtocolName(id: Int): String?
    }
}
