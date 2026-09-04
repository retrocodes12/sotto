<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/hero-dark.svg">
  <img alt="sotto. The word hello as the Sotto modem sends it, drawn as a spectrogram: a single tone hopping between 64 frequencies, 27 symbols, 0.58 seconds." src="docs/hero-light.svg" width="100%">
</picture>

<sub>The banner is not decoration. It is the word “hello” as this app sends it, drawn from the actual 48 kHz waveform: four sync tones, six tones that spell the length, then 14 bytes of Reed–Solomon-protected data, one tone per six bits. 29 tones, 0.62 seconds of air.</sub>

# sotto

Type on one phone. It chirps. The other phone hears the chirp and prints your words.

Sotto is a messenger whose only network is the air between a speaker and a microphone. No
pairing, no server, no account, no permission beyond the mic. Both roles live in one Android
app, so any two phones with it installed can talk in either direction.

It ships its own modem, written for this app, and keeps [ggwave](https://github.com/ggerganov/ggwave)
next to it so the two can be compared on real phones with the built-in test burst.

| | |
|---|---|
| **Carrier** | one tone at a time, 2.1–8.0 kHz audible or 18–19.5 kHz ultrasound; Bluetooth LE when a phone is in radio range |
| **Payload** | up to 100 bytes of UTF-8 per message |
| **Speed** | 5 bytes in 0.62 s, 20 bytes in 1.22 s, 100 bytes in 4.1 s |
| **Integrity** | Reed–Solomon parity and a CRC-16. A message decodes intact or not at all |
| **Modem** | 550 lines of C++, 32 KB of decoder heap, 3 ms of CPU per second of audio |
| **Stack** | Kotlin, Jetpack Compose, C++ through the NDK, minSdk 26 |

## How a message travels

```mermaid
sequenceDiagram
    participant A as Phone A (sending)
    participant B as Phone B (listening)
    Note over A: bytes -> parity + CRC -> 6-bit symbols -> one tone each:<br/>4 sync tones, 6 length tones, then the data
    A->>A: pause its own decoder
    A->>B: AudioTrack plays the tones through the speaker
    Note over B: AudioRecord at 48 kHz mono, 1024-sample frames,<br/>own thread, echo cancel / noise / AGC switched off
    B->>B: FFT every 5 ms, sync word spotted, tones read at symbol rate
    B->>B: parity corrected, CRC checked, log: 12:04:31 IN Sotto Fast 5 B hello
    Note over A: 300 ms after the last sample leaves the DAC<br/>the decoder resumes
```

Half duplex, on purpose. A phone never decodes its own chirp because capture keeps running
while transmitting but decoding is gated: off the moment playback starts, back on 300 ms
after `playbackHeadPosition` reports the final sample played.

## The modem

A phone speaker is peak limited. ggwave's audible protocols play six tones at once, so each
tone gets a sixth of the amplitude, which is a 36× loss of power per tone before the sound
has left the phone. The Sotto modem plays one tone at a time and chooses it from 64, so every
symbol carries six bits with the whole speaker behind it. That single decision is worth about
15 dB, and the rest of the design spends it on speed and on rooms:

- **Alternating frequency sets.** Odd and even symbols use disjoint sets of 64 tones, so the
  reverberation of one symbol lands on bins the next symbol does not use.
- **Tail cancellation.** During a symbol's window, the other set's bins hold nothing but the
  decaying tail of earlier symbols. Half of that energy is subtracted before the tone is
  picked. It took Fast mode from 96% to 100% in the simulated room.
- **A four-tone sync word** instead of ggwave's two 340 ms markers. Three of the four tones
  must win their set outright and the fourth must place in the top three, so one unlucky
  tone does not lose the frame.
- **The length as six hashed tones, decoded by likelihood.** The receiver scores all 141
  possible lengths against the raw tone energies, at the sync alignment and one FFT hop
  either side, and takes the best. It never snaps on a bad bit, and the winning alignment
  corrects the symbol timing for everything that follows. This replaced a twice-sent
  CRC-4 header after a real-phone burst lost exactly one frame that way.
- **Reed–Solomon plus CRC-16, decoded on a ladder.** Parity scales with the frame (6 to 32
  bytes). Doubtful symbols go in as erasures, then fewer erasures, then none, then chase
  decoding: the six least confident symbols swapped for their runner-up tones in every
  combination until parity and CRC agree. The CRC means noise never decodes to garbage.
- **Streaming.** The decoder delivers the message the moment the last symbol has arrived.
  No end marker, no post-analysis.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/protocols-dark.svg">
  <img alt="Three spectrograms of the word hello: Sotto Fast, a single tone hopping over 0.58 seconds; Sotto Robust, the same over 1.15 seconds; ggwave Audible Fast, six tones at once between two dense marker blocks over 1.19 seconds." src="docs/protocols-light.svg" width="100%">
</picture>

### Measured against ggwave

`tools/bench.cpp` runs both modems through the same simulated air channel: every waveform
is normalised to the same peak (the speaker's limit), attenuated 20 dB for distance, and hit
with white noise at the level in the header. The room variant adds six early reflections,
a comb-filter reverb tail with an RT60 of 0.35 s, and a random ±8 dB speaker/mic tilt.
20-byte payloads, 24 trials per cell, percentage decoded correctly.

Free field, signal peak −21 dBFS at the microphone:

| noise, dBFS | −40 | −30 | −25 | −20 | −15 | −10 | −5 |
|---|---|---|---|---|---|---|---|
| **Sotto Ultrasound** | 100 | 96 | 100 | 100 | 100 | 100 | 100 |
| **Sotto Fast** | 100 | 100 | 100 | 100 | 100 | 100 | 0 |
| **Sotto Robust** | 100 | 100 | 100 | 100 | 100 | 100 | 92 |
| **Sotto Near** | 100 | 100 | 100 | 0 | 0 | 0 | 0 |
| ggwave Normal | 100 | 100 | 92 | 0 | 0 | 0 | 0 |
| ggwave Fast | 100 | 100 | 96 | 0 | 0 | 0 | 0 |
| ggwave Fastest | 100 | 100 | 96 | 12 | 0 | 0 | 0 |

Room, same signal:

| noise, dBFS | −40 | −30 | −25 | −20 | −15 | −10 | −5 |
|---|---|---|---|---|---|---|---|
| **Sotto Ultrasound** | 100 | 100 | 100 | 100 | 100 | 92 | 0 |
| **Sotto Fast** | 100 | 100 | 100 | 100 | 75 | 17 | 0 |
| **Sotto Robust** | 100 | 100 | 100 | 100 | 96 | 75 | 0 |
| **Sotto Near** | 100 | 62 | 8 | 0 | 0 | 0 | 0 |
| ggwave Normal | 100 | 88 | 33 | 0 | 0 | 0 | 0 |
| ggwave Fast | 100 | 96 | 33 | 0 | 0 | 0 | 0 |
| ggwave Fastest | 100 | 88 | 33 | 0 | 0 | 0 | 0 |
| ggwave Fast, hard-clipped | 42 | 25 | 71 | 25 | 0 | 0 | 0 |

Sotto's audible modes keep decoding with noise 6 dB above the signal's own peak; ggwave stops
about 10 dB before that in free field and 15 dB before it in the room. The clipped row is what
happens when ggwave's six-tone sum is driven past full scale to make it louder: clipping
breaks it even without noise, which is why the app caps ggwave's amplitude at 25.

The interesting row is the default one. Ultrasound is the most robust protocol here, not the
most fragile — a narrow band above hearing means the reverb tail and the room's noise are
mostly somewhere else, and the whole speaker goes into one tone. It pays for that in airtime:
four seconds for twenty bytes against Sotto Fast's one. Silence is the reason to use it; the
robustness is a bonus that only shows up once the band stops fighting the room.

`tools/bench.cpp gate` runs a subset of these cells with a pass/fail floor under each and
exits non-zero if any of them drops, so CI fails on a modem regression rather than printing
a slightly smaller number nobody reads.

| | airtime, 20 B | airtime, 100 B | decoder heap | decoder CPU |
|---|---|---|---|---|
| Sotto Fast | 1.22 s | 4.1 s | 32 KB | 2.9 ms per s of audio |
| Sotto Robust | 2.43 s | 8.1 s | 48 KB | 4.7 ms |
| Sotto Ultrasound | 4.35 s | 14.7 s | 36 KB | 3.2 ms |
| Sotto Near | 0.30 s | 0.83 s | 24 KB | 3.0 ms |
| ggwave Fast | 2.09 s | 6.8 s | 8.2 MB | 0.6 ms idle, more while analysing |
| ggwave Fastest | 1.39 s | 3.8 s | 8.2 MB | |

The channel is a model. Real speakers, real rooms and real phones are the next thing this
table needs, and the app's test burst exists to get those numbers.

## Protocols

The receiver decodes every protocol at all times, so the two phones do not have to agree on
one. The log tells you which protocol each message arrived on.

| Protocol | Band | Symbol | 20 bytes | Reach for it when |
|---|---|---|---|---|
| Sotto Fast | 2.1–8.0 kHz | 21 ms | 1.22 s | range in the audible band; the "Silent messages" switch off |
| Sotto Robust | 2.1–8.0 kHz, tones 47 Hz apart | 43 ms | 2.43 s | far apart, loud room, or someone is walking |
| **Sotto Ultrasound** (default for messages) | 18–19.5 kHz, 16 tones, rate-½ parity | 43 ms, 4 bits | 4.4 s | nobody hears it. Band tuned on one phone pair; see below |
| ggwave Normal / Fast / Fastest | 1.8–6.2 kHz | 192 / 128 / 64 ms chunks | 2.8 / 2.1 / 1.4 s | comparison, and talking to other ggwave software |
| ggwave Ultrasound ×3 | 15–19 kHz | as above | 2.8 / 2.1 / 1.4 s | same, above hearing |
| ggwave Dual-tone ×3 | about 1–3 kHz | | 6.6 / 4.7 / 2.7 s | tiny speakers |

ggwave's mono-tone protocols are not offered because they only work with fixed-length
payloads.

## On the screen

- **Listening** switch, a live mic level in dBFS, and the capture source it managed to open
  (`UNPROCESSED`, `VOICE_RECOGNITION` or `MIC`).
- **Message** box with a byte counter and, for Sotto protocols, the seconds of audio the
  draft will take. Send greys out above 100 bytes.
- **Reach.** The radar button plays three short probes; every phone that hears one answers
  with how loudly it arrived. A sheet shows a bar and a plain verdict per person, and offers
  to switch to the audible band when silent would drop messages.
- **Share cards.** The + menu sends a link, a Wi-Fi network or a contact as one frame on Near,
  at arm's length, in about a second. The receiving tile opens the link, copies the password
  or saves the contact.
- **History.** Messages and photos stay on the phone across restarts, up to 500. Settings has
  a clear that really forgets.
- **Listens with the screen off.** A foreground service holds the microphone behind a quiet
  "Sotto is listening" notification with a Stop action, so a phone on the table keeps
  receiving. Messages that arrive while the app is not on screen show as notifications.
  Settings can switch it off, in which case listening stops when the app does.
- **Private chats.** Tap a name to open a chat only that person can read. The two phones
  swap X25519 public keys by sound once (about six seconds each way on ultrasound); messages
  are then AES-256-GCM encrypted to that phone. Relays repeat them without being able to read
  them, and other phones show nothing. The room stays the broadcast it always was. The
  receiver answers each private message with a seven-byte receipt, so the sender's tile says
  "delivered", and any relay still holding the message drops it. A key that arrives for
  someone whose key is already held is never swapped in silently: the chat shows both
  fingerprints and asks, since anyone in earshot can play a KEY frame. Replayed private
  messages are dropped by a per-peer counter.
- **Messages keep travelling after they leave your phone.** Anything you send also goes into a
  store that other phones carry: when two phones meet they trade what the other lacks, so a
  message rides along in someone's pocket and arrives somewhere you were never in range of.
  Room messages spread to everyone for a day; private ones are sealed to one person, given
  sixteen copies that halve at each meeting, and kept for three days. When one arrives, a
  receipt travels back the same way, the tile says how long it took and through how many
  phones, and every carrier drops its copy. Each phone also publishes a small profile (name and
  public key) that spreads the same way, so you can write privately to someone you have never
  stood next to. A tile says only what the phone can know: "handed to 3 phones", "reached about
  40 phones" from a 32-byte counting sketch that merges as copies meet, and "delivered" only
  when the receipt is actually back.
- **Bluetooth, beside the sound.** The same frames also travel over Bluetooth LE when a phone is
  in radio range: further, faster, and without spending airtime. Every phone advertises its tag
  and scans for others, so there is still nothing to pair and no server. A frame the radio fails
  to deliver is played through the speaker instead, so nothing depends on it, and sound carries
  everything the moment no phone is in Bluetooth range. Share cards and the reach test stay on
  sound on purpose: a card is the hold-the-phones-together gesture, and the reach test measures
  the room. Settings can switch the radio off entirely.
- **Who's in reach.** The status line under the wordmark says who is nearby: phones announce
  themselves with a hello when opened, chirp three bytes when they have been quiet for over a
  minute, and anyone heard directly in the last three minutes counts. Phones heard only through
  a relay show as "farther".
- **Phones repeat for each other.** A phone that hears a message plays it again once, after a
  random pause and only when the band is quiet, unless another phone repeats it first. Two
  hops by default, so a phone in the doorway carries a message into the next room. A relay
  bridges the two media in both directions: whoever it is repeating for is somewhere the
  sender did not reach, so it goes out over Bluetooth and over the speaker as well whenever
  some phone it knows of is not visible on the radio. Relayed tiles say "via" whoever repeated
  them. Photos are forwarded whole, hop by hop, after the relay has received them completely.
  Settings can switch a phone's repeating off.
- **Silent messages** on by default: text goes on ultrasound, photos on Near (audible, at
  arm's length). Settings can switch messages to the audible Fast band for range, or pin
  any protocol for everything. Loudness slider defaults to full; ggwave protocols are
  capped at 25 so their six-tone sum does not clip.
- **Test burst**: a fixed 20-byte message ten times, 2 s apart. The receiving phone counts
  `received / 10`, so distance, room and modem can be scored in one number.
- **Log**: time, direction, protocol, size, text. Newest first.
- A banner whenever media volume is under 100%, red under 70%, with a button that opens
  the volume panel. Every 6 dB of speaker level doubles the range.
- A blocking screen if the microphone permission is refused, with a way into app settings.

## Install

No toolchain needed to try it: the [latest release](https://github.com/retrocodes12/sotto/releases/latest)
carries `sotto.apk`. Put it on both phones and open it, or from a computer:

```sh
adb install -r sotto.apk
```

After that the app updates itself: it checks the project's releases when opened (at most
every six hours, or on demand in Settings), downloads the new `sotto.apk` into its cache
and hands it to Android's installer. Every build is signed with the same key, so updates
install over the top and keep your settings. Both phones should run the same version;
the app says so when one is behind.

## Build

Needs JDK 17, Android SDK platform 36, NDK 28.2.13676358 and CMake 3.22.1, all available from
Android Studio's SDK Manager.

```sh
git clone https://github.com/retrocodes12/sotto
cd sotto
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk    # once per phone
```

`assembleDebug` also works and is the faster loop while developing; the published builds are
release builds, which are not debuggable. Both are signed with the machine's Android debug key,
which is why later builds install over the top — and why a build made on another machine will
not.

## Checks

```sh
./gradlew testDebugUnitTest    # the protocol, the crypto and the parsers
./gradlew lintDebug            # zero errors is the bar
```

The tests are the part that runs without a phone, so they cover the parts that are hard to see
on one: the X25519 against the RFC 7748 vectors, the AEAD refusing every tampered byte, every
frame parser fed a hundred thousand hostile inputs without throwing, and the carry network run
as a set of phones that meet in a chosen order.

The modem has its own gate, because a radio regression shows up as a slightly smaller number
rather than a failure:

```sh
g++ -O2 -std=c++17 -I app/src/main/cpp -I app/src/main/cpp/ggwave/src -I app/src/main/cpp/ggwave/include \
    tools/bench.cpp app/src/main/cpp/sotto_modem.cpp app/src/main/cpp/ggwave/src/ggwave.cpp -o /tmp/bench
/tmp/bench gate     # pass/fail floor per protocol and channel; non-zero if any cell drops
/tmp/bench          # the full comparison tables above
/tmp/bench diag 2 -15 1 200    # one protocol, one noise level, with the decoder's reasons
```

All three run on every push (`.github/workflows/build.yml`). The artwork tools build with plain
`g++` too; the commands are in their headers.

## Range test

1. Install on phone A and phone B. Open the app on both, allow the microphone.
2. Media volume to at least 70% on both. The red banner goes away.
3. Turn on **Listening** on B. Talk; the level meter should move.
4. On A, type something and tap **Send**. Within about a second B's log shows the text,
   the time, the protocol and the byte count. Reply from B to A to check the other direction.
5. Now score it. On B tap **Reset** under "Received". On A tap **Send burst**. Half a minute
   later B shows `n / 10`. Switch A to **ggwave Fast** and send the burst again from the same
   spot. Walk apart, repeat both. Write the numbers down; they are the whole point.
6. `adb logcat -s sotto-jni SoundLink Sotto` shows the capture source, which audio effects
   were switched off, every sync the modem found, and for every frame the mean tone level,
   the noise floor per bin, the SNR, the received level across eight slices of the band,
   and why it was dropped if it was.

Things that hurt: a case over the mic, a Bluetooth speaker or headset stealing the output on
the sending phone, and ultrasound on hardware that rolls off before 19 kHz.

## Under the hood

- **One engine, two modems.** `cpp/jni_bridge.cpp` encodes with whichever protocol was
  chosen and feeds every decoder the same capture stream. ggwave is driven through its C++
  class rather than its C API because only the class reports which protocol decoded.
- **`UNPROCESSED` only when the device says so.** Android substitutes a processed source on
  phones that lack it, which is worse than falling back to `VOICE_RECOGNITION` deliberately.
  Echo cancellation, noise suppression and automatic gain control are then disabled by name
  on the capture session, and the effect objects are held until capture stops.
- **Nothing is resampled.** Capture, playback and both modems run at 48 kHz.
- **Rectangular windows on purpose.** Tones sit exactly on FFT bin centres, so a rectangular
  window over one symbol gives one clean bin per tone; the receiver's FFT is a real-input
  N/2 transform with the split done only for the 128 bins of the band.
- **Everything native is compiled `-O2`** even in the debug variant. The decoders run on the
  capture thread on every frame.
- **The artwork is data.** `tools/dumpwave.cpp` writes the waveform a modem generates for a
  message; `tools/hero.py` draws it as run-length merged SVG rectangles.

### On real phones

Two phones, one a Samsung Galaxy A21s receiving over adb, ultrasound only so far, 20-byte
bursts of ten. Each row is one change, tested at the same spots. Tone level is the mean
energy of the winning tone at the receiver's microphone; the noise floor per bin sat at
−88 to −103 dBFS throughout.

| build | change | 25 cm | ~1 m | ~2 m | off sight, one wall bounce |
|---|---|---|---|---|---|
| v0.2 | as benchmarked, 15–18 kHz | 10/10, −55 dBFS | 9/10, −65 dBFS | | |
| v0.3 | likelihood header | | 10/10 | 8/10, −73 dBFS | |
| v0.4 | band moved to 16.5–19.5 kHz | | 9/10 | 9/10, **−67 dBFS** | |
| v0.5 | rate-½ parity | | | 10/10 | 5/10, −84 dBFS |
| v0.6 | 16 tones on 18–19.5 kHz | | | | **7/10**, −79 dBFS |

What the log taught, in order: the one v0.2 loss was a header whose timing slipped, not a
weak signal; these phones are 16 dB louder at 18 kHz than at 15 kHz, so moving the band up
was worth 6 dB, a full doubling of range; the losses that remained happened at 21–26 dB of
SNR, because at 2 cm wavelengths a few tones per frame fall into narrow fades, so parity
went to rate ½; and off sight, with everything arriving by reflection, the pair's edge is a
mean SNR of about 16–18 dB. In this flat the line-of-sight limit was never reached: at the
far end of the longest room the tone level was the same as at 2 m, which is the room's
reverberant field taking over.

## What it does not protect you from

Sotto has no server and no accounts, and private messages are sealed end to end. That is not
the same as being invisible, and the difference matters:

- **Ultrasound is inaudible, not undetectable.** Any phone recording in the room captures the
  same signal your microphone does; it is above hearing, not above a microphone. Someone who
  is looking for it will find it.
- **Who is talking is in the clear.** The sealed part is the text. The sender's tag, the
  recipient's tag, the length and the timing all travel unencrypted, over sound and over
  Bluetooth alike.
- **Being present is broadcast.** A phone running Sotto advertises an id on Bluetooth and
  chirps its presence over sound. Anyone in range can tell that this particular phone is here,
  and can do so repeatedly to follow it.
- **Room messages are for everyone.** They are not encrypted at all, by design.
- **There is no forward secrecy.** A pair key is derived once from two long-term keys and used
  from then on. A phone that is seized or unlocked reveals every private message it still
  holds, and any recorded ciphertext from that pair.
- **A substituted key is only caught if you look.** When a key changes, Sotto stops, says so
  and shows both fingerprints — but it cannot tell a reinstall from someone in earshot
  pretending. Comparing those eight characters out loud is the whole defence.
- **Carriers hold your sealed messages.** For up to three days, phones you have never met keep
  a copy of a private message they cannot read. They do learn that a message exists, roughly
  how big it is, and who it is addressed to.
- **The update check reaches GitHub.** It is the only network request the app makes, and it is
  the one place your IP address meets this project.

## Status

v0.24, and honest about it. What has been proven and what has not are different lists.

Proven on two real phones: the ultrasound mode, tuned to their measured response, decoding
10/10 at 25 cm and at 2 m, and 7/10 through one wall off sight.

Proven on this laptop, by eighty tests that run in a second: the modem against a simulated
channel including dropouts, with a pass/fail gate under it; the X25519 against the RFC 7748
vectors; the AEAD against every single-bit tamper; the anti-replay window against every order
its inputs can arrive in; every frame parser against a hundred thousand hostile inputs; the
carry network as a set of phones meeting in a chosen order and forging receipts at each other.
The modem is also clean under AddressSanitizer and UndefinedBehaviorSanitizer.

Those tests were written after a twelve-dimension audit of this codebase found eighty-two
defects, and they exist because of what it found. Two of them mattered more than the rest: the
carry network could not receive a single frame, because the check deciding whether a frame
belonged to it compared an unsigned byte against a signed range and was never once true; and
carried private messages could not be decrypted by anybody, because the sender and the receiver
built the nonce from two different counters. Both features had shipped. Neither had ever worked.

Not yet seen on a phone at all: Bluetooth, the carry network, the audible modes, and the whole
identity and private-message layer. Seventeen releases have shipped since the last time anything
was measured on hardware, and the honest reading of that is that some of it does not work. The
next thing this project needs is not a feature. It is three phones and an afternoon.

## Layout

```
app/src/main/cpp/sotto_modem.h, .cpp         the modem: encoder and streaming decoder
app/src/main/cpp/jni_bridge.cpp              JNI: create, encode, decode, protocol names
app/src/main/cpp/ggwave/                     vendored ggwave, commit in VENDORED.md, MIT
app/src/main/java/com/sotto/Modem.kt         Kotlin face over the engine
app/src/main/java/com/sotto/SoundLink.kt     AudioRecord, AudioTrack, half-duplex gate
app/src/main/java/com/sotto/BleLink.kt       the Bluetooth transport: advertise, scan, GATT
app/src/main/java/com/sotto/Identity.kt      who this phone is, and the frame formats
app/src/main/java/com/sotto/Crypto.kt        X25519, the pair key, AES-256-GCM
app/src/main/java/com/sotto/MainViewModel.kt UI state, send, burst counting, log
app/src/main/java/com/sotto/MainActivity.kt  permission gate and screen switch
app/src/main/java/com/sotto/ListenService.kt the foreground service that keeps the mic open
app/src/main/java/com/sotto/History.kt       messages kept on the phone
app/src/main/java/com/sotto/ui/              conversation, welcome and settings screens, theme
app/src/main/java/com/sotto/Transfer.kt      chunking and resend requests for photos
app/src/main/java/com/sotto/Updates.kt       in-app updates from GitHub releases
app/src/main/java/com/sotto/carry/           the carry network: bundles, store, sync, engine
app/src/test/java/com/sotto/              unit tests: crypto, parsers, transfers, the network
.github/workflows/build.yml                  tests, lint, the modem gate and an APK per push
tools/bench.cpp                              the channel simulation, the tables and the gate
tools/dumpwave.cpp, hero.py                  the README artwork
docs/                                        the generated SVGs
```

The modem reuses the Reed–Solomon implementation vendored with ggwave (Mike Lubinets, MIT).
ggwave is by Georgi Gerganov, MIT licensed, vendored unchanged at the commit named in
`app/src/main/cpp/ggwave/VENDORED.md`.

<sub>sotto, as in <i>sotto voce</i>: said under the breath, meant for one listener.</sub>
