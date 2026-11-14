# C36 — Edith's own voice (on-device speech for phones with no voice)

| | |
|---|---|
| Phase | 3 — Voice (added 2026-11-14) |
| Status | **Built** (PR for C36); the check in Andarium on the owner's iPhone is the owner's, see As built |
| Depends on | C13 (`TtsEngine`, `SpeechOutput`), C14 (hands-free reads `speakingMessageId`; ONNX Runtime is already shipped), C17b (`AppVisibility`) |
| Unblocks | Spoken replies and hands-free answers in Andarium on the owner's iPhone |
| Size | Large (a native library, a model download, audio playback, Settings) |
| Read first | 06-decisions **A4**, **D21**; C13 (spec **and** As built); C14 (ONNX Runtime packaging); 00-product §4.2; 04-lessons L13, L36, L37 |

## Why

On 2026-11-14 the owner found that Andarium (the Android environment that runs Edith on their iPhone) has **no
text-to-speech engine installed**. Edith only speaks through Android's own `TextToSpeech` (A4, C13), so there she is
silent. Settings tells the owner to "install a voice in Android settings", which can't be done there.

Ruled out on 2026-11-14:
- **Ollama's `phonon` voice.** The speech endpoint (`/v1/audio/speech`) exists on the owner's Windows server
  (0.40.16), but `ollama pull phonon` fails with "this model requires MLX support, but the MLX runtime is not
  available" (L13).
- **A voice app** (for example SherpaTTS from F-Droid) as the answer. It may work as the owner's own workaround, but
  Android makes them approve every install, and nobody has checked that Andarium lets them choose an engine. Edith
  can't depend on it.

So Edith carries a voice herself: a **Piper** neural voice (a VITS model, ONNX format) run on the phone by
**sherpa-onnx** (decision D21).

Facts checked on 2026-11-14 (re-verify; they change):
- sherpa-onnx (k2-fsa) supports text-to-speech on Android with a Kotlin API (`OfflineTts`). Its Piper voices are
  published as `vits-piper-<voice>.tar.bz2` in the project's GitHub `tts-models` release. Each holds an `.onnx`
  model, `tokens.txt` and an `espeak-ng-data/` folder. Seen: `en_US-lessac-medium` 61 MB (male), `libritts_r-medium`
  75 MB (904 voices), all 22,050 Hz. (<https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html>)
- The library is **not on Maven Central** (a search for the group `com.k2fsa.sherpa.onnx` found nothing). AARs are
  on Hugging Face (`csukuangfj/sherpa-onnx-libs`, `android/aar/<version>/`), about 36 MB each with all CPU types.
  Versions up to 1.12.21 were listed, and a "static-link-onnxruntime" build was seen for 1.11.1. Whether each
  version has the static build, and which CPU types it holds, is **not checked**.
- One benchmark found a Piper "low" voice at about 39 tokens/s on an Android phone and Kokoro at about 2.4
  (slower than real time). **Kokoro is out; Piper only.**
- Edith already ships ONNX Runtime (`onnxruntime-android` 1.31.0, for the wake word, C14), with only `arm64-v8a`
  and `x86_64` kept and `useLegacyPackaging` on. A second `libonnxruntime.so` from another library would clash with
  it (Android keeps one file per name).
- **Not checked: licences.** Each Piper voice has its own (see its MODEL_CARD and its source dataset), and
  `espeak-ng-data` is GPL-3.0. Edith is a private, personal app, so this is fine today, but it has to be recorded
  (see §2).

## Goal

- On a phone with no usable voice, Settings → Voice says "Your phone has no voice. Edith can use her own voice
  (about 60 MB)" with a **Download** button. One tap downloads it, with progress. Then she speaks replies and
  hands-free answers.
- On a phone with a voice, nothing changes (the phone's voice stays the default), but the owner can choose Edith's
  own voice in Settings.
- With either voice the behaviour is C13's and C14's: sentence by sentence while a reply streams, Play and Stop,
  the speed slider, and hands-free doesn't listen to her own voice.
- **Speech never uses the network** (A4). Only the one-time voice download does.

## Before you start — owner decisions

1. **The voice is the owner's choice, by ear.** Before building the app side, make 3–4 sample WAVs of a typical
   Edith reply (three sentences, with a temperature, a time such as "3:00 PM" and a percentage) using
   sherpa-onnx's command-line tools or Python on the PC. Keep them in the scratchpad, never in the repo. Candidates
   (check each is in the `tts-models` release): `en_US-amy-medium`, `en_US-hfc_female-medium`,
   `en_US-lessac-medium`, `en_US-libritts_r-medium` with 3 different speaker ids, and one "low" voice for speed.
   The owner listens and picks. Don't pick for them. Record the choice, its size and its licence in As built.
2. **Download or bundle.** The recommendation is **download on tap** (§3): bundling a 60 MB voice would roughly
   triple the APK (33 MB today), and it's only needed where the phone has no voice. Bundling is a small change if
   the owner prefers an install that never needs the network. Ask when building.

## Specification

### 1. The engine (`voice/own/`)
- `SherpaTts : TtsEngine`, the same interface as `AndroidTts` (`available`, `problem`, `setListener`, `speak`,
  `stop`, `idle`). `SpeechOutput` and hands-free don't change.
- Hide the native library behind a small interface so JVM tests never load it:
  ```kotlin
  interface SpeechSynth {
      val sampleRate: Int
      /** Calls [onSamples] as audio is produced; return false from it to stop. Blocking. */
      fun generate(text: String, speed: Float, onSamples: (FloatArray) -> Boolean)
      fun close()
  }
  ```
  The real one wraps `OfflineTts` (`generateWithCallback(text, sid, speed, callback)`; the callback returns 1 to
  continue and 0 to stop; **check the exact API in the version used**).
- Voice files live in `filesDir/voices/<voiceId>/` (`model.onnx`, `tokens.txt`, `espeak-ng-data/`): app-private, not
  backed up (`allowBackup` is already false).
- **Generation** runs on one worker thread, never the main thread (01-architecture §8.4), with 2 threads for
  ONNX. **Playback** is an `AudioTrack` (stream mode, the same `USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH` attributes
  as `AndroidTts`), fed from a second thread. Sentence 2 is generated while sentence 1 plays, with a queue of at
  most 3 sentences ahead.
- **Audio focus** exactly as `AndroidTts`: request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` for the first sentence,
  stop speaking on focus loss (`onInterrupted`), give it up in `idle()`.
- **Speed:** the Settings speed (0.6–1.6) is the `speed` argument. Check that larger means faster.
- **Callbacks, with C13's utterance ids** (stale ids are already ignored by `SpeechOutput`):
  - `onStart(id)` when the first samples of that sentence reach the speaker
  - `onDone(id)` when its **last sample has been played**. This matters: hands-free starts listening on `onDone`,
    so "done generating" would make her hear herself (trap 3).
  - `onError(id)` when generation throws.
- **`stop()`** drops the queue, makes the current generation return "stop", and flushes the `AudioTrack`. It has to
  feel instant: the owner interrupts by sending a message, and hands-free interrupts her by speaking.
- **Lifetime and memory.** Andarium runs inside an iPhone app, and Android may kill Edith there when memory is short
  (C17b trap 1). So: load the model **on the first sentence**, on the worker thread; the sentence waits until it's
  loaded (as `AndroidTts` queues before `onInit`); **unload after 2 minutes without speech**, except while
  hands-free is on. Log the load time and the memory it takes (numbers, never text).
- **Text:** C13's `SentenceChunker` and `SpeechText.clean` are used unchanged. Listen to how Piper reads "53°F",
  "3:00 PM", "50%" and "Fri Nov 14". If it reads them badly, add a small `SpeechNormalizer` (°F → "degrees
  Fahrenheit", "%" → "percent", times and dates) that only Edith's own voice uses, with tests.

### 2. Getting the library into the build
- **Prefer the AAR with ONNX Runtime linked in statically**, so it holds no `libonnxruntime.so` and can't clash
  with `onnxruntime-android` 1.31.0. Check that the version you pick has that build and that it holds both
  `arm64-v8a` and `x86_64` (the emulator is x86_64).
- **Not in Git** (35+ MB). `scripts/fetch-sherpa.sh` downloads the AAR for the pinned version into `app/libs/`
  (git-ignored) and checks a **pinned SHA-256** that's committed in the script. The Gradle build stops with a clear
  message when the file is missing. `.github/workflows/ci.yml` runs the script (cache the file). Like every script
  it logs to `build-logs/` (`lib.sh`'s `start_log`).
- **R8:** native code calls into sherpa's Kotlin classes, so they need a keep rule (for example
  `-keep class com.k2fsa.sherpa.onnx.** { *; }`). Test a **release** build, never only debug.
- **Do this first, before any UI (the spike):** on `Edith_Release_Test`, a release build loads a voice and speaks one
  sentence, **and** hands-free is on and the wake word still loads (both native libraries in one process).
  - If the static AAR doesn't exist for a recent version, try the normal AAR with
    `packaging { jniLibs { pickFirst("**/libonnxruntime.so") } }` so Edith's newer ONNX Runtime wins (its C API
    stays backward compatible), and test **both** users.
  - If neither works, build sherpa-onnx from source against Edith's ONNX Runtime, or stop and ask the owner before
    choosing anything else (as C24 does for libraries).
- **Licences:** record in As built the licences of sherpa-onnx, the chosen voice and its dataset, and
  `espeak-ng-data` (GPL-3.0). Ask the owner before Edith is ever given to anyone beyond their own devices.

### 3. The voice download (`voice/own/VoiceDownloader.kt`)
- The owner's chosen voice only. Its **URL, byte size and SHA-256 are pinned in code** (`OwnVoices.kt`): the
  `.tar.bz2` from the sherpa-onnx `tts-models` GitHub release.
- Flow, started by the owner's tap:
  1. Check free space (at least 2× the size).
  2. Stream to `voices/<id>.part` with HTTP `Range` to resume.
  3. Check the SHA-256. A mismatch deletes the file and says "The file didn't match what I expected. Try again."
  4. Extract (Apache Commons Compress BZip2 + Tar, or another extractor you've proved under R8) into
     `voices/<id>.tmp/`.
  5. Check the expected files exist, then rename the folder to `voices/<id>/`. Nothing is used before step 3
     passes, and a kill mid-extraction leaves no half voice.
- **Downloaded files are untrusted.** Reject tar entries with absolute paths, `..`, or links (zip-slip). Cap the
  entry count and the total extracted size (for example 300 MB). Extract only `*.onnx`, `tokens.txt`,
  `espeak-ng-data/**` and the model card.
- **No network while Edith is off screen** (L36): the download runs only while she's on screen (`AppVisibility`).
  Leaving the app pauses it ("Waiting for Edith to be on screen"), and coming back resumes with `Range`. No
  foreground service.
- States: `NotDownloaded`, `Downloading(percent)`, `Verifying`, `Ready(size)`, `Failed(message)` (plain messages:
  no network, no space, mismatch). A **Delete** removes the voice.

### 4. Choosing the voice (`voice/speech/ChoosingTts.kt`)
- Setting `speech_voice` (DataStore): `auto` (default), `phone`, `own`.
- `auto`: the phone's voice when it's available and offline; otherwise Edith's own voice when it's `Ready`;
  otherwise unavailable, with the problem text "Your phone has no voice. Download Edith's own voice in Settings →
  Voice."
- `ChoosingTts : TtsEngine` forwards to the active engine. It stops speaking and switches when the setting
  changes or a download finishes. Its `available` and `problem` reflect the active choice.
- The rule is a pure function, like C17b's `chooseBrowser`, so a unit test covers every combination. A phone voice
  that needs the network counts as unavailable (A4, `AndroidTts.preferOfflineVoice`, as now).

### 5. Settings → Voice
- **Voice** with three options: *Automatic (recommended)*, *Phone's voice*, *Edith's own voice*. Under the last, its
  state: Not downloaded **[Download (about N MB)]** · Downloading 43% **[Cancel]** · Ready, N MB **[Delete]** ·
  the error with **[Try again]**.
- When there's no phone voice, the error text in the Voice section becomes "Your phone has no voice. Edith can use
  her own." with **Download** beside it.
- **Test voice** uses whichever voice is active, and is off only when none is.
- **First time she should speak but can't** (Speak replies on, no usable voice): one dismissible banner in Chat,
  "I can't speak yet. Get my voice in Settings → Voice." The dismissal is saved. Keep it as light as the crash
  banner.

### 6. Hands-free (C14)
No change is expected: it reads `speakingMessageId` and uses `announce`. Check it with the new engine, and
especially that `onDone` means heard (trap 3).

## Tests
- The voice-choice rule: every combination of the setting, the phone's voice and Edith's voice.
- `ChoosingTts` with fake engines: switching stops speech first; the problem text; a finished download switches in
  `auto`.
- `SherpaTts` with a fake `SpeechSynth` and a fake audio sink (no native code in JVM tests):
  - sentences in order, with the queue limit
  - `onStart` and `onDone` timed against the sink (done = played, not generated)
  - `stop()` mid-sentence, and the sink flushed
  - a generation error → `onError`
  - lazy load, the 2-minute unload with a fake clock, and no unload while hands-free is on
  - focus loss
- `VoiceDownloader` against MockWebServer:
  - a normal download; a cut connection resumed with `Range`
  - a hash mismatch (deleted, `Failed`); a tar entry with `..` or an absolute path; an oversized archive; a missing
    expected file; not enough space
  - going off screen pauses and coming back resumes (fake `AppVisibility`)
  - a kill mid-extraction leaves no `voices/<id>/` folder
- The Settings round trip for `speech_voice`. Device (Compose): the Voice section's states and the banner.
- **Not testable in JVM:** the real voice under R8, covered by the spike and the acceptance list.

## Acceptance criteria
- [x] The owner has chosen the voice by ear, from samples. The choice and the licences are recorded in As built.
- [x] **Spike** done first: a release build on `Edith_Release_Test` speaks a sentence **and** the wake word still
      loads with hands-free on. Cite the build log.
- [~] `Pixel_9_API_37`: choose Edith's own voice → Download (progress) → Test voice → a streamed reply is spoken
      sentence by sentence → Stop is immediate → the speed slider changes the pace → a hands-free answer is
      spoken and she doesn't hear herself.
- [x] `auto` with no phone voice (with the owner's OK, disable Google's engine on the emulator with
      `pm disable-user`, then turn it back on afterwards) → Settings offers the download, and after it she speaks.
- [x] **No network for speech:** no requests while she speaks (logcat or `netstats`). Only the download uses the
      network.
- [~] **Speed:** from a sentence completing to the first sound is at most 1.5 s on the emulator (C13's budget). In
      Andarium on the iPhone, measure the real-time factor. If a 15-word sentence takes longer to generate than to
      play, use the "low" voice or a bigger buffer, and write down which.
- [~] `scripts/check.sh --device` passes. The release APK is tested on `Edith_Release_Test`, and then in Andarium on
      the iPhone: download, replies spoken, hands-free answers spoken, no crash, and Edith isn't killed while
      speaking.

([x] done · [~] done except what As built says is not verified · [ ] the owner's)

## Implementation notes (traps)
1. **Prove the library first (§2).** The riskiest parts are the clash with the wake word's ONNX Runtime, and R8 with
   native code. Don't build UI on a library that doesn't load in release.
2. **Measure memory.** Load the model on the emulator and on the iPhone, and record the numbers. If Andarium kills
   Edith while she speaks, use the "low" voice or unload sooner.
3. **`onDone` must mean heard, not generated.** Hands-free starts listening on it.
4. **Never log what she says.** Replies can hold private email or calendar text. Release builds log lengths and
   timings only (as C19 keeps subjects and bodies out of logs).
5. **`espeak-ng-data` is many small files.** Extraction takes a moment; do it off the main thread, with progress.
6. **Docs to update in this PR:**
   - README (chunk status), C13's header (a pointer to this chunk), 00-product §4.2 (it's reworded in the design PR)
   - 01-architecture §2 (`voice/own/`), §5.3 (`speech_voice`), §9 (a download from GitHub; no secrets involved)
   - 05-environment (the fetch script), D21's notes (the chosen voice)

## Not in scope
- A choice between several voices, other languages, voice cloning (one English voice for now).
- Speech from the PC or from Ollama (not possible on Windows, L13).
- Kokoro (too slow on phones).
- Making hands-free *listening* work in Andarium (a separate topic, as C17b says).

## As built

### The voice, the library, the licences
- **Voice (the owner's choice by ear, 2026-11-14):** `en_US-libritts_r-medium`, **speaker 0** (sample 4 of 8; sample 8 was
  the same voice as the 23 MB int8 build, and the owner chose the full one). 22,050 Hz, a download of **82,038,311 bytes**
  (82 MB), SHA-256 `10dc268f…1b1c` (it is also the digest GitHub shows for the release asset), **97 MB on the phone**
  (model 78.5 MB + `espeak-ng-data`). Pinned in `voice/own/OwnVoices.kt`. libritts_r has 904 speakers; Edith uses one.
- **Library:** sherpa-onnx **1.13.8**, `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` (38,691,998 bytes, SHA-256
  `b22c3fc1…5471`, from the GitHub release `v1.13.8`). **Not 2.x:** 2.0 removed the espeak-ng phonemizer that Piper voices
  need (L38). ONNX Runtime is linked in statically, so the AAR adds no `libonnxruntime.so` for arm64-v8a and x86_64 and
  can't clash with `onnxruntime-android` 1.31.0. The release APK went from 33 MB to **51 MB**.
- **Licences** (recorded as the chunk asks; none of this is a problem while Edith stays on the owner's own devices):
  sherpa-onnx **Apache-2.0**; ONNX Runtime **MIT**; Apache Commons Compress **Apache-2.0**; the voice: its model card
  says the dataset is **LibriTTS-R (openslr.org/141), CC BY 4.0**, which needs attribution to the dataset's authors if
  Edith is ever given to anyone; `espeak-ng-data` comes from **espeak-ng, GPL-3.0**. I checked the first three and the
  model card; I did not read the voice's upstream Piper licence text beyond the model card. **Ask the owner before Edith
  goes to anyone beyond their own devices.**

### The spike (done first, on `Edith_Release_Test`, release build, R8 on)
- Wake word (ONNX Runtime 1.31.0 through Java) and the voice (static ONNX Runtime inside `libsherpa-onnx-jni.so`) ran in
  one process: both loaded, the wake word scored before and after speech, no crash. Voice load 463 ms. Log:
  `build-logs/2026-11-14_09-23-56_gradle_assembleRelease.log` (the passing spike APK).
- It found two things that would have broken the release build (L38): the 32-bit and x86 libraries inside the AAR (one is a
  second `libonnxruntime.so`) stop the native-library merge, so `packaging` excludes them; and sherpa's native code calls
  the audio callback by name, which R8 renamed (`NoSuchMethodError … invoke([F)Ljava/lang/Integer;`, SIGABRT), so the
  callback is the named class `SherpaCallback` with a keep rule.

### What was built
- `voice/own/`: `SherpaTts` (the `TtsEngine`: a generator thread and a player thread; at most 3 sentences generated ahead;
  `onStart` once the first 4,096-sample slice reaches the speaker; `onDone` only when the speaker's play position has
  reached the end of the sentence; `stop()` bumps an epoch, drops the queues and flushes the speaker, and audio of an
  older epoch can't reach a new one), `SpeechSynth`/`SherpaSynth`, `AudioSink`/`AudioTrackSink`, `SpeechFocus`
  (focus exactly as `AndroidTts`), `VoiceDownloader`, `VoiceArchive`, `OwnVoices`, `SherpaCallback`.
- `voice/speech/ChoosingTts.kt`: `chooseVoice` (the pure rule), `ChoosingTts`, the problem texts. `SpeechOutput` now takes
  `ChoosingTts`; `AndroidTts` and `SpeechOutput` are unchanged otherwise.
- Settings: `speech_voice` (`SpeechVoice`: auto / phone / own) and `voice_banner_dismissed`. Settings → Voice: the three
  options, Edith's own voice's state with Download / Cancel / Delete / Try again, "Your phone has no voice. Edith can use
  her own." with Download beside it, Test voice. Chat: the dismissible "I can't speak yet" banner above the chat.
- `scripts/fetch-sherpa.sh` (pinned version and SHA-256; `check.sh`, `run.sh`, `release.sh` and CI run it; CI caches
  `app/libs`), a Gradle message when the file is missing, `app/libs/` git-ignored.
- **Unload:** the model is loaded on the first sentence (about 0.4–0.5 s) and unloaded after 2 minutes without speech unless
  hands-free is on (`SherpaTts.unloadIfIdle`, checked every 5 s from the player thread).

### Differences from the design
- **No `SpeechNormalizer`.** I can't listen. The owner heard sample 4, which reads "53°F", "40%", "3:00 PM" and "Fri Nov 14",
  and chose it; if any of those sounds wrong in use, the normaliser is a small change (C36 §1).
- **A phone voice that needs the network** is treated as the phone's voice exactly as before (`AndroidTts.preferOfflineVoice`
  only prefers an offline one); I did not make it count as unavailable, because the chunk says "as now".
- **Cancel** in the download throws away the partial file (Try again after a failure keeps it and resumes).
- **Retries:** a dropped connection is retried 3 times (2 s apart), each with `Range`, before it shows "I couldn't reach the
  download"; the file stays, so Try again resumes.
- The speed argument is sherpa's `speed`: larger is faster (checked below).

### Verified
- `scripts/check.sh`: passes, 461 unit tests (44 of them new) and lint clean (`build-logs/2026-11-14_09-43-11_check.log`). New: ChoosingTts (the rule for all 12 combinations, switching, problem texts), SherpaTts with
  fake voice, speaker, focus and clock (order, done = played, queue limit of 3, stop mid-sentence, errors, lazy load,
  unload and not while hands-free, focus), VoiceDownloader against MockWebServer (normal, cut connection resumed with
  `Range`, bad hash, `..`, absolute path, drive letter, link, missing file, two models, too many entries or bytes, not
  enough space, 404, off screen and back, Cancel, a half-unpacked folder left by a kill), the settings round trip.
- `scripts/check.sh --device`: OK, 121 Compose UI tests on `Pixel_9_API_37` (`build-logs/2026-11-14_10-02-00_check.log`), including the new `VoiceSettingsTest` cases.
- `Pixel_9_API_37` (debug): Settings → Voice → Download (progress shown, 97 MB on disk, hash checked, unpacked) → chose
  Edith's own voice → Test voice spoke → Play on a stored message spoke sentence by sentence (3 sentences generated ahead,
  one `AudioTrack` with the assistant/speech attributes in `dumpsys audio`) → **Stop** abandoned audio focus within about
  half a second → the **speed** slider changed the length of the same line from 1.46 s (0.6×) to 0.93 s (1.0–1.1×) to 0.62 s
  (1.6×), so larger is faster. Real-time factor on the emulator about 0.04–0.06 (93 characters generated in 275 ms for 5.4 s
  of audio); first sound **135 ms** after a sentence is queued with the model loaded, **about 0.5 s** with the load
  (budget 1.5 s). Model load 0.37–0.51 s; native memory +96 MB.
- **`auto` with no phone voice:** with Google's text-to-speech disabled (`pm disable-user`, then enabled again and checked):
  Test voice greyed out; Chat showed "I can't speak yet. Get my voice in Settings → Voice." (inset correct under the
  status bar); Settings showed "Your phone has no voice. Edith can use her own." with Download; after the download Test
  voice spoke in `auto`. Speed was set back to 1.0 and the voice left downloaded.
- **Release build on `Edith_Release_Test`** (`dist/Edith-2.0.0-dev.4.apk`, built with `scripts/release.sh --allow-dirty`,
  `build-logs/2026-11-14_09-53-41_release.log`): download, Test voice, hands-free **on** (wake word loaded, "Listening for
  Hey Edith") and a stored reply played aloud, no crash (`logcat -b crash` empty). With hands-free on and the voice
  loaded the app's total PSS was about 300 MB (native heap about 230 MB).
- **No network for speech:** with the emulator in airplane mode (`ping`: "Network is unreachable") a stored reply was
  generated and played normally. Only `VoiceDownloader` touches the network.

### Not verified, and why
- **A streamed reply from Ollama, spoken as it streams:** the owner's 12B model timed out ("Edith took too long to answer",
  as it had before this change), so I could not stream one. The streaming path is C13's `SpeechOutput`, unchanged; the
  same sentence-by-sentence pipeline was exercised through Play.
- **Hands-free "doesn't hear herself" with a real microphone:** not checked (like C14, it still needs a real-mic check;
  the emulator's mic can't say "Hey Edith"). `onDone` is tied to the speaker's play position, which is what hands-free
  needs, and `HandsFree` is unchanged.
- **Andarium on the iPhone** (the owner's check, needs a release published first): download, replies spoken, hands-free
  answers spoken, no crash, Edith not killed while speaking, and the **real-time factor** (does a 15-word sentence take
  longer to generate than to play?). Measure it from the log line "Own voice: N chars generated in X ms, Y ms of audio". If
  it is too slow or Android kills Edith there, the fallbacks are the "low" voice (`en_US-amy-low`, sample 7), the int8
  build of this voice (23 MB, sample 8), or unloading sooner. The memory to compare: +96 MB native for the model.
- `Edith_Release_Test` ran the x86_64 library; the arm64-v8a library is in the APK but has not run on arm64 here.
