// Host benchmark: the Sotto modem against ggwave over a simulated air channel.
//
// Build from the repo root:
//   g++ -O2 -std=c++17 -I app/src/main/cpp -I app/src/main/cpp/ggwave/include -I app/src/main/cpp/ggwave/src \
//       tools/bench.cpp app/src/main/cpp/sotto_modem.cpp app/src/main/cpp/ggwave/src/ggwave.cpp -o /tmp/bench && /tmp/bench
//
// Every waveform is peak-normalised to the same level first, because a phone
// speaker is peak limited: that is the constraint the modems compete under.
#include "ggwave/ggwave.h"
#include "sotto_modem.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <random>
#include <string>
#include <vector>

namespace {

constexpr int   kFs = 48000;
constexpr int   kPayload = 20;
constexpr int   kTrials = 24;
constexpr float kPeak = 0.9f;
constexpr float kDistanceGain = 0.1f;   // -20 dB from speaker peak to mic, "across the room"

struct Modem {
    std::string name;
    std::function<std::vector<float>(const std::vector<uint8_t> &)> encode;   // peak-normalised
    std::function<bool(const std::vector<int16_t> &, const std::vector<uint8_t> &, int &)> decode; // ok, decodes
    float airtime20 = 0, airtime100 = 0;
};

std::vector<float> normalise(const int16_t * w, size_t n) {
    std::vector<float> out(n);
    float peak = 1;
    for (size_t i = 0; i < n; ++i) peak = std::max(peak, std::fabs(static_cast<float>(w[i])));
    for (size_t i = 0; i < n; ++i) out[i] = w[i] / peak * kPeak;
    return out;
}

GGWave::Parameters ggParams(int mode) {
    GGWave::Parameters p = GGWave::getDefaultParameters();
    p.sampleRateInp = p.sampleRateOut = p.sampleRate = kFs;
    p.samplesPerFrame = 1024;
    p.sampleFormatInp = p.sampleFormatOut = GGWAVE_SAMPLE_FORMAT_I16;
    p.operatingMode = mode;
    return p;
}

struct Channel {
    float noiseRms; bool room; std::mt19937 & rng;

    std::vector<int16_t> apply(const std::vector<float> & tx) {
        std::uniform_real_distribution<float> U(0, 1);
        const int lead = kFs * (0.3f + 0.7f * U(rng)), tail = kFs / 2;
        std::vector<float> x(lead + tx.size() + tail, 0.0f);
        for (size_t i = 0; i < tx.size(); ++i) x[lead + i] = tx[i] * kDistanceGain;

        if (room) {
            // speaker/mic tilt: split at 4 kHz, random gain per band (+-8 dB)
            const float a = std::exp(-2.0f * static_cast<float>(M_PI) * 4000.0f / kFs);
            const float gLo = 0.4f + 0.6f * U(rng), gHi = 0.4f + 0.6f * U(rng);
            std::vector<float> y(x.size());
            float lp = 0;
            for (size_t i = 0; i < x.size(); ++i) { lp = a * lp + (1 - a) * x[i]; y[i] = lp * gLo + (x[i] - lp) * gHi; }
            // early reflections + four feedback combs (RT60 ~ 0.35 s)
            const int   d[6] = { 101, 350, 667, 1104, 1776, 2640 };
            const float g[6] = { 0.6f, 0.45f, 0.35f, 0.25f, 0.18f, 0.12f };
            std::vector<float> z(y.size());
            for (size_t i = 0; i < y.size(); ++i) {
                float v = y[i];
                for (int k = 0; k < 6; ++k) if (i >= static_cast<size_t>(d[k])) v += g[k] * y[i - d[k]];
                z[i] = v;
            }
            const int cd[4] = { 1426, 1781, 1973, 2098 };
            std::vector<std::vector<float>> comb(4, std::vector<float>(z.size()));
            for (int c = 0; c < 4; ++c) {
                const float cg = std::pow(10.0f, -3.0f * cd[c] / (0.35f * kFs));
                for (size_t i = 0; i < z.size(); ++i) comb[c][i] = z[i] + (i >= static_cast<size_t>(cd[c]) ? cg * comb[c][i - cd[c]] : 0);
            }
            for (size_t i = 0; i < z.size(); ++i) x[i] = z[i] + 0.2f * (comb[0][i] + comb[1][i] + comb[2][i] + comb[3][i] - 4 * z[i]);
        }

        std::normal_distribution<float> N(0, noiseRms);
        std::vector<int16_t> out(x.size());
        for (size_t i = 0; i < x.size(); ++i) {
            const float v = std::max(-1.0f, std::min(1.0f, x[i] + N(rng)));
            out[i] = static_cast<int16_t>(std::lround(v * 32767));
        }
        return out;
    }
};

} // namespace

int diag(int protoIndex, float noiseDb, bool room, int trials) {
    std::mt19937 rng(99);
    const sotto::Params & p = sotto::protocol(protoIndex);
    int ok = 0; std::map<std::string, int> reasons;
    for (int t = 0; t < trials; ++t) {
        std::vector<uint8_t> msg(kPayload); for (auto & b : msg) b = 32 + rng() % 95;
        auto w = sotto::encode(p, msg.data(), msg.size(), 1.0f);
        Channel ch{ std::pow(10.0f, noiseDb / 20), room, rng };
        auto s = ch.apply(normalise(w.data(), w.size()));
        sotto::Decoder dec(p); bool got = false; std::vector<std::string> log;
        dec.onDebug = [&](const char * m) { log.push_back(m); };
        for (size_t off = 0; off < s.size(); off += 1024)
            dec.feed(s.data() + off, std::min<size_t>(1024, s.size() - off), [&](const uint8_t * d, int n) { got = std::vector<uint8_t>(d, d + n) == msg; });
        if (got) ++ok; else { std::string r; for (auto & l : log) r += l + "; "; ++reasons[r.empty() ? "no sync" : r]; }
    }
    printf("%s, noise %.0f dBFS, %s: %d/%d ok\n", p.name, noiseDb, room ? "room" : "free field", ok, trials);
    for (auto & [r, n] : reasons) printf("  %3d x %s\n", n, r.c_str());
    return 0;
}

int main(int argc, char ** argv) {
    if (argc >= 2 && std::string(argv[1]) == "diag") return diag(argc > 2 ? atoi(argv[2]) : 0, argc > 3 ? atof(argv[3]) : -60, argc > 4 ? atoi(argv[4]) != 0 : true, argc > 5 ? atoi(argv[5]) : 200);
    std::mt19937 rng(12345);
    GGWave::setLogFile(nullptr);
    GGWave::Protocols::tx().enableAll();
    GGWave::Protocols::rx().disableAll();
    for (auto id : { GGWAVE_PROTOCOL_AUDIBLE_NORMAL, GGWAVE_PROTOCOL_AUDIBLE_FAST, GGWAVE_PROTOCOL_AUDIBLE_FASTEST })
        GGWave::Protocols::rx().toggle(id, true);

    std::vector<Modem> modems;
    for (int i = 0; i < 2; ++i) {   // Sotto Fast, Sotto Robust
        const sotto::Params & p = sotto::protocol(i);
        Modem m;
        m.name = p.name;
        m.encode = [&p](const std::vector<uint8_t> & d) { auto w = sotto::encode(p, d.data(), d.size(), 1.0f); return normalise(w.data(), w.size()); };
        m.decode = [&p](const std::vector<int16_t> & s, const std::vector<uint8_t> & want, int & decodes) {
            sotto::Decoder dec(p); bool ok = false; decodes = 0;
            for (size_t off = 0; off < s.size(); off += 1024)
                dec.feed(s.data() + off, std::min<size_t>(1024, s.size() - off), [&](const uint8_t * d, int n) { ++decodes; ok = ok || std::vector<uint8_t>(d, d + n) == want; });
            return ok && decodes == 1;
        };
        m.airtime20 = sotto::airtimeSeconds(p, 20); m.airtime100 = sotto::airtimeSeconds(p, 100);
        modems.push_back(m);
    }
    struct GG { int id; const char * name; int volume; bool clip; };
    for (GG g : { GG{ GGWAVE_PROTOCOL_AUDIBLE_NORMAL, "ggwave Normal", 25, false }, GG{ GGWAVE_PROTOCOL_AUDIBLE_FAST, "ggwave Fast", 25, false },
                  GG{ GGWAVE_PROTOCOL_AUDIBLE_FASTEST, "ggwave Fastest", 25, false }, GG{ GGWAVE_PROTOCOL_AUDIBLE_FAST, "ggwave Fast, clipped", 100, true } }) {
        Modem m;
        m.name = g.name;
        m.encode = [g](const std::vector<uint8_t> & d) {
            GGWave tx(ggParams(GGWAVE_OPERATING_MODE_TX));
            tx.init(d.size(), reinterpret_cast<const char *>(d.data()), static_cast<GGWave::TxProtocolId>(g.id), g.volume);
            const uint32_t bytes = tx.encode();
            const int16_t * w = static_cast<const int16_t *>(tx.txWaveform());
            if (!g.clip) return normalise(w, bytes / 2);
            std::vector<float> out(bytes / 2);
            for (size_t i = 0; i < out.size(); ++i) out[i] = std::max(-kPeak, std::min(kPeak, w[i] / 32768.0f * 4.0f));
            return out;
        };
        m.decode = [](const std::vector<int16_t> & s, const std::vector<uint8_t> & want, int & decodes) {
            GGWave rx(ggParams(GGWAVE_OPERATING_MODE_RX)); bool ok = false; decodes = 0;
            for (size_t off = 0; off + 1024 <= s.size(); off += 1024) {
                rx.decode(s.data() + off, 1024 * 2);
                GGWave::TxRxData v; const int n = rx.rxTakeData(v);
                if (n > 0) { ++decodes; ok = ok || std::vector<uint8_t>(v.data(), v.data() + n) == want; }
            }
            return ok && decodes == 1;
        };
        for (int len : { 20, 100 }) {
            GGWave tx(ggParams(GGWAVE_OPERATING_MODE_TX)); std::vector<char> d(len, 'x');
            tx.init(len, d.data(), static_cast<GGWave::TxProtocolId>(g.id), g.volume);
            (len == 20 ? m.airtime20 : m.airtime100) = tx.encode() / 2.0f / kFs;
        }
        modems.push_back(m);
    }

    printf("Airtime (s): %-22s %6s %7s\n", "modem", "20 B", "100 B");
    for (auto & m : modems) printf("             %-22s %6.2f %7.2f\n", m.name.c_str(), m.airtime20, m.airtime100);

    const float noiseDb[] = { -40, -30, -25, -20, -15, -10, -5, 0 };
    for (bool room : { false, true }) {
        printf("\n%s. Signal peak %.0f dBFS at the mic, 20-byte payload, %d trials per cell, success %%:\n",
               room ? "Room: reflections, reverb tail, speaker/mic tilt, white noise" : "Free field: white noise only",
               20 * std::log10(kPeak * kDistanceGain), kTrials);
        printf("%-22s", "noise dBFS");
        for (float n : noiseDb) printf(" %5.0f", n);
        printf("\n");
        for (auto & m : modems) {
            printf("%-22s", m.name.c_str());
            for (float n : noiseDb) {
                int ok = 0;
                for (int t = 0; t < kTrials; ++t) {
                    std::vector<uint8_t> msg(kPayload); for (auto & b : msg) b = 32 + rng() % 95;
                    Channel ch{ std::pow(10.0f, n / 20), room, rng };
                    int decodes = 0;
                    if (m.decode(ch.apply(m.encode(msg)), msg, decodes)) ++ok;
                }
                printf(" %5.0f", 100.0 * ok / kTrials);
                fflush(stdout);
            }
            printf("\n");
        }
    }

    // CPU: decoder time per second of audio, on silence + noise
    printf("\nDecoder cost (ms of CPU per second of audio, this machine):\n");
    std::vector<int16_t> noise(kFs * 10);
    { std::normal_distribution<float> N(0, 0.01f); for (auto & s : noise) s = static_cast<int16_t>(N(rng) * 32767); }
    for (int i = 0; i < sotto::protocolCount(); ++i) {
        sotto::Decoder dec(sotto::protocol(i));
        auto t0 = std::chrono::steady_clock::now();
        for (size_t off = 0; off < noise.size(); off += 1024) dec.feed(noise.data() + off, 1024, [](const uint8_t *, int) {});
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / 10;
        printf("  %-18s %6.2f ms/s   heap %zu KB\n", sotto::protocol(i).name, ms, dec.heapBytes() / 1024);
    }
    {
        GGWave rx(ggParams(GGWAVE_OPERATING_MODE_RX));
        auto t0 = std::chrono::steady_clock::now();
        for (size_t off = 0; off < noise.size(); off += 1024) rx.decode(noise.data() + off, 2048);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / 10;
        printf("  %-18s %6.2f ms/s   heap %d KB (audible protocols only)\n", "ggwave", ms, rx.heapSize() / 1024);
    }
    return 0;
}
