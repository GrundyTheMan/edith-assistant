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
- [ ] On a phone (preferred) or emulator with host mic: "Hey Edith, what's the weather in Boston?" → answer spoken;
      then "and tomorrow?" without the wake word → answer. Record a short screen video for the PR if possible.
- [ ] Pause/Stop from the notification work; the mic indicator disappears when stopped.
- [ ] Incoming call (emulator: Extended controls → Phone) stops hands-free cleanly.
- [ ] No crash/ANR over a 30-minute listening session; note battery/CPU usage.

## Risks to report honestly
Wake-word accuracy varies by device and room; the emulator can't judge it. Some devices don't allow two audio
captures at once (barge-in may need to be disabled there — detect and degrade gracefully).
