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
constexpr int   kRampSamples   = 48;    // 1 ms raised-cosine edge per symbol
constexpr float kSyncSnr       = 3.0f;  // each sync tone must be this far above the floor
constexpr float kEraseRatio    = 1.6f;  // best/second-best below this marks an erasure
constexpr float kFloorAlpha    = 0.02f;
#ifndef SOTTO_TAIL_ALPHA
#define SOTTO_TAIL_ALPHA 0.5f
#endif
constexpr float kTailAlpha     = SOTTO_TAIL_ALPHA;  // fraction of the previous window's energy to cancel
constexpr int   kSyncSeed[kSyncSymbols] = { 5, 42, 21, 58 };

const Params kProtocols[] = {
    // id   name               N     first step bits      band at 48 kHz
    { 100, "Sotto Fast",       1024,  44,   1,   6 },   // 2.06-8.02 kHz, 21 ms symbols
    { 101, "Sotto Robust",     2048,  88,   2,   6 },   // 2.06-8.04 kHz, 43 ms symbols
    { 102, "Sotto Ultrasound", 2048, 640,   2,   5 },   // 15.0-18.0 kHz, 43 ms symbols
};

uint16_t crc16(const uint8_t * d, int n) {           // CRC-16/CCITT-FALSE
    uint16_t crc = 0xFFFF;
    for (int i = 0; i < n; ++i) {
        crc ^= static_cast<uint16_t>(d[i]) << 8;
        for (int b = 0; b < 8; ++b) crc = (crc & 0x8000) ? static_cast<uint16_t>((crc << 1) ^ 0x1021) : static_cast<uint16_t>(crc << 1);
    }
    return crc;
}

uint8_t crc4(uint8_t v) {                            // CRC-4-ITU over the 8 bits of v
    uint8_t crc = 0;
    for (int b = 7; b >= 0; --b) {
        const bool bit = (((v >> b) & 1) ^ ((crc >> 3) & 1)) != 0;
        crc = static_cast<uint8_t>((crc << 1) & 0xF);
        if (bit) crc ^= 0x3;
    }
    return crc;
}

int parityBytes(int frameBytes) {
    return std::min(32, std::max(6, (frameBytes + 1) / 2));
}

int headerSymbols(int bits) { return 2 * ((12 + bits - 1) / bits); }

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

int toneBin(const Params & p, int g, int v) {
    return p.firstBin + (((g & 1) << p.bits) + v) * p.binStep;
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
    const int code = frame + parityBytes(frame);
    return kSyncSymbols + headerSymbols(p.bits) + (code * 8 + p.bits - 1) / p.bits;
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
    const int par = parityBytes(static_cast<int>(frame.size()));
    std::vector<uint8_t> code(frame.size() + par);
    RS::ReedSolomon rs(static_cast<uint8_t>(frame.size()), static_cast<uint8_t>(par));
    rs.Encode(frame.data(), code.data());

    // symbols: sync, header twice, data
    std::vector<int> syms;
    for (int k = 0; k < kSyncSymbols; ++k) syms.push_back(syncTone(k, M));
    const uint16_t hdr = static_cast<uint16_t>((n << 4) | crc4(static_cast<uint8_t>(n)));
    const uint8_t hdrBytes[2] = { static_cast<uint8_t>(hdr >> 4), static_cast<uint8_t>((hdr & 0xF) << 4) };
    packBits(hdrBytes, 12, p.bits, syms);
    packBits(hdrBytes, 12, p.bits, syms);
    packBits(code.data(), static_cast<int>(code.size()) * 8, p.bits, syms);

    // waveform: one tone per symbol with raised-cosine edges
    const int N = p.symbolLen;
    std::vector<int16_t> out;
    out.reserve(syms.size() * N);
    const float amp = std::min(1.0f, amplitude) * 32767.0f;
    for (size_t g = 0; g < syms.size(); ++g) {
        const float f = toneBin(p, static_cast<int>(g), syms[g]) * static_cast<float>(kSampleRate) / N;
        const float w = 2.0f * static_cast<float>(M_PI) * f / kSampleRate;
        for (int t = 0; t < N; ++t) {
            float env = 1.0f;
            if (t < kRampSamples) env = 0.5f * (1 - std::cos(static_cast<float>(M_PI) * t / kRampSamples));
            else if (t >= N - kRampSamples) env = 0.5f * (1 - std::cos(static_cast<float>(M_PI) * (N - 1 - t) / kRampSamples));
            out.push_back(static_cast<int16_t>(std::lround(amp * env * std::sin(w * t))));
        }
    }
    return out;
}

// ---- decoder --------------------------------------------------------------------------

Decoder::Decoder(const Params & p)
    : m_p(p), m_N(p.symbolLen), m_hop(p.symbolLen / 4), m_M(1 << p.bits), m_bandBins(2 * (1 << p.bits)) {
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
    m_erased.clear();
    m_payloadLen = -1;
    m_frameSymbols = 0;
}

const float * Decoder::row(int64_t hop) const {
    return &m_hist[static_cast<size_t>(hop % kHistory) * m_bandBins];
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
    for (int k = 0; k < kSyncSymbols; ++k) {
        const int64_t h = hop - 4 * (kSyncSymbols - 1 - k);
        if (h < 0) return false;
        const float * e = row(h) + ((k & 1) ? m_M : 0);
        const int want = syncTone(k, m_M);
        int best = 0;
        for (int v = 1; v < m_M; ++v) if (e[v] > e[best]) best = v;
        if (best != want) return false;
        const float snr = e[want] / m_floor[((k & 1) ? m_M : 0) + want];
        if (snr < kSyncSnr) return false;
        score += std::log(snr);
    }
    return true;
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
            m_g = kSyncSymbols;
            m_nextHop = m_t0 + 4;
            m_syms.clear();
            m_erased.clear();
            m_payloadLen = -1;
        }
        return;
    }

    // Decoding: one symbol every four hops
    if (m_hopIndex != m_nextHop) return;
    {
        // Reverb cancellation: during the previous symbol's window this set's bins carried
        // nothing but the decaying tail of earlier symbols (the previous symbol used the
        // other set), so that energy is subtracted before picking the tone.
        const int base = (m_g & 1) ? m_M : 0;
        const float * prev = row(m_hopIndex - 4) + base;
        const float * cur = e + base;
        float sb = -1, ss = -1; int best = 0;
        for (int v = 0; v < m_M; ++v) {
            const float sc = std::max(0.0f, cur[v] - kTailAlpha * prev[v]);
            if (sc > sb) { ss = sb; sb = sc; best = v; }
            else if (sc > ss) ss = sc;
        }
        m_syms.push_back(best);
        m_erased.push_back(ss > 0 && sb < kEraseRatio * ss ? 1 : 0);
        ++m_g;
        m_nextHop += 4;
    }

    const int hdr = headerSymbols(m_p.bits);
    if (m_payloadLen < 0) {
        if (static_cast<int>(m_syms.size()) == hdr && !finishHeader()) { if (onDebug) onDebug("header failed"); reset(); }
        return;
    }
    if (static_cast<int>(m_syms.size()) == hdr + m_frameSymbols) {
        finishFrame(onMessage);
        reset();
    }
}

bool Decoder::finishHeader() {
    const int per = headerSymbols(m_p.bits) / 2;
    for (int copy = 0; copy < 2; ++copy) {
        uint8_t b[2];
        unpackBits(m_syms.data() + copy * per, per, m_p.bits, 12, b);
        const uint16_t hdr = static_cast<uint16_t>((b[0] << 4) | (b[1] >> 4));
        const int len = hdr >> 4;
        if (crc4(static_cast<uint8_t>(len)) == (hdr & 0xF) && len <= kMaxPayload) {
            m_payloadLen = len;
            const int frame = len + 3;
            const int code = frame + parityBytes(frame);
            m_frameSymbols = (code * 8 + m_p.bits - 1) / m_p.bits;
            return true;
        }
    }
    return false;
}

bool Decoder::finishFrame(const OnMessage & onMessage) {
    const int hdr = headerSymbols(m_p.bits);
    const int frame = m_payloadLen + 3;
    const int par = parityBytes(frame);
    const int code = frame + par;
    std::vector<uint8_t> codeBytes(code);
    unpackBits(m_syms.data() + hdr, m_frameSymbols, m_p.bits, code * 8, codeBytes.data());

    // erased symbols -> erased byte positions in the codeword
    std::vector<uint8_t> erasures;
    for (int s = 0; s < m_frameSymbols; ++s) {
        if (!m_erased[hdr + s]) continue;
        const int b0 = (s * m_p.bits) / 8, b1 = std::min(code - 1, (s * m_p.bits + m_p.bits - 1) / 8);
        for (int b = b0; b <= b1; ++b) {
            if (std::find(erasures.begin(), erasures.end(), static_cast<uint8_t>(b)) == erasures.end())
                erasures.push_back(static_cast<uint8_t>(b));
        }
    }

    std::vector<uint8_t> out(frame);
    RS::ReedSolomon rs(static_cast<uint8_t>(frame), static_cast<uint8_t>(par));
    bool ok = false;
    if (!erasures.empty() && static_cast<int>(erasures.size()) <= par) {
        ok = rs.Decode(codeBytes.data(), out.data(), erasures.data(), erasures.size()) == 0;
    }
    if (!ok) ok = rs.Decode(codeBytes.data(), out.data()) == 0;
    if (!ok) { if (onDebug) { char b[64]; std::snprintf(b, sizeof b, "parity failed, %zu erasures", erasures.size()); onDebug(b); } return false; }
    if (out[0] != m_payloadLen) { if (onDebug) onDebug("length mismatch"); return false; }
    const uint16_t crc = static_cast<uint16_t>((out[frame - 2] << 8) | out[frame - 1]);
    if (crc16(out.data(), frame - 2) != crc) { if (onDebug) onDebug("crc failed"); return false; }
    onMessage(out.data() + 1, m_payloadLen);
    return true;
}

} // namespace sotto
