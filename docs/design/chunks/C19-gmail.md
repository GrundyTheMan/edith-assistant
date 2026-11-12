# C19 — Gmail: search, read, draft, send

| | |
|---|---|
| Phase | 5 |
| Depends on | C17, C08 |
| Size | Medium |
| Read first | C17; 06-decisions **D5** (drafts only vs send — ask the owner), D11 |
| Since C22 | The tools work across every account and moved to `accounts/tools/` (`MailTools.kt`). `search_email` no longer takes Gmail syntax: it has `from`, `to`, `subject`, `text`, `unread_only`, `after`, `before`, `account` (C22 §4); `GmailQuery.build` turns them into Gmail's syntax. |

## Goal

"Any emails from the school this week?", "What did Sam say about Friday?", "Draft a reply saying I'll be there"
— against the owner's real Gmail. v1: **never sends**; drafts are created (with confirmation) for the owner to
send from Gmail, unless the owner decides otherwise (D5).

## Specification

### 1. `GmailClient` (REST, `google/gmail/`)
Base `https://gmail.googleapis.com/gmail/v1/users/me`. Tokens: `GmailReadonly` for reads, `GmailCompose` for drafts.
- `search(q, max)` → `GET /messages?q=&maxResults=` then `GET /messages/{id}?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date`
  (batch sequentially with a small concurrency limit, max 10 messages).
- `read(id)` → `GET /messages/{id}?format=full`; decode MIME parts (prefer `text/plain`, else strip `text/html`
  with Jsoup); base64url decode; cap 6,000 chars; strip quoted reply history (lines starting `>` and
  "On … wrote:" blocks) where obvious.
- `createDraft(to, subject, body, replyToMessageId?)` → `POST /drafts` with a raw RFC 2822 message (base64url);
  replies set `In-Reply-To`/`References` and `threadId`.
Capture real fixtures (message list, metadata, full message with multipart) — verify the API shape from Google's docs.

### 2. Tools (integration `google`)
| Tool | Params | Confirmation | Result |
|---|---|---|---|
| `search_email` | `query` String (Gmail search syntax, e.g. `from:school newer_than:7d`), `max_results` Integer = 5 | no | `1. Thu Oct 29 — Lincoln Elementary <office@…> — "Field trip Friday" — <snippet> [id:…]` |
| `read_email` | `message_id` | no | From, To, Date, Subject, then the cleaned body |
| `draft_email` | `to`, `subject`, `body`, `reply_to_message_id`? | **yes**: "Save this draft?" showing to/subject/body | `Draft saved in Gmail — open Gmail to send it.` |
Tool descriptions teach Gmail search syntax briefly (`from:`, `to:`, `subject:`, `newer_than:7d`, `is:unread`).

### 3. Privacy
Email content is sent only to the owner's Ollama server, only as tool results within the current turn (never
persisted — C08). Release builds never log subjects, senders or bodies.

## Tests
- MIME decoding: plain, HTML-only, multipart/alternative, base64url, quoted-history stripping, size cap.
- Search formatting; draft raw message encoding (headers, UTF-8 subject encoded per RFC 2047).
- Confirmation required for drafts; no send endpoint exists in the code (grep test).

## Acceptance criteria
- [ ] Owner's account: "Do I have any unread email from today?" matches Gmail. (Edith listed five; the owner hasn't
      compared them with Gmail yet.)
- [x] "Read the latest email from <sender>" summarises correctly.
- [x] "Draft a reply saying thanks" → confirmation → draft appears in Gmail Drafts (owner deletes it afterwards).

## As built (2026-11-02)

Where the code differs from the specification above, the code is right.

- **D5 changed the goal:** Edith may **send** email, always after the owner confirms a card showing the recipient,
  subject and the whole message (decided by the owner, recorded in `06-decisions.md`). The `gmail.compose` scope
  covers both drafts and sending, so C17's scopes are unchanged. Settings → Google account and
  `docs/google-setup.md` now say so.
- **`GmailClient`** (`google/gmail/GmailClient.kt`):
  - `search` lists messages, then fetches their metadata one at a time (at most 10). Headers: From, To, Subject,
    Date, Message-ID; dates come from `internalDate` in the device zone.
  - `read` uses `format=full`.
  - `createDraft` posts to `drafts`; `send` posts to `messages/send`. A reply carries `threadId`.
  - Errors: 401 → retry once, then Grant access; 403 `insufficientPermissions` → Grant access; 404 and 400 have
    plain messages.
- **`MailText`:**
  - The body is `text/plain` first, otherwise HTML made readable with C10's `PageReader`. Parts with a file name
    are skipped. Capped at 6,000 characters.
  - Quoted history is removed: "On … wrote:", "-----Original Message-----" and `>` lines.
  - The raw message is RFC 2822 with a UTF-8 base64 body; non-ASCII headers use RFC 2047. Line breaks in To and
    Subject become spaces, so the model can't add headers such as Bcc.
- **Tools** (`GmailTools.kt`, integration `google`):
  - Emails get short handles (`m1`, `m2`, …) in `GmailSession`, like the calendar's `e1`.
  - `search_email`, `read_email`: no confirmation.
  - `draft_email`: "Save this draft?" → **Save draft**. `send_email`: "Send this email?" → **Send**.
    - Both show To, Subject and the whole message, never a preview, so what the owner confirms is exactly what is
      saved or sent (D5). The first version cut the card at 1,500 characters while sending the full body; the
      2026-11-11 gap analysis found it and the cut was removed. Checked live: a 40-item packing-list draft showed
      every line and the sign-off, the buttons could still be reached, and Cancel saved nothing.
      (`build-logs/2026-11-11_10-20-30_check.log`: 208 unit tests, 107 device tests.)
    - A reply (`reply_to_message_id`) fills in the sender's address, "Re: …" and the thread.
  - The spec's "no send endpoint" grep test became: the only caller of `client.send(` is `GmailTools.kt`.

**Verified**
- `scripts/check.sh --device`: OK (`build-logs/2026-11-02_14-40-31_check.log`). 207 unit tests (12 new in
  `GmailTest`), lint clean, 107 device tests.
  - `GmailTest` (JVM, MockWebServer; the responses follow Gmail's API reference, made up):
    - MIME: plain preferred, HTML made readable, attachments skipped, base64url
    - quoted history; the 6,000 cap
    - the raw message: headers, RFC 2047 subject, reply headers, no header injection
    - search lines, handles and query parameters; read
    - a reply draft's card, thread and `In-Reply-To`; send's card and endpoint
    - **declining the send card through the real tool loop sends no request**
    - an unknown id sends nothing
- **Live on the emulator** (the owner's account):
  - "Do I have any unread email from today?" → five (VIZIO, Garbage Day, Synchrony HOME, Bricks & Minifigs,
    August Home).
  - "Read the latest email from August Home" → a correct summary of the battery email.
  - "Draft a reply to that email saying thanks" → the card (To noreply@august.com, "Re: Change your August Smart
    Lock batteries immediately") → the owner tapped Save draft → it appeared in Gmail Drafts.
  - No crash.
- **Not verified live:** a real send, and a real 401 or 403.
- **Seen:** Edith drafted to a no-reply address without saying so. Harmless for a draft; worth a warning before a
  send.

### Changes after the gap analysis (2026-11-11)
- **"Today" is the calendar day.** The first version told the model `"Today" is newer_than:1d`, which is the last
  24 hours. Gmail also reads `after:2026/11/11` as midnight *Pacific* time (its "Searching for messages" guide), so
  a plain date would start Michigan's day at 3 AM.
  - Now `search_email` turns `after:`/`before:` (and `newer:`/`older:`) dates into local midnight in epoch
    seconds, which the same guide gives as the way to be exact (`GmailQuery.localDates`).
  - The model can write `today`, `yesterday` or `tomorrow` instead of a date. In the live check it had written
    yesterday's date for "today".
  - Live: "Do I have any unread email from today?" → `is:unread after:today` → five from Nov 11.
- **Release logs don't quote what a parse error couldn't read.** A kotlinx parse error's message holds part of
  its input (a test proves it), and Gmail, Calendar, Ollama and the chat engine logged the whole exception. That
  text went to logcat and to the crash report's last log lines.
  - In release builds `Log` now shows only the type for a `SerializationException`, or for anything thrown inside
    kotlinx.serialization, and for each cause. Stack frames and other messages (hosts, HTTP codes) are kept.
    Debug builds are unchanged.
- **Still open:** search returns at most 5 by default and doesn't tell the model whether more match, so "you have
  five unread" can be an undercount.
- Verified: `scripts/check.sh --device` passed (`build-logs/2026-11-11_10-50-39_check.log`): 213 unit tests
  (3 new in `LogTest`, 2 new in `GmailTest`), lint clean, 107 device tests.
