# C16 — Attachment storage, retention, Edith's files and resends

| | |
|---|---|
| Phase | 4 |
| Depends on | C15, C05, C07, C13 |
| Size | Medium |
| Read first | 00-product §4.3; 06-decisions A6, **O1** (confirm per-direction counting with the owner) |

## Goal

Keep recent attachments on the phone (setting, default 10 per direction), let Edith attach files she writes
(lists, notes, tables, code) and resend the owner's attachments, and show all of it clearly — including what
has been removed by retention.

## Specification

### 1. `AttachmentStore` (`attachments/`)
- Files in `filesDir/attachments/<uuid>.<ext>` (app-private; excluded from backup — C01 rules).
- `suspend fun save(pending: PendingAttachment): StoredFile`, `open(attachment): File?`, `delete(attachment)`.
- `stored_path` relative to `filesDir`. Writes are atomic (write to `*.tmp` then rename).

### 2. Retention (`attachments/Retention.kt`)
- Setting `keep_recent_attachments` (0–50, default **10**), applied **per direction** (`from_user`, `from_edith`)
  — confirm O1 with the owner before building; record the answer in 06-decisions.
- After every message with attachments, and on app start: for each direction, list stored attachments newest
  first; delete files beyond N (`markRemoved`: `stored_path = NULL`, `removed_at = now`). Resend rows
  (`resend_of != null`) don't count (they reference the original).
- `0` → files are deleted when the app process ends: store them in `cacheDir/session/` instead and clear that
  folder on start; rows still get `removed_at` on next start.
- Changing the setting applies immediately (a lower number prunes now, with a confirmation dialog:
  "This will delete 7 older attachments.").
- Counts span **all conversations** (D6). **Deleting a conversation** (C07 §8) deletes its stored attachment
  files too: the message and attachment rows cascade in the database, but the files on disk don't. Delete the
  files before the rows, and clean up any orphaned files on app start.
- Settings → Attachments: the number field/slider with the explanation "Keeps the most recent N attachments you
  sent and N Edith sent, on this phone only. Older ones are deleted." plus current usage ("12 files, 8.4 MB")
  and **Delete all attachments now** (confirmation).

### 3. Edith's files (`<file>` blocks)
Conventions taught in a prompt section (registered with `PromptBuilder`, always on):
```
When your reply contains something worth keeping as a file (a list, note, table, plan or code), put it in a file block:
<file name="short-name.ext">the content</file>
Use .md for notes and lists, .csv for tables, and the right extension for code. Keep the chat text around it to a sentence or two.
To send one of the user's attachments back to them, write <attachment number="3"/> with its number. Only use numbers from labels you've been given.
```
- `ReplyFormat.parse(text): List<ReplyPart>` — `Text`, `File(name, content)`, `Resend(number)`,
  `RemovedFile(name)`. An unclosed `<file>` runs to the end of the reply. File names sanitised
  (`[A-Za-z0-9._-]`, max 60 chars, default `note.md`).
- When an assistant reply is finished (C06), before storing: each `<file>` becomes an `attachments` row
  (`direction from_edith`, `kind file`, mime from extension) with its content saved by `AttachmentStore`; the
  stored message text has the block replaced by `[Attached file: name]`. Each `<attachment number="N"/>` becomes a
  row with `resend_of = <original id>` (no file copy); unknown numbers are left as text "Attachment N isn't available".
- Speech (C13) skips file contents and tags.

### 4. Display
- File card in Edith's bubble: icon, name, first 3 lines (monospace), **View** (dialog, selectable, scrollable),
  **Save** (SAF `CreateDocument(mime)` → writes the content where the owner chooses), **Share**
  (`ACTION_SEND` with a `FileProvider` URI and `text/*` type). Removed by retention → muted line "name — no longer kept".
- Resend: renders the original attachment (thumbnail/audio row) inside Edith's bubble, or "Attachment N is no longer available".

### 5. Context
Edith's own file contents go back to the model only within the media window (last 10 messages) and only while
stored; otherwise the `[Attached file: name]` placeholder.

## Tests
- Retention: 12 user + 3 Edith attachments with N=10 → 2 oldest user files deleted, Edith's untouched; resends not counted;
  N lowered → prunes; N=0 → session folder behaviour.
- ReplyFormat: files, unclosed file, resend, unknown number, sanitising names; stored text replacement.
- FileProvider share URI resolves; Save writes exact bytes (instrumented).
- No attachment bytes in the database (byte-level scan test on the DB file for a JPEG/WAV signature).

## Acceptance criteria
- [x] Emulator: ask "make me a packing list for a beach day as a file" → file card; View/Save/Share work.
- [x] "Send me that receipt photo again" → the original photo shown in Edith's bubble.
- [x] Attach 11 photos over time with N=10 → the oldest shows "no longer available" and its file is gone (`run-as` listing).
- [x] Restart the app → kept attachments still show; removed ones show the placeholder.

## As built (2026-11-01)

Where the code differs from the specification above, the code is right.

**O1 (the owner, 2026-11-01): per direction.** The owner's 10 newest and Edith's 10 newest are kept, across all chats.
Recorded in 06-decisions.

**Files** (`attachments/`)
- `AttachmentStore.kt`: `AttachmentStore`, and `FileAttachmentStore` (replaces C15's one-run store).
- `Retention.kt`: pruning per direction, startup cleanup, Delete all, and usage.
- `ReplyFormat.kt`: parsing `<file>` blocks and resend tags, name sanitising, placeholders, and `EdithFilesSection`
  (the prompt).
- `FileExport.kt`: Save (exact bytes to a chosen URI) and Share (a FileProvider URI).
- `MediaAttachmentContext.kt` (C15): now also handles Edith's own files and resends.

Also:
- **Engine:** `ChatEngine` stores files before their rows; after a reply, `edithAttachments` turns her blocks and
  tags into rows. A retention hook (`afterAttachments`) runs after a message with attachments.
- **Repository:** `ChatRepository` deletes files with their conversation, message (Regenerate) or Clear.
  `addAssistantMessage` takes her files and resends.
- **Database:** new `AttachmentDao` queries. No schema change: the columns were there since C05.
- **Chat UI:** `EdithAttachments` (file cards, resends, removed lines) and `FileViewer`. The screen handles
  View/Save/Share, and a streaming reply shows "Writing name…" in place of a file block.
- **Settings:** `ui/settings/AttachmentSettings.kt` (Settings → Attachments).
- **Manifest:** a `FileProvider` (`${applicationId}.files`) exposing only `cacheDir/share`.

**Differences from the spec**
- **`stored_path`** is `attachments/<uuid>.<ext>` under `filesDir`, or `session/<uuid>.<ext>` under `cacheDir` when
  keeping 0. `FileAttachmentStore` resolves both.
- **The store API is path-based:** `keep(pending, session)` / `write(name, content, session)` return the
  `stored_path`, and `file(storedPath)`, `durationMs(storedPath)`, `delete(storedPath)` read from it. Files are
  stored before their rows, so a row never points at a file that isn't there yet.
- **Keep = 0:**
  - New files go to `session/` and aren't counted during the run.
  - At the next start, `session/` is emptied and those rows get `removed_at`.
  - Lowering the setting to 0 deletes the kept files at once, after the confirmation.
- **Retention also runs at startup**, after the session and orphan cleanup. That cleanup also removes `*.tmp`
  leftovers and C15's old `cacheDir/attachments`.
- **Resend rows** take the original's number (so "Attachment 3" stays 3), `kind`, name and size, with
  `stored_path = null` and `resend_of` set. A resend of a resend points at the original.
- **Edith's files** get the next number in the chat like the owner's. Name sanitising keeps the extension when
  cutting to 60 characters (a name without one gets `.md`).
- **Stored text:** a file block becomes `[Attached file: name]` on its own line, and a resend tag is removed (the
  bubble shows the attachment). An unknown number becomes "Attachment N isn't available." The bubble hides the
  placeholders; speech skips them and the tags.
- **Context:**
  - Within the 10-message window and while stored, her placeholder becomes her file block again (up to 8,000
    characters).
  - Her resends go back as the `<attachment number="N"/>` tag she wrote, with no media.
  - Older ones keep the placeholder.
- **Removed files:** a removed Edith file shows "name — no longer kept"; a resend whose original is gone shows
  "Attachment N is no longer available."
- **Share** copies the file to `cacheDir/share/<name>` (the stored name is random), the only folder the provider
  exposes, emptied on each share.
- **The prompt says plainly that Edith can send attachments back.** Live, gemma4 once answered "I'm afraid I can't
  actually 'send' files…".
  - In a replay with Ollama, the spec's wording gave the tag 15 times in 16. The added line ("You can send
    attachments back … Never say you can't send it.") gave it 10 times in 10, and the next two live tries both
    worked. The variance is the model's; the line targets what it said.

**Verified**
- `scripts/check.sh --device`: OK (`build-logs/2026-11-01_17-13-01_check.log`). 167 unit tests (10 new in `ReplyFormatTest`), lint
  clean, 104 device tests (13 new).
  - `ReplyFormatTest` (JVM): parts in order; an unclosed file; resend tag forms; sanitising (spaces, `../`,
    non-ASCII, empty, length with the extension kept); streaming text; placeholders; MIME types; speech; Edith's
    file in and out of the window; a resend in the context.
  - `RetentionTest` (Room and real files):
    - 12 owner + 3 Edith with N=10 → the 2 oldest owner files go; Edith's and the resend don't count
    - a lower number prunes; counts span chats
    - keep = 0 → session files go at the next start, rows marked
    - orphans and `.tmp` files deleted at start
    - deleting a message or a chat deletes its files
    - Delete all and usage
    - Save writes the exact bytes; the Share URI is `content://…files/…/beach-list.md` and reads back the same bytes
  - `AttachmentSendTest`:
    - Edith's file → a `from_edith` `file` row, the content on disk, the placeholder in the text
    - a resend → `resend_of` set, number kept, no copy; an unknown number → "Attachment 7 isn't available."
    - **no attachment bytes in the database**: a file-backed DB and its WAL scanned for JPEG and WAV signatures
  - `ChatScreenTest.edithsFilesAndResendsInHerBubble` and `AttachmentSettingsTest`.
- **Live on the emulator** (`gemma4:12b`):
  - **"Make me a packing list for a beach day as a file"** → a card "beach_day_packing_list.md" with its first lines.
    - **View** shows the whole file.
    - **Save** → Android's save screen → Downloads: `md5` identical to the stored file.
    - **Share** → Android's share sheet with "beach_day_packing_list.md" (Quick Share, Drive, Gmail).
  - **"Send me that receipt photo again"** (after sending a photo as a receipt): the first try got the "I can't
    send files" reply (see above). After the prompt line, two tries in a row → the photo in Edith's bubble. Only one
    copy stored.
  - **11 more photos with N=10** → the store stayed at 10 photos; "Retention: removed 1 attachment(s)" each time;
    the oldest (the receipt and photo 1) show "26.jpg — no longer available".
  - **Restart** → the 10 photos and Edith's file still show (View/Save/Share). The removed ones keep their
    placeholders, and the receipt's resends show "Attachment 2 is no longer available."
  - Settings → Attachments: "Keep the most recent 10 each way", the explanation, "11 files, 644 KB".
  - No crash.
- **Not verified:**
  - Lowering the number from Settings on the emulator (it deletes files). The dialog has a UI test and the pruning
    has device tests.
  - Saving to a cloud provider (Drive), and sharing into another app (the sheet was closed).
