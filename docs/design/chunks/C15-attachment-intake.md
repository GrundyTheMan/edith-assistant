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
- [ ] Emulator: attach a photo of a printed list and ask "what's on this list?" → correct answer (gemma4).
- [ ] Attach an M4A voice note and ask "what do I need to do?" → correct summary (gemma4).
- [ ] With a model lacking vision (e.g. `ornith:9b`) → the friendly message, no request sent (check Ollama log).
- [ ] Temp files cleaned up (`run-as` listing).
