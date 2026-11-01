# C15 — Attachments in: photos and audio files

| | |
|---|---|
| Phase | 4 — Attachments |
| Depends on | C06, C07, C05 |
| Unblocks | C16 |
| Size | Medium |
| Read first | 03-ollama-reference §5; 00-product §4.3; 04-lessons L12 |

## Goal

The owner attaches photos and audio files to a message; Edith (with a capable model) sees and hears them.
This chunk handles picking, preparing, sending to the model and showing them in the chat. Storage and
retention, Edith's own files and resends are C16 — until C16 lands, attachments are kept in memory only
(the same behaviour as `keep_recent_attachments = 0`).

## Specification

### 1. Picking (input row paperclip, "Attach a file")
Menu: **Photo** (Android Photo Picker, `PickMultipleVisualMedia(ImageOnly, max 4)` — no permission needed) and
**Audio file** (`OpenDocument(arrayOf("audio/*"))`). Pending attachments appear as removable chips above the
input (thumbnail or audio icon, file name, ✕ "Remove <name>"). Send is enabled with attachments and no text.
Note for testers: on API 37 the photo picker is select-then-**Done** (05-environment §4).

### 2. Preparation (`attachments/AttachmentIntake.kt`, off the main thread)
- **Images:** decode with `ImageDecoder` (applies EXIF rotation; API 28+) or `BitmapFactory` with sampling on
  API 26–27 (apply EXIF orientation via `ExifInterface`), scale so the longest side ≤ **1536 px**, encode JPEG q85.
  Reject non-images and decode failures with a clear message.
- **Audio:** WAV and MP3 are kept as-is (Ollama accepts only these — 03 §5); anything else (M4A, AAC, OGG, 3GP,
  FLAC…) is decoded with `MediaExtractor`/`MediaCodec` to 16-bit PCM, downmixed to mono, written as WAV.
  Max **5 minutes** (check duration with `MediaMetadataRetriever` before decoding) — longer → refuse with the reason.
- Result: `PendingAttachment(kind, fileName, mimeType, bytes or temp file, sizeBytes, durationMs?)`. Use temp files
  in `cacheDir/pending/` for anything over 1 MB (avoid holding large audio in memory); delete on send/remove.
- Display names via `OpenableColumns.DISPLAY_NAME`; fall back to "image.jpg"/"audio.wav".

### 3. Sending to the model (`AttachmentContext` from C06)
- On send, `ChatEngine` writes the user message and its attachment rows (numbers from `AttachmentDao.nextNumber`,
  direction `from_user`) in one transaction; files are handed to the attachment store (C16; in-memory until then).
- `ContextBuilder` asks `AttachmentContext` for each user message's content suffix and media:
  label lines `[Attachment 3: receipt.jpg (image)]` appended to the text, and `images = [base64…]` for messages
  within the **last 10 messages** (media window) whose files are still available. Older or removed → label only,
  plus " — no longer available" for removed ones.
- Capability check before sending: images present but the model lacks `vision`, or audio without `audio` → don't
  call the model; show "<model> can't see images — pick a model marked 'Sees images' in Settings." (no server error).
- Prompt section (registered with `PromptBuilder`) when attachments exist in context:
  `The user can attach images and audio files; they appear as labels like [Attachment 3: receipt.jpg (image)].`

### 4. Display (C07 bubbles)
- In the user's bubble, above the text: image thumbnails (max 240 dp, rounded 12 dp; tap → full-screen viewer
  with pinch-zoom) and audio rows (▶/■ "Play <name>", name, duration). Audio plays with `MediaPlayer` from the file
  (or a `MediaDataSource` over bytes when in memory). Starting attachment playback stops Edith's speech.
- Use Coil for thumbnails (memory cache only).

## Tests
- Image scaling: a 4000×3000 JPEG → longest side 1536; EXIF-rotated input upright.
- Audio: MP3/WAV passthrough; an M4A fixture → valid WAV header, mono, duration preserved ±50 ms; >5 min refused.
- ContextBuilder with attachments: labels; media only within the window; removed → "no longer available".
- Capability gate prevents the call and produces the friendly message.

## Acceptance criteria
- [x] Emulator: attach a photo of a printed list and ask "what's on this list?" → correct answer (gemma4).
- [x] Attach an M4A voice note and ask "what do I need to do?" → correct summary (gemma4).
- [x] With a model lacking vision (e.g. `ornith:9b`) → the friendly message, no request sent (check Ollama log).
- [x] Temp files cleaned up (`run-as` listing).

## As built (2026-11-01)

Where the code differs from the specification above, the code is right.

**Files** (`attachments/`)
- `AttachmentIntake.kt`: prepares picked photos and audio (off the main thread), into `cacheDir/pending/`.
- `AudioToWav.kt`: `MediaExtractor` + `MediaCodec` → 16-bit mono WAV at the source's sample rate.
- `AttachmentStore.kt`: `PendingAttachment`, `AttachmentStore`, and `SessionAttachmentStore` (until C16).
- `MediaAttachmentContext.kt`: the `AttachmentContext` (labels and media) and `AttachmentsSection` (the prompt line).
- `AttachmentPlayer.kt`: plays audio attachments in bubbles.

Also:
- `ChatEngine.send(…, attachments)`: stores the message and its rows, then hands the files to the store.
  `ChatEngine.cantUse` is the capability gate.
- `ChatRepository.addUserMessage(…, attachments)`: one transaction, numbered with `nextNumber`.
- `chat/ui`:
  - `AttachmentViews.kt`: chips, the attachments in a bubble, and the full-screen viewer.
  - The paperclip menu, the pickers, and the view model's pending list.
- Coil 3.6.3 for thumbnails. `ChatFeatures.attach` is on.

**Differences from the spec**
- **Until C16, sent files are kept on disk for this run of the app, not in memory.**
  - They move from `pending/` to `cacheDir/attachments/`, so a 5-minute WAV isn't held in memory.
  - Both folders are emptied at startup. After a restart, older attachments show "<name> — no longer available",
    as `keep_recent_attachments = 0` would.
  - The rows' `stored_path` stays null; C16 fills it in.
- **The capability gate checks only the newest user message.** Older attachments in the media window are sent only
  if the current model can use them, else as labels only. So switching to a text model after sending a photo
  doesn't block every later message.
  - The message reuses `AppError.ModelLacksCapability`, which gained a `badge`: "ornith:9b can't see images — pick a
    model marked ‘Sees images’ in Settings."
- **A message can be only attachments.** `ContextBuilder` keeps rows that have attachments but no text, and the
  model sees the label lines.
- **Audio format** comes from the content where possible (the resolver's type, else the file's own MIME type),
  not the name. A missing name falls back to "audio.wav", which would otherwise skip the conversion.
- **WAV output keeps the source's sample rate** (e.g. 44.1 kHz), mono 16-bit. Ollama resampled it without trouble.
- **The photo picker hides the original file name.** Android's picker returns its own name ("26.jpg"), so that's
  what the chip and label show. Audio files from the document picker keep their names.
- **Up to 6 attachments per message** (`ChatViewModel.MAX_ATTACHMENTS`); the picker gives at most 4 photos at a
  time.
- **Pending attachments belong to the screen, not to a chat:** switching chats keeps them in the input row. They're
  deleted if the screen goes away unsent.
- **Media isn't counted** in the context token budget. Images (~1536 px JPEG) and short audio fit in `num_ctx 16384`
  with the 10-message window; long audio in several recent messages could crowd it.

**Verified**
- `scripts/check.sh --device`: OK. 157 unit tests (9 new in `AttachmentContextTest`), lint clean, 91 device tests
  (11 new). Log: `build-logs/2026-11-01_15-32-22_check.log`. New tests:
  - `AttachmentContextTest` (JVM): labels and media; an attachment alone; the 10-message window; "no longer
    available"; media a model can't use left out; the gate's messages; attachment-only rows kept; the prompt line;
    the scaling arithmetic.
  - `AttachmentIntakeDeviceTest`:
    - a 4000×3000 JPEG → 1536×1152; an EXIF "rotate 90" photo comes out portrait
    - not an image → refused
    - WAV and MP3 kept byte for byte
    - a stereo 44.1 kHz M4A → mono 16-bit WAV, length within 96 ms, with real audio in it
    - a 302 s M4A → "… is 5:02 long; audio can be up to 5 minutes", nothing left behind
    - not audio → refused
  - `AttachmentSendTest` (real Room, engine and store):
    - rows numbered 1, 2, then 3 in the next message; files moved into the store
    - base64 media and labels in the request; the prompt line
    - a model without vision → no request, and the friendly message on the failed reply
    - sending while a turn runs → false, and the attachments stay the caller's
  - `ChatScreenTest.attachmentsInTheInputRowAndInBubbles`: the menu, chips (Preparing…, ready, ✕), Send with
    attachments only, and a bubble's image, audio row (▶/■, 1:05) and "no longer available".
- **Live on the emulator** (`gemma4:12b`):
  - **Photo of a printed list** (picked with Android's photo picker, select then Done) + "what's on this list?" →
    all five items correct. Spoken (C13). Tap → full screen, ✕ closes.
  - **M4A voice note** (12 s, stereo AAC) → "Audio converted to WAV: audio/mp4, 12070 ms" + "what do I need to
    do?" → all three tasks correct. ▶ plays it (media), ■ while playing, back to ▶ at the end.
  - **`ornith:9b` (no vision)** + a photo → "ornith:9b can't see images — pick a model marked ‘Sees images’ in
    Settings." The Ollama server log had no new `/api/chat` (692 before and after). The model was set back to
    `gemma4:12b-it-q8_0` afterwards.
  - **Temp files:** `cache/pending` empty after sending. After a restart, `cache/attachments` is empty too, and the
    old message shows "26.jpg — no longer available".
  - No crash.
- **Not verified:**
  - Android 8 (API 26–27) image path (`BitmapFactory` + EXIF): the emulator is API 37, which uses `ImageDecoder`.
  - Real camera photos (HEIC, very large, rotated by a real camera); the rotation test used a written EXIF tag.
  - OGG, FLAC and 3GP audio: they take the same decoder path as M4A, but weren't tried.
