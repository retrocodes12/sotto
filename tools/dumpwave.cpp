// Dumps the raw 48 kHz int16 waveform a modem generates for a message. Used only to
// make the README artwork (tools/hero.py). Build from the repo root with:
//   g++ -O2 -std=c++17 -I app/src/main/cpp -I app/src/main/cpp/ggwave/include -I app/src/main/cpp/ggwave/src \
//       tools/dumpwave.cpp app/src/main/cpp/sotto_modem.cpp app/src/main/cpp/ggwave/src/ggwave.cpp -o /tmp/dumpwave
//   /tmp/dumpwave hello 100 > hello_sotto_fast.pcm   # <message> <protocol id: 100+ Sotto, else ggwave>
#include "ggwave/ggwave.h"
#include "sotto_modem.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>

int main(int argc, char ** argv) {
    const char * msg = argc > 1 ? argv[1] : "hello";
    const int proto = argc > 2 ? atoi(argv[2]) : 100;
    const int n = (int) strlen(msg);
    if (proto >= 100) {
        const sotto::Params * p = sotto::protocolById(proto);
        if (!p) return 1;
        auto w = sotto::encode(*p, (const uint8_t *) msg, n, 0.9f);
        fwrite(w.data(), sizeof(int16_t), w.size(), stdout);
        fprintf(stderr, "%s: %zu samples, %.2f s\n", p->name, w.size(), w.size() / 48000.0);
        return 0;
    }
    GGWave::setLogFile(nullptr);
    GGWave::Parameters p = GGWave::getDefaultParameters();
    p.sampleRateInp = p.sampleRateOut = p.sampleRate = 48000; p.samplesPerFrame = 1024;
    p.sampleFormatInp = p.sampleFormatOut = GGWAVE_SAMPLE_FORMAT_I16; p.operatingMode = GGWAVE_OPERATING_MODE_TX;
    GGWave tx(p);
    if (!tx.init(n, msg, (GGWave::TxProtocolId) proto, 25)) return 1;
    const uint32_t bytes = tx.encode();
    fwrite(tx.txWaveform(), 1, bytes, stdout);
    fprintf(stderr, "ggwave %d: %u samples, %.2f s\n", proto, bytes / 2, bytes / 2 / 48000.0);
    return 0;
}
