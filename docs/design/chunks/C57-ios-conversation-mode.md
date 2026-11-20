# C57 — Conversation mode on iOS (hands-free without a wake word)

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave A |
| Status | **Design** (decision D30: conversation mode first, the wake word later in C69) |
| Depends on | C44 (voice memos: recorder, transcriber, `VoiceMemos`), C40 (speech output), C45 |
| Unblocks | C69 (the wake word plugs into the same state machine) |
| Runs in | Expo Go |
| Size | Large |
| Ports | C14 (the state machine, follow-ups, privacy, settings) **minus** the wake word, the foreground service and barge-in |
| Read first | C14 spec **and** As built, C12 As built (`VoiceMemos` coordinator, the silence rule, the 5-minute limit), C13 (`speakingMessageId`, speech stops when the owner records), `04-lessons-learned.md`, `ios-roadmap.md` §4 (iOS has no foreground services) |

## Why
The owner wants to talk to Edith without tapping for every message. Android does it with a wake word, a foreground service and
Android's recognizer. **None of those is available in Expo Go**, and iOS suspends an app that leaves the screen. What *is*
possible, and useful on its own: tap **Conversation** once; Edith listens; when you stop talking she transcribes, answers
aloud, and listens again for a follow-up for a few seconds; silence ends the conversation. The app stays open with the screen on
(like using Siri in the car with the phone propped up). It also builds the state machine C69 needs.

## Specification

### 1. States (`voice/conversation.ts`, pure state machine with injected parts)
```
Off ──start──► Listening ──speech start──► Capturing ──speech end──► Transcribing ──text──► Thinking ──reply starts──► Speaking
 ▲                 ▲                                                    │ (silence)                                  │
 │                 └── follow-up window expires ◄── FollowUp ◄──────────┴────── reply finished (speech done) ◄───────┘
 └── stop / error ×3 / app leaves screen / call / another app takes the mic
```
- **Listening:** the microphone is open (via `Recorder`), the meter is watched for **speech start** (level above a threshold for ≥ 250 ms).
- **Capturing:** recording continues; **speech end** = level below the threshold for `endSilenceMs` (default 1100 ms) after at least 400 ms of speech. Hard limit 60 s per utterance.
- **Transcribing:** the recording is sent to the model exactly as a C44 memo (`OllamaTranscriber`), file deleted afterwards. `[NO_SPEECH]` or empty → back to Listening quietly.
- **Thinking/Speaking:** the transcript is sent with `engine.send(conversationId, text, 'hands_free')` (extend `MessageSource` use: it already includes `'hands_free'`). The reply is spoken by `SpeechOutput` as for typed messages (the owner's speech setting must be on in conversation mode; if it is off, the mode **turns speaking on for the session and says so**).
- **No barge-in** in this chunk: the microphone is **closed while Edith speaks** (her voice would be recorded). A big **Stop** button stops speech and returns to Listening. (Barge-in needs iOS voice processing/echo cancellation; deferred to C69.)
- **FollowUp:** after speech finishes, listen for `followUpSeconds` (setting, default 8, range 0–15) with the same Listening/Capturing rules; silence for that long ends the conversation (`Off`).
- **Errors:** the recorder failing, the transcriber failing, or a model error → one short spoken-free notice and back to Listening; **three in a row → Off** with a message.

### 2. Voice activity detection (`voice/vad.ts`, pure and unit-tested)
Input: a stream of levels 0..1 (the `Recorder.level()` already maps dBFS −60..0) sampled every 100 ms (`expo-audio` `getStatus().metering`, `isMeteringEnabled: true`).
- **Adaptive threshold:** the first 700 ms of each Listening period measures the ambient floor; speech = level > max(0.15, floor + 0.12). Constants in one place, tested with synthetic level sequences (silence, a cough of 150 ms (ignored), speech with a pause of 600 ms inside (not an end), speech then silence (an end), constant noise (never starts)).
- It is a heuristic: say in the PR that real rooms will need tuning, and expose **Sensitivity** (0–1) in settings that shifts the threshold margin.

### 3. Keeping the app alive and the phone awake
- `expo-keep-awake` (`activateKeepAwakeAsync('conversation')`) while the mode is on; deactivate when it ends.
- Audio session: `setAudioModeAsync({ allowsRecording: true, playsInSilentMode: true })` while listening (C44's `ExpoRecorder` already does this) and back to playback for speaking. **Check on the device** that speech after recording uses the loudspeaker, not the earpiece (C44's open item), and that the ring/silent switch doesn't mute Edith (record the result in As built).
- **Leaving the app** (AppState not `active`) or the screen locking **ends the mode** (`Off`) with the notice "Conversation stopped when you left the app." (No background audio in Expo Go; C69 may add `UIBackgroundModes: audio`.)
- **Phone calls / Siri / another app taking the mic:** treat an audio-session interruption as stop. `expo-audio` has no interruption event in all SDKs: poll the recorder's status; if `isRecording` goes false unexpectedly, end the mode.

### 4. UI
- A **Conversation** button in the chat header (ear icon `ear-outline`, label "Conversation mode"). While on: a banner under the header with the state text — "Listening…", "Hearing you…", "Transcribing…", "Thinking…", "Speaking…", "Listening for a follow-up…" — a level meter, and a large **End** button. The input row is disabled except Stop-speaking while the mode is on.
- Messages created this way show a small ear icon in the bubble (source `hands_free`).
- Settings → **Conversation**: switch "Speak replies during conversation" (see above), **Follow-up window** (0–15 s), **Sensitivity** slider, **Pause length** (0.7–2.0 s), "Test" (shows the live meter and a check when speech is detected, as C14's wake-word test did).
- First start shows iOS's microphone permission (already requested by C44; reuse the denied → Open settings notice).

### 5. Privacy
Audio is recorded to a temp file, sent only to the owner's Ollama as a transcription request, then deleted (C44 rule: **always deleted, success or failure**). The microphone is never live without the banner. Nothing is stored except the transcript as a normal message.

### 6. Safety rules carried over
Anything that needs approval still shows a **card on screen** and **waits for a tap** — conversation mode **must not approve by voice** (D10, C14 As built: "hands-free has no way to confirm by voice, and while hands-free is on Edith says to tap it"). While a card waits in this mode, Edith **says once** "Tap Confirm on the screen" (`SpeechOutput.announce`-like call) and the state shows "Waiting for your tap".

## Files
New: `voice/conversation.ts` (state machine), `voice/vad.ts`, `ui/ConversationViews.tsx`, tests `voice/conversation.test.ts`, `voice/vad.test.ts`, `ui/conversation.test.tsx`. Changed: `voice/voiceMemos.ts` (extract a small `UtteranceCapture` that both memos and conversation use: start, `level()`, stop → audio + duration), `core/services.tsx` (`conversation`), `ui/ChatScreen.tsx`, `ui/ChatComponents.tsx` (header button, ear icon), `settings/settings.ts` (`followUpSeconds`, `conversationSensitivity`, `conversationPauseMs`, `speakDuringConversation`), `ui/SettingsScreen.tsx`, `package.json` (`expo-keep-awake`).

## Tests
- State machine with fake recorder/transcriber/speech/clock: Listening → Capturing → Transcribing → Thinking → Speaking → FollowUp → Listening; silence in follow-up ends; empty transcript returns to Listening; three errors end it; leaving the app ends it with the notice; Stop during speech returns to Listening; a waiting card pauses the loop and speaks the tap prompt once; the temp file is deleted in every path.
- VAD with synthetic sequences (above), including the adaptive floor.
- UI: the banner texts per state; the End button; the header button disabled while a reply is running.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner, on the iPhone (the first real-microphone test of anything voice):** Conversation → "What's the weather in Boston?" → spoken answer; a follow-up without tapping works; 8 s of silence ends it; locking the screen ends it; an unlock request in this mode shows the card and Edith asks for the tap.
- [ ] Record: does Edith's speech come from the loudspeaker; does the silent switch mute her; how often does the VAD cut the owner off or wait too long (tune the defaults with the owner).

## Risks and unverified assumptions
- Metering-based VAD is crude; rooms with TV noise will fail. If it is unusable, C69 (with a real wake word) is the answer, or add `SFSpeechRecognizer` end-of-speech in a development build (the recognizer detects the end itself).
- `expo-audio` metering while recording to a file on iOS (`isMeteringEnabled`): C44 used it for the meter; verify the update rate.
- Opening/closing the microphone between turns each costs ~100–300 ms; fine.

## Not in this chunk
The wake word, background listening, barge-in, Bluetooth headset routing (verify but don't promise), a lock-screen widget.
