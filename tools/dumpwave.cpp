// Dumps the raw 48 kHz int16 waveform ggwave generates for a message. Used only to
// make the README artwork (tools/hero.py). Build from the repo root with:
//   g++ -O2 -std=c++17 -I app/src/main/cpp/ggwave/include -I app/src/main/cpp/ggwave/src \
//       tools/dumpwave.cpp app/src/main/cpp/ggwave/src/ggwave.cpp -o /tmp/dumpwave
//   /tmp/dumpwave hello 1 > hello_fast.pcm     # <message> <ggwave protocol id>
#include <cstdio>
#include <cstring>
#include <cstdlib>
int main(int argc, char ** argv) {
    const char * msg = argc > 1 ? argv[1] : "hello";
    int proto = argc > 2 ? atoi(argv[2]) : GGWAVE_PROTOCOL_AUDIBLE_FAST;
    GGWave::setLogFile(nullptr);
    GGWave::Parameters p = GGWave::getDefaultParameters();
    p.sampleRateInp = p.sampleRateOut = p.sampleRate = 48000; p.samplesPerFrame = 1024;
    p.sampleFormatInp = p.sampleFormatOut = GGWAVE_SAMPLE_FORMAT_I16; p.operatingMode = GGWAVE_OPERATING_MODE_TX;
    GGWave tx(p);
    if (!tx.init((int) strlen(msg), msg, (GGWave::TxProtocolId) proto, 30)) return 1;
    uint32_t bytes = tx.encode();
    fwrite(tx.txWaveform(), 1, bytes, stdout);
    fprintf(stderr, "%u samples, %.2f s\n", bytes / 2, bytes / 2 / 48000.0);
    return 0;
}
