# C68 — Audio files as attachments on iOS

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **Design** — has a feasibility step that decides the library |
| Depends on | C58 (development build), C43/C47 (attachments), C49 (generalised attachment bytes), C44 (the audio capability gate and WAV facts) |
| Unblocks | — |
| Runs in | the development build (the conversion needs native code; picking and playing alone would run in Expo Go) |
| Size | Medium |
| Ports | C15's audio half (§1 "Audio file", §2 audio, §3 gate, §4 audio rows) |
| Read first | C15 spec **and** As built (`AudioToWav`, the 5-minute limit, display), `03-ollama-reference.md` §5 (**Ollama accepts only WAV and MP3**; M4A/AAC/OGG/3GP/FLAC return HTTP 400), C44 (WAV recording, `OllamaTranscriber`), C49 |

## Why
The owner attaches voice notes and recordings ("what do I need to do?" about a saved M4A). The model (an audio-capable one such
as gemma4) hears them through the same `images` field as pictures. iPhone voice notes are **M4A (AAC)**, so almost every
real attachment must be **converted to 16-bit PCM mono WAV** first. JavaScript can't decode AAC; a native decoder is needed.

## Feasibility step (do first; one afternoon; report in the PR; ask the owner before adding a library)
Pick the converter, in this order of preference, by trying each with **one real iPhone voice note** (the owner supplies one; keep it out of the repo):
1. **`react-native-audio-api`** (Software Mansion; Web-Audio-like): `decodeAudioData(arrayBuffer | path)` returns float PCM + sample rate for common formats on iOS. Check Expo SDK 58/New Architecture compatibility and its config plugin. If it decodes M4A and MP3 on the phone: resample to 16 kHz (or keep the source rate; Android kept the source's rate) → downmix to mono → encode 16-bit PCM WAV in TypeScript (`audio/wav.ts`: the 44-byte header with `little-endian` fields; unit-tested by parsing it back).
2. **A small local Expo module in Swift** (`ios-app/modules/audio-convert/`, using `AVAudioFile` + `AVAudioConverter` to 16 kHz mono Int16 and writing a WAV). The owner has **no Mac**, so the code can only be compiled by an EAS build: every mistake costs a build. Keep it ≤ 80 lines, copy Expo's module template, and test the logic by reading the Apple docs twice. Choose this only if option 1 fails.
3. **Not `ffmpeg-kit-react-native`**: it was retired and its binaries removed in 2025 (verify). **Not** cloud conversion (nothing leaves the PC and the phone).
Record the choice and the measured time to convert a 60 s M4A.

## Specification

### 1. Picking
- The attach menu gets a second item: tapping the paperclip opens a small action sheet: **Photo** (C43) / **Audio file**. Use `expo-document-picker` (`getDocumentAsync({ type: 'audio/*', copyToCacheDirectory: true })`; it is in Expo Go). Show the name; reject non-audio.
- Pending attachments appear as chips: a photo thumbnail, or an **audio chip** (waveform icon, name, duration `0:47`, ✕ "Remove <name>"). Send is enabled with attachments and no text. Up to 6 per message (existing limit); at most **2 audio** per message (memory/latency).

### 2. Preparation (`attachments/audioIntake.ts`)
- **WAV and MP3 are kept as they are** (Ollama accepts them). Everything else (M4A, AAC, CAF, AIFF, OGG, FLAC) → converted WAV (§feasibility).
- **Maximum 5 minutes**: read the duration first (`expo-audio` `createAudioPlayer(uri)` + `duration` once loaded, or the decoder's metadata); longer → refuse: "That recording is 12 minutes long; Edith can take up to 5."
- **Large files go to disk, not memory:** keep the converted WAV as a file in `FileSystem.cacheDirectory + 'pending/'` and the `PendingAttachment` holds `uri`, `sizeBytes`, `durationMs`; read it to Base64 only when sending (a 5-minute 16 kHz mono 16-bit WAV is ~9.6 MB; Base64 ~12.8 MB: fine but not for six at once). Delete the temp files on send/remove/startup.
- `PendingAttachment` becomes a union: `{kind:'image', …}` (as now) | `{kind:'audio', fileName, mimeType:'audio/wav'|'audio/mpeg', uri, sizeBytes, durationMs}`. Update `attachments.ts`, `AttachmentViews`, `ChatScreen`, the engine, `contextBuilder` accordingly (**TypeScript will point at each place**).

### 3. Sending (`ChatEngine.send` and `contextBuilder`)
- Store the row (`kind:'audio'`, `mime_type`, `size_bytes`) and the bytes via the C47/C49/C56 file store (`attachments/<id>.wav|mp3`), retention as for photos (the owner's direction; audio counts toward the same N).
- Label `[Attachment 4: voice-note.wav (audio, 0:47)]`; the audio is sent in `images: [base64]` (03 §5) **only** for messages within the media window and only if the model has the `audio` capability.
- **Capability gate:** audio present and the model lacks `audio` → don't call the model; "<model> can't hear audio — pick a model marked "Hears audio" in Settings." (`AppError.lacksCapability(model, 'audio')` exists). Mixed pictures and audio need both capabilities.
- Prompt section `attachmentsSection`: add "…and audio files, labelled like [Attachment 4: note.wav (audio)]." for models that hear.

### 4. Display
In the owner's bubble: an **audio row**: play/pause button (`expo-audio` player on the stored file), name, duration, a thin progress bar; after retention removal "name — no longer available". **Starting playback stops Edith's speech**, and starting Edith's speech stops playback. Only one audio plays at a time. The audio session mode must return to playback after recording (C44's `setAudioModeAsync` rule).

### 5. Settings → Attachments
Usage counts audio as well; text says "photos and recordings".

## Files
New: `attachments/{audioIntake,wav,audioPicker}.ts`, `ui/AudioRow.tsx`, (optional) `modules/audio-convert/`, tests. Changed: `attachments/attachments.ts` (union), `attachments/retention.ts` (kinds), `chat/{contextBuilder,engine,promptBuilder}.ts`, `ui/{ChatScreen,AttachmentViews,ChatComponents}.tsx`, `package.json` (`expo-document-picker`, the converter), `NATIVE_CHANGES.md` (**needs a new build**).

## Tests
`wav.ts` (header fields, parse it back, sizes, little-endian samples, mono downmix, clamp); passthrough rules (WAV/MP3 kept, M4A converted via a fake converter, >5 min refused, non-audio refused); temp files deleted on every path (send, remove, error, startup); the context builder (labels with duration, `images` only in the window and only for hearing models, "no longer available"); the **capability gate sends no request**; union types in the views (chips and rows render, ✕ removes); the bubble's playback toggles with a fake player and stops speech.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner, on the iPhone:** attach a Voice Memos M4A and ask "what do I need to do?" → a correct summary; attach a WAV and an MP3 (passthrough); a >5-minute file is refused with the reason; a text-only/vision-only model → the friendly message and **no request in Ollama's log**; the audio plays in the bubble; temp files are gone afterwards (check by the app's cache listing in a debug line).

## Risks and unverified assumptions
- The converter library's iOS format support and New Architecture compatibility; the Swift fallback can't be tested without an EAS build.
- The sample rate: Android kept the source rate; 16 kHz is what the model wants; check both with the real model and keep the one that transcribes better.
- Memory with long files on older iPhones; keep the file-based path.

## Not in this chunk
Recording inside the chat other than memos (C44), editing audio, sending audio to Ollama's speech endpoints, playing Edith's own audio output.
