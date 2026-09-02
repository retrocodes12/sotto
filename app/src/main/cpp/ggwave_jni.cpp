// JNI bridge for com.sotto.GgWave.
//
// Uses the GGWave C++ class rather than the ggwave_* C functions because the
// C API cannot report which protocol decoded a message (rxProtocolId()).
// Two instances per engine: a TX-only one used from the transmit thread and an
// RX-only one used from the capture thread, so neither thread ever touches the
// other's buffers.

#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <vector>

#include "ggwave/ggwave.h"

namespace {

constexpr const char * kTag = "sotto-jni";

struct Engine {
    GGWave tx;
    GGWave rx;
    int lastRxProtocolId = -1;
};

Engine * engineOf(jlong handle) {
    return reinterpret_cast<Engine *>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_sotto_GgWave_nativeCreate(JNIEnv *, jclass, jint sampleRate, jint samplesPerFrame) {
    // ggwave logs to stderr, which goes nowhere on Android.
    GGWave::setLogFile(nullptr);

    // Protocol tables are global and read once by prepare(). Enable everything
    // except the mono-tone [MT] protocols: they only work with fixed-length
    // payloads, and this app sends variable-length messages.
    GGWave::Protocols::tx().enableAll();
    GGWave::Protocols::rx().enableAll();
    for (auto id : { GGWAVE_PROTOCOL_MT_NORMAL, GGWAVE_PROTOCOL_MT_FAST, GGWAVE_PROTOCOL_MT_FASTEST }) {
        GGWave::Protocols::tx().toggle(id, false);
        GGWave::Protocols::rx().toggle(id, false);
    }

    GGWave::Parameters p = GGWave::getDefaultParameters();
    p.payloadLength   = -1;                       // variable length, with sound markers
    p.sampleRateInp   = static_cast<float>(sampleRate);
    p.sampleRateOut   = static_cast<float>(sampleRate);
    p.sampleRate      = static_cast<float>(sampleRate);
    p.samplesPerFrame = samplesPerFrame;
    p.sampleFormatInp = GGWAVE_SAMPLE_FORMAT_I16;
    p.sampleFormatOut = GGWAVE_SAMPLE_FORMAT_I16;

    auto * e = new Engine();

    p.operatingMode = GGWAVE_OPERATING_MODE_TX;
    const bool txOk = e->tx.prepare(p);
    p.operatingMode = GGWAVE_OPERATING_MODE_RX;
    const bool rxOk = e->rx.prepare(p);

    if (!txOk || !rxOk) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "ggwave prepare failed (tx=%d rx=%d)", txOk, rxOk);
        delete e;
        return 0;
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "ggwave ready: %d Hz, %d samples/frame, rx heap %d bytes",
                        sampleRate, samplesPerFrame, e->rx.heapSize());
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_sotto_GgWave_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    delete engineOf(handle);
}

// Returns the 16-bit mono waveform for `payload`, or null if ggwave rejected
// the request (bad protocol, payload too long, volume out of range).
JNIEXPORT jshortArray JNICALL
Java_com_sotto_GgWave_nativeEncode(JNIEnv * env, jclass, jlong handle, jbyteArray payload,
                                   jint protocolId, jint volume) {
    auto * e = engineOf(handle);
    if (e == nullptr || payload == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(payload);
    std::vector<char> data(static_cast<size_t>(n) + 1);
    env->GetByteArrayRegion(payload, 0, n, reinterpret_cast<jbyte *>(data.data()));

    if (!e->tx.init(n, data.data(), static_cast<GGWave::TxProtocolId>(protocolId), volume)) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "tx init rejected: %d bytes, protocol %d, volume %d",
                            n, protocolId, volume);
        return nullptr;
    }

    const uint32_t bytes = e->tx.encode();
    if (bytes == 0) return nullptr;

    const jsize samples = static_cast<jsize>(bytes / sizeof(int16_t));
    jshortArray out = env->NewShortArray(samples);
    if (out == nullptr) return nullptr;
    env->SetShortArrayRegion(out, 0, samples, static_cast<const jshort *>(e->tx.txWaveform()));
    return out;
}

// Feeds `count` 16-bit samples to the decoder. Returns the decoded payload
// when a complete message was recognised, otherwise null.
JNIEXPORT jbyteArray JNICALL
Java_com_sotto_GgWave_nativeDecode(JNIEnv * env, jclass, jlong handle, jshortArray samples, jint count) {
    auto * e = engineOf(handle);
    if (e == nullptr || samples == nullptr || count <= 0) return nullptr;

    jshort * p = env->GetShortArrayElements(samples, nullptr);
    if (p == nullptr) return nullptr;
    const bool ok = e->rx.decode(p, static_cast<uint32_t>(count) * sizeof(int16_t));
    env->ReleaseShortArrayElements(samples, p, JNI_ABORT);
    if (!ok) return nullptr;

    // rxTakeData() hands back a view into ggwave's own buffer, valid until the
    // next decode() call, so copy it out right away.
    GGWave::TxRxData view;
    const int n = e->rx.rxTakeData(view);
    if (n <= 0) return nullptr;

    e->lastRxProtocolId = static_cast<int>(e->rx.rxProtocolId());

    jbyteArray out = env->NewByteArray(n);
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte *>(view.data()));
    return out;
}

JNIEXPORT jint JNICALL
Java_com_sotto_GgWave_nativeLastRxProtocolId(JNIEnv *, jclass, jlong handle) {
    auto * e = engineOf(handle);
    return e ? e->lastRxProtocolId : -1;
}

JNIEXPORT jint JNICALL
Java_com_sotto_GgWave_nativeProtocolCount(JNIEnv *, jclass) {
    return GGWAVE_PROTOCOL_COUNT;
}

// ggwave's own short name for a protocol ("Fast", "[U] Fast", ...), or null
// for ids without one (the CUSTOM_* slots).
JNIEXPORT jstring JNICALL
Java_com_sotto_GgWave_nativeProtocolName(JNIEnv * env, jclass, jint id) {
    if (id < 0 || id >= GGWAVE_PROTOCOL_COUNT) return nullptr;
    const char * name = GGWave::Protocols::kDefault()[id].name;
    return name ? env->NewStringUTF(name) : nullptr;
}


} // extern "C"
