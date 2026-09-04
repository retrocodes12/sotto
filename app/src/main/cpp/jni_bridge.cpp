// JNI bridge for com.sotto.Modem: one native engine that encodes with either the
// Sotto modem (protocol ids 100+) or ggwave (ids 0..), and decodes with all of them
// at once on the same capture stream.
//
// ggwave is driven through its C++ class rather than the ggwave_* C functions
// because the C API cannot report which protocol decoded a message.

#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <deque>
#include <memory>
#include <vector>

#include "ggwave/ggwave.h"
#include "sotto_modem.h"

namespace {

constexpr const char * kTag = "sotto-jni";
constexpr int kSottoIdBase = 100;
// Decoded messages waiting for Kotlin to collect them. Bounded so a stream of frames from
// someone in the room cannot grow this without limit if the capture thread stalls.
constexpr size_t kMaxPending = 64;

struct Pending {
    std::vector<uint8_t> payload;
    int protocolId;
    float snrDb;
};

struct Engine {
    GGWave ggTx;
    GGWave ggRx;
    std::vector<std::unique_ptr<sotto::Decoder>> sottoRx;
    std::deque<Pending> pending;
    int lastRxProtocolId = -1;
    float lastRxSnrDb = 0;
};

Engine * engineOf(jlong handle) {
    return reinterpret_cast<Engine *>(handle);
}

// Releases a pinned primitive array however the scope is left. The decoders allocate, so an
// allocation failure inside one would otherwise leave the capture buffer pinned for good.
struct PinnedShorts {
    JNIEnv * env; jshortArray array; jshort * data;
    PinnedShorts(JNIEnv * e, jshortArray a) : env(e), array(a), data(e->GetShortArrayElements(a, nullptr)) {}
    ~PinnedShorts() { if (data != nullptr) env->ReleaseShortArrayElements(array, data, JNI_ABORT); }
    PinnedShorts(const PinnedShorts &) = delete;
    PinnedShorts & operator=(const PinnedShorts &) = delete;
};

jbyteArray takePending(JNIEnv * env, Engine * e) {
    if (e->pending.empty()) return nullptr;
    Pending p = std::move(e->pending.front());
    e->pending.pop_front();
    e->lastRxProtocolId = p.protocolId;
    e->lastRxSnrDb = p.snrDb;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(p.payload.size()));
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(p.payload.size()), reinterpret_cast<const jbyte *>(p.payload.data()));
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_sotto_Modem_nativeCreate(JNIEnv *, jclass, jint sampleRate, jint samplesPerFrame) {
    GGWave::setLogFile(nullptr);

    // ggwave protocol tables are global and read once by prepare(). Everything except
    // the mono-tone [MT] protocols, which only work with fixed-length payloads.
    GGWave::Protocols::tx().enableAll();
    GGWave::Protocols::rx().enableAll();
    for (auto id : { GGWAVE_PROTOCOL_MT_NORMAL, GGWAVE_PROTOCOL_MT_FAST, GGWAVE_PROTOCOL_MT_FASTEST }) {
        GGWave::Protocols::tx().toggle(id, false);
        GGWave::Protocols::rx().toggle(id, false);
    }

    GGWave::Parameters p = GGWave::getDefaultParameters();
    p.payloadLength   = -1;
    p.sampleRateInp   = static_cast<float>(sampleRate);
    p.sampleRateOut   = static_cast<float>(sampleRate);
    p.sampleRate      = static_cast<float>(sampleRate);
    p.samplesPerFrame = samplesPerFrame;
    p.sampleFormatInp = GGWAVE_SAMPLE_FORMAT_I16;
    p.sampleFormatOut = GGWAVE_SAMPLE_FORMAT_I16;

    auto * e = new Engine();
    p.operatingMode = GGWAVE_OPERATING_MODE_TX;
    const bool txOk = e->ggTx.prepare(p);
    p.operatingMode = GGWAVE_OPERATING_MODE_RX;
    const bool rxOk = e->ggRx.prepare(p);
    if (!txOk || !rxOk) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "ggwave prepare failed (tx=%d rx=%d)", txOk, rxOk);
        delete e;
        return 0;
    }

    size_t sottoHeap = 0;
    for (int i = 0; i < sotto::protocolCount(); ++i) {
        const sotto::Params & sp = sotto::protocol(i);
        auto dec = std::make_unique<sotto::Decoder>(sp);
        dec->onDebug = [name = sp.name](const char * msg) {
            __android_log_print(ANDROID_LOG_DEBUG, kTag, "%s: %s", name, msg);
        };
        sottoHeap += dec->heapBytes();
        e->sottoRx.push_back(std::move(dec));
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "engine ready: %d Hz; ggwave rx heap %d KB, sotto decoders %zu KB",
                        sampleRate, e->ggRx.heapSize() / 1024, sottoHeap / 1024);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_sotto_Modem_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    delete engineOf(handle);
}

// 16-bit mono waveform for `payload`, or null if the modem rejected the request.
JNIEXPORT jshortArray JNICALL
Java_com_sotto_Modem_nativeEncode(JNIEnv * env, jclass, jlong handle, jbyteArray payload, jint protocolId, jint volume) {
    auto * e = engineOf(handle);
    if (e == nullptr || payload == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(payload);
    std::vector<uint8_t> data(static_cast<size_t>(n) + 1);
    env->GetByteArrayRegion(payload, 0, n, reinterpret_cast<jbyte *>(data.data()));

    const int16_t * samples = nullptr;
    jsize count = 0;
    std::vector<int16_t> sottoWave;
    if (protocolId >= kSottoIdBase) {
        const sotto::Params * sp = sotto::protocolById(protocolId);
        if (sp == nullptr) return nullptr;
        sottoWave = sotto::encode(*sp, data.data(), n, volume / 100.0f);
        if (sottoWave.empty()) return nullptr;
        samples = sottoWave.data();
        count = static_cast<jsize>(sottoWave.size());
    } else {
        if (!e->ggTx.init(n, reinterpret_cast<const char *>(data.data()), static_cast<GGWave::TxProtocolId>(protocolId), volume)) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "ggwave tx init rejected: %d bytes, protocol %d, volume %d", n, protocolId, volume);
            return nullptr;
        }
        const uint32_t bytes = e->ggTx.encode();
        if (bytes == 0) return nullptr;
        samples = static_cast<const int16_t *>(e->ggTx.txWaveform());
        count = static_cast<jsize>(bytes / sizeof(int16_t));
    }

    jshortArray out = env->NewShortArray(count);
    if (out == nullptr) return nullptr;
    env->SetShortArrayRegion(out, 0, count, reinterpret_cast<const jshort *>(samples));
    return out;
}

// Feeds `count` samples to every decoder. Returns the first decoded payload, if any;
// nativeTakePending returns the rest one at a time.
JNIEXPORT jbyteArray JNICALL
Java_com_sotto_Modem_nativeDecode(JNIEnv * env, jclass, jlong handle, jshortArray samples, jint count) {
    auto * e = engineOf(handle);
    if (e == nullptr || samples == nullptr || count <= 0) return nullptr;

    // Never feed a decoder more samples than the caller actually handed over.
    const jsize have = env->GetArrayLength(samples);
    if (count > have) count = have;

    PinnedShorts pin(env, samples);
    if (pin.data == nullptr) return nullptr;

    if (e->ggRx.decode(pin.data, static_cast<uint32_t>(count) * sizeof(int16_t))) {
        GGWave::TxRxData view;
        const int n = e->ggRx.rxTakeData(view);
        if (n > 0) e->pending.push_back({ std::vector<uint8_t>(view.data(), view.data() + n), static_cast<int>(e->ggRx.rxProtocolId()), 0.0f });
    }
    for (auto & dec : e->sottoRx) {
        dec->feed(reinterpret_cast<const int16_t *>(pin.data), count, [&](const uint8_t * d, int n) {
            if (e->pending.size() < kMaxPending) {
                e->pending.push_back({ std::vector<uint8_t>(d, d + n), dec->params().id, dec->lastSnrDb() });
            }
        });
    }
    return takePending(env, e);
}

JNIEXPORT jbyteArray JNICALL
Java_com_sotto_Modem_nativeTakePending(JNIEnv * env, jclass, jlong handle) {
    auto * e = engineOf(handle);
    return e ? takePending(env, e) : nullptr;
}

// Drops any frame in progress in every Sotto decoder and forgets undelivered messages.
JNIEXPORT void JNICALL
Java_com_sotto_Modem_nativeReset(JNIEnv *, jclass, jlong handle) {
    auto * e = engineOf(handle);
    if (e == nullptr) return;
    for (auto & dec : e->sottoRx) dec->clear();
    e->pending.clear();
}

// True while any Sotto decoder is in the middle of a frame: listen-before-talk for relays.
JNIEXPORT jboolean JNICALL
Java_com_sotto_Modem_nativeReceiving(JNIEnv *, jclass, jlong handle) {
    auto * e = engineOf(handle);
    if (e == nullptr) return JNI_FALSE;
    for (auto & dec : e->sottoRx) if (dec->receiving()) return JNI_TRUE;
    return JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_sotto_Modem_nativeLastRxSnr(JNIEnv *, jclass, jlong handle) {
    auto * e = engineOf(handle);
    return e ? e->lastRxSnrDb : 0.0f;
}

JNIEXPORT jint JNICALL
Java_com_sotto_Modem_nativeLastRxProtocolId(JNIEnv *, jclass, jlong handle) {
    auto * e = engineOf(handle);
    return e ? e->lastRxProtocolId : -1;
}

// Protocol ids the Sotto modem offers (100+), in display order.
JNIEXPORT jintArray JNICALL
Java_com_sotto_Modem_nativeSottoProtocolIds(JNIEnv * env, jclass) {
    const int n = sotto::protocolCount();
    std::vector<jint> ids(n);
    for (int i = 0; i < n; ++i) ids[i] = sotto::protocol(i).id;
    jintArray out = env->NewIntArray(n);
    env->SetIntArrayRegion(out, 0, n, ids.data());
    return out;
}

JNIEXPORT jint JNICALL
Java_com_sotto_Modem_nativeGgwaveProtocolCount(JNIEnv *, jclass) {
    return GGWAVE_PROTOCOL_COUNT;
}

// Short name of a protocol: the Sotto modem's own for ids 100+, ggwave's ("Fast",
// "[U] Fast", ...) below that, null for ids without one.
JNIEXPORT jstring JNICALL
Java_com_sotto_Modem_nativeProtocolName(JNIEnv * env, jclass, jint id) {
    if (id >= kSottoIdBase) {
        const sotto::Params * sp = sotto::protocolById(id);
        return sp ? env->NewStringUTF(sp->name) : nullptr;
    }
    if (id < 0 || id >= GGWAVE_PROTOCOL_COUNT) return nullptr;
    const char * name = GGWave::Protocols::kDefault()[id].name;
    return name ? env->NewStringUTF(name) : nullptr;
}

// Seconds of audio a payload of n bytes takes on a Sotto protocol (-1 for others).
JNIEXPORT jfloat JNICALL
Java_com_sotto_Modem_nativeAirtime(JNIEnv *, jclass, jint protocolId, jint n) {
    const sotto::Params * sp = sotto::protocolById(protocolId);
    return sp ? sotto::airtimeSeconds(*sp, n) : -1.0f;
}

} // extern "C"
