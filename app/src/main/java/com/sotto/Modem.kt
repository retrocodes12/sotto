package com.sotto

/**
 * Kotlin face over the native engine in cpp/jni_bridge.cpp: the Sotto modem (protocol
 * ids 100 and up) and ggwave (ids below 100) behind one encode/decode pair.
 *
 * [encode] is meant to be called from the transmit thread and [decode] from the capture
 * thread. The native side keeps separate TX and RX state, so that pairing is safe
 * without locking.
 */
class Modem(sampleRate: Int, samplesPerFrame: Int = SAMPLES_PER_FRAME) : AutoCloseable {

    private var handle: Long = nativeCreate(sampleRate, samplesPerFrame)

    init {
        check(handle != 0L) { "modem failed to initialise" }
    }

    /** 16-bit mono waveform for [payload], or null if the modem rejected the request. */
    fun encode(payload: ByteArray, protocolId: Int, volume: Int): ShortArray? =
        nativeEncode(handle, payload, protocolId, volume)

    /**
     * Feed [count] samples to every decoder. Returns the first message decoded during
     * this call, else null. Call [takePending] until it returns null for any others.
     */
    fun decode(samples: ShortArray, count: Int): ByteArray? = nativeDecode(handle, samples, count)

    fun takePending(): ByteArray? = nativeTakePending(handle)

    /** Protocol id of the most recent message returned by [decode] or [takePending]. */
    val lastRxProtocolId: Int
        get() = nativeLastRxProtocolId(handle)

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    data class Protocol(val id: Int, val name: String) {
        val isSotto: Boolean get() = id >= SOTTO_ID_BASE
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val SAMPLES_PER_FRAME = 1024
        const val SOTTO_ID_BASE = 100

        /** Sotto Fast. */
        const val DEFAULT_PROTOCOL_ID = 100

        /** ggwave sums up to six tones, so its `volume` must stay low or the sum clips. */
        const val GGWAVE_MAX_VOLUME = 25

        init {
            System.loadLibrary("sotto")
        }

        /**
         * Everything the app can transmit with: the Sotto modem's protocols first, then
         * ggwave's. ggwave's mono-tone ([MT]) protocols are left out because ggwave only
         * supports them with fixed-length payloads.
         */
        val protocols: List<Protocol> by lazy {
            val sotto = nativeSottoProtocolIds().toList().mapNotNull { id -> nativeProtocolName(id)?.let { Protocol(id, it) } }
            val ggwave = (0 until nativeGgwaveProtocolCount()).mapNotNull { id ->
                val raw = nativeProtocolName(id) ?: return@mapNotNull null
                if (raw.startsWith("[MT]")) return@mapNotNull null
                Protocol(id, ggwaveName(raw))
            }
            sotto + ggwave
        }

        fun protocolName(id: Int): String =
            protocols.firstOrNull { it.id == id }?.name ?: "protocol $id"

        /** Seconds of audio for [bytes] on a Sotto protocol, or null for ggwave ones. */
        fun airtime(protocolId: Int, bytes: Int): Float? =
            nativeAirtime(protocolId, bytes).takeIf { it >= 0f }

        private fun ggwaveName(raw: String): String = when {
            raw.startsWith("[U] ") -> "ggwave Ultrasound " + raw.removePrefix("[U] ")
            raw.startsWith("[DT] ") -> "ggwave Dual-tone " + raw.removePrefix("[DT] ")
            else -> "ggwave $raw"
        }

        @JvmStatic private external fun nativeCreate(sampleRate: Int, samplesPerFrame: Int): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeEncode(handle: Long, payload: ByteArray, protocolId: Int, volume: Int): ShortArray?
        @JvmStatic private external fun nativeDecode(handle: Long, samples: ShortArray, count: Int): ByteArray?
        @JvmStatic private external fun nativeTakePending(handle: Long): ByteArray?
        @JvmStatic private external fun nativeLastRxProtocolId(handle: Long): Int
        @JvmStatic private external fun nativeSottoProtocolIds(): IntArray
        @JvmStatic private external fun nativeGgwaveProtocolCount(): Int
        @JvmStatic private external fun nativeProtocolName(id: Int): String?
        @JvmStatic private external fun nativeAirtime(protocolId: Int, n: Int): Float
    }
}
