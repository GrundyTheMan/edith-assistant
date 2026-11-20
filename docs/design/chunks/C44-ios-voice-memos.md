# C44 — Voice memos on iOS

| | |
|---|---|
| Phase | 9 — iOS (Expo) |
| Status | **Built** in Expo Go; see "As built" at the end. The owner's iPhone check is open |
| Depends on | C40 (chat in `ios-app/`), C43 (the model-capability message) |
| Ports | C12, the voice memo part |
| Not here | Dictation into the text field and live recognition for models that can't hear (iOS's recognizer needs a native module that Expo Go doesn't have; it needs a development build), hands-free (C14), recordings kept after sending |

## Why
On Android the owner taps Record, speaks, and the message is sent as if typed. This is the same on the iPhone, in Expo Go.

## Specification (differences from Android only)
- **Record button:** a mic in the input row, shown while the text field has nothing to send. Tap: the app asks for the
  microphone the first time (iOS's own dialog; the reason text is in `app.json`). Refused → "Edith needs microphone access
  to hear you. You can allow it in Settings." with **Open settings**.
- **Recording** (`voice/expoRecorder.ts`, `expo-audio`): **16 kHz, mono, 16-bit linear PCM, `.wav`**, the only format
  Ollama's audio models take (03-ollama-reference §5). While recording the input row shows ✕ (Cancel recording), a red
  dot, the time `0:07`, a level meter and ■ (Stop and send recording). Edith's voice stops when recording starts (C13).
  After a recording the audio session goes back to playback so replies use the speaker.
- **Limits:** under 0.7 s → "That was too short to send."; at 5 minutes the recording stops by itself and is sent.
- **Transcribing** (`voice/voiceMemos.ts`, `OllamaTranscriber`): one non-streamed `/api/chat` call with the current model,
  one user message with the transcription prompt and the WAV in `images`. A reply containing `[NO_SPEECH]` → "No speech
  was heard in that recording." and nothing is sent. The row says "Transcribing…" meanwhile.
- **Sending:** the words go to the chat the memo was started in as a normal user message, source `voice_memo`.
- **Model that can't hear:** the model is not called; "<model> can't hear audio — pick a model marked "Hears audio" in
  Settings." (Android falls back to live recognition here; see "Not here".) Capabilities unknown → it tries anyway.
- **Leaving the app mid-recording** drops the memo ("The recording stopped when you left the app, so it wasn't sent.").
- **The file is always deleted** after transcribing, success or failure, and on Cancel.
- One thing at a time: Record is off while Edith replies or a memo is going; Send is off while a memo is going.

## Tests
`OllamaTranscriber` (the exact request, `[NO_SPEECH]`, a model that can't hear is not called, unknown capabilities
tried); `VoiceMemos` with a fake microphone (send to the starting chat as `voice_memo`, Transcribing state, too short,
silence, failure, Cancel, refused permission, microphone that won't open, the 5-minute limit, leaving the app, double
starts); the level scale; the recording row and notice bar.

## Acceptance criteria
- [x] `scripts/ios-check.sh` is green.
- [ ] **The owner, on the iPhone** with a model that hears audio (gemma4): allow the microphone, say a sentence → it
      appears as your message and Edith answers; Cancel sends nothing; a silent recording → "No speech was heard…";
      Home mid-recording → the notice on return; refuse the microphone → Open settings opens iOS Settings; a text-only
      model → the friendly message; replies still play from the speaker (not the earpiece) after a recording.

## As built (2026-11-20)
Built by an AI agent; Android is untouched. Where the code differs from the specification above, the code is right.
- Files: `voice/voiceMemos.ts` (the coordinator, transcriber, interfaces), `voice/expoRecorder.ts` (expo-audio and
  expo-file-system), `ui/VoiceViews.tsx`; changes in `ui/ChatComponents.tsx` (mic button), `ui/ChatScreen.tsx`,
  `core/services.tsx`; `app.json` has the `expo-audio` plugin with the microphone text.
- **Not verified:** everything on a real iPhone, and the microphone code has no unit test (it needs a device). In
  particular that `AVAudioRecorder` really writes a 16 kHz mono PCM WAV with these settings (if Ollama answers HTTP 400
  "Failed to load image or audio file", this is why), that the level meter moves, that the audio mode switch restores the
  loud speaker, and how long a real transcription takes. `expo-audio` is imported by its internal path
  (`expo-audio/build/AudioModule`) to create a recorder outside a React component.
