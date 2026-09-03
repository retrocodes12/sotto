// Sotto modem: a small data-over-sound modem built for this app.
//
// Physical layer: one tone at a time (single-tone MFSK), so a peak-limited phone
// speaker puts all of its amplitude into the one frequency that carries the bits.
// Consecutive symbols use disjoint frequency sets, so the ringing of a room does
// not land on the next symbol's bins. A four-symbol sync word gives symbol timing,
// a twice-sent 12-bit header carries the length, and the frame is protected by
// Reed-Solomon parity plus a CRC-16 so noise never decodes to garbage.
//
// Everything is streaming: feed 16-bit 48 kHz mono samples in any chunk size and
// the decoder calls back the moment the last symbol of a frame has arrived.
#pragma once

#include <cstdint>
#include <functional>
#include <vector>

namespace sotto {

constexpr int kSampleRate = 48000;
constexpr int kMaxPayload = 140;

struct Params {
    int          id;        // protocol id as seen by the app (100+)
    const char * name;
    int          symbolLen; // samples per symbol, power of two
    int          firstBin;  // FFT bin (at symbolLen points) of the lowest tone
    int          binStep;   // bins between adjacent tones
    int          bits;      // bits per symbol; alphabet is 1 << bits tones per set
    int          parityMax; // Reed-Solomon parity: half the frame (32 max) or, for bands with
                            // narrow fades, the whole frame (64 max)
};

int            protocolCount();
const Params & protocol(int index);
const Params * protocolById(int id);

// Number of symbols and seconds of audio a payload of n bytes needs.
int   symbolCount(const Params & p, int n);
float airtimeSeconds(const Params & p, int n);

// 16-bit mono waveform at 48 kHz. amplitude is 0..1 of full scale. Empty on bad input.
std::vector<int16_t> encode(const Params & p, const uint8_t * payload, int n, float amplitude);

class Decoder {
public:
    using OnMessage = std::function<void(const uint8_t * payload, int n)>;

    explicit Decoder(const Params & p);

    const Params & params() const { return m_p; }

    // Feed samples. onMessage fires once per decoded frame, from inside this call.
    void feed(const int16_t * samples, int n, const OnMessage & onMessage);

    // Rough heap use, for the README's honesty table.
    size_t heapBytes() const;

    // Optional: one line per notable event (sync found, header/parity/crc failure).
    std::function<void(const char *)> onDebug;

private:
    enum class State { Idle, SyncPeak, Decoding };

    void onHop(const OnMessage & onMessage);
    bool syncScore(int64_t hop, float & score) const;
    const float * row(int64_t hop) const;
    bool decideHeader();
    bool finishFrame(const OnMessage & onMessage);
    bool tryDecode(const std::vector<int> & syms, const std::vector<uint8_t> & erasures, std::vector<uint8_t> & out) const;
    void reset();

    Params m_p;
    int m_N, m_hop, m_M, m_bandBins;

    // input ring + FFT work
    std::vector<float> m_ring; int m_ringPos = 0; int m_sinceHop = 0;
    std::vector<float> m_re, m_im, m_cos, m_sin, m_tcos, m_tsin; std::vector<int> m_rev;
    void fft();

    // per-hop band energies, one row per hop, ring of kHistory rows
    static constexpr int kHistory = 32;
    std::vector<float> m_hist; int64_t m_hopIndex = -1;
    std::vector<float> m_floor; bool m_floorInit = false;

    State m_state = State::Idle;
    int64_t m_syncBestHop = 0; float m_syncBestScore = 0; int64_t m_syncDeadline = 0;
    int64_t m_t0 = 0; int64_t m_nextHop = 0; int m_g = 0;
    std::vector<int> m_syms, m_second; std::vector<float> m_conf;   // tone, runner-up, best/second energy ratio
    int m_payloadLen = -1; int m_frameSymbols = 0;
    double m_toneSum = 0; int m_toneCount = 0;   // chosen-bin energy over the frame, for level stats
    std::vector<double> m_binSum; std::vector<int> m_binCount;   // per-bin, to see the channel's shape
    void debugStats(const char * what, size_t erasures) const;
};

} // namespace sotto
