# Sotto

A v0 "data over sound" messenger for Android. Type a message on one phone and press
Send: it plays as audio, the other phone hears it through its microphone and shows the
decoded text. Both roles live in the one app. No server, no accounts, nothing leaves the
phone.

Encoding and decoding is [ggwave](https://github.com/ggerganov/ggwave) (MIT), vendored
under `app/src/main/cpp/ggwave/` and built with the NDK. The JNI wrapper is
`app/src/main/cpp/ggwave_jni.cpp`. It talks to ggwave's C++ class rather than the C
functions because the C API cannot report which protocol decoded a message.

## Build

Requirements: JDK 17, Android SDK with platform 36, NDK 28.2.13676358 and CMake 3.22.1
(all installable from Android Studio's SDK Manager). Point `local.properties` at the SDK:

```
sdk.dir=/path/to/Android/Sdk
```

Then:

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install on both phones. The debug APK is signed with the debug key, so reinstalling over
an older build works without uninstalling.

## How it works

- Audio in: `AudioRecord`, 48 kHz mono PCM16, source `UNPROCESSED` when the device
  advertises it, otherwise `VOICE_RECOGNITION`, otherwise `MIC`. The buffer is at least
  4x the minimum. Echo cancellation, noise suppression and automatic gain control are
  switched off explicitly where the device reports them. Capture and decoding run on one
  dedicated thread; 1024 samples per ggwave frame.
- Audio out: `AudioTrack` in streaming mode, `USAGE_MEDIA`, 48 kHz mono PCM16, played at
  the phone's media volume. The UI warns when media volume is under 70%.
- ggwave runs at 48 kHz for capture, playback and its operating rate, so nothing is
  resampled.
- Half duplex: decoding pauses the moment playback starts and resumes 300 ms after the
  last sample has left the DAC (checked via `playbackHeadPosition`), so a phone never
  decodes its own transmission. Capture keeps running while paused so the record buffer
  does not fill with stale audio.
- Protocols: default is ggwave's audible "Fast". The dropdown lists all audible,
  ultrasound and dual-tone variants. Mono-tone protocols are left out because ggwave only
  supports them with fixed-length payloads. The receiver decodes every protocol, so the
  two phones do not need to match; the log shows which one arrived.
- Messages are capped at 100 bytes (UTF-8). ggwave itself allows 140.
- "Tx amplitude" is ggwave's `volume` parameter (5..100, default 30). It sets the
  amplitude of each tone; up to six tones are summed, so values above about 50 clip.
  Clipping is usually fine for audible protocols and worth trying at range, less so for
  ultrasound.

Airtime per message at 48 kHz, measured on the host with the vendored ggwave (the app
adds 0.1 s of trailing silence and a 0.3 s decode guard):

| Protocol            | 20 bytes | 100 bytes |
|---------------------|----------|-----------|
| Audible Normal      | 2.8 s    | 9.9 s     |
| Audible Fast        | 2.1 s    | 6.8 s     |
| Audible Fastest     | 1.4 s    | 3.8 s     |
| Ultrasound (same)   | 2.8 / 2.1 / 1.4 s | 9.9 / 6.8 / 3.8 s |
| Dual-tone Normal    | 6.6 s    | 28.1 s    |
| Dual-tone Fast      | 4.7 s    | 19.0 s    |
| Dual-tone Fastest   | 2.7 s    | 9.8 s     |

So a 10-message burst on Audible Fast takes about 45 s end to end.

## Test procedure

1. Install on phone A and phone B. Open the app on both and allow the microphone.
2. On both phones, set media volume to at least 70% (the red banner goes away).
3. Turn on **Listening** on phone B. The level meter should move when you talk.
4. On phone A, type a message and tap **Send**. Phone A plays a short chirp; phone B's log
   shows the text, the timestamp, the protocol and the byte count within a second or two.
   Reply the other way to check both directions.
5. Success rate by distance and protocol:
   - On phone B, tap **Reset** under "Received" so the counter starts clean.
   - On phone A, pick a protocol, then tap **Send burst**. It sends a fixed 20-byte
     message ten times with a 2 s gap. Phone B shows `received / 10`.
   - Move the phones apart and repeat; change protocol and repeat. A burst that starts
     later than 20 s after the previous one, or whose sequence number restarts, is
     counted as a new burst automatically.
6. `adb logcat -s sotto-jni SoundLink` shows the capture source chosen, which effects
   were disabled, and any ggwave rejections.

Things that hurt reception: phone cases over the mic, media volume below ~70%, a
Bluetooth headset or speaker connected on the sending phone (audio goes there instead),
and "Fastest" protocols in echoey rooms. Ultrasound protocols need a clear line of sight
and phones whose speaker and mic actually reach 18 kHz+; some do not.

## Layout

```
app/src/main/cpp/ggwave_jni.cpp        JNI wrapper (encode, decode, protocol names)
app/src/main/cpp/ggwave/               vendored ggwave (see VENDORED.md for the commit)
app/src/main/java/com/sotto/GgWave.kt         Kotlin face over the JNI wrapper
app/src/main/java/com/sotto/SoundLink.kt      AudioRecord/AudioTrack, half-duplex gating
app/src/main/java/com/sotto/MainViewModel.kt  UI state, send, burst counting, log
app/src/main/java/com/sotto/MainActivity.kt   Compose UI and the permission gate
```
