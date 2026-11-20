# C56 — Edith's files and resending attachments on iOS

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave A |
| Status | **Design** |
| Depends on | C49 (Edith's attachments foundation), C47 |
| Unblocks | — |
| Runs in | Expo Go |
| Size | Medium |
| Ports | C16 §3–5 (`ReplyFormat`, `EdithFilesSection`, file cards, `FileViewer`, Save, Share, resends) |
| Read first | C16 spec **and** As built (the parse rules, the placeholders, the prompt text), C13 (speech skips file contents and tags), C49 |

## Why
"Make me a packing list for a beach day as a file" should produce a **file card** the owner can View, Save to Files or Share;
"Send me that receipt photo again" should show the original photo in Edith's bubble. Android does this with two reply
conventions the model is taught in the prompt: `<file name="x.md">…</file>` and `<attachment number="3"/>`.

## Specification

### 1. The prompt section (always on; registered with `PromptBuilder` in `createServices`)
Use exactly C16's text (re-read it in C16 §3 before pasting; it may have changed in As built):
```
When your reply contains something worth keeping as a file (a list, note, table, plan or code), put it in a file block:
<file name="short-name.ext">the content</file>
Use .md for notes and lists, .csv for tables, and the right extension for code. Keep the chat text around it to a sentence or two.
To send one of the user's attachments back to them, write <attachment number="3"/> with its number. Only use numbers from labels you've been given.
```

### 2. `ReplyFormat` (`chat/replyFormat.ts`, pure)
`parse(text): ReplyPart[]` with `ReplyPart = {kind:'text', text} | {kind:'file', name, content} | {kind:'resend', number} | {kind:'removed', name}`.
- An **unclosed** `<file>` runs to the end of the reply.
- **Names are sanitised**: keep `[A-Za-z0-9._-]`, replace the rest with `-`, max 60 characters, default `note.md`; strip path separators and leading dots.
- No regex lookbehind (Hermes). Implement with `indexOf` scanning; tolerate attribute quote styles (`name="x"`, `name='x'`), upper/lower case tags, and the model writing `<file name=x>`.
- `<attachment number="N"/>` with any spacing; an unknown number becomes the text "Attachment N isn't available".
- Streaming: while a reply streams and a `<file>` is open, the in-flight bubble shows "Writing <name>…" instead of the raw block (`ui/ChatScreen` in-flight text goes through `ReplyFormat.display(partial)`).

### 3. Storing (`ChatEngine`, after the reply finishes and **before** it is stored)
- Each `<file>` → bytes (UTF-8 of the content; `new TextEncoder().encode` is fine in Hermes) → a `from_edith` row (`kind: 'file'`, mime from the extension: `.md` → `text/markdown`, `.csv` → `text/csv`, `.json`, `.txt`, `.html` → `text/html`, code → `text/plain`) with `size_bytes`; bytes via `AttachmentStore` — **extend the store to hold files as well as pictures**: `put(id, base64)` is image-specific today; add `putText(id, text)` / `text(id)` using `expo-file-system` (`attachments/<id>.<ext>`), keeping the same retention (C49). Add `kind: 'file'` to `StoredAttachment` (the migration already allows any `kind` string).
- The stored message text has each block replaced by `[Attached file: name]`.
- Each `<attachment number="N"/>` → a row with `resend_of = <original id>` and no file copy (resends don't count toward retention). Unknown or removed numbers stay as text.
- **Speech** (C13's `cleanForSpeech`) skips file contents and tags ("There's a file called packing list" is optional; Android says nothing: match it).
- Edith's file contents go back to the model only within the media window and only while kept; otherwise the placeholder `[Attached file: name]`.

### 4. Display (`ui/FileCard.tsx`, `ui/FileViewer.tsx`)
- A **file card** in Edith's bubble: a document icon, the name, the first 3 lines in a monospace style, buttons **View**, **Save**, **Share**. Removed by retention → a muted line "name — no longer kept".
- **View:** a modal with the whole text, selectable (`selectable`), scrollable.
- **Save / Share:** write the text to a temp file in `FileSystem.cacheDirectory` (`share/<name>`) and call `expo-sharing`'s `shareAsync(uri, { mimeType, UTI })` — the iOS share sheet includes **Save to Files**. (There is no separate Save dialog in Expo Go; `expo-document-picker` can't pick a save location.) Delete the temp file afterwards. Install `expo-sharing` (in Expo Go).
- **Resend:** renders the original attachment (thumbnail, via the existing `BubbleAttachments`) inside Edith's bubble, or "Attachment N is no longer available".
- Clipboard: a **Copy** button on the card (`expo-clipboard`, already installed).

### 5. Settings → Attachments
The usage line now counts files and pictures per direction; Delete all removes both.

## Tests
`ReplyFormat`: files, an unclosed file, two files, attribute quote styles, a resend, an unknown number, sanitising (path separators, unicode, empty, 100-char name), tags inside code fences are still parsed (document), text around blocks preserved. Engine on SQLite: a reply with a file stores a `from_edith` `file` row, the stored text has the placeholder, the bytes are on the fake disk; a resend row has `resend_of` and doesn't count in retention; Regenerate removes the file; chat deletion removes it. Cards: View opens the text; Share writes the temp file and calls the sharing module (mock `expo-sharing`); a removed file shows the muted line. Prompt: the section text is present. No file bytes in the SQLite file (scan the database for the content, as C16's test does).

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner, on the iPhone:** "Make me a packing list for a beach day as a file" → a card; View shows it; Share → Save to Files works and the file's text is exact; "Send me that receipt photo again" shows the photo; 11 attachments over time with the setting at 10 → the oldest says "no longer kept"; a restart keeps the rest.

## Risks and unverified assumptions
- Models don't always follow the `<file>` convention (they sometimes put it in a code fence). Judge with `gemma4:12b` (L26). Record what happens.
- `expo-sharing` with a `.md` file and the UTI; the share sheet's Save to Files should accept it.

## Not in this chunk
Editing files, folders, other kinds (PDF, images Edith generates), sharing pictures from the viewer (a small addition if the owner asks).
