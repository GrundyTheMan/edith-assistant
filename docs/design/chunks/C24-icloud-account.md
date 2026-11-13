# C24 — iCloud account: iCloud Mail and Calendar

| | |
|---|---|
| Phase | 7 — Accounts |
| Depends on | C22 (accounts, sources, tools) |
| Unblocks | — |
| Size | Large (two protocols and a mail library; plus ~5 minutes of owner setup at Apple) |
| Read first | C22; 06-decisions **D17**, D5, D11; C19 As built (`MailText`, the raw message); 04-lessons L29 |

## Why

D17: Edith reads and writes iCloud mail and calendars next to the owner's other accounts. Apple has **no OAuth
for third-party apps** to reach iCloud Mail or Calendar. A third-party app uses an **app-specific password** over
the standard protocols: IMAP and SMTP for mail, CalDAV for the calendar.

Facts checked on 2026-11-12 (re-verify):
- Mail settings from Apple (<https://support.apple.com/en-us/102525>):
  - **IMAP:** `imap.mail.me.com`, port 993, SSL. The username is the iCloud mail name without the domain; if that
    fails, the full address.
  - **SMTP:** `smtp.mail.me.com`, port 587, STARTTLS. The username is the full iCloud address.
  - Both use the **app-specific password**. There's no POP.
- CalDAV:
  - `https://caldav.icloud.com`, with the Apple Account email and the app-specific password (HTTP Basic over TLS).
  - The calendar home is on a partition host (`pNN-caldav.icloud.com`), found by discovery.
  - Implementers report that iCloud rejects UID queries, and that changing the Apple Account password revokes
    every app-specific password.
  - Apple publishes no CalDAV reference, so the rest comes from RFC 4791 and these reports. Check them in the
    first step.
- An app-specific password gives **full access to the account's mail, calendar and contacts** over these
  protocols. There's no narrower permission. It works until the owner revokes it or changes their Apple Account
  password.

## Goal

Settings → Accounts → **Add account → iCloud**. The owner types their iCloud address and an app-specific
password. Edith checks both connections (IMAP login, and CalDAV discovery) and saves the account. From then on
C22's tools include iCloud's calendars and mail.

## Before you start — owner setup (walk the owner through it; write it as `docs/icloud-setup.md`)
1. <https://account.apple.com> → **Sign-In and Security → App-Specific Passwords** → **+** → label **Edith** →
   copy the password. It needs two-factor authentication, which the owner has.
2. In Edith: Add account → iCloud → paste it into the password field. **The owner types or pastes it themselves.**
   It's never sent to chat, the repo or a log.

## Specification

### 1. Adding the account
- Fields: **iCloud email** and **App-specific password** (masked, with a show toggle; Apple's format is
  `xxxx-xxxx-xxxx-xxxx`). The address must be an iCloud Mail address (`@icloud.com`, `@me.com`, `@mac.com`). A
  custom-domain address works only if it's set up in iCloud Mail. Accept it, and let the check below decide.
- **Check** (spinner, 20 s): IMAP login (the mail name first, then the full address; remember which worked), then
  CalDAV discovery.
  - Both fine → save the account and the password in `SecretStore` (`account/<id>/password`).
  - Mail fine, calendar failing → save it as mail only, and say so.
  - A login failure → "Apple didn't accept that password. Use an app-specific password, not your Apple Account
    password."
- The password is never shown again. **Sign in again** asks for a new one.
- **Disconnect** deletes it from the device. The dialog says: "To stop it working everywhere, revoke 'Edith' at
  account.apple.com → App-Specific Passwords."

### 2. Mail (`icloud/mail/`)
- **Library:** Jakarta Mail through **Eclipse Angus Mail** (`org.eclipse.angus:angus-mail` +
  `jakarta.mail:jakarta.mail-api`). Before writing anything else, prove that it runs on Android 17 and survives R8
  in a release build (it uses reflection and service files, so it needs keep rules). Also check its APK size. If it
  doesn't work, stop and ask the owner before choosing another library or writing a small IMAP client.
- `ICloudMailSource.search`:
  - One IMAP connection per operation (no long-lived IDLE), in `INBOX`.
  - Use `SEARCH` with `FROM`, `TO`, `SUBJECT`, `TEXT`, `UNSEEN`, `SINCE`, `BEFORE`.
  - IMAP dates are **whole days**. Filter `after`/`before` exactly on the client, using each message's
    `INTERNALDATE` in the device zone.
  - Newest first; `max` messages; "more match" when there were more.
  - Fetch the envelope and `BODY.PEEK[HEADER]` only. **Searching must never mark mail as read** (use `PEEK`).
- `read`: fetch the MIME body with `BODY.PEEK[]`, so reading doesn't mark it either. Then C19's `MailText`.
- Message handles map to `(UIDVALIDITY, UID)`. A changed `UIDVALIDITY` → "list the emails again".
- `draft`: build the RFC 2822 message with C19's builder (RFC 2047 headers, no header injection), then `APPEND`
  it to the Drafts folder with `\Draft`. Find the folder by its `\Drafts` SPECIAL-USE flag, not by name.
- `send`:
  - SMTP `smtp.mail.me.com:587` with STARTTLS required, and the full address as the username.
  - Then `APPEND` a copy to the folder flagged `\Sent`, **unless iCloud already saves SMTP sends there**. Check
    this live, so there's no duplicate.
  - A reply sets `In-Reply-To` and `References` from the original.

### 3. Calendar (`icloud/calendar/`, CalDAV over OkHttp)
- **Discovery** (cached per app run):
  1. `PROPFIND https://caldav.icloud.com/` for `current-user-principal`.
  2. `PROPFIND <principal>` for `calendar-home-set`. Follow redirects to the partition host.
  3. `PROPFIND <home>` Depth 1. Keep collections whose `resourcetype` is `calendar` and whose
     `supported-calendar-component-set` includes `VEVENT`. That skips Reminders' `VTODO` lists.
  4. Read `displayname`, the colour, and `current-user-privilege-set` (`write` → `canWrite`).
- **Default calendar:** iCloud has no "primary". Use `schedule-default-calendar-URL` if the principal has one;
  otherwise the first writable calendar. When creating an event, the card shows the calendar's name.
- `events`: `REPORT <calendar>` with a `calendar-query`: `VEVENT` and a `time-range`, plus `<C:expand
  start end/>` to get recurring events as instances.
  - **First step: check that iCloud honours `expand`.** If it doesn't, expand `RRULE`/`EXDATE`/`RECURRENCE-ID` on
    the phone with a library (ical4j, after the same Android/R8 check as the mail library), and ask the owner
    before adding it.
- **Parsing:** iCalendar `VEVENT` → `CalendarEvent`. Handle `DTSTART`/`DTEND` with `TZID`, `Z` and floating
  times, and all-day `VALUE=DATE` (with an exclusive end); also `DURATION`, `SUMMARY`, `LOCATION`, `UID`
  (→ `iCalUid`) and `STATUS:CANCELLED` (left out). Line folding, and escaped `\,` `\;` `\n`.
  - A `TZID` that isn't an IANA id (Outlook-style "Eastern Standard Time") → map it, or fall back to the
    calendar's zone. Never read it as UTC (L29).
- `create`: `PUT <calendar>/<uuid>.ics` with `If-None-Match: *` and a minimal `VCALENDAR`/`VEVENT`. Timed events
  are written in UTC (`Z`), so no `VTIMEZONE` is needed; all-day events use `VALUE=DATE`. Include `UID`,
  `DTSTAMP`, `PRODID:-//Edith//EN`.
- `update`: `GET` the resource, change only the asked fields, then `PUT` it with `If-Match: <etag>`. A 412 →
  fetch again once and retry; again → "It changed elsewhere; list the events again."
- `delete`: `DELETE` with `If-Match`.
- **Recurring events:** changing or deleting **one occurrence** of a recurring iCloud event is not supported in
  C24. Edith says so, and doesn't change the whole series instead. A whole series can be changed only when the
  owner says so.

### 4. Errors
- IMAP `AUTHENTICATIONFAILED`, or 401 from CalDAV → NeedsSignIn ("The app-specific password stopped working: it
  was revoked, or your Apple Account password changed. Make a new one."). There's no automatic retry.
- No network or a timeout → `Error` with **Try again**.
- Throttling: iCloud may refuse many logins in a short time. Use one connection per operation. Don't retry in a
  loop.

## Tests
- iCalendar parser: timed with `TZID`, `Z`, floating, all-day, `DURATION`, folding, escapes, cancelled, an
  Outlook-style `TZID`.
- The writer: `PUT` bodies (timed in UTC, all-day), `If-None-Match`/`If-Match`, and a 412 refetched once.
- Discovery against MockWebServer, with multistatus XML following RFC 4791/6638 (made up): the partition
  redirect, `VTODO` collections skipped, read-only calendars.
- The `calendar-query` body (`time-range` and `expand`).
- Mail, against a fake IMAP server (GreenMail, test only):
  - search → `SEARCH` keys, with exact dates on the client
  - **no `\Seen` set by search or read**
  - draft `APPEND` with `\Draft` to the SPECIAL-USE folder
  - send through a fake SMTP server; the reply headers
- The add-account check: wrong password, mail-only, both.
- Through C22's tool loop: an iCloud account next to Google, labelled; "which account?"; declining sends nothing.

## Acceptance criteria
- [ ] Emulator: Add account → iCloud with an app-specific password → Connected.
- [ ] The calendar this week includes iCloud events (with a recurring one), labelled, and matches iCloud Calendar.
- [ ] "Any unread email today?" includes iCloud's, labelled, and **they're still unread afterwards**.
- [ ] "Add a test event tomorrow at 3pm" → asked which account → iCloud → the card (with the calendar's name) → it
      appears in iCloud Calendar on the owner's iPhone. "Delete it" → gone. (Clean up.)
- [ ] A reply draft appears in iCloud Mail's Drafts.
- [ ] A restart: still Connected. A revoked password → "stopped working", and Sign in again works with a new one.
- [ ] `scripts/check.sh --device` passes; release APK on `Edith_Release_Test` (R8 keep rules!); then in Andarium.

## Implementation notes (traps)
1. **Prove the libraries first**, in a release build, before building on them: Angus Mail on Android/R8, and, if
   it's needed, ical4j.
2. **Never mark mail as read** by searching or reading (`PEEK`). The owner reads the same inbox on their iPhone.
3. **Folder names are localised and vary.** Use SPECIAL-USE flags (`\Drafts`, `\Sent`), never names.
4. **The password is a full-access credential.** Logs show the account id only, never the password, the address
   or the IMAP conversation (turn off the library's debug output in release builds). Crash reports go through
   the same filter.
5. **Off screen:** no browser is involved, so L36 isn't triggered at sign-in. But reads during a reply while the
   app is in the background rely on D13's foreground service, as the other providers do.
6. **Docs to update in this PR:** README (chunk status), `docs/icloud-setup.md` (new), 01-architecture §1 (new
   libraries), §2, §9 (a stored password, D17).

## Not in scope
- iCloud Contacts and Reminders.
- Shared and subscribed calendars beyond what discovery lists as `VEVENT` collections in the home.
- Push/IDLE.

## As built (2026-11-12) — built and tested, not verified live

Where the code differs from the specification above, the code is right. **The owner has no iCloud account**, so none
of the acceptance criteria has been checked live; the owner chose to finish C24 with tests only. Branch
`c24-icloud-account` is built on C23's branch (`c23-microsoft-account`), because both change `Accounts`, Settings →
Accounts and `AppContainer`.

**Files**
- `icloud/ICloudAccounts.kt`: the provider (the add-account check, 20 s) and `ICloudConnection` (state, capabilities,
  disconnect). `Account.login` (new, optional) keeps the IMAP user name that worked.
- `icloud/mail/ICloudMailSource.kt`: IMAP and SMTP with Jakarta Mail; `MailServers` (Apple's hosts; tests point it at
  GreenMail).
- `icloud/calendar/`: `CalDavClient` (discovery, `REPORT`, `GET`/`PUT`/`DELETE` with ETags), `ICalendar` (the parser and
  writer), `ICloudCalendarSource`.
- Settings → Accounts: **iCloud** in Add account; a dialog with the address and the password (masked, **Show**);
  **Sign in again** opens the same dialog for a new password; Disconnect says where to revoke it at Apple.
- `docs/icloud-setup.md` (new).

**The libraries (trap 1)**
- Jakarta Mail API 2.1.5, Angus Mail 2.0.5, Angus Activation 2.0.3 (latest stable; the 2.1/2.2 lines are milestones).
  GreenMail 2.1.15 for tests only. The jars' duplicate `META-INF/LICENSE.md`/`NOTICE.md` are left out of the APK.
- Proven before anything else, with a temporary probe (since removed) that logged in to `imap.mail.me.com` and
  `smtp.mail.me.com` with a made-up user: Apple's "authentication failed" proves TLS, STARTTLS, the protocols and
  the provider loading.
  - Debug build on `Pixel_9_API_37` (Android 17): IMAP refused in 0.6 s, SMTP (STARTTLS) in 1.2 s. ✔
  - Release build on `Edith_Release_Test`: at first every connection failed at once with `NoSuchProviderException`
    ("imaps", "smtp"): R8 had renamed the classes the library loads by name. Keep rules for `Provider`, `Service`
    subclasses' `(Session, URLName)` constructors and the activation handlers, plus `-dontwarn` for `java.awt` (the
    image handlers) and `javax.security.sasl` (optional SASL, never enabled). Then IMAP 0.7 s, SMTP 1.1 s. ✔
  - APK: 34.06 MB, about 250 KB more than v2.0.0-dev.2 (C22 and C23 included).
- **ical4j isn't added.** Whether iCloud honours `<C:expand>` couldn't be checked (no account). If it doesn't, a
  repeating series comes back with its `RRULE` unexpanded: Edith then lists only its first occurrence and logs
  "a repeating event came back unexpanded". Check this first when an iCloud account is available; ask the owner
  before adding a library to expand on the phone.

**Decisions made while building**
- **Mail never marks anything read:** the inbox is opened read-only (`EXAMINE`) and `mail.imap.peek` makes every body
  fetch `BODY.PEEK`. Tested: the `\Seen` flags are the same before and after searching and reading.
- **Search:** `SEARCH` with `FROM`/`TO`/`SUBJECT`/`UNSEEN`/`SINCE`/`BEFORE`; free text is `SUBJECT` or `BODY` (Angus
  has no `TEXT` term). The exact `after`/`before` instants are checked on the phone with each message's
  `INTERNALDATE`. A search without words looks at the newest 50. Only the envelope is fetched, so results have no
  snippet.
- **Message ids** are `UIDVALIDITY:UID`; a changed `UIDVALIDITY` says "list the emails again".
- **Drafts and Sent are found by SPECIAL-USE flag.** GreenMail doesn't report the flags, so the folder finder is
  swappable in tests, and a scripted IMAP server (localised names in modified UTF-7, plus a decoy "Drafts") tests
  finding by flag.
- **Sent copies:** `APPEND_TO_SENT` is false until a live check shows whether iCloud keeps SMTP sends in Sent itself.
- **The SMTP login is the full address; the IMAP login is the mail name first, then the full address** (Apple's
  settings). The one that worked is saved as `Account.login`.
- **CalDAV credentials** are added by a network interceptor, only for the CalDAV host and `*.icloud.com`, so they
  follow Apple's redirect to the partition host and go nowhere else. The XML parser refuses DTDs.
- **The default calendar** is `schedule-default-calendar-URL` when the server names a writable one, else the first
  writable calendar.
- **Repeating events:** an occurrence's id is `<href>#<RECURRENCE-ID>`; changing or deleting an occurrence, or a whole
  series, is refused with no request ("do it in the Calendar app"). The specification allowed changing a series when
  the owner says so; that's left out for now.
- **Times:** timed events are written in UTC (`Z`), all-day as `VALUE=DATE`. A `TZID` that isn't an IANA id is mapped
  from a short list of Windows names, else read in the device's zone; never as UTC (L29).
- **Errors:** a refused password (IMAP `AUTHENTICATIONFAILED`, CalDAV 401) → NeedsSignIn ("needs a new app-specific
  password"), no retry. Errors reuse `AppError.GoogleAuth` for account sign-in problems, as C23 does.
- **The check at startup** makes one IMAP login (a one-message search) and the calendar's discovery.

**Verified (tests only)**
- `scripts/check.sh`: OK (`build-logs/2026-11-12_21-33-58_check.log`), 340 unit tests (C23 and C24 included). Lint: no new
  warnings (the 2 `ExifInterface` ones are C15's).
- `ICalendarTest` (5): `TZID`, `Z`, floating, all-day with an exclusive end, `DURATION` (with weeks), a Windows `TZID`,
  an unknown `TZID`, folding, escapes, `VALARM` skipped, cancelled, instances and series; the writer (UTC, dates,
  escapes, folding at 75 octets in UTF-8); a change keeping every other line.
- `CalDavTest` (7): discovery across two servers (the partition host, with the password following the redirect),
  `VTODO` lists skipped, read-only calendars, the server's default first, a 401; the `calendar-query` body; events
  with instances, all-day and cancelled; create (`If-None-Match: *`), update (`If-Match`, a 412 read again once, twice
  → "it changed elsewhere"), delete (`If-Match`); an occurrence refused with no request.
- `ICloudMailTest` (8, GreenMail): unread today with the exact local midnight; from, newest first, "more"; text;
  **no `\Seen` set by search or read**; read without the quoted history; a stale id; send (the full address, reply
  headers, a UTF-8 subject, CR/LF never in a header); a draft appended with `\Draft`; a refused password, no retry.
- `SpecialUseFolderTest` (1), `ICloudAccountsTest` (6): a wrong password, mail and calendar (the password kept,
  never logged, nor the address), mail only with the full address as login, disconnect; through the tool loop next
  to Google: labelled reads, "which account?" offering both, declining sends nothing, accepting `PUT`s to iCloud.

**Not verified**
- Every acceptance criterion (no iCloud account): Add account, a real calendar with a repeating event, unread mail
  still unread afterwards, an event added and deleted, a reply draft, a restart, a revoked password.
- Whether iCloud honours `<C:expand>` and whether it saves SMTP sends in Sent.
- `scripts/check.sh --device` (no new device tests were written for the iCloud dialog), the release APK with the
  iCloud screens in use, Andarium.
