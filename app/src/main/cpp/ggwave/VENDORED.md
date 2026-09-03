Vendored from https://github.com/ggerganov/ggwave
commit 060aec73dd7123ccac200442f75bdc7369795ffe (2026-04-16), version 0.4.3.
Files: include/ggwave/ggwave.h, src/ggwave.cpp, src/fft.h, src/reed-solomon/*.hpp. MIT licensed (see LICENSE).
Changes were made to src/reed-solomon/rs.hpp, all marked "Sotto patch":
- FindErrors() refuses an empty locator and stops once more roots turn up than the
  locator's degree (the original appended past the polynomial: assert in debug builds,
  heap overflow in release).
- FindErrorLocator() does its capacity check in signed arithmetic; the unsigned version
  wrapped whenever there were more erasures than unmarked errors, so every erasures-only
  codeword was rejected.
- DecodeBlock() honours FindErrorLocator's failure, accepts a codeword whose only
  corruption is the marked erasures, and rejects a found error that coincides with an
  erasure (the original then divided by zero in CorrectErrata).
ggwave.cpp is unchanged.
