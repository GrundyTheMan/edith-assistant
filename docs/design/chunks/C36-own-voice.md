# C36 — Edith's own voice (on-device speech for phones with no voice)

| | |
|---|---|
| Phase | 3 — Voice (added 2026-11-14) |
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
- [ ] The owner has chosen the voice by ear, from samples. The choice and the licences are recorded in As built.
- [ ] **Spike** done first: a release build on `Edith_Release_Test` speaks a sentence **and** the wake word still
      loads with hands-free on. Cite the build log.
- [ ] `Pixel_9_API_37`: choose Edith's own voice → Download (progress) → Test voice → a streamed reply is spoken
      sentence by sentence → Stop is immediate → the speed slider changes the pace → a hands-free answer is
      spoken and she doesn't hear herself.
- [ ] `auto` with no phone voice (with the owner's OK, disable Google's engine on the emulator with
      `pm disable-user`, then turn it back on afterwards) → Settings offers the download, and after it she speaks.
- [ ] **No network for speech:** no requests while she speaks (logcat or `netstats`). Only the download uses the
      network.
- [ ] **Speed:** from a sentence completing to the first sound is at most 1.5 s on the emulator (C13's budget). In
      Andarium on the iPhone, measure the real-time factor. If a 15-word sentence takes longer to generate than to
      play, use the "low" voice or a bigger buffer, and write down which.
- [ ] `scripts/check.sh --device` passes. The release APK is tested on `Edith_Release_Test`, and then in Andarium on
      the iPhone: download, replies spoken, hands-free answers spoken, no crash, and Edith isn't killed while
      speaking.

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
