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
- [ ] Emulator: ask "make me a packing list for a beach day as a file" → file card; View/Save/Share work.
- [ ] "Send me that receipt photo again" → the original photo shown in Edith's bubble.
- [ ] Attach 11 photos over time with N=10 → the oldest shows "no longer available" and its file is gone (`run-as` listing).
- [ ] Restart the app → kept attachments still show; removed ones show the placeholder.
