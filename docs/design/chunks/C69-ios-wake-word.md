# C69 — "Hey Edith" on iOS: the wake word while the app is open

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **Design** — begins with a spike that decides whether the plan below is feasible; ask the owner at each fork |
| Depends on | C57 (the conversation state machine, VAD, keep-awake), C58 (development build with the native modules below), C44 (recorder, transcriber) |
| Unblocks | — |
| Runs in | the development build only |
| Size | **XL** |
| Ports | C14 (the wake word, barge-in, follow-ups) and D4 |
| Read first | C14 spec **and** As built (the openWakeWord pipeline numbers, the sensitivity mapping, the false-trigger lessons, `OpenWakeWordDeviceTest`), D4, `docs/wake-word-training.md` and `scripts/wakeword/`, `app/src/main/assets/wakeword/` (the models: `melspectrogram.onnx`, `embedding_model.onnx`, `hey_edith.onnx`, plus `hey_jarvis_v0.1.onnx` used for testing), C57 (what already exists) |

## Why
C57 lets the owner start a conversation with a tap. The wake word ("Hey Edith") lets them start one by voice. On Android this is
a foreground service listening in the background. **iOS does not allow an app to listen once it is suspended**; the options are
(1) listen **only while Edith is on screen** (screen on, app open: a phone propped up, a car dock), or (2) hold an active audio
session with `UIBackgroundModes: audio` so the microphone keeps running when the screen locks (iOS shows the orange microphone
indicator the whole time, and battery use is real). The owner decides (**D35**, recommended: **1 first**, then 2 only if they want
it after trying 1).

## Before you start
1. **Owner:** decides D35; confirms they are happy with the microphone indicator if 2.
2. **Spike (1–2 days; report before building the rest).** In a throwaway screen of a development build:
   a. **Continuous microphone PCM** at 16 kHz mono in real time. `expo-audio` records to files and has no raw-sample stream: candidates are `react-native-audio-api` (an `AudioRecorder` with an `onAudioReady` callback) or `react-native-live-audio-stream` (PCM chunks as Base64 events). Measure: chunk size, latency, CPU, whether it coexists with `expo-audio` playback (speech) on iOS (`AVAudioSession` category `playAndRecord`).
   b. **ONNX Runtime on iOS:** `onnxruntime-react-native` (Microsoft) with its Expo config plugin; load `melspectrogram.onnx`, `embedding_model.onnx`, `hey_edith.onnx` (ship them as assets via `expo-asset`; ~2 MB total: check). Run the three-model pipeline on a recorded "Hey Edith" WAV and on "Hey Jarvis" and compare with Android's scores (≥ 0.99 for the right phrase, ~0 for other speech). Measure time per 80 ms chunk (must be well under 80 ms).
   c. If either fails, **stop and tell the owner**; the fallback is "no wake word on iOS: C57's tap-to-start conversation is the feature".
3. Add the confirmed libraries to the batch for the next EAS build (C58 rules: ask first).

## Specification (only if the spike succeeds)

### 1. The detector (`voice/wakeword/openWakeWord.ts`)
Port Android's `OpenWakeWord` exactly (C14 As built: "openWakeWord's own numbers: **80 ms chunks, 480 samples of overlap, 76 mel frames, 16 embeddings**"): feed 16 kHz mono Int16 chunks; keep the rolling buffers; run mel spectrogram → embedding (every 8 mel frames) → the wake-word model on the last 16 embeddings; score per chunk. Pure TypeScript around an injected `OrtSession` interface (`run(inputs): Promise<Float32Array>`) so the buffering logic is **unit-tested with fake sessions**, and the real ONNX path is tested on the device only. The sensitivity setting maps to the score threshold as Android does: `threshold = 0.9 − 0.8 × sensitivity` (0.5 → 0.5). Require the score above the threshold for **2 consecutive chunks** and ignore re-triggers for 2 s (the false-trigger lessons).

### 2. Where it plugs into C57's state machine
`Listening(for wake word)` is a new state before `Capturing`: the microphone stream feeds the detector; on a hit play a short earcon (a 120 ms tone: `expo-audio` player with a bundled tiny WAV asset), then **Capturing** with C57's VAD, **Transcribing**, **Thinking**, **Speaking**, **FollowUp** as there. The detector uses the **same microphone stream**; **barge-in**: keep the detector running while Edith speaks **only if** the spike shows iOS voice processing/echo cancellation (`AVAudioSession` mode `voiceChat`) prevents her own voice from triggering it; otherwise no barge-in (C57's Stop button remains).

### 3. Scope of listening (D35)
- **Option 1 (default):** active only while Edith is in the foreground with the screen on (C57's keep-awake). Leaving the app ends it with the notice.
- **Option 2:** `ios.infoPlist.UIBackgroundModes: ['audio']` (a native change: the app config, needs a new build), the audio session stays active, the detector keeps running with the screen locked; **Stop** is a persistent control; the in-app indicator "Edith is listening for 'Hey Edith'" is always visible. Provide **Settings → Conversation → Keep listening when the screen is locked** (off by default). Measure battery per hour on the phone and tell the owner.
- A call, Siri, or another app taking the microphone ends listening cleanly (audio-session interruption notification; the audio library may expose it: if not, poll).

### 4. Settings → Conversation
Hands-free switch ("Listen for 'Hey Edith'"), **Sensitivity** (0–1), **Follow-up window** (C57), **Test wake word** (shows a live score bar and a check mark when detected), the model's status ("Hey Edith model loaded").

### 5. Privacy
Wake-word audio is processed on the phone in memory and never stored or sent; only the recognised utterance text reaches Ollama (as a normal message). The microphone is never live without the in-app indicator.

## Files
New: `voice/wakeword/{openWakeWord,pcmSource,ort}.ts`, `assets/wakeword/*.onnx` (copy from the Android assets; they are binary files: **commit them with `.gitattributes` `binary`**; note the licence in the PR: openWakeWord's shared models are CC BY-NC-SA 4.0, fine for a personal app, D4), tests. Changed: `voice/conversation.ts`, `settings`, `ui` (Settings → Conversation), `package.json`, `app.config.ts`/`app.json` (background mode if D35 = 2), `NATIVE_CHANGES.md`.

## Tests
Detector logic with fake ORT sessions (buffer sizes, overlap, 76 frames/16 embeddings windows, threshold mapping, 2-chunk rule, debounce); a **recorded-sample test** that is skipped under Jest and run on the device (a debug screen: "Run wake-word self-test" on bundled WAVs of "Hey Edith"/"Hey Jarvis"/other speech/silence; reports scores and time per chunk); state-machine tests extended (wake → capture → …; a false trigger with no speech after the earcon returns to Listening; interruption ends it).

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green; the on-device self-test reports the Android-equivalent scores and faster-than-real-time speed.
- [ ] **Owner, on the iPhone:** "Hey Edith, what's the weather in Boston?" → spoken answer; the follow-up without the wake word works; false triggers in a day of normal use (count them and record the number); battery use per hour (if D35 = 2).

## Risks and unverified assumptions
- Real-microphone accuracy of the custom "Hey Edith" model is **still unjudged on Android** (C14 acceptance is open). Expect to retrain (`docs/wake-word-training.md`).
- Coexistence of continuous capture with speech playback (`AVAudioSession`) on iOS.
- App Store review is irrelevant (personal builds), but keep the orange-indicator honesty.

## Not in this chunk
A foreground-service equivalent, Siri/Shortcuts integration (a possible separate chunk: an App Intent "Ask Edith" that opens the app in conversation mode; not planned unless asked).
