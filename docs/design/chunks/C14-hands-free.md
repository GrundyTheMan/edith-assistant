# C14 — Hands-free mode

| | |
|---|---|
| Phase | 3 |
| Depends on | C12, C13, C06 |
| Size | Large (highest platform risk in v1) |
| Read first | 06-decisions **D4** (wake-word engine — ask the owner first); 01-architecture §9; 05-environment §3 |

## Goal

With hands-free on, the owner says "Hey Edith", asks something, hears the answer, and can follow up within a
few seconds without the wake word — no tapping. Works with the app open; optionally while the screen is off
via a foreground service. Battery- and privacy-conscious; obvious when the mic is live.

## Before you start
1. Ask the owner D4. Recommended: **Picovoice Porcupine** (on-device, reliable, custom "Hey Edith" keyword
   from the Picovoice Console; requires a free AccessKey — the owner creates it; store via `SecretStore`).
   Verify current SDK version, licence terms for personal use, and keyword training steps from Picovoice's docs.
   Alternatives: openWakeWord (ONNX Runtime, open source), Vosk keyword spotting.
2. Be explicit with the owner that the emulator mic needs host audio input enabled, and that real-world
   accuracy must be judged on a phone.

## Specification

### 1. States
```
Off ──enable──► Listening(for wake word) ──wake word──► Capturing(utterance) ──end of speech──► Thinking
 ▲                     ▲                                                                    │
 │                     └──────── follow-up window expires ◄── FollowUp(listening, no wake) ◄─┘ (after Speaking)
 └──disable / error / phone call / another app takes the mic
```
- **Listening:** wake-word engine consuming 16 kHz PCM from `AudioRecord` (shared audio source owned by the service).
- **Capturing:** after the wake word, play a short earcon (`ToneGenerator`, 120 ms), then capture the utterance with
  Android `SpeechRecognizer` (fast, on-device when available, built-in end-of-speech detection) — release the
  wake-word `AudioRecord` first (only one client can hold the mic reliably), re-acquire afterwards.
- **Thinking/Speaking:** send via `ChatEngine.send(text, MessageSource.HandsFree)`; C13 speaks the reply.
  **Barge-in:** keep the wake-word engine running while speaking if the device allows concurrent capture; on
  wake word, stop speech and capture a new utterance.
- **FollowUp:** after speech finishes, listen for `follow_up_seconds` (default 8) with `SpeechRecognizer`
  directly (no wake word). Silence → back to Listening.
- Recognizer "no match"/silence → back to Listening quietly. Repeated errors (3 in a row) → Off with a notification.

### 2. Foreground service (`voice/handsfree/HandsFreeService`)
- `foregroundServiceType="microphone"`; permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`,
  `POST_NOTIFICATIONS` (13+), `RECORD_AUDIO`. Must be **started while the app is in the foreground** (Android 14+
  restriction for while-in-use permissions) — i.e. from the toggle in the app.
- Ongoing notification: "Edith is listening for 'Hey Edith'" with **Pause** and **Stop** actions; tapping opens the chat.
- Stops on: toggle off, Stop action, incoming/ongoing call (`AudioManager` mode / `TelephonyCallback`), another app
  starting recording (audio recording callback), low battery saver if the owner opts in.
- Battery: no wake locks beyond what the service needs; document measured battery drain per hour on a phone if possible.

### 3. UI
- Chat screen top bar (or a chip above the input): hands-free toggle (ear icon, "Hands-free") with state text
  "Listening for 'Hey Edith'" / "Listening…" / "Thinking…" / "Speaking…".
- Settings → Hands-free: enable switch, wake-word sensitivity slider (0–1, engine-specific mapping),
  follow-up window (0–15 s), wake-word engine status / AccessKey field (via `SecretStore`), "Test wake word" button
  that shows a check mark when detected.
- Messages created hands-free show a small ear icon (source `hands_free`).

### 4. Privacy
- Audio for wake-word detection is processed on the phone and never stored or sent. Only the recognised
  utterance text is sent to Ollama (as a normal message).
- The mic is never live without the notification and the in-app indicator.

## Tests
- State machine unit tests with fake engine/recognizer/speech: wake → capture → send → speak → follow-up → listening;
  silence in follow-up returns to listening; errors ×3 → off; barge-in stops speech.
- Service lifecycle test (Robolectric or instrumented): start/stop, notification actions.

## Acceptance criteria
- [ ] **Not done: needs a phone or the emulator's host mic.** On a phone (preferred) or emulator with host mic: "Hey Edith, what's the weather in Boston?" → answer spoken;
      then "and tomorrow?" without the wake word → answer. Record a short screen video for the PR if possible.
- [x] Pause/Stop from the notification work; the mic indicator disappears when stopped.
- [x] Incoming call (emulator: Extended controls → Phone) stops hands-free cleanly.
- [x] No crash/ANR over a 30-minute listening session; note battery/CPU usage (CPU ~2% of a core on the emulator; battery needs a phone).

## Risks to report honestly
Wake-word accuracy varies by device and room; the emulator can't judge it. Some devices don't allow two audio
captures at once (barge-in may need to be disabled there — detect and degrade gracefully).

## As built (2026-11-01)

Where the code differs from the specification above, the code is right.

**D4, the wake word:** openWakeWord, with a custom "Hey Edith" model the owner trains once in Colab
(`docs/wake-word-training.md`). Picovoice was ruled out: its free tier ended on 2026-06-30 (see D4). Until the owner's
`hey_edith.onnx` is in `app/src/main/assets/wakeword/`, the app uses openWakeWord's "Hey Jarvis" model as a stand-in
and says so in Settings. `WakeWordModel.find` picks whichever is there; no code change is needed to switch.

**Files** (`voice/handsfree/`)
- `WakeWord.kt`:
  - `WakeWordDetector`, and `WakeWordModel` (which phrase this build has).
  - `OpenWakeWord`: openWakeWord's three ONNX models (mel spectrogram → embedding → wake word) through ONNX Runtime
    1.31.0, with openWakeWord's own numbers (80 ms chunks, 480 samples of overlap, 76 mel frames, 16 embeddings).
- `WakeListener.kt`: `WakeListener`, and `MicWakeListener` (`AudioRecord` on its own thread; the models load there,
  not on the main thread).
- `HandsFree.kt`: the state machine, app-scoped.
- `HandsFreeService.kt`: the microphone foreground service and its notification. It stops hands-free for a call or
  when another app takes the mic.

Also:
- Assets `wakeword/`: `melspectrogram.onnx`, `embedding_model.onnx`, `hey_jarvis_v0.1.onnx` (openWakeWord v0.5.1,
  ~3.7 MB).
- `chat/ui`: the ear button in the top bar, the status line under it, and the ear by hands-free messages. Record and
  dictation are off while hands-free is on; hands-free can't start during a memo or dictation.
- `ui/settings/HandsFreeSettings.kt`: Settings → Hands-free.
- Manifest: `FOREGROUND_SERVICE_MICROPHONE` and the service.
- Build: ONNX Runtime's native library is ~35 MB per CPU type, so the APK keeps only `arm64-v8a` (phones) and
  `x86_64` (the emulator), and native libraries are compressed (`useLegacyPackaging`).
  - Debug APK: 165 MB → 55 MB. Release APK: 33 MB, of which ONNX Runtime is ~30 MB.
- `ReplyListener`/`SpeechOutput` from C13 are used as they are. `ChatEngine.cancel` is used for barge-in.
- `src/debug`: `DebugHandsFreeReceiver` (`wake` scores a WAV with the real models; `say` stands in for the recognizer).

**Differences from the spec**
- **Barge-in also stops the reply** if it's still being written (what she wrote is kept, marked stopped). Otherwise
  the next question got "Edith is still answering".
- **The wake word is listened for while she speaks only, not while she's thinking.**
- **The follow-up window restarts the recognizer.** Android's recognizer gives up on silence after a few seconds;
  the window (default 8 s) is longer, so an empty result is retried (after 250 ms) until the window ends. Talking
  closes the timer.
- **A busy engine** (a typed message being answered) isn't interrupted: "Edith is still answering — ask again when
  she's done." Then it goes back to the wake word.
- **The microphone is never shared:** the wake-word listener stops (and releases the mic) before the recognizer
  starts, and starts again after.
- **Calls:** `AudioManager.OnModeChangedListener` (Android 12+; polled every 2 s before that), so no phone
  permission is needed. Ringing, in a call, or in a VoIP call → "Hands-free stopped for a call."
- **Another app taking the mic:** Android silences Edith's recording (Android 10+), and hands-free stops.
- **Settings:**
  - No AccessKey (openWakeWord needs none).
  - No "enable" switch: the ear button in Chat turns it on and off, because the service must be started from the
    screen anyway. `Settings.handsFreeEnabled` is unused.
  - Hands-free is off when the app starts.
  - Sensitivity 0..1 maps to openWakeWord's score threshold: 0.9 − 0.8 × sensitivity (0.5 → its default of 0.5).
- **Low battery saver opt-in: not built.** The listener costs little (see below); add it if a phone shows otherwise.
- **The notification asks for notification permission** on the first hands-free tap (Android 13+), like the first
  Send. The mic still works without it.

**Verified**
- `scripts/check.sh --device`: OK, 148 unit tests, lint clean, 80 device tests. Log:
  `build-logs/2026-11-01_14-42-11_check.log`. New tests:
  - `HandsFreeTest` (JVM, 14): wake → capture → send → speak → follow-up; follow-up silence, a restarted recognizer
    and talking past the timer; follow-ups off; nothing heard; 3 errors → off; reset on success; barge-in (voice
    and reply stopped); stop/pause/resume; busy engine; a mic that won't open or fails; the debug sentence; the
    threshold.
  - `OpenWakeWordDeviceTest`: the real models on the device. "Hey Jarvis" (two Windows voices) scores ≥ 0.5, other
    speech and silence < 0.1, and scoring is faster than real time.
  - `ChatScreenTest.handsFreeButtonStatusAndEar` and `HandsFreeSettingsTest`.
- **Live on the emulator** (`gemma4:12b`, "Hey Jarvis" stand-in, through the debug hook because the emulator's mic
  hears silence):
  - "Hey Jarvis" WAVs scored 0.994–0.999; a different sentence scored 0.000.
  - **"Hey Jarvis" → "what's the weather in Boston"** → `get_weather` Boston, spoken (179 ms after the first
    sentence); then **"and tomorrow?" without the wake word** → `get_weather` Boston, 1 day, spoken. Both stored with
    the ear (`hands_free`). Then 8 s of silence → back to the wake word.
  - **Barge-in:** "Hey Jarvis" during a long story → her voice and the reply stopped at once; "what time is it" →
    answered and spoken.
  - **Notification:** "Listening for “Hey Jarvis”" with Pause and Stop. **Pause** → "Paused. The microphone is off."
    and Android's mic indicator disappears; **Resume** → listening; **Stop** → notification and mic indicator gone.
  - **Incoming call** (`adb emu gsm call`) → "Hands-free stopped for a call.", notification gone.
  - **Screen off, app in the background:** wake → question → answer spoken. Google's on-device recognizer ran in the
    background (it returned "no match" for the silent mic, not a permission error).
  - **CPU while listening (found and changed):**
    - The first 30-minute session ran clean: no crash or ANR, mic live, notification up, memory flat (~163 MB PSS).
      But Edith used **~26% of one core** all the time.
    - The cause was ONNX Runtime's default thread pool, which spin-waits between runs. With one thread per model
      and spinning off (`OpenWakeWord.sessionOptions`), it's **~2% of one core** with the same scores.
  - **30-minute listening session on the final build** (app in the background):
    - ~600 CPU ticks per 5 min, **~2% of one core**, steady.
    - Memory flat at ~158 MB PSS; the process, the recording and the notification stayed up.
    - No crash, no ANR.
    - Afterwards "Hey Jarvis" still woke it, and it went back to listening.
    - Battery on a phone isn't measured.
- **Not verified:**
  - **The real microphone path end to end:** wake-word accuracy in a room, and the recognizer hearing real speech.
    The emulator's mic hears silence. This needs a phone (or the emulator's host microphone).
  - **"Hey Edith" itself:** the owner's model doesn't exist yet.
  - **Battery on a phone.**
  - **Another app taking the mic:** not reproduced; Android's callback is wired but untested.
- **An emulator system crash during the first full check (not Edith):**
  - At 13:12:52 `system_server`'s watchdog fired during `ChatScreenTest.sendingClearsTheFieldAndTheReplyArrives`, an
    older test that doesn't use hands-free, and the system restarted ("System has crashed").
  - The watchdog asked `surfaceflinger` for a dump. The emulator had run 4 hours, was swapping (720 MB), and its
    graphics composer had used ~17 min of CPU.
  - The traces in `/data/anr` aren't readable without root, so the blocked thread isn't known.
  - A rerun right after had two slow-emulator timeouts in older chat tests. After a cold boot the full check passed.
    Same pattern as C10's emulator graphics hang.
