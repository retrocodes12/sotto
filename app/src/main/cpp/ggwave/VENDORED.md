Vendored from https://github.com/ggerganov/ggwave
commit 060aec73dd7123ccac200442f75bdc7369795ffe (2026-04-16), version 0.4.3.
Files: include/ggwave/ggwave.h, src/ggwave.cpp, src/fft.h, src/reed-solomon/*.hpp. MIT licensed (see LICENSE).
One change was made: src/reed-solomon/rs.hpp, FindErrors(), gained a bound check so a
degenerate error locator cannot append more roots than the polynomial holds (the original
asserted in debug builds and overflowed the heap in release builds; reached through the
Sotto modem's erasure decoding, reachable in principle through ggwave too).
