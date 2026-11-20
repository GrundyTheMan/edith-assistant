# C65 — iCloud account on iOS, part 2: mail over IMAP and SMTP

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **Design** — two PRs: (a) the socket layer and IMAP read, (b) drafts and send over SMTP |
| Depends on | C58 (a development build **with `react-native-tcp-socket`**), C61 (mail tools, `mailText`, `rfc2822`), C64 (the iCloud account) |
| Unblocks | the owner's iCloud mail in every mail tool |
| Runs in | the development build only (raw TLS sockets) |
| Size | **XL** (a small IMAP client and a small SMTP client written in TypeScript) |
| Ports | C24 §2 (mail) |
| Read first | C24 spec **and** As built (the mail rules, `PEEK`, SPECIAL-USE, the throttling note, the SMTP-Sent duplicate check), RFC 3501 (IMAP4rev1), RFC 6154 (SPECIAL-USE), RFC 5321/4954/3207 (SMTP, AUTH, STARTTLS), C61 (`mailText`, `rfc2822`), D17 |

## Why
iCloud Mail is only reachable by IMAP/SMTP with the app-specific password. React Native has no raw sockets in Expo Go and there
is **no maintained pure-JS IMAP library that runs on Hermes** (the popular ones need Node's `net`/`tls`). So: add the native
module `react-native-tcp-socket` (a TCP/TLS socket for React Native) in the development build, and write a **small, strict IMAP
client** and a **small SMTP client** in TypeScript on top of an injected `Socket` interface. This is the largest piece of
plumbing in the roadmap: keep it small, tested with scripted servers, and stop to ask if it gets bigger than planned.

## Before you start (agent pre-checks; report each result in the PR)
1. **Prove the socket module:** in a throwaway screen of the development build, open TLS to `imap.mail.me.com:993` (`tls: true`), read the greeting (`* OK …`), send `a1 LOGOUT`. Then open `smtp.mail.me.com:587` plain, read `220`, send `EHLO`, send `STARTTLS`, and **upgrade the same socket to TLS** (verify the module can wrap an existing connected socket; if not, check whether iCloud also accepts implicit TLS on **465**, and use that instead and say so). Do **not** use the owner's password for this probe (a greeting needs no login).
2. **Check a MIME parser:** `postal-mime` (pure JS, handles multipart, encodings, charsets) on Hermes: feed it three saved sample messages (plain, HTML-only, multipart/alternative with an attachment). It relies on `TextDecoder` for non-UTF-8 charsets; if Hermes' `TextDecoder` can't decode ISO-8859-1/Windows-1252, add a small decoder table for those two. If it fails entirely, write a minimal MIME walker (boundary split, `Content-Transfer-Encoding` base64/quoted-printable, charset utf-8/latin1) — it is ~150 lines.
3. Ask the owner before adding **either** dependency (the AGENTS.md rule for libraries that need keep rules/size).

## Specification

### 1. Socket layer (`net/socket.ts`)
```ts
interface Socket { write(data: Uint8Array | string): Promise<void>; onData(cb: (chunk: Uint8Array) => void): void; onClose(cb: () => void): void; onError(cb: (e: Error) => void): void; close(): void; upgradeToTls?(host: string): Promise<void>; }
type Connect = (opts: { host: string; port: number; tls: boolean; timeoutMs: number }) => Promise<Socket>;
```
`net/tcpSocket.ts` implements it with `react-native-tcp-socket`; tests use a **scripted fake server** (`net/fakeServer.ts`: a list of expected lines and canned replies). Everything below depends only on `Connect`.

### 2. IMAP client (`icloud/imap.ts`)
One connection **per operation** (no IDLE), commands tagged `a1`, `a2`…; a response reader that understands status lines, untagged `*` data and **literals** `{123}\r\n…` (read exactly N bytes). Commands needed: `LOGIN` (quote/escape the arguments; **try the mail name first (the part before `@`), then the full address**, remember which worked in the key-value store (`icloud_login_style`, not secret)), `LIST "" "*" RETURN (SPECIAL-USE)` (or `LIST (SPECIAL-USE)`; find `\Drafts` and `\Sent` **by flag, never by name**), `SELECT INBOX` / `EXAMINE` (read-only for searches), `UID SEARCH` with `FROM`, `TO`, `SUBJECT`, `TEXT`, `UNSEEN`, `SINCE`, `BEFORE` (strings sent as IMAP literals when non-ASCII: `CHARSET UTF-8` with `{n}+` literals — **use literals, not quoted strings, for any non-ASCII text**), `UID FETCH <uids> (UID INTERNALDATE ENVELOPE BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT DATE MESSAGE-ID)])`, `UID FETCH <uid> BODY.PEEK[]`, `APPEND "Drafts" (\Draft) {n}` with the literal continuation `+`, `LOGOUT`.
- **Searching and reading must never mark mail as read:** only `PEEK` forms and `EXAMINE` for reads (a test asserts no `\Seen` store and no non-PEEK fetch).
- IMAP dates are **whole days**: send `SINCE`/`BEFORE` as day-granular and **filter exactly on the client** using each message's `INTERNALDATE` in the phone's zone.
- Newest first; `max` messages; "more match" when there were more UIDs than `max`.
- **Handles** map to `(UIDVALIDITY, UID)`; a changed `UIDVALIDITY` → "list the emails again."
- Parse ENVELOPE (nested parenthesised lists with NIL, quoted strings and literals) with a small recursive parser (`icloud/imapParse.ts`, pure, heavily tested), RFC 2047 encoded-words in subjects/names (UTF-8, ISO-8859-1; `B` and `Q`).
- Timeouts: connect 10 s, command 20 s; abort cleanly (`close()` the socket).

### 3. `ICloudMailSource` (implements `MailSource`)
`search`, `read` (the MIME body via the parser → `mail/mailText.ts`: prefer text/plain, HTML → readable text, strip quoted history, 6,000 cap), `draft` (build with C61's `rfc2822`, `APPEND` to the `\Drafts` folder with `\Draft`).

### 4. SMTP client (`icloud/smtp.ts`) — **second PR**
`smtp.mail.me.com:587`, **STARTTLS required** (refuse to send credentials before TLS), `EHLO edith`, `AUTH PLAIN` (or `LOGIN`) with the **full address** and the app-specific password (base64), `MAIL FROM:<addr>`, `RCPT TO:<each>`, `DATA` with **dot-stuffing** and CRLF.CRLF end, `QUIT`. Then `APPEND` a copy to the `\Sent` folder **unless iCloud already saves SMTP sends there** (check live; no duplicates). A reply sets `In-Reply-To`/`References` (C61's builder). Errors: 535 → needs sign-in; 5xx → plain message; never retry a send (a retry could send twice): on any failure after `DATA` was accepted by the server say "I'm not sure it sent: check Sent" rather than retry.
- `send_email` still requires the card (C61); this chunk does not touch the card.

### 5. Errors, throttling, privacy
`AUTHENTICATIONFAILED` → `needs_sign_in` ("The app-specific password stopped working…"); timeout/no network → `error` + Try again; iCloud may throttle many logins in a short time: **one connection per operation, never a retry loop**. Logs: account id only; **never** commands containing the password, never message text.

## Files
New: `net/socket.ts`, `net/tcpSocket.ts`, `net/fakeServer.ts`, `icloud/imap.ts`, `icloud/imapParse.ts`, `icloud/mime.ts` (or the `postal-mime` wrapper), `icloud/mailSource.ts`, `icloud/smtp.ts`, tests for each. Changed: `package.json` (+ `react-native-tcp-socket`, maybe `postal-mime`), `docs/icloud-setup.md`, `NATIVE_CHANGES.md` (C58: if the first build didn't include the socket module, this PR says **"needs a new build"**).

## Tests
Scripted-server tests for every command and response shape: literals, NIL, nested envelopes, encoded words, `LIST` with SPECIAL-USE flags, `UIDVALIDITY` change, search keys (incl. non-ASCII via literals), exact-date filtering across DST, **no `\Seen`** assertion, `APPEND` with `\Draft` to the flagged folder, `AUTHENTICATIONFAILED`, server drop mid-response, a 20 s command timeout; SMTP: STARTTLS before AUTH (refuse otherwise), dot-stuffing (a body line `.hi`), `RCPT` failures, a 535, reply headers, no retry after `DATA`; the add-account check wiring (C64) with a fake server; through C61's tool loop with an iCloud account (labels, "which account", reads leave mail unread).

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green (both PRs).
- [ ] **Owner:** "Any unread email today?" includes iCloud's, labelled, and **they are still unread afterwards in Apple's Mail**; "Draft a reply saying thanks" → card → the draft appears in iCloud Drafts; "Send it" (to the owner's own address) → card → arrives, and appears once in Sent; a revoked password → "stopped working".

## Risks and unverified assumptions
- The socket module's TLS behaviour on iOS (certificate validation: it must validate `imap.mail.me.com`; verify the option name and that it does not default to "accept any"); STARTTLS upgrade support.
- iCloud IMAP quirks (folder names, SPECIAL-USE support, how SMTP and Sent interact).
- XL size: if the IMAP/SMTP clients exceed ~800 lines of TypeScript, **stop and ask the owner** whether to continue or to drop iCloud mail.

## Not in this chunk
IDLE/push, attachments (names only), flags changes, moving/deleting mail, other IMAP providers.
