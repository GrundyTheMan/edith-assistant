# C38 — Work account through Jarvis (D25)

| | |
|---|---|
| Phase | 7 Accounts |
| Depends on | C22 (several accounts), C23/C24 (the account pattern), C35 (to-dos), C37 (`httpClients.secure`) |
| Unblocks | Work mail, calendar and to-dos in every feature that already reads accounts (tools, briefings, the Calendar tab) |
| Size | Medium: a provider package, three read-only sources, pairing UI, tests |
| Read first | [`../jarvis-feed-spec.md`](../jarvis-feed-spec.md) (the contract with Jarvis), C24 "As built" (the nearest provider)  |

## Why

The owner's **work Outlook exists only on the PC**: Jarvis reads it through classic Outlook (COM). There is no password
or token that another device could use, so Edith on the phone can't sign in to it (and a work tenant would likely refuse
an unmanaged device anyway). Jarvis already has the data, so **Jarvis serves it and Edith asks** (D25). Jarvis's side is
built by the Jarvis agent from the spec; this chunk is Edith's side.

## What the owner gets

- **Settings → Accounts → Add account → Work (via Jarvis).** Type the PC's address (the **Tailscale** address works at home
  and away; `192.168.x.x` at home), tap **Find Jarvis**; Edith shows the certificate fingerprint she found, the owner
  compares it with Jarvis's screen, then types the one-time code from **Pair a phone** in Jarvis.
- The account appears as **Work** (renamable), like Google, Microsoft and iCloud. It gives Edith the work **mail, calendar
  and to-dos** (what the owner ticked in Jarvis), through the tools she already has: "anything from Dana?", "what's on my
  work calendar this week?", "what are my work to-dos?". Calendar and Calendar-tab merging work as for other accounts.
- **Read-only, except email drafts.** "Reply to Dana, say I'll check the travel line" shows the usual **Save this draft?**
  card; Jarvis saves it in Outlook's Drafts. Edith **can't send** from this account (no Send card; she's told to offer a
  draft), can't add or change events or to-dos on it, and doesn't offer it as a place for a new event or to-do.
- If the PC is off, Jarvis isn't running or the feed is off, she says so ("Edith can't reach Jarvis…"). If Jarvis removed
  this phone the account shows **"Jarvis removed this phone: pair it again"** with a **Pair again** button.

## Design

- **Package `jarvis/`**: `JarvisWire.kt` (the wire types; unknown fields ignored), `JarvisApi.kt` (pinned HTTPS, pairing
  calls, error mapping), `JarvisSources.kt` (mail, calendar and task sources), `JarvisAccounts.kt` (pairing, the connection).
  UI: `ui/settings/JarvisDialog.kt`. Provider `Provider.Jarvis`; one Edith account per paired PC (`address` = the PC's name),
  holding all the mailboxes, calendars and task lists Jarvis shares.
- **Transport:** `httpClients.secure` (HTTPS only, no redirect to http) with the certificate **pinned**: the chain is
  accepted only if its first certificate's SHA-256 equals the fingerprint pinned at pairing; host names aren't checked (the
  address is an IP that can change; the pin is what proves it's the owner's PC). While pairing there's nothing to compare
  yet, so the owner compares the fingerprint by eye (spec 6.3). **No new cleartext exception**: the feed is always https.
- **Pairing proof:** `HMAC-SHA256(key = code without dash, uppercase; message = fingerprint hex)`, so the code itself is never
  sent (spec 6.1). A test pins the value against Jarvis's own algorithm.
- **Secrets:** `account/<id>/jarvis_link` in the Keystore-backed secret store: JSON of address, fingerprint, device token.
  Never logged (`JarvisLink.toString()` hides the token). The work account's own credentials never reach the phone.
- **Read-only is in the types:** `CalendarSource.readOnly`, `TaskSource.readOnly`, `MailSource.canSend`. `WhichAccount.forCalendar`
  and `forTasks` skip read-only accounts (they choose where something new goes); `send_email` refuses an account that can't
  send, with no confirmation card. The sources also return a plain "Edith can only read your work account…" if a write
  ever reaches them.
- **Time:** all times are ms since 1970; an all-day event runs from local midnight to the next local midnight (spec 7.4).
  Events are asked for in 90-day pieces (Jarvis allows 92 days per call).
- **Ids:** mail ids contain `:`; they're sent as a single path segment.
- Local network: the phone already holds `ACCESS_LOCAL_NETWORK` for Ollama (L23); the Tailscale address is not "local".

## Acceptance

- [x] Pairing: address → fingerprint → code → account "Work", connected (emulator, with a pretend Jarvis over real TLS).
- [x] Mail search and read, calendar, to-dos and a reply draft, all through Edith's chat (emulator, pretend Jarvis).
- [x] A removed phone shows "pair it again"; **Pair again** works.
- [x] A wrong certificate is refused before anything is sent; a wrong code is explained; plain `http://` is refused.
- [x] Work can't send, isn't offered for new events or to-dos (unit tests).
- [x] Unit tests (31): pairing maths, pinned TLS (MockWebServer), mapping, chunking, drafts, account flow, tool refusals.
- [ ] **Owner: against the real Jarvis** (its feed switched on, Outlook open on the PC): pair, then mail, calendar and to-dos.
- [ ] **Owner: away from home over Tailscale** (phone on mobile data).

## As built (2026-11-17)

- Built from the spec as it stood on 2026-11-16 (with the Tailscale update) and the Jarvis agent's `feed-1-server` branch
  (read, not run): wire types match; Edith ignores fields she doesn't use (`attendees`, `importance`, `score` beyond mail).
- `scripts/fake-jarvis.mjs` is a **pretend Jarvis** (Node + openssl, no dependencies): `node scripts/fake-jarvis.mjs`, then
  Add account → Work (via Jarvis) with address `10.0.2.2`, code `ABCDE-FGHJK`. It prints its fingerprint. It serves sample mail,
  events and tasks and writes drafts to `drafts.json` in the folder it was started from (delete that file; it is not
  committed).
- **A bug the emulator found and a test now holds:** pairing again kept the *old* token in memory, so a re-paired account
  still showed "removed this phone" until Edith restarted. `start()` now always reads the link from the secret store.
- A first version of the API paths lacked the `/v1/` prefix; a unit test caught it before the emulator.

## Not verified

- **Against the real Jarvis.** Edith was built against the spec and tried only against the pretend server; the Jarvis agent's
  server was read, not run. The first real pairing is the owner's check.
- **Tailscale and a phone off the home Wi-Fi.** Nothing here distinguishes the two, but it hasn't been tried.
- **The Windows firewall** (the spec's Tailscale note) and the owner's **Outlook under load**.
- **How Jarvis shows a mailbox's Drafts** after a saved draft, and whether Outlook's reply draft keeps the quoted history.
- A phone with the **wrong date or timezone** (all-day events follow the phone's zone).
- The attendee list is not shown (Jarvis sends names only; the Calendar tab doesn't use attendees).

## Known limits

- One Jarvis (PC) at a time per account name; a second PC would be a second account.
- Search by sender/recipient/subject is a text search in Jarvis plus a filter in Edith, so it can miss on odd names.
- A new email draft goes from the **first shared mailbox**; with several shared, a draft *reply* always goes from the
  mailbox the message is in.
- Attachments aren't fetched (names only), no HTML mail, no sending, no editing.
- The mail list shows what Jarvis's own cache holds (about the last 30 days by default); older mail is found only by a text search that reaches back further than 30 days
  (Edith then asks Jarvis to search Outlook's older mail too, `archive`).
