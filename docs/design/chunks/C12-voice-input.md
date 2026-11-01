# C12 — Voice input: voice memos and dictation

| | |
|---|---|
| Phase | 3 — Voice |
| Depends on | C03, C06, C07 |
| Unblocks | C14 |
| Size | Medium |
| Read first | 03-ollama-reference §5; 04-lessons L11, L12; 05-environment §3 (emulator mic) |

## Goal

Two ways to talk instead of type:
1. **Voice memo** (record button): record → transcribe (Ollama if the model hears audio, else on-device) →
   the transcript is sent as the user's message (source `voice_memo`).
2. **Dictation** (mic inside the text field): Android's speech recognizer fills the text field live; the owner
   edits and sends (source `dictated`).

## Specification

### 1. Permission
`RECORD_AUDIO`, requested on first use of either button with a one-line rationale. Denied → inline message
"Edith needs microphone access to hear you. You can allow it in Settings → Apps → Edith." with an
**Open settings** action. Never crash when denied (v1 crashed in this case at first).

### 2. `AudioRecorder` (`voice/capture/`)
- `AudioRecord`, `MediaRecorder.AudioSource.VOICE_RECOGNITION` (falls back to `MIC`), 16 kHz, mono, PCM 16-bit.
- Writes a WAV to `cacheDir/recordings/<uuid>.wav` on a background thread (44-byte header patched on stop).
- `start()`, `stop(): RecordedAudio(file, durationMs)`, `cancel()` (deletes the file), `level: StateFlow<Float>` (RMS 0..1 for a level meter).
- Max length 5 minutes (auto-stop with a notice). Minimum 0.7 s — shorter → "That was too short to send." and delete.
- Stop and release on `onStop` of the screen / when the app goes to background (don't leave the mic open).
- The recording file is deleted after transcription, success or failure.

### 3. Transcription (`voice/capture/Transcriber.kt`)
```kotlin
interface Transcriber { suspend fun transcribe(audio: RecordedAudio): Outcome<String> }  // "" = no speech
class OllamaTranscriber(client, settings, modelCatalog) : Transcriber      // model has "audio"
class AndroidSpeechTranscriber(context) : Transcriber                      // fallback
```
- Ollama: non-streaming `/api/chat` with the current model, one user message with the transcription prompt
  from 03-ollama-reference §5 and `images = [base64(wav)]`. Reply containing `[NO_SPEECH]` → `""`.
  Same `num_ctx`/`think` policy (it's in the client).
- Fallback when the model can't hear: Android `SpeechRecognizer` can't transcribe a file on most devices, so
  the fallback path for voice memos is: record with `SpeechRecognizer` live instead (same UI), using
  `EXTRA_PREFER_OFFLINE = true` where supported. Document this branch clearly in code.
- Empty transcript → don't send; show "No speech was heard in that recording." (L11).

### 4. UI (C07 input row)
- **Record** button (dot icon, "Record audio"): tap to start. While recording: the input row shows a red dot,
  elapsed time `0:07`, a level meter, **Cancel** (✕, "Cancel recording") and **Stop & send** (■, "Stop and send recording").
  Speech output stops when recording starts (C13) so Edith's voice isn't recorded.
- After stop: the in-flight bubble says "Transcribing…" until the transcript is sent as a normal user message.
- **Dictation** mic (trailing icon, "Speak a message"): starts `SpeechRecognizer` (`LANGUAGE_MODEL_FREE_FORM`,
  partial results on); partial text appears in the field; final result replaces it. Tap again to stop.
  Recognizer errors map to short messages ("Didn't catch that", "Speech recognition isn't available on this device").

## Tests
- WAV writer: header fields correct for 16 kHz mono 16-bit; data size matches; file playable (parse header back).
- OllamaTranscriber with MockWebServer: request has `images` with base64 WAV and the exact prompt; `[NO_SPEECH]` → "".
- Too-short and cancel paths delete the file and send nothing.
- Fallback selection: model without `audio` → AndroidSpeech path.

## Acceptance criteria
- [ ] **Not done: needs the owner.** Emulator with host mic enabled (or a phone): say a sentence → it appears as
      your message and Edith answers. The same path was checked with a spoken WAV through the debug hook (below).
- [x] Silence → "No speech was heard…", nothing sent.
- [x] Denied permission → message + Open settings; no crash.
- [x] Transcription of a pushed 10 s WAV via a debug hook (or a unit/instrumented test) < 4 s (budget).
- [x] No recordings left in `cacheDir/recordings` after use (`run-as` listing in the PR).

## As built (2026-11-01)

Where the code differs from the specification above, the code is right.

**Files** (`voice/capture/`)
- `WavWriter.kt`: `RecordedAudio`, and the WAV writer (44-byte header, sizes patched on close).
- `AudioRecorder.kt`: `Recorder` and `AudioRecorder` (`AudioRecord` on its own thread; `levelOf`; `deleteLeftovers`).
- `Transcriber.kt`: `Transcriber` and `OllamaTranscriber`.
- `LiveSpeech.kt`: `SpeechInput` and `LiveSpeech` (Android's `SpeechRecognizer`) and its error messages.
- `VoiceMemos.kt`: the memo coordinator.

Also:
- `chat/ui`: `InputRow` (Record, the recording row, the dictation mic), the ViewModel's voice state and dictation,
  and the screen's microphone permission and leaving-the-app handling.
- `AppContainer`: `voiceMemos`, `dictation`, `chatFeatures` (record on; dictation when the phone has a recognizer).
- Manifest: `RECORD_AUDIO`, plus a `<queries>` entry for `android.speech.RecognitionService`. Without it Android 11+
  hides the recognizer, and dictation reports "not available".
- `src/debug`: `DebugVoiceMemoReceiver`, the debug hook. Only the shell may send to it (`DUMP` permission). Usage is
  in its KDoc.

**Differences from the spec**
- **Voice memos are app-scoped** (`VoiceMemos`), like the chat engine. Rotating while recording or transcribing loses
  nothing, and the memo goes to the chat it was started in.
  - While a memo is starting, recording or being transcribed, Send and Record are off (`ChatUiState.voiceBusy`).
  - Record is also off while Edith replies: one message at a time.
- **Leaving the app while recording discards the memo**, with "The recording stopped when you left the app, so it
  wasn't sent." The spec said to stop and release; sending a half-finished memo you didn't choose to send seemed
  wrong.
  - A rotation isn't leaving: the screen checks `isChangingConfigurations`.
  - A memo already being transcribed still finishes and sends.
- **No rationale before Android's dialog** (like D14): the first Record or mic tap goes straight to Android's
  "Allow Edith to record audio?". The one-line reason is the message shown after a refusal, with **Open settings**
  (Edith's App info page).
- **Model selection:**
  - Whether the model hears audio comes from `ModelCatalog.capabilities` (cached).
  - If that's unknown (server unreachable, no model), the memo goes to the model anyway: its error is clearer than a
    fallback, and a chat message would fail too.
  - Fallback (no "audio"): live `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE`, same UI. If the recognizer ends on
    its own (silence), the text is sent then.
- **Transcription failures** show "Couldn't transcribe the recording: <reason>". The file is always deleted.
  - Recordings left by a crash or a killed process are deleted at startup.
- **Dictation:**
  - The recognized text is added after what was already typed. Partial results replace the dictated part as they
    arrive.
  - A message whose draft came partly from dictation is sent with source `dictated`, even if edited.
  - "Didn't catch that." when nothing was recognized; other recognizer errors map to short messages
    (`LiveSpeech.messageFor`).
- **Level meter:** RMS on a −60..0 dBFS scale. It's drawn from a lambda, so it redraws without recomposing the row.

**Verified**
- `scripts/check.sh --device`: OK, 118 unit tests (9 new in `VoiceInputTest`), lint clean, 73 device tests (1 new)
  (`build-logs/2026-11-01_09-58-44_check.log`).
- `VoiceInputTest` (JVM) covers:
  - the WAV header fields and data size, parsed back, and little-endian samples
  - the level meter, and deleting leftover recordings
  - `OllamaTranscriber` against MockWebServer: model, `stream:false`, the exact prompt, the WAV round-tripped through
    base64 in `images`, and `[NO_SPEECH]` → ""
  - memos: sent to the starting chat with the file deleted; too short (nothing transcribed), cancelled, silent,
    failed (file deleted each time); a microphone that won't open; the live-recognition fallback (and none
    available); and the 5-minute auto-stop
- `ChatScreenTest.voiceControlsRecordStopCancelDictateAndOpenSettings` covers the buttons, the recording row,
  Transcribing… with Send and Record off, the dictation state, and Open settings.
- **Live on the emulator** (`gemma4:12b`):
  - **Don't allow** → the message and Open settings, which opens Edith's App info. No crash.
  - **Record 3 s of the emulator's (silent) mic** → recording row (0:03, timer, meter) → "Transcribing…" →
    "No speech was heard in that recording." Nothing sent.
  - **Debug hook with a 9.9 s spoken WAV** (Windows text-to-speech, 16 kHz mono):
    - Transcribed exactly: "Hey, Edith, can you remind me what the weather is going to be like in Fowlerville
      tomorrow afternoon? I want to know if I should bring an umbrella to the game."
    - It was stored with source `voice_memo`, and Edith answered using `get_weather` for Fowlerville, tomorrow.
    - **Transcription times: 921, 880, 1,296 ms** (budget 4 s).
    - One rerun heard "Faulerville" (model accuracy on synthetic speech).
  - **An empty file** (a failed push) → "Couldn't transcribe the recording: … HTTP 400: Failed to load image or audio
    file". The file was deleted; no crash.
  - **Dictation:** "Listening…" and Stop dictation appear. Google's on-device recognizer (SODA) heard silence →
    "Didn't catch that."
  - **Home while recording** → Android's record log shows Edith's mic stopped right after; the notice shows on
    return; no file.
  - **Rotate twice while recording** → the recording continued (0:06 → 0:11 → 0:18), with no false "left the app".
  - **`run-as … ls cache/recordings`** after every run: empty.
- **Not verified:**
  - **Real speech through a microphone**, and dictation's live partial text. Both need the emulator's host
    microphone (Extended controls → Microphone) or a phone.
  - **The live-recognition fallback on a device:** the owner's models hear audio, so it's covered by the JVM tests
    only.
  - **One slow transcription:** 7.8 s of near-silence took 4,225 ms (all 3 speech runs were under 1.3 s). Probably
    the model's first audio request after a while; not investigated further.
- **One native crash during a test run (emulator graphics, not reproduced):**
  - At 09:56:08 the test process died with SIGSEGV on the RenderThread, inside the emulator's GPU driver
    (`libEGL_emulation.so`, `eglDestroySurface`), while a window was being torn down after
    `newChatOpensAnEmptyChatAndTheReplyInAnotherChatDisablesSend` timed out (Send hadn't taken effect: engine idle,
    nothing sent).
  - No Edith frames are in the stack, and Edith does no direct GPU work.
  - ChatScreenTest then passed 3 times alone and once in the full check, and there was no other crash.
  - It's like the emulator graphics hang during C10's checks.
