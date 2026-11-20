# C66b — The work account through Jarvis on iOS (mail, calendar, to-dos)

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **Design — the transport and pairing moved to C66a (decision D39); this chunk is only the three read-only sources and their tools.** The text below that describes pinning (§2, most of §3 and the D29 table) is **superseded**: use C66a's client. Kept for the fallback if the owner ever picks D29 A |
| Depends on | **C66a** (the Jarvis connection), C58 (the accounts need a development build), C59 (accounts), C60, C61, C62 (the tools that read sources) |
| Unblocks | C67 (the printer through Jarvis uses the same transport and account) |
| Runs in | the development build (the account screens), over C66a's Expo Go-capable client |
| Size | **XL** |
| Ports | C38 (`jarvis/`: `JarvisApi`, `JarvisSources`, `JarvisAccounts`, `JarvisDialog`), D25 |
| Read first | C38 spec **and** As built (read-only rules, `Patient` sources, time rules, the pairing bug found on the emulator), **`docs/design/jarvis-feed-spec.md` — the contract, in full (parts 5–9 especially)**, D25, C37, `scripts/fake-jarvis.mjs` (the pretend Jarvis the Android agent wrote: use it to test, run with `node scripts/fake-jarvis.mjs`; it prints its fingerprint, pairs with the code `ABCDE-FGHJK`, serves sample mail, events, tasks, writes drafts to `drafts.json`) |

## Update 2026-11-20 (D39): what changed
Jarvis gets a second listener with a **Tailscale-issued certificate** (`jarvis-tailscale-listener-spec.md`), so the iPhone connects with ordinary HTTPS and **needs no pinning, no TLS socket module and no hand-written HTTP client**. The connection (the name rules, the pairing proof, the token, the authenticated client, the 401 handling) is **C66a**, built first and usable in Expo Go. This chunk keeps §1 (the account) and §4–§5 (the sources, the read-only rules, errors, privacy), and uses C66a's `JarvisApi` instead of `PinnedHttp`. **Ignore §2 (the pinned transport), §3's fingerprint steps and the socket-module pre-checks**, unless the owner chooses the D29 A fallback.

## Why
The owner's **work Outlook exists only on the PC** (classic Outlook through COM). Jarvis serves it over **HTTPS on the home
network and Tailscale**, read-only, plus saving email drafts. Edith pairs once (a code Jarvis shows, a certificate fingerprint the
owner compares, a revocable device token) and then reads. **No work-account credential ever reaches the phone** (D25).

**The problem that makes this XL on iOS:** Jarvis's certificate is **self-signed** and the contract says Edith must **pin its
fingerprint**. iOS's networking (what `fetch` uses) rejects a self-signed certificate and **cannot be told to trust one specific
certificate from JavaScript**. Android's OkHttp could. Options (decision **D29**; ask the owner; recommended **A**):

| | How | Cost |
|---|---|---|
| **A (recommended)** | Do the TLS ourselves: a **native TLS socket module** (`react-native-tcp-socket`, in the development build, C58) opens the TLS connection to Jarvis with **the pinned certificate as the only trusted CA** and a small **HTTP/1.1 client written in TypeScript** speaks over it. | No change to Jarvis. ~400 lines of careful code (§2). |
| B | Jarvis serves a **Tailscale-issued certificate** (`tailscale cert`, a real Let's Encrypt certificate for the PC's `*.ts.net` name), so normal `fetch` works and no pinning is needed. | Needs a **change to the Jarvis spec and server** (the contract says self-signed, parts 5.2/6.3), only works over Tailscale (not the plain home LAN address), requires HTTPS certificates enabled on the owner's tailnet. |
| C | Install Jarvis's certificate as a trusted CA on the iPhone through a configuration profile (manual, Settings → General → VPN & Device Management → Certificate Trust Settings). | Owner does it by hand; trusts that CA for **all** apps' TLS: a security regression. **Not recommended.** |

This document specifies **A**. If the owner picks B, the chunk shrinks to "use `secureFetch` with the Tailscale name and skip
§2" and the Jarvis agent changes the server; write that in the PR.

## Before you start
1. **Owner:** Jarvis's feed switched on (`Settings → Edith` in Jarvis), the work mailbox/calendar/tasks ticked, Tailscale on both devices for away-from-home use. They will read the PC's address and the pairing code from Jarvis.
2. **Agent pre-checks** (record in the PR):
   - **Prove the socket module can trust one certificate:** from the development build, connect with `react-native-tcp-socket` TLS to the **fake Jarvis** (`node scripts/fake-jarvis.mjs` on the PC; it makes a self-signed certificate) with the certificate as `ca`, and verify (1) the connection succeeds with the right certificate, (2) **fails** with a different one (run the fake with a regenerated certificate), (3) the module does **not** silently skip validation when `ca` is given, (4) how to read the **peer certificate** (needed to show the fingerprint at pairing). If the module cannot expose the peer certificate, use this fallback and tell the Jarvis agent: `GET /v1/hello` also returns `"certPem"` (a small additive change allowed by the contract: "adding fields is allowed"), the phone computes the SHA-256 of the DER and the owner still compares it with Jarvis's screen. Hostname checking must be off (the address is an IP that changes; the pin proves identity, C38).
   - **Check a SHA-256 and an HMAC-SHA256** are available: `expo-crypto` has `digest` (SHA-256) but **no HMAC**: implement HMAC per RFC 2104 (block size 64, ipad 0x36, opad 0x5c) over `digest` and test it against RFC 4231's vectors **and** the Jarvis spec's own vector (code `ABCDE-FGHJK`, fingerprint `00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff` — compute the expected hex with `node -e` once and pin it in the test; the Android test pins the same value).

## Specification

### 1. Account
`Provider 'jarvis'`, **one Edith account per paired PC** (`address` = the PC's name from the pair reply), label default "Work", renamable. It holds all mailboxes, calendars and task lists Jarvis shares (`GET /v1/shared`). Secrets (`SecretStore` `account.<id>.jarvis_link`, JSON): `{address, port, fingerprint, certPem?, token}` — **the token never appears in a log, error or `toString`** (wrap in a class whose `toJSON`/`toString` hide it).

### 2. The pinned transport (`jarvis/transport.ts`)
```ts
interface PinnedHttp { request(req: { method: 'GET'|'POST'; path: string; headers?: Record<string,string>; body?: string; timeoutMs: number; signal?: AbortSignal }): Promise<{ status: number; headers: Record<string,string>; body: string }> }
```
- Opens TLS (`tls: true`) to `host:port` with `ca: [pinnedCertPem]`, **no hostname verification** (`checkServerIdentity` no-op or the module's equivalent), **TLS 1.2+**, a new connection per request, header `Connection: close`.
- **Own HTTP/1.1** (`jarvis/http1.ts`, pure, tested without sockets): build the request line and headers (`Host`, `Authorization: Bearer …`, `Content-Type`, `Content-Length` computed in **bytes** of the UTF-8 body), parse the status line and headers, read the body by `Content-Length`, **chunked** transfer (the feed is Node: it may chunk), or until close. Limit the response to 2 MB (the feed's cap is 60,000 characters of text plus JSON overhead) and reject larger. Decode UTF-8 safely across chunk boundaries using `ollama/utf8.ts`.
- **Redirects are refused** (the feed has none). Only `https` semantics: this never speaks plain HTTP.
- **Pairing connection** (no fingerprint yet): a second `unpinned` mode that **accepts any certificate for this one request only** (`rejectUnauthorized: false` or the module's equivalent) and returns the peer certificate's DER SHA-256 (or the `certPem` fallback). It may be used only by `pairStart(address)` and is a separate function so it can't be reused by mistake.
- Timeouts: connect 10 s; the whole request `timeoutMs` (default 15 s; **Jarvis sources are "patient": 45 s per read, tools wait up to 55 s**, because Outlook can be slow; the HTTP call must be cancellable via `signal`, L43).
- **Local-network and VPN notes:** the first connection triggers the iOS Local Network prompt (home address); the Tailscale address (`100.64.0.0/10`) is not "local network".

### 3. Pairing (`jarvis/pairing.ts`, UI `ui/JarvisPairing.tsx`)
Settings → Accounts → Add account → **Work (via Jarvis)**: type the PC's address (the Tailscale one works at home and away; `192.168.x.x` at home), tap **Find Jarvis**:
1. `GET /v1/hello` over the **unpinned** connection → expects `{"app":"jarvis-feed","version":1}`; reads the certificate fingerprint.
2. **Show the fingerprint** in groups of four hex characters for the first 24 characters (`a559 fd3a cd73 9f17 e54c 7701`), with "Does this match what Jarvis shows? Compare them yourself." and **Matches** / **Doesn't match**. (This human comparison is the whole trust anchor: do not skip it.)
3. The owner types the **code** `ABCDE-FGHJK` (10 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, shown with a dash; accept lowercase and spaces; the proof uses the code **without the dash, uppercase**).
4. `POST /v1/pair` over the **now-pinned** connection with `{ "name": "<this phone's name, ≤ 40 chars; default 'iPhone'>", "proof": "<hex>" }`, `proof = HMAC-SHA256(key = code (no dash, uppercase), message = fingerprint as lowercase hex without colons)`. **The code itself is never sent.** Success → `{token, deviceId, pcName, version}`; save the link. `PAIRING_REFUSED` (same text for expired/burned/too many tries): "Jarvis didn't accept that code. Make a new one on the PC (it lasts 5 minutes)."
5. The account appears as **Work**, Connected.
- **A wrong certificate** (the pinned fingerprint no longer matches) is refused **before anything is sent** and shows "This isn't the PC you paired with. If you reset Jarvis's certificate, pair again." Plain `http://` addresses are refused.
- **Pair again:** if a call returns 401 `UNAUTHORIZED` ("Jarvis removed this phone"), the account shows **"Jarvis removed this phone: pair it again"** with a **Pair again** button (re-reads the link from the SecretStore at the start of every pairing: the Android bug found on the emulator kept the old token in memory).

### 4. The sources (`jarvis/sources.ts`; part (b))
- `GET /v1/shared` → mailboxes, calendars, task lists (the account's detail text). Cache per run; refresh on pairing and when a source answers `NOT_SHARED`.
- **`JarvisMailSource` (`MailSource`):** `search` → `GET /v1/mail/messages?account=&sinceDays=&unreadOnly=1&text=&limit=` (**sender/recipient/subject filters are a text search in Jarvis plus a filter on the phone**, so it can miss on odd names: say so in the tool text when the result is empty and a `from` was given; when the search range is longer than 30 days set `archive=1` with `text`); `read` → `GET /v1/mail/messages/{encodeURIComponent(id)}` (ids contain `:`; send them as a **single path segment**) → `text` is plain text up to 60,000 characters; pass through `mail/mailText.ts` (cap 6,000, strip quotes); **no HTML, no attachment contents**; `draft` → `POST /v1/mail/drafts` (`{replyTo, text}` or `{account, to, subject, body}`); `send` is **not supported** (`canSend = false`).
- **`JarvisCalendarSource` (`CalendarSource`, `readOnly = true`):** `GET /v1/calendar/events?from=&to=&calendar=` in **90-day pieces** (Jarvis allows 92 days per call; ≤ 500 items). All times are ms since 1970. **An all-day event runs from the PC's local midnight to the next local midnight**: convert to the phone's date by taking the PC's date (document the limit when the phone and PC zones differ; the contract says so).
- **`JarvisTaskSource` (`TaskSource`, `readOnly = true`):** `GET /v1/tasks/lists`, `GET /v1/tasks?list=&completed=`; `due` is `YYYY-MM-DD` (use C62's `TaskDue` date-only handling).
- Sources are **`Patient`**: C59's `Reads` takes a per-source timeout (45 s here).
- **Read-only in the types:** `CalendarSource.readOnly`, `TaskSource.readOnly`, `MailSource.canSend`. C59's `whichAccount` **skips read-only accounts** for `create_calendar_event`/`add_task` ("Edith can only read your work account…") and `send_email` refuses without a card; `update`/`delete` on a work item answer the same sentence.
- **Errors:** `OUTLOOK_UNAVAILABLE` (503) → "Outlook isn't running on the PC"; `BUSY` → retry once after 3 s; `TIMEOUT`; unreachable → "Edith can't reach Jarvis. Is the PC on and Jarvis running?"; `NOT_SHARED` → refresh `shared`, then "That isn't shared with Edith anymore".

### 5. Prompt-injection and privacy
Mail text from Jarvis is **data**: C61's label wrapper applies. Nothing from the feed is logged (subjects, names, titles, bodies). The token is never logged.

## Files
New: `jarvis/{transport,http1,hmac,pairing,link,sources,api,wire}.ts`, `ui/JarvisPairing.tsx`, `scripts/fake-jarvis.mjs` is **reused as is** (it is already in the repo; extend only with printer routes in C67), tests for each. Changed: `accounts` provider list, `core/services.tsx`, `docs/design/jarvis-feed-spec.md` (**only if** the `certPem` fallback is needed: note it in the same PR and tell the owner so the Jarvis agent builds it).

## Tests
`http1` (status/header parsing, `Content-Length`, chunked incl. split chunk-size lines, `Connection: close` bodies, the 2 MB cap, UTF-8 split across chunks, byte length of a body with emoji); `hmac` (RFC 4231 vectors + the pinned Jarvis vector); the fingerprint grouping; the transport against **a local TLS test server** in Jest? (not possible under Jest: use the socket **interface** with the scripted fake from C65 `net/fakeServer`) — plus the manual pre-check with the real fake Jarvis; pairing flows (wrong code, refused, mismatched certificate refused before sending, `http://` refused); 401 → "pair again" and the **re-pair reads the new token**; each source's mapping (mail ids with `:` encoded once, 90-day chunking, all-day mapping, tasks dates); `canSend` false → no `send_email` card; calendar/tasks read-only through C60/C62's tools (`whichAccount` skips, "Edith can only read…"); the 45 s patient timeout and cancellation; partial failure line when Jarvis is off while Google answers.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green (both PRs).
- [ ] **Agent, with `fake-jarvis.mjs`:** pair (address `<PC LAN IP>`, code `ABCDE-FGHJK`), mail search/read/reply-draft, calendar, tasks through chat; a wrong certificate refused; Pair again works.
- [ ] **Owner, real Jarvis:** pair; "anything from Dana?"; "what's on my work calendar this week?"; "what are my work to-dos?"; "reply to Dana, say I'll check the travel line" → the **Save this draft?** card → the draft appears in Outlook's Drafts; **away from home over Tailscale (phone on mobile data)** the same works; with the PC off Edith says she can't reach Jarvis.

## Risks and unverified assumptions
- The socket module's certificate handling is the single biggest risk (the pre-check exists to retire it first). If neither the pin nor the `certPem` fallback can be made safe, **stop and ask the owner to pick D29 B**.
- Real Outlook slowness (45 s may not be enough), the Windows firewall for Tailscale (spec 2.8), whether Outlook keeps quoted history in reply drafts.

## Not in this chunk
Sending, editing events or tasks, attachments, HTML mail, a second PC, the printer (C67).
