# C19 — Gmail: search, read, draft

| | |
|---|---|
| Phase | 5 |
| Depends on | C17, C08 |
| Size | Medium |
| Read first | C17; 06-decisions **D5** (drafts only vs send — ask the owner), D11 |

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
- [ ] Owner's account: "Do I have any unread email from today?" matches Gmail.
- [ ] "Read the latest email from <sender>" summarises correctly.
- [ ] "Draft a reply saying thanks" → confirmation → draft appears in Gmail Drafts (owner deletes it afterwards).
