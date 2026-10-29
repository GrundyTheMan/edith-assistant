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
- [ ] Emulator with host mic enabled (or a phone): say a sentence → it appears as your message and Edith answers.
- [ ] Silence → "No speech was heard…", nothing sent.
- [ ] Denied permission → message + Open settings; no crash.
- [ ] Transcription of a pushed 10 s WAV via a debug hook (or a unit/instrumented test) < 4 s (budget).
- [ ] No recordings left in `cacheDir/recordings` after use (`run-as` listing in the PR).
