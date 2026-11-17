# Spec for Jarvis: the "Edith feed" (work mail, calendar and to-dos for Edith)

**Written for:** the AI agent that works on the Jarvis project (`D:\Dev Projects\jarvis`). This document is the
whole job. It can be done without asking questions, except where part 12 says to ask the owner.
**Written by:** the Edith agent, 2026-11-16. **Updated 2026-11-16 for Tailscale** (the owner's phone reaches the PC over Tailscale, not
only the home Wi-Fi: parts 1, 2, 5, 9, 10 and 13 changed; they are marked "Tailscale"). Edith's own side (reading this feed) is a separate chunk in the Edith repo;
the contract in parts 5-9 is what both sides build to. If you must change the contract, say so to the owner, and
change this document in the same PR.

## 1. Why this exists

The owner's work email, calendar and to-dos exist only in **Outlook (classic) on this PC**. Jarvis reads them through
the Outlook helper (`src/main/outlook/`, COM through PowerShell, accounts with `auth: 'outlook-com'`). There is no
password or token anywhere that another device could use, so Edith (an Android app on the owner's phone) cannot sign
in to the work account. The fix: **Jarvis serves what it already reads, over the home network or the owner's Tailscale network, to the paired phone.**
Edith asks when she needs something; nothing is copied to the cloud; with the PC off or Jarvis closed, Edith simply
has no work data (the owner accepts this).

Hard rules, from the owner:
- **Read-only.** The only thing the feed may change is **saving a draft** (part 7.5). It never sends, moves, deletes,
  marks read/unread, flags, accepts or declines anything. Same promise as Jarvis's Mail page.
- **No work-account credential ever leaves the PC.** The phone gets a revocable key that opens this feed and nothing else.
- **Home network and the owner's Tailscale network only.** Not reachable from the open internet, no cloud relay, no port
  forwarding, no UPnP. Tailscale (a private network between the owner's own devices, addresses `100.64.0.0/10`) is how the
  phone reaches the PC away from home; the PC and phone are signed in to the owner's account there. Jarvis installs, starts
  and configures nothing of Tailscale: it only accepts callers that arrive from its address range.
- **Off by default.** Nothing is served until the owner switches it on and picks what to share.
- **Contents are never logged** (subjects, bodies, names, addresses, event titles, task titles, tokens, codes). Logs
  say what was asked, how many items came back and how long it took, like `src/main/outlook/helper.ts` does.

## 2. What the owner sees in Jarvis (Settings → new section "Edith")

1. A switch **"Share work data with Edith on my phone"**. Off by default.
2. **Address** (read-only text, with a Copy button): `https://<this PC's LAN IPv4>:<port>`, e.g.
   `https://192.168.1.20:47821`. Computed from `os.networkInterfaces()`: every non-internal IPv4 address in a private
   range (10/8, 172.16/12, 192.168/16) **and, for Tailscale, in `100.64.0.0/10`**. Show them one per line, each labelled
   "home network" or "Tailscale" (the Tailscale one is the interface whose name starts with `Tailscale`, or failing that any
   address in `100.64.0.0/10`), with its own Copy button. The Tailscale address is the one that works away from home, and it
   does not change when the owner changes Wi-Fi. If none: "This PC isn't on a home network or Tailscale."
3. **Port** (number box, default `47821`; valid 1024-65535; changing it restarts the server). If the port is busy
   the section says so in plain words and the switch shows off.
4. **What to share** (nothing ticked by default): one tick-box per
   - mail account (only `auth: 'outlook-com'` accounts are offered; list them by `label` and `email`),
   - Outlook (classic) calendar (connections of kind `outlook-desktop`; one tick per connection, then per calendar
     inside it using the existing per-calendar enable flags),
   - to-do source (part 8; offered only once part 8 exists).
   Anything not ticked is invisible to the feed (not listed, and 404 if asked for by id).
5. **"Pair a phone"** button → shows a **pairing code** and a 5-minute countdown (part 6). Shows the certificate
   fingerprint in short form too (part 6.3).
6. **Paired phones** list: name, paired on, last used, and a **Remove** button per phone (revokes at once). A
   **"Reset (remove all phones and make a new certificate)"** button with a `confirm()`.
7. A status line: "Listening on …", "Off", "Port 47821 is in use", "Outlook isn't answering" (from the last request).
8. A note under the switch: "Windows may ask to allow Jarvis on private networks. Choose **Private networks only**. Windows often
   treats the Tailscale connection as a *public* network: if the phone can't reach this PC over Tailscale, add a Windows Firewall
   rule that allows this port from `100.64.0.0/10` only." Jarvis shows the words and the exact port; it **must not** change
   firewall settings itself.
9. Jarvis must keep working with the section off exactly as today: no server, no certificate, no new background work.

Follow the existing Settings page patterns (`src/renderer/src/components/SettingsView.tsx`, styles under
`src/renderer/src/styles/`, variables in `base.css`). New channels follow `src/shared/channels/<area>.ts` +
`src/main/ipc/<area>.ts` + `src/preload/index.ts` exactly as the AGENTS.md layout describes (new area: `feed`).

## 3. Files to add or change

New (main process):
- `src/main/feed/server.ts` - starts/stops the HTTPS server, routing, auth, rate limit, private-address check.
- `src/main/feed/routes.ts` - one function per endpoint; calls the existing services (below); pure mapping in `map.ts`.
- `src/main/feed/map.ts` - **pure** functions: Jarvis types → the wire types in part 7 (truncation, field picking). Unit-tested.
- `src/main/feed/pairing.ts` - pairing codes, device tokens, hashing, wrong-try counting. Pure where possible, unit-tested.
- `src/main/feed/cert.ts` - makes, stores and loads the self-signed certificate; computes the fingerprint.
- `src/main/feed/store.ts` - reads/writes the feed's settings (part 4).
- `src/main/ipc/feed.ts`, `src/shared/channels/feed.ts` - the Settings page's channels (get state, switch on/off, set port,
  set shared items, start pairing, list/remove phones, reset).
- `src/shared/feed.ts` - wire types shared by main and tests (part 7), and the settings types.
- Tests next to each file (`*.test.ts`, vitest, as the rest of the project) and one e2e (part 10).

Changed: `src/main/index.ts` (register the feed, start it at launch only when switched on, stop on quit),
`src/preload/index.ts` and `src/shared/ipc.ts` (the new area), `SettingsView.tsx` and a new styles file for the section,
`README.md` (a short paragraph under Accounts or Mail: what the feed is and that it is read-only except drafts),
`AGENTS.md` ("Where things are" row for `src/main/feed/`), and `tools/outlook-helper/outlook.ps1` only if part 8 needs a task op.

Reuse, don't copy: mail via `src/main/mail/service.ts` (`query`, `body`, `saveDraft`, `saveNewDraft`), accounts via
`src/main/mail/accounts.ts`, calendar via `src/main/calendar/service.ts` (`events(range)`, connections via `list()`),
Outlook through `src/main/outlook/helper.ts` (it already serialises requests with a mutex and has the e2e fake
`JARVIS_E2E_OUTLOOK`). Do not open Outlook yourself and do not start a second PowerShell helper.

## 4. Settings stored

In Jarvis's existing settings store (`store.ts`, `settings.json` in `app.getPath('userData')`), a new object, absent = defaults:

```ts
interface FeedSettings {
  enabled: boolean            // default false
  port: number                // default 47821
  shareMail: string[]         // mail account ids (only outlook-com accounts)
  shareCalendars: string[]    // `${connectionId}:${calendarId}` of enabled Outlook calendars
  shareTasks: string[]        // task source ids (part 8); empty until part 8 exists
  devices: { id: string; name: string; tokenHash: string; created: number; lastSeen: number }[]
}
```

- `tokenHash` = hex SHA-256 of the device token. **The token itself is never stored.**
- The certificate and private key live in `userData/feed/` (`cert.pem`, `key.enc`). The key file is encrypted with
  Electron `safeStorage` (the same way Jarvis stores mail passwords); if `safeStorage.isEncryptionAvailable()` is false,
  refuse to enable the feed and say why in the Settings section.
- Account ids in `shareMail` that no longer exist are ignored (and dropped on the next save).

## 5. Transport, address and access rules

1. **HTTPS only**, Node `https.createServer` in the main process, TLS 1.2+ (set `minVersion: 'TLSv1.2'`), listening on
   `0.0.0.0:<port>`. No plain HTTP listener at all.
2. **Certificate:** self-signed, ECDSA P-256 (or RSA 2048 if the library can't do ECDSA), valid 10 years, CN `Jarvis feed`,
   subjectAltName = the PC's hostname and its current private IPv4 addresses, **including its Tailscale address** (Edith ignores the name and pins the
   fingerprint, so a changed IP does not break her). Make it **once**, on first enable. It changes only on "Reset".
   Use the small pure-JS `selfsigned` package (check `package.json`; if it is not there, add it as a dependency and
   say so in the PR). If you know a safer way that adds no dependency, that is fine; the requirement is only: a
   stable self-signed certificate whose **fingerprint = lowercase hex SHA-256 of the DER-encoded certificate** (this is
   what Node's `X509Certificate.fingerprint256` gives, minus the colons).
3. **Only private callers:** on every connection, check `req.socket.remoteAddress`. Allow only loopback (`127.0.0.1`,
   `::1`), IPv4 10/8, 172.16/12, 192.168/16, link-local 169.254/16, **Tailscale's `100.64.0.0/10` (100.64.0.0 to 100.127.255.255)**,
   and IPv4-mapped forms of those (`::ffff:192.168.x.x`, `::ffff:100.80.x.x`), IPv6 `fe80::/10` and `fc00::/7` (this covers
   Tailscale's `fd7a:115c:a1e0::/48`). Anything else gets the connection destroyed without a response. (`100.64.0.0/10` is also the range ISPs use for shared
   addressing, but a caller only arrives from it if it reached this PC through Tailscale or the PC's own network; the pairing
   code and the device token are what actually protect the feed, and the address check is a second wall.) Pure function
   `isPrivateAddress(addr)`, unit-tested with each range's edges.
4. **Auth:** every endpoint except `GET /v1/hello` and `POST /v1/pair` needs `Authorization: Bearer <device token>`.
   Compare `sha256(token)` to each `devices[].tokenHash` with `crypto.timingSafeEqual`. No match → `401`
   `{"error":{"code":"UNAUTHORIZED","message":"…"}}`. A match updates `lastSeen` (write at most once a minute).
5. **Rate limit:** per remote address, after 10 failed auths (401) or failed pairings within 60 s, answer `429` with
   `Retry-After: 60` for the next 60 s. Count in memory.
6. **Limits:** request bodies at most 64 KB (`413` beyond); per-request timeout 60 s (`504` `TIMEOUT`); at most 4
   requests being worked on at once (`503` `BUSY` beyond; the Outlook helper is single-file anyway).
7. **Headers:** every response `Content-Type: application/json; charset=utf-8`, `Cache-Control: no-store`, and
   `X-Content-Type-Options: nosniff`. No CORS headers (a phone app is not a browser page).
8. Methods: `GET` everywhere except `POST /v1/pair` and `POST /v1/mail/drafts`. Any other method → `405`.
9. **Versioning:** every path starts with `/v1/`. `GET /v1/hello` returns `{"app":"jarvis-feed","version":1}`. Adding fields
   later is allowed; removing or renaming one needs `/v2/`.

## 6. Pairing

Goal: the phone learns the PC's address, trusts exactly this PC's certificate, and gets a revocable device token,
without the work account's sign-in ever being involved.

1. The owner taps **Pair a phone** on the PC. Jarvis makes a **code**: 10 characters from the base-32 alphabet
   `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no 0/O/1/I), shown as `ABCDE-FGHJK`, from `crypto.randomInt`. Valid **5 minutes**,
   **one use**, dies after **5 wrong tries**. Only one code exists at a time (a new one replaces the old).
2. The owner types the address and the code into Edith.
3. Edith connects (trusting any certificate for this one request, because she has nothing to compare yet), reads the
   certificate fingerprint, and **shows it to the owner**. Jarvis shows the same fingerprint on the PC in groups of
   four hex characters for the first 24 characters, e.g. `a559 fd3a cd73 9f17 e54c 7701`. The owner confirms they match.
4. Edith then calls `POST /v1/pair` (below) **on a connection that pins that fingerprint**.

### 6.1 `POST /v1/pair` (no Authorization header)

Request:
```json
{ "name": "Pixel 9", "proof": "<hex>" }
```
`proof` = lowercase hex of `HMAC-SHA256(key = the code without the dash, uppercase, message = the certificate
fingerprint as lowercase hex without colons)`. Jarvis computes the same with its own certificate and the current code
and compares with `timingSafeEqual`. (Sending a proof, not the code, means a stranger who answers in the PC's place
learns nothing usable.)

- Success `200`: `{ "token": "<43-char base64url of 32 random bytes>", "deviceId": "<uuid>", "pcName": "<os.hostname()>", "version": 1 }`.
  Jarvis stores `{id, name (trimmed, at most 40 characters, default "Phone"), tokenHash, created, lastSeen}` and **burns the
  code**. The token is returned this once and never again.
- No current code, expired, burned or too many tries → `403` `{"error":{"code":"PAIRING_REFUSED","message":"…"}}` (same
  message for all of them, so nothing is revealed). A wrong proof counts as a try.
- The Settings page updates at once (phone appears in the list, the code disappears).

### 6.2 Removing a phone
Remove in Settings deletes the device entry; its token fails with `401` from the next request. Reset also deletes the
certificate and key (the next enable makes a new pair) and clears all devices.

### 6.3 Where the fingerprint is shown
Settings shows it next to the pairing code (`GET` via the Settings channel, not an HTTP endpoint). `GET /v1/hello`
does **not** return it (Edith reads it from the TLS handshake).

## 7. Endpoints

Conventions: all times are **milliseconds since 1970 UTC as integers**. Ids are Jarvis's own ids, opaque strings; the
phone URL-encodes them (mail ids contain `:`). Lists are newest-first unless said. Every list response is
`{ "items": [...], "more": boolean }` and `more` is true when the limit cut the list off. Errors are always
`{ "error": { "code": "<CODE>", "message": "<one plain sentence>" } }` with these codes: `UNAUTHORIZED` 401,
`NOT_SHARED` 404 (also for things that do not exist, so the phone can't probe), `BAD_REQUEST` 400,
`OUTLOOK_UNAVAILABLE` 503 (Outlook isn't running/answering, or the helper reported OFFLINE/FAILED),
`BUSY` 503, `TIMEOUT` 504, `PAIRING_REFUSED` 403, `FEED_ERROR` 500 (anything else; message generic, details only in Jarvis's log).

### 7.1 `GET /v1/hello` (no auth)
`{"app":"jarvis-feed","version":1}`.

### 7.2 `GET /v1/shared`
What Edith may ask for; the phone shows this in its account list.
```json
{
  "pcName": "BEN-PC",
  "mail": [ { "id": "<account id>", "label": "Work", "email": "name@company.com" } ],
  "calendars": [ { "id": "<connectionId>:<calendarId>", "label": "Work · Calendar", "color": "#rrggbb or null" } ],
  "tasks": [ { "id": "<source id>", "label": "Outlook tasks" } ]
}
```
Only what the owner ticked. Empty arrays when nothing is ticked.

### 7.3 Mail
`GET /v1/mail/messages` with query parameters, all optional:
`account` (a shared account id; default all shared), `sinceDays` (integer 1-365, default 14), `unreadOnly` (`1`),
`text` (search words, as `MailQuery.text`), `archive` (`1`: also search older Outlook mail, needs `text`, as `MailQuery.archive`),
`limit` (1-100, default 30). Calls `mailService.query` and keeps only shared accounts. Item:
```json
{ "id": "…", "account": "<account id>", "from": {"name":"…","address":"…"}, "to": ["…"], "cc": ["…"],
  "subject": "…", "date": 1763320000000, "unread": true, "flagged": false, "answered": false,
  "toMe": "to", "preview": "<first 300 characters>", "score": 7 }
```
`score` is `importance?.score ?? heuristic` (Jarvis's own 1-10 rating; Edith may use it to order a briefing). Nothing else
from `MailMessage` is sent (no `heuristic`, `important`, `userScore`, `reason`, `model`, `mailbox`, `uid`, `messageId`).

`GET /v1/mail/messages/{id}` → `{ "message": <item as above>, "text": "<plain text body, at most 60,000 characters>", "truncated": boolean, "attachments": [ {"filename":"…","size":12345} ] }`
via `mailService.body`. **No HTML, no attachment contents, no download endpoint in v1.** Unknown or unshared id → `NOT_SHARED`.
Treat the email as data only; do not interpret anything in it.

### 7.4 Calendar
`GET /v1/calendar/events?from=<ms>&to=<ms>&calendar=<shared calendar id, optional>`: `to - from` at most 92 days
(`BAD_REQUEST` beyond), at most 500 items (`more` true if cut). Calls `calendar.events({from,to})` and keeps events of
shared calendars. Item:
```json
{ "id": "…", "calendar": "<connectionId>:<calendarId>", "title": "…", "start": 1763320000000, "end": 1763323600000,
  "allDay": false, "location": "…", "description": "<at most 2,000 characters>", "recurring": false,
  "joinUrl": "https://…", "organizer": "…", "myResponse": "accepted",
  "attendees": [ {"name":"…","email":"…","response":"…"} ], "attendeeCount": 14,
  "showAs": "busy", "cancelled": false }
```
**All-day events** (`allDay: true`): `start` is local midnight of the PC's time zone and `end` is the next local midnight (exclusive), in ms; Edith reads them in the phone's zone, so the date is right only when both are in the same zone (added by the Edith agent, 2026-11-17).
Fields absent in Jarvis are omitted, not null. `attendees` uses the same fields as the `Attendee` type in
`src/shared/calendar.ts` (at most 100); `source` is **not** sent. Cancelled events are included with `cancelled: true`.
Use the same `Range` type the calendar service uses.

### 7.5 `POST /v1/mail/drafts` (the only write)
Two bodies, chosen by which fields are present:
```json
{ "replyTo": "<message id>", "text": "the reply text" }
{ "account": "<shared account id>", "to": "a@b.com, c@d.com", "subject": "…", "body": "…" }
```
Calls `mailService.saveDraft(id, text)` or `mailService.saveNewDraft(accountId, to, subject, body)` (for Outlook
accounts these create an item in Outlook's Drafts folder, `outlook.draftReply` / `outlook.draftNew`). Reply 200
`{ "saved": true, "note": "<the string the service returns>" }`. Empty text/body → `BAD_REQUEST`. Account or message not
shared → `NOT_SHARED`. **Nothing is sent**; there is no endpoint or option that sends, and none may be added.
`text`/`body` at most 50,000 characters, `subject` at most 300, `to` at most 1,000. Jarvis logs
`Edith saved a draft (reply|new)` with no contents. (The phone asks the owner to approve every draft before calling this.)

## 8. To-dos (second step; do after parts 1-7 work end to end)

What exists today: Jarvis's tasks are **Google Tasks only** (`src/main/tasks/`), and Edith already reads Google Tasks
herself. The owner's *work* to-dos are in Outlook (classic) and Jarvis cannot read them yet. So this step adds a
**read-only** Outlook Tasks source to the Outlook helper, then serves it:

1. Extend `tools/outlook-helper/outlook.ps1` with ops `tasklists` and `tasks` (Outlook's default Tasks folder,
   `olFolderTasks` = 13, per store like the existing mail ops; completed tasks only when asked) and map them in
   `src/main/outlook/map.ts` with the same parse/validate style and tests as the existing ops. Extend the e2e fake
   (`JARVIS_E2E_OUTLOOK`) with `taskLists` and `tasks`.
2. **Decided by the owner (2026-11-16): the work to-dos are Outlook Tasks.** Build only that. (Flagged emails, Microsoft To Do and Google Tasks are out of scope; if the owner later wants flagged emails, serve them as tasks with `title` = the subject.)
3. Endpoints:
   - `GET /v1/tasks/lists` → `{ "items": [ {"id":"<source id>:<list id>","title":"…"} ], "more": false }`
   - `GET /v1/tasks?list=<id>&completed=<0|1>` → `{ "items": [ { "id":"…","title":"…","notes":"<at most 2,000 characters>","due":"YYYY-MM-DD","done":false,"completed":1763320000000,"importance":"normal|high|low" } ], "more": false }` (omit fields that are absent; at most 500).
   Read-only: no create, edit or complete endpoint in v1.
4. Settings (part 2.4) gets its tick-box per source.

## 9. Behaviour details that are easy to get wrong

- **Outlook not running or locked** (the helper's OFFLINE/FAILED codes) → `OUTLOOK_UNAVAILABLE`, never a 500, never a hang
  past the 60 s timeout. Do not try to start Outlook yourself beyond what the helper already does.
- **Hidden start:** Jarvis started at sign-in with the tray on stays hidden; the server must run in that state.
- **Quit/sleep:** the server stops with the app. After the PC wakes, the listener should still work (it listens on `0.0.0.0`,
  so it already covers an interface that appears later, such as Tailscale connecting after Jarvis starts); check
  `os.networkInterfaces()` every 60 s while enabled, and when the set of private or Tailscale addresses changes, **refresh the
  Settings address display** and restart the listener only if it has stopped answering on its port.
- **Tailscale off or signed out:** the Tailscale address simply isn't listed and the phone can't reach the PC from outside; nothing
  else changes. Jarvis never starts Tailscale or reads its account.
- **Another VPN holding `100.64.0.0/10`** (NordVPN's Meshnet does) sends Tailscale's traffic to the wrong adapter and breaks it; the
  owner has removed NordVPN. If the Tailscale address is missing or unreachable, the Settings help text may say so, nothing more.
- **Several windows / single-instance:** only the primary instance runs the server (`requestSingleInstanceLock` already guards this).
- **Never block the main process:** each request is `async`; no sync file or crypto loops except the one-off certificate creation, which may run in a worker or at least not during a request.
- **Truncation is by characters, not bytes**, with no splitting of surrogate pairs.
- **IDs from the phone** are looked up in the shared set first; never pass phone input to PowerShell or a file path.
- **Prompt injection:** the feed passes mail text through unchanged as data. Nothing in Jarvis may act on it.
- **Dependencies:** no web framework; Node's `https` and a ~100-line router are enough.

## 10. Tests and checks (all must pass: `npm run typecheck`, `npm test`, `npm run build`)

Unit tests (vitest), at least:
- `isPrivateAddress`: each allowed range at its first/last address and the first address outside it; IPv4-mapped IPv6; garbage input.
  **Tailscale:** `100.64.0.0` and `100.127.255.255` allowed, `100.63.255.255` and `100.128.0.0` refused, `::ffff:100.80.204.24` and
  `fd7a:115c:a1e0::1` allowed.
- Address listing: given a fake `networkInterfaces()` with a Wi-Fi `192.168.1.20`, a `Tailscale` `100.64.0.1`, a loopback and a public
  address, only the first two are listed, with the right labels.
- Pairing: code format and alphabet; expiry at 5 minutes (inject a clock); one use; death after 5 wrong tries; a new code replaces the old; proof computed from the example in the next bullet verifies, a changed fingerprint or code does not.
- Proof vector: with code `ABCDE-FGHJK` and fingerprint `00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff`, compute `HMAC-SHA256(key="ABCDEFGHJK", msg=<that fingerprint string>)` once, put the hex in the test, and check it. (The Edith side uses the identical vector.)
- Auth: valid token, wrong token, missing header, a token whose hash is of a removed device; the 429 after 10 failures and its release after 60 s.
- `map.ts`: every wire type, truncations (60,000 / 2,000 / 300 chars), omitted-not-null fields, no extra fields leak (assert the exact key set of each mapped item).
- Shared-set filtering: an unshared account/calendar is `NOT_SHARED` and absent from lists.
- Draft endpoint: both shapes; empty text rejected; limits; and **a test that the router has no route that sends, moves, deletes or marks anything** (list the registered method+path pairs and compare with an expected list).
- Certificate: fingerprint is stable across a restart (load, not remake); reset makes a new one.

e2e (Playwright as the project does, with `JARVIS_E2E_OUTLOOK`): switch the feed on in Settings, tick the fake account and
calendar, pair with `fetch` from the test using the code shown (pin the fingerprint with a custom `https.Agent`
`checkServerIdentity`), list mail, read a body, list events, save a reply draft and see it in the fake's `actions`,
remove the phone and see 401. Under `JARVIS_E2E` use an ephemeral port and a temp `userData`.

Manual check list for the PR (say which were done and which were not): Windows firewall prompt appears once and "Private" works;
the phone on the same Wi-Fi reaches the address; **the phone on mobile data (Wi-Fi off) with Tailscale on reaches the PC's Tailscale
address, and whether a firewall rule was needed**; the PC's other Wi-Fi/VPN states; Jarvis hidden in the tray still serves;
Outlook closed → `OUTLOOK_UNAVAILABLE`.

## 11. Order of work and PR

1. Settings types, store, certificate, pairing, server skeleton with `/v1/hello`, `/v1/pair`, auth, private-address check, rate limit; Settings section (switch, address, port, pair, phones, reset). Tests.
2. `/v1/shared`, mail list, mail body, calendar events, drafts, the "what to share" ticks. Tests, e2e.
3. To-dos (part 8, Outlook Tasks).
One PR per step, branch `feed-1-…` etc., merge commit, as your project does. Update `README.md` and `AGENTS.md` in the same PR.
Stop and tell the owner if Outlook's COM access does something unexpected under load (the helper is single-file; a phone
refreshing several lists at once must queue, not fail).

## 12. Questions to ask the owner (do not guess)

1. (Answered: the work to-dos are Outlook Tasks. No need to ask.)
2. Is it right that **only** `outlook-com` mail accounts and `outlook-desktop` calendars can be shared, or should other Jarvis accounts also be offerable? (default: only those)
3. Is the default port `47821` fine, or does something on the PC already use it?

## 13. What Edith will do with this (so you can see the other side of the contract)

Edith stores the PC address (the owner can use the Tailscale address, which works at home and away), the pinned fingerprint and the device token (in Android Keystore-backed storage), shows "Work
(via Jarvis)" as one more account next to Google/Microsoft/iCloud, and calls the endpoints above only when the owner asks
for something that needs work data. She shows the owner every draft and waits for approval before `POST /v1/mail/drafts`.
She never sends a request to any host except the paired address. When the PC isn't reachable she says so. The wire
types in part 7 are the contract: field names and shapes there are final unless both sides change this document.

## 14. As built on the Jarvis side (2026-11-16, by the Jarvis agent)

Built on three stacked branches in the Jarvis repo, committed locally, **not pushed and no PR yet**: `feed-1-server`
(certificate, pairing, HTTPS server, Settings section), `feed-2-data` (shared, mail, calendar, drafts), `feed-3-tasks`
(Outlook tasks). `feed-3-tasks` holds all three. Code is in `src/main/feed/`; 951 unit tests pass, `npm run typecheck` and a build pass.

**Where the build differs from the text above (Edith, build to this):**
- **Tailscale** is allowed: callers from `100.64.0.0/10` and `fd7a:115c:a1e0::/48` are accepted, and Settings lists the PC's Tailscale
  address (labelled) next to its home-network one. The owner can pair with either address; the Tailscale one works away from home.
- **Attendees have no `email`.** Jarvis's calendar keeps only each attendee's name and response, so `attendees` items are
  `{ "name", "response"? }`. `organizer` is a display name.
- **Tasks:** the source id is the Outlook **mail account id** (each Outlook account's own default Tasks folder), and there is
  one list per source: `GET /v1/tasks/lists` → `{ "id": "<accountId>:default", "title": "<label> · Outlook tasks" }`;
  `GET /v1/tasks?list=<accountId>:default&completed=0|1`. A task id is `<accountId>:<Outlook entry id>`. `importance` is
  `low|normal|high`; `due` is `YYYY-MM-DD` (omitted when none); `completed` (ms) only on a done task. `/v1/shared` lists the
  same sources under `tasks`.
- **Calendar:** only Outlook (classic) calendars can be shared (spec 12.2's default); a Google or other calendar that is ticked
  or asked for is `NOT_SHARED`. The phone should URL-encode ids (`outlook:main` contains a colon).
- **Settings storage:** the feed's settings are in `userData/feed/feed.json`, not `settings.json` (the Settings page saves
  that file whole and would erase them). Paired phones are stored as token hashes only.
- **Errors:** a path the feed doesn't know is `404 NOT_SHARED`; a known path with the wrong method is `405`; the 429 body
  code is `TOO_MANY`; a body over 64 KB is `413 TOO_LARGE`.
- **Port:** 47821 is the default (spec 12.3 not asked; the owner can change it in Settings). The PC's Windows Firewall may
  need a rule for Tailscale (Windows often treats it as a public network); the Settings text says so, and Jarvis doesn't change the firewall.
- **Pinning in Node tests:** Node skips `checkServerIdentity` for a self-signed certificate unless the certificate is also
  given as `ca`, and reuses TLS sessions; the Jarvis tests pin with `ca` plus `agent: false`. Android's own client has no such trap.
- Pairing proof vector confirmed: code `ABCDE-FGHJK`, fingerprint `0011…eeff` → `c1fdf40ac14a134c3754fdc75a316a9a139f730dcbac5b9dc75f5d5226d6f1de`.

**Not verified:** the Outlook tasks op against a real Outlook (the PowerShell parses and the TypeScript side is tested against a
stand-in only); the Settings card on screen; the Windows Firewall prompt; a real phone over Wi-Fi or Tailscale; the Playwright e2e
of part 10 was **not written** (the HTTPS round trip is covered by a unit test, `feed.int.test.ts`, against stand-in data).

## 15. Follow-up request (2026-11-17, from Edith's side): log how long each request takes

**Why.** The owner paired Edith with the real Jarvis (it works) and then asked her about work email. The request **timed
out and Edith never answered**. Edith's side had a defect (a blocking call that her time limit could not interrupt; fixed on
her side and waiting for her next release) and gave a Jarvis read only 10 seconds. She now waits **45 seconds** per read, and
cancels the call when her limit or the owner's Stop fires. We still don't know whether classic Outlook, through the helper,
answers inside 45 seconds on the owner's real mailbox, and Jarvis's log had **no trace of the request at all**: the
"Edith asked for mail: N returned" line is only written when a search finishes, so a search that is slow or never finishes
leaves nothing. We cannot tune anything until the feed tells us where the time goes.

**Do this (no change to the wire contract in part 7; nothing here is visible to Edith):**

1. **One log line per request, when it ends**, in Jarvis's normal log under the existing `feed` tag, at INFO:
   `GET /v1/mail/messages 200 1840 ms (queued 0 ms, Outlook 1790 ms) 37 items`. Fields: method, **route template**
   (`/v1/mail/messages/{id}`, never the real id), status, total milliseconds, how long it **waited in the helper's queue**
   (parts 9 and 11 say concurrent calls queue), how long the **Outlook/helper call itself** took, and the number of items
   returned (omit for requests without a list). For a mail list add whether `archive` was on and the `limit` and `sinceDays`
   actually used. Errors log the error `code` from part 5 (`OUTLOOK_UNAVAILABLE`, `TIMEOUT`, ...) and the status.
2. **A "still running" line for slow requests.** If a request has not finished after **10 s**, log once
   `GET /v1/mail/messages still running after 10 s (queued 0 ms)`, and again at **30 s**. A hang then leaves a trace even
   if it never ends.
3. **A line when the phone gives up.** Edith now cancels her call when her time limit or the owner's Stop fires. If the
   connection closes before Jarvis has replied, log `GET /v1/mail/messages: phone closed the connection after 12040 ms` and
   **stop the work if it is cheap to do so** (do not leave a 1000-message Outlook read running for nobody). If the helper
   cannot be interrupted, say so in the log line and let it finish, but do not queue more work behind it for the same request.
4. **Privacy is part of the requirement.** Never log the bearer token, a pairing code or proof, mail text, subject, sender,
   recipient, a search `text`/`from`/`to` value, a draft body, a calendar title or a task title. Route templates, counts, ids
   of **accounts** (not messages) and times are fine. A test (below) must prove it.
5. **Optional, small:** under Settings → Edith, a read-only **"Recent requests"** list of the last 20 (time, route, status,
   milliseconds), held in memory only (gone when Jarvis quits, not written to disk). Its purpose is that the owner can look at it
   right after a failed question without opening log files. Skip it if it needs more than a small component.
6. **Look at the slow path while you are there, and tell the owner, do not guess.** `GET /v1/mail/messages` asks the cache for
   up to `limit: 1000` messages. If the helper turns out to be the slow part (Outlook COM under load), report the numbers
   (typical and worst) in part 14 and propose, but do not make without asking, a change such as a smaller default `limit`
   or a time-boxed read that returns what it has with a `truncated: true` flag. Any change to the wire shapes in part 7 must be
   agreed with Edith's side first, because the contract says field names and shapes are final unless both sides change this
   document.

**Tests (vitest), at least:**
- a finished request writes exactly one summary line with the route **template**, status, duration, queue and Outlook times
  (use a fake clock and a fake data source that "takes" 1,840 ms);
- a request that runs past 10 s writes the "still running" line once, and one at 30 s, and still writes its summary;
- a connection that closes mid-request writes the "phone closed the connection" line and does not log it as an error;
- **no log line from any request contains** the token, the pairing code, a mail subject/sender/body, or a search value
  (feed those in through a fake data source and assert on every line written);
- error responses log their code and status.

**Report back (add to part 14, "As built"):** the log format you chose, whether `Recent requests` was built, and, after the owner
asks Edith one work-mail question on the real mailbox, the numbers the log shows (queued / Outlook / total) so the next step
(a smaller `limit`, a time-boxed read, or none) rests on measurements.

One PR, branch `feed-4-timing`, merge commit, as before. Update the Jarvis `README.md` and `AGENTS.md` if they describe the feed.
