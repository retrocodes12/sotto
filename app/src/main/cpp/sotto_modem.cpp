#include "sotto_modem.h"

#if !defined(ARDUINO) && !defined(PROGMEM)
#define PROGMEM
#endif
#include "reed-solomon/rs.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace sotto {

namespace {

constexpr int   kSyncSymbols   = 4;
constexpr int   kHeaderSymbols = 6;     // the length, as six hash-mapped tones, decoded by likelihood
constexpr float kHeaderSnr     = 4.0f;  // best length's mean header energy must be this far above the floor
constexpr int   kRampSamples   = 48;    // 1 ms raised-cosine edge per symbol
constexpr float kSyncSnr       = 2.0f;  // each sync tone must be this far above the floor
constexpr float kSyncScore     = 4.4f;  // summed log-SNR of the four sync tones (4 * log 3)
constexpr int   kChaseSymbols  = 8;     // least confident symbols tried at their runner-up tone
constexpr float kEraseRatio    = 1.6f;  // best/second-best below this marks an erasure
constexpr float kFloorAlpha    = 0.02f;
#ifndef SOTTO_TAIL_ALPHA
#define SOTTO_TAIL_ALPHA 0.5f
#endif
constexpr float kTailAlpha     = SOTTO_TAIL_ALPHA;  // fraction of the previous window's energy to cancel
// Four sync tones. Note that on a 4-bit protocol these reduce to 5, 10, 5, 10 -- two tones
// alternating, not four distinct ones -- and both of the app's defaults are 4-bit. That looks
// like it should cost false syncs, so it was measured rather than assumed: ten minutes of white
// noise per protocol gives zero false syncs, and forty frames of every protocol fed to every
// other decoder (which is what the app actually does, decoding all four on one stream) gives
// one, between two bands 10 kHz apart. Changing these values would break the wire format for
// no measured gain, so they stay. If the format ever changes for another reason, { 3, 41, 22,
// 60 } is distinct under both 16 and 64.
constexpr int   kSyncSeed[kSyncSymbols] = { 5, 42, 21, 58 };

const Params kProtocols[] = {
    // id   name               N     first step bits parity ch sync  band at 48 kHz
    { 100, "Sotto Fast",       1024,  44,   1,   6,  32,   1,  3 },  // 2.06-8.02 kHz, 21 ms symbols
    { 101, "Sotto Robust",     2048,  88,   2,   6,  32,   1,  3 },  // 2.06-8.04 kHz, 43 ms symbols
    { 102, "Sotto Ultrasound", 2048, 768,   2,   4,  64,   1,  3 },  // 18.0-19.5 kHz, 43 ms symbols, 16 tones. The
                                                                 // first test phones were 16 dB louder at 18 kHz
                                                                 // than at 15 kHz and flat 18-19.5, so the whole
                                                                 // band sits on that peak; 2 cm wavelengths fade
                                                                 // single tones, hence rate 1/2 parity
    { 103, "Sotto Near",        512,  16,   1,   4,  32,   4,  4 },  // 1.5-13.5 kHz, 10.7 ms symbols, four tones at
                                                                 // once: 16 bits per symbol for photos at arm's
                                                                 // length, where SNR is plentiful
};

uint16_t crc16(const uint8_t * d, int n) {           // CRC-16/CCITT-FALSE
    uint16_t crc = 0xFFFF;
    for (int i = 0; i < n; ++i) {
        crc ^= static_cast<uint16_t>(d[i]) << 8;
        for (int b = 0; b < 8; ++b) crc = (crc & 0x8000) ? static_cast<uint16_t>((crc << 1) ^ 0x1021) : static_cast<uint16_t>(crc << 1);
    }
    return crc;
}

int parityBytes(const Params & p, int frameBytes) {
    if (p.parityMax > 32) return std::min(p.parityMax, std::max(8, frameBytes));   // rate 1/2
    return std::min(p.parityMax, std::max(6, (frameBytes + 1) / 2));
}

// Tone for header symbol k of a payload of length len: a hash, so that any two lengths
// differ in nearly every one of the six symbols and the decoder can tell them apart by
// energy alone.
int headerTone(int len, int k, int M) {
    uint32_t h = static_cast<uint32_t>(len + 1) * 0x9E3779B1u ^ static_cast<uint32_t>(k + 1) * 0x85EBCA6Bu;
    h ^= h >> 15; h *= 0x2C1B3C6Du; h ^= h >> 12;
    return static_cast<int>(h % static_cast<uint32_t>(M));
}

int syncTone(int k, int M) { return kSyncSeed[k] % M; }

// MSB-first bit packing of nBits bits into `bits`-wide symbols, zero padded.
void packBits(const uint8_t * bytes, int nBits, int bits, std::vector<int> & out) {
    int acc = 0, nacc = 0;
    for (int i = 0; i < nBits; ++i) {
        acc = (acc << 1) | ((bytes[i >> 3] >> (7 - (i & 7))) & 1);
        if (++nacc == bits) { out.push_back(acc); acc = 0; nacc = 0; }
    }
    if (nacc > 0) out.push_back(acc << (bits - nacc));
}

void unpackBits(const int * syms, int nSyms, int bits, int nBits, uint8_t * out) {
    std::memset(out, 0, (nBits + 7) / 8);
    int bi = 0;
    for (int s = 0; s < nSyms && bi < nBits; ++s) {
        for (int b = bits - 1; b >= 0 && bi < nBits; --b, ++bi) {
            if ((syms[s] >> b) & 1) out[bi >> 3] |= static_cast<uint8_t>(0x80 >> (bi & 7));
        }
    }
}

// Channel c has its own pair of tone sets; odd and even symbols alternate between them.
int toneBin(const Params & p, int g, int c, int v) {
    return p.firstBin + (((c * 2 + (g & 1)) << p.bits) + v) * p.binStep;
}

int dataSymbols(const Params & p, int codeBytes) {
    const int chanSyms = (codeBytes * 8 + p.bits - 1) / p.bits;
    return (chanSyms + p.channels - 1) / p.channels;   // time symbols, last one zero padded
}

} // namespace

int protocolCount() { return static_cast<int>(sizeof(kProtocols) / sizeof(kProtocols[0])); }
const Params & protocol(int index) { return kProtocols[index]; }
const Params * protocolById(int id) {
    for (const auto & p : kProtocols) if (p.id == id) return &p;
    return nullptr;
}

int symbolCount(const Params & p, int n) {
    const int frame = n + 3;
    const int code = frame + parityBytes(p, frame);
    return kSyncSymbols + kHeaderSymbols + dataSymbols(p, code);
}

float airtimeSeconds(const Params & p, int n) {
    return symbolCount(p, n) * static_cast<float>(p.symbolLen) / kSampleRate;
}

std::vector<int16_t> encode(const Params & p, const uint8_t * payload, int n, float amplitude) {
    if (n < 0 || n > kMaxPayload || amplitude <= 0) return {};
    const int M = 1 << p.bits;

    // frame = [len][payload][crc16], then Reed-Solomon parity
    std::vector<uint8_t> frame;
    frame.push_back(static_cast<uint8_t>(n));
    frame.insert(frame.end(), payload, payload + n);
    const uint16_t crc = crc16(frame.data(), static_cast<int>(frame.size()));
    frame.push_back(static_cast<uint8_t>(crc >> 8));
    frame.push_back(static_cast<uint8_t>(crc & 0xFF));
    const int par = parityBytes(p, static_cast<int>(frame.size()));
    std::vector<uint8_t> code(frame.size() + par);
    RS::ReedSolomon rs(static_cast<uint8_t>(frame.size()), static_cast<uint8_t>(par));
    rs.Encode(frame.data(), code.data());

    // data as channel symbols, zero padded to whole time symbols
    const int K = p.channels;
    std::vector<int> data;
    packBits(code.data(), static_cast<int>(code.size()) * 8, p.bits, data);
    const int nData = dataSymbols(p, static_cast<int>(code.size()));
    data.resize(static_cast<size_t>(nData) * K, 0);

    // waveform: K tones per symbol (sync and header on every channel), raised-cosine edges
    const int N = p.symbolLen;
    const int total = kSyncSymbols + kHeaderSymbols + nData;
    std::vector<int16_t> out(static_cast<size_t>(total) * N);
    const float amp = std::min(1.0f, amplitude) * 32767.0f / K;
    for (int g = 0; g < total; ++g) {
        for (int c = 0; c < K; ++c) {
            int v;
            if (g < kSyncSymbols) v = syncTone(g, M);
            else if (g < kSyncSymbols + kHeaderSymbols) v = headerTone(n, g - kSyncSymbols, M);
            else v = data[static_cast<size_t>(g - kSyncSymbols - kHeaderSymbols) * K + c];
            const float f = toneBin(p, g, c, v) * static_cast<float>(kSampleRate) / N;
            const float w = 2.0f * static_cast<float>(M_PI) * f / kSampleRate;
            const float phase = c * 1.9f;   // spread the channels' phases so peaks rarely coincide
            int16_t * o = out.data() + static_cast<size_t>(g) * N;
            for (int t = 0; t < N; ++t) {
                float env = 1.0f;
                if (t < kRampSamples) env = 0.5f * (1 - std::cos(static_cast<float>(M_PI) * t / kRampSamples));
                else if (t >= N - kRampSamples) env = 0.5f * (1 - std::cos(static_cast<float>(M_PI) * (N - 1 - t) / kRampSamples));
                o[t] = static_cast<int16_t>(o[t] + std::lround(amp * env * std::sin(w * t + phase)));
            }
        }
    }
    return out;
}

// ---- decoder --------------------------------------------------------------------------

Decoder::Decoder(const Params & p)
    : m_p(p), m_N(p.symbolLen), m_hop(p.symbolLen / 4), m_M(1 << p.bits), m_K(p.channels),
      m_bandBins(p.channels * 2 * (1 << p.bits)) {
    m_ring.assign(m_N, 0.0f);
    // N real samples are transformed as an N/2-point complex FFT and split afterwards
    const int H = m_N / 2;
    m_re.assign(H, 0.0f);
    m_im.assign(H, 0.0f);
    m_cos.resize(H / 2);
    m_sin.resize(H / 2);
    for (int i = 0; i < H / 2; ++i) {
        m_cos[i] = static_cast<float>(std::cos(2.0 * M_PI * i / H));
        m_sin[i] = static_cast<float>(-std::sin(2.0 * M_PI * i / H));
    }
    m_tcos.resize(H);
    m_tsin.resize(H);
    for (int i = 0; i < H; ++i) {
        m_tcos[i] = static_cast<float>(std::cos(2.0 * M_PI * i / m_N));
        m_tsin[i] = static_cast<float>(std::sin(2.0 * M_PI * i / m_N));
    }
    m_rev.resize(H);
    int bitsH = 0; while ((1 << bitsH) < H) ++bitsH;
    for (int i = 0; i < H; ++i) {
        int r = 0;
        for (int b = 0; b < bitsH; ++b) if (i & (1 << b)) r |= 1 << (bitsH - 1 - b);
        m_rev[i] = r;
    }
    m_hist.assign(static_cast<size_t>(kHistory) * m_bandBins, 0.0f);
    m_floor.assign(m_bandBins, 1e-6f);
    m_binSum.assign(m_bandBins, 0.0);
    m_binCount.assign(m_bandBins, 0);
}

// In-place N/2-point complex FFT of (m_re, m_im), then the standard split so that
// bin k of the N-point real transform is recovered for k in [1, N/2).
void Decoder::fft() {
    const int H = m_N / 2;
    for (int len = 2; len <= H; len <<= 1) {
        const int half = len >> 1, stride = H / len;
        for (int start = 0; start < H; start += len) {
            for (int j = 0; j < half; ++j) {
                const float wr = m_cos[j * stride], wi = m_sin[j * stride];
                const int a = start + j, b = a + half;
                const float tr = m_re[b] * wr - m_im[b] * wi;
                const float ti = m_re[b] * wi + m_im[b] * wr;
                m_re[b] = m_re[a] - tr; m_im[b] = m_im[a] - ti;
                m_re[a] += tr;          m_im[a] += ti;
            }
        }
    }
}

size_t Decoder::heapBytes() const {
    return (m_ring.size() + m_re.size() + m_im.size() + m_cos.size() + m_sin.size() + m_tcos.size() + m_tsin.size()
            + m_hist.size() + m_floor.size()) * sizeof(float) + m_rev.size() * sizeof(int);
}

void Decoder::reset() {
    m_state = State::Idle;
    m_syms.clear();
    m_second.clear();
    m_conf.clear();
    m_payloadLen = -1;
    m_frameSymbols = 0;
    m_toneSum = 0;
    m_toneCount = 0;
    std::fill(m_binSum.begin(), m_binSum.end(), 0.0);
    std::fill(m_binCount.begin(), m_binCount.end(), 0);
}

// One line with the frame's mean tone level and the per-bin noise floor, both in dBFS
// (a full-scale sine gives an FFT magnitude of N/2), so range tests can read signal
// strength straight off logcat.
void Decoder::debugStats(const char * what, size_t erasures) const {   // updates m_lastSnrDb (mutable)
    if (!onDebug) return;
    const double ref = static_cast<double>(m_N) / 2 * (static_cast<double>(m_N) / 2);
    double floorMean = 0;
    for (int i = 0; i < m_bandBins; ++i) floorMean += m_floor[i];
    floorMean /= m_bandBins;
    const double tone = m_toneCount ? m_toneSum / m_toneCount : 0;
    const double toneDb = 10 * std::log10(std::max(tone, 1e-12) / ref);
    const double floorDb = 10 * std::log10(std::max(floorMean, 1e-12) / ref);
    m_lastSnrDb = static_cast<float>(toneDb - floorDb);
    char b[640];
    int n = std::snprintf(b, sizeof b, "%s: tone %.0f dBFS, floor %.0f dBFS/bin, snr %.0f dB, %zu erasures, %d symbols; low>high",
                          what, toneDb, floorDb, toneDb - floorDb, erasures, m_toneCount);
    // mean received tone level per eighth of the band, both sets merged by frequency order
    const int groups = 8, per = m_bandBins / groups;
    for (int gI = 0; gI < groups && n < static_cast<int>(sizeof b) - 8; ++gI) {
        double sum = 0; int cnt = 0;
        for (int i = gI * per; i < (gI + 1) * per; ++i) { sum += m_binSum[i]; cnt += m_binCount[i]; }
        n += std::snprintf(b + n, sizeof b - n, cnt ? " %.0f" : " .", cnt ? 10 * std::log10(std::max(sum / cnt, 1e-12) / ref) : 0.0);
    }
    // one character per data symbol: how far the winning tone beat the runner-up
    // ('#' over 6 dB, '+' 3-6 dB, '?' 2-3 dB, '!' under 2 dB)
    n += std::snprintf(b + n, sizeof b - n, " |");
    for (size_t i = 0; i < m_conf.size() && n < static_cast<int>(sizeof b) - 2; ++i) {
        const float r = m_conf[i];
        b[n++] = r >= 4.0f ? '#' : r >= 2.0f ? '+' : r >= 1.6f ? '?' : '!';
        b[n] = 0;
    }
    onDebug(b);
}

const float * Decoder::row(int64_t hop) const {
    return &m_hist[static_cast<size_t>(hop % kHistory) * m_bandBins];
}

// Energy of tone v in set `set` summed over all channels (sync and header use every channel).
float Decoder::sumOverChannels(const float * r, int set, int v) const {
    float e = 0;
    for (int c = 0; c < m_K; ++c) e += r[((c * 2 + set) << m_p.bits) + v];
    return e;
}

void Decoder::feed(const int16_t * samples, int n, const OnMessage & onMessage) {
    for (int i = 0; i < n; ++i) {
        m_ring[m_ringPos] = samples[i] / 32768.0f;
        if (++m_ringPos == m_N) m_ringPos = 0;
        if (++m_sinceHop == m_hop) {
            m_sinceHop = 0;
            onHop(onMessage);
        }
    }
}

// True if the four sync tones sit at hops hop-12, hop-8, hop-4, hop (in symbol units:
// the last sync symbol ends exactly at `hop`). score is the summed log-SNR.
bool Decoder::syncScore(int64_t hop, float & score) const {
    score = 0;
    int wins = 0;
    for (int k = 0; k < kSyncSymbols; ++k) {
        const int64_t h = hop - 4 * (kSyncSymbols - 1 - k);
        if (h < 0) return false;
        const float * r = row(h);
        const int want = syncTone(k, m_M);
        const float ew = sumOverChannels(r, k & 1, want);
        int above = 0;                       // tones louder than the expected one
        for (int v = 0; v < m_M; ++v) if (sumOverChannels(r, k & 1, v) > ew) ++above;
        if (above == 0) ++wins;
        else if (above > 2) return false;    // expected tone not even in the top three
        const float snr = ew / sumOverChannels(m_floor.data(), k & 1, want);
        if (snr < kSyncSnr) return false;
        score += std::log(snr);
    }
    return wins >= m_p.syncWins && score >= kSyncScore;
}

void Decoder::onHop(const OnMessage & onMessage) {
    ++m_hopIndex;

    // FFT of the last N samples, rectangular window (tones sit on bin centres).
    // Even samples go to the real part, odd samples to the imaginary part.
    {
        const int H = m_N / 2;
        int pos = m_ringPos;
        for (int i = 0; i < H; ++i) {
            const float a = m_ring[pos]; if (++pos == m_N) pos = 0;
            const float b = m_ring[pos]; if (++pos == m_N) pos = 0;
            m_re[m_rev[i]] = a;
            m_im[m_rev[i]] = b;
        }
        fft();
    }
    float * e = &m_hist[static_cast<size_t>(m_hopIndex % kHistory) * m_bandBins];
    {
        const int H = m_N / 2;
        for (int i = 0; i < m_bandBins; ++i) {
            const int k = m_p.firstBin + i * m_p.binStep;      // 1 <= k < N/2 for every protocol
            const float zr = m_re[k], zi = m_im[k], cr = m_re[H - k], ci = -m_im[H - k];
            const float er = 0.5f * (zr + cr), ei = 0.5f * (zi + ci);          // even-sample spectrum
            const float orr = 0.5f * (zi - ci), oi = -0.5f * (zr - cr);         // odd-sample spectrum
            const float xr = er + m_tcos[k] * orr + m_tsin[k] * oi;
            const float xi = ei - m_tsin[k] * orr + m_tcos[k] * oi;
            e[i] = xr * xr + xi * xi;
        }
    }

    if (m_state == State::Idle) {
        if (!m_floorInit) { std::copy(e, e + m_bandBins, m_floor.begin()); m_floorInit = true; }
        for (int i = 0; i < m_bandBins; ++i) m_floor[i] = std::max(1e-9f, m_floor[i] + kFloorAlpha * (e[i] - m_floor[i]));
        float score;
        if (m_hopIndex >= 12 && syncScore(m_hopIndex, score)) {
            m_state = State::SyncPeak;
            m_syncBestHop = m_hopIndex;
            m_syncBestScore = score;
            m_syncDeadline = m_hopIndex + 3;
        }
        return;
    }

    if (m_state == State::SyncPeak) {
        float score;
        if (syncScore(m_hopIndex, score) && score > m_syncBestScore) {
            m_syncBestScore = score;
            m_syncBestHop = m_hopIndex;
        }
        if (m_hopIndex >= m_syncDeadline) {
            if (onDebug) onDebug("sync");
            m_state = State::Decoding;
            m_t0 = m_syncBestHop;
            m_syms.clear();
            m_second.clear();
            m_conf.clear();
            m_payloadLen = -1;
        }
        return;
    }

    // Decoding. First the header, once every header window plus one hop of slack is in
    // the history; it also settles the exact symbol alignment. Then one data symbol
    // every four hops.
    if (m_payloadLen < 0) {
        if (m_hopIndex == m_t0 + 4 * kHeaderSymbols + 1 && !decideHeader()) {
            if (onDebug) onDebug("header failed");
            reset();
        }
        return;
    }
    if (m_hopIndex != m_nextHop) return;
    for (int c = 0; c < m_K; ++c) {
        // Reverb cancellation: during the previous symbol's window this set's bins carried
        // nothing but the decaying tail of earlier symbols (the previous symbol used the
        // other set), so that energy is subtracted before picking the tone.
        const int base = (c * 2 + (m_g & 1)) << m_p.bits;
        const float * prev = row(m_hopIndex - 4) + base;
        const float * cur = e + base;
        float sb = -1, ss = -1; int best = 0, second = 0;
        for (int v = 0; v < m_M; ++v) {
            const float sc = std::max(0.0f, cur[v] - kTailAlpha * prev[v]);
            if (sc > sb) { ss = sb; second = best; sb = sc; best = v; }
            else if (sc > ss) { ss = sc; second = v; }
        }
        m_syms.push_back(best);
        m_second.push_back(second);
        // Confidence is how far the winning tone stands above the runner-up. When the runner-up
        // scores nothing there are two very different reasons, and they were treated the same:
        // one tone alone in the band (certain), or NO tone at all, because the symbol was lost
        // to a fade or a dropout (the least certain thing there is). Calling the second case
        // maximum confidence pointed the erasure and chase decoders away from exactly the
        // symbols they exist to repair.
        m_conf.push_back(ss > 0 ? sb / ss : (sb > 0 ? 1e9f : 0.0f));
        m_toneSum += cur[best];
        ++m_toneCount;
        m_binSum[base + best] += cur[best];
        ++m_binCount[base + best];
    }
    ++m_g;
    m_nextHop += 4;

    if (static_cast<int>(m_syms.size()) == m_frameSymbols) {
        finishFrame(onMessage);
        reset();
    }
}

// Maximum-likelihood header: for every possible length and for the sync alignment plus
// one hop either side, sum the received energy at the six tones that length would have
// used. The best sum wins; its alignment becomes the frame's. Header symbol k is symbol
// kSyncSymbols + k and its window ends at hop t0 + 4 * (k + 1).
bool Decoder::decideHeader() {
    double bestSum = -1; int bestLen = -1, bestOff = 0;
    for (int off = -1; off <= 1; ++off) {
        for (int len = 0; len <= kMaxPayload; ++len) {
            double sum = 0;
            for (int k = 0; k < kHeaderSymbols; ++k) {
                const int g = kSyncSymbols + k;
                sum += sumOverChannels(row(m_t0 + off + 4 * (k + 1)), g & 1, headerTone(len, k, m_M));
            }
            if (sum > bestSum) { bestSum = sum; bestLen = len; bestOff = off; }
        }
    }
    double floorMean = 0;
    for (int i = 0; i < m_bandBins; ++i) floorMean += m_floor[i];
    floorMean /= m_bandBins;
    if (bestSum < kHeaderSnr * kHeaderSymbols * m_K * floorMean) return false;

    m_t0 += bestOff;
    m_payloadLen = bestLen;
    const int frame = bestLen + 3;
    const int code = frame + parityBytes(m_p, frame);
    m_frameSymbols = dataSymbols(m_p, code) * m_K;   // channel symbols, including padding
    m_g = kSyncSymbols + kHeaderSymbols;
    m_nextHop = m_t0 + 4 * (m_g - (kSyncSymbols - 1));
    return true;
}

// One Reed-Solomon attempt on a symbol vector: unpack, correct, check length and CRC.
bool Decoder::tryDecode(const std::vector<int> & syms, const std::vector<uint8_t> & erasures, std::vector<uint8_t> & out) const {
    const int frame = m_payloadLen + 3;
    const int par = parityBytes(m_p, frame);
    const int code = frame + par;
    std::vector<uint8_t> codeBytes(code);
    unpackBits(syms.data(), m_frameSymbols, m_p.bits, code * 8, codeBytes.data());
    RS::ReedSolomon rs(static_cast<uint8_t>(frame), static_cast<uint8_t>(par));
    std::vector<uint8_t> era(erasures);
    const int rc = era.empty() ? rs.Decode(codeBytes.data(), out.data())
                               : rs.Decode(codeBytes.data(), out.data(), era.data(), era.size());
    if (rc != 0 || out[0] != m_payloadLen) return false;
    const uint16_t crc = static_cast<uint16_t>((out[frame - 2] << 8) | out[frame - 1]);
    return crc16(out.data(), frame - 2) == crc;
}

// Decode ladder, cheapest first: erasures for every doubtful symbol, then only the
// most doubtful up to half the parity, then none, then chase decoding: the least
// confident symbols swapped for their runner-up tones in every combination.
bool Decoder::finishFrame(const OnMessage & onMessage) {
    const int frame = m_payloadLen + 3;
    const int par = parityBytes(m_p, frame);
    const int code = frame + par;
    std::vector<uint8_t> out(frame);

    // symbols ordered by confidence, least confident first
    std::vector<int> order(m_frameSymbols);
    for (int i = 0; i < m_frameSymbols; ++i) order[i] = i;
    std::sort(order.begin(), order.end(), [&](int a, int b) { return m_conf[a] < m_conf[b]; });

    auto erasuresFor = [&](int count, float maxRatio) {
        std::vector<uint8_t> era;
        for (int i = 0; i < count && i < m_frameSymbols; ++i) {
            const int sIdx = order[i];
            if (m_conf[sIdx] >= maxRatio) break;
            const int b0 = (sIdx * m_p.bits) / 8, b1 = std::min(code - 1, (sIdx * m_p.bits + m_p.bits - 1) / 8);
            for (int b = b0; b <= b1; ++b)
                if (std::find(era.begin(), era.end(), static_cast<uint8_t>(b)) == era.end()) era.push_back(static_cast<uint8_t>(b));
        }
        return era;
    };

    int doubtful = 0;
    for (int i = 0; i < m_frameSymbols; ++i) if (m_conf[i] < kEraseRatio) ++doubtful;

    std::vector<uint8_t> era = erasuresFor(doubtful, kEraseRatio);
    if (!era.empty() && static_cast<int>(era.size()) < par && tryDecode(m_syms, era, out)) { debugStats("decoded", era.size()); onMessage(out.data() + 1, m_payloadLen); return true; }
    for (int share : { 4, 2, 1 }) {   // a quarter, half, then most of the parity spent on erasures
        era = erasuresFor(par / share, kEraseRatio);
        if (!era.empty() && static_cast<int>(era.size()) < par && tryDecode(m_syms, era, out)) { debugStats("decoded (fewer erasures)", era.size()); onMessage(out.data() + 1, m_payloadLen); return true; }
    }
    if (tryDecode(m_syms, {}, out)) { debugStats("decoded (no erasures)", 0); onMessage(out.data() + 1, m_payloadLen); return true; }

    const int L = std::min(kChaseSymbols, m_frameSymbols);
    std::vector<int> syms(m_syms);
    for (int weight = 1; weight <= L; ++weight) {
        for (int mask = 1; mask < (1 << L); ++mask) {
            if (__builtin_popcount(mask) != weight) continue;
            for (int i = 0; i < L; ++i) syms[order[i]] = (mask >> i) & 1 ? m_second[order[i]] : m_syms[order[i]];
            if (tryDecode(syms, {}, out)) { debugStats("decoded (chase)", weight); onMessage(out.data() + 1, m_payloadLen); return true; }
        }
    }
    debugStats("parity failed", doubtful);
    return false;
}

} // namespace sotto
