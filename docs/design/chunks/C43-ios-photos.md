# C43 — Photo attachments on iOS

| | |
|---|---|
| Phase | 9 — iOS (Expo) |
| Status | **Built** in Expo Go; see "As built" at the end. The owner's iPhone check is open |
| Depends on | C40 (chat in `ios-app/`) |
| Ports | C15 (attachments in: photos), the pictures part; the "memory only until C16" behaviour |
| Not here | Audio files (C15's WAV conversion has no Expo Go equivalent; it needs a development build), the camera, storage across restarts and Edith's own files (C16), voice memos (C12) |

## Why
The Android chat lets the owner attach photos so a vision model can read a list or a receipt. This is the same on the
iPhone, in Expo Go, with nothing for the owner to set up.

## Specification (differences from Android only)
- **Picking:** the paperclip in the input row opens the system photo picker (`expo-image-picker`, up to 4 photos at a time,
  6 per message). iOS needs no permission for it. Pending photos show as thumbnails above the input with a Remove button
  each; Send is enabled with photos and no text.
- **Preparing** (`attachments/attachments.ts`, `expoImageProcessor.ts`): `expo-image-manipulator` applies the photo's rotation,
  scales so the longest side is at most **1536 px** (never enlarged), and encodes **JPEG at 0.85**; HEIC and PNG come out as
  `.jpg`. A photo that can't be read gives "That photo couldn't be read".
- **Sending:** the user message and its `attachments` rows (numbered across all chats, direction `from_user`) are stored in
  one transaction. The picture bytes are held in `AttachmentStore` for **this run of the app** only, as on Android before
  C16. `buildContext` appends `[Attachment 3: receipt.jpg (image)]` lines to the text and sends `images: [base64…]` for
  the newest **10** messages; older, removed or lost pictures are labels, with " — no longer available" when the bytes are
  gone. A message of attachments only is kept. The prompt line about attachments is added for models with `vision`.
- **Capability gate:** if the newest message has pictures and the model lacks `vision`, the model is not called. The turn
  fails with "<model> can't see images — pick a model marked "Sees images" in Settings." and Retry (after the owner
  picks another model) sends it.
- **Display:** thumbnails (160 pt) above the text in the owner's bubble; a tap opens the picture full screen on black.
  After an app restart a bubble shows "<name> — no longer available".
- Not done on purpose: media is not counted in the context budget (as on Android).

## Tests
Scaling maths and the processor call (4000×3000 → 1536×1152, small photos untouched, HEIC → `.jpg`), Base64 sizes; the
context builder (labels, pictures, the 10-message window, "no longer available", a model that can't see, attachment-only
messages, no change without media); the engine on real SQLite (numbered rows across chats, bytes held, the request has
`images` and the labels, a photo with no text, the capability gate sends no request, an empty send ignored); the views
(Remove, open, missing, viewer close).

## Acceptance criteria
- [x] `scripts/ios-check.sh` is green.
- [ ] **The owner, on the iPhone** with a vision model: attach a photo of a list and ask "what's on this list?" → a correct
      answer; attach two photos; Remove works; tap a thumbnail → full screen; with a text-only model → the friendly message
      and no request in Ollama's log; restart the app → "no longer available".

## As built (2026-11-20)
Built by an AI agent; Android is untouched. Where the code differs from the specification above, the code is right.
- Files: `attachments/` (`attachments.ts`, `expoImageProcessor.ts`, `pickPhotos.ts`), `ui/AttachmentViews.tsx`;
  changes in `data/chatRepository.ts` (`StoredAttachment`, `attachments()`, numbered inserts), `chat/contextBuilder.ts`,
  `chat/engine.ts` (`send(..., attachments)`), `chat/promptBuilder.ts` (`attachmentsSection`), `core/errors.ts`
  (`model_lacks_capability`), `ui/ChatComponents.tsx`, `ui/ChatScreen.tsx`, `core/services.tsx`; `app.json` has the
  `expo-image-picker` plugin and its text (used by builds).
- **Not verified:** everything on a real iPhone. In particular that `manipulateAsync` returns the Base64 and honours HEIC
  and EXIF rotation, how big the Base64 is for a real photo (the largest is about 1536 px at JPEG 0.85), and how the picker
  and the thumbnails look.
