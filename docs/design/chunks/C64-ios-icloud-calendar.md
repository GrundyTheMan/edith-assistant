# C64 — iCloud account on iOS, part 1: calendar over CalDAV

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B (could run in Expo Go for the calendar alone; kept in Wave B because the account layer needs C59) |
| Status | **Design — needs an app-specific password from the owner** |
| Depends on | C59 (accounts), C60 (calendar tools and tab) |
| Unblocks | C65 (iCloud mail adds to the same account) |
| Runs in | the development build (the calendar part itself would work in Expo Go) |
| Size | Large |
| Ports | C24 §1 (adding the account), §3 (calendar), §4 (errors) |
| Read first | C24 spec **and** As built (CalDAV discovery, `expand`, the `PUT` rules, the "Default calendar" choice, the whole-series delete, the traps), RFC 4791 (CalDAV) and RFC 6638, 04-lessons L29, D17 |

## Why
Apple has no OAuth for third-party apps to reach iCloud Calendar or Mail. A third-party app uses an **app-specific password**
over the standard protocols: **CalDAV** (HTTPS) for the calendar, IMAP/SMTP for mail (C65). The owner's iPhone already syncs
iCloud calendars with Apple's Calendar app; Edith needs her own read/write access to answer "what's on my calendar" with
iCloud events included. This chunk does the calendar half and the account; mail follows (C65).

## Before you start — owner setup (write `docs/icloud-setup.md` §iOS)
1. <https://account.apple.com> → **Sign-In and Security → App-Specific Passwords** → **+** → label **Edith** → copy the password (needs two-factor auth, which the owner has). The password gives **full access to the account's mail, calendar and contacts** over these protocols; there is no narrower scope. It works until the owner revokes it or changes their Apple Account password.
2. In Edith: Add account → iCloud → the **owner types or pastes it themselves**. Never to chat, the repo or a log.

## Specification

### 1. Adding the account (`icloud/account.ts`, form in the generic add-account sheet)
Fields: **iCloud email** (keyboard email) and **App-specific password** (secret, masked, show toggle; Apple's format `xxxx-xxxx-xxxx-xxxx`). Accept `@icloud.com`, `@me.com`, `@mac.com`; a custom domain works only if set up in iCloud Mail: accept and let the check decide.
- **Check** (spinner, 20 s): CalDAV discovery (this chunk) and, once C65 exists, the IMAP login. Both fine → save the account and the password (`SecretStore` `account.<id>.password`, kept in the Keychain, never in the key-value store). Calendar fine / mail failing (or the reverse) → saved with what works, and the sheet says so. A login failure → "Apple didn't accept that password. Use an app-specific password, not your Apple Account password."
- Disconnect: delete the secret; dialog: "To stop it working everywhere, revoke 'Edith' at account.apple.com → App-Specific Passwords."
- **Sign in again** asks for a new password only.

### 2. CalDAV (`icloud/caldav.ts`, `secureFetch` allowed hosts `icloud.com`; HTTP Basic over TLS)
Custom methods (`PROPFIND`, `REPORT`, `PUT`, `DELETE`): **verify early that `expo/fetch` / RN `fetch` send arbitrary methods and request bodies on iOS** (first hour). If not, use `XMLHttpRequest`. Request XML is built by hand; responses parsed with a small XML parser (`fast-xml-parser` is pure JS: check Hermes; or write a minimal namespace-aware tokenizer reusing C42's `html.ts` tokenizer ideas — pick one and test it on real multistatus samples).
- **Discovery** (cached per app run): `PROPFIND https://caldav.icloud.com/` (`current-user-principal`) → `PROPFIND <principal>` (`calendar-home-set`; follow redirects to the **partition host** `pNN-caldav.icloud.com`) → `PROPFIND <home>` Depth 1: keep collections whose `resourcetype` is `calendar` and whose `supported-calendar-component-set` includes `VEVENT` (skips Reminders' `VTODO` lists). Read `displayname`, colour, `current-user-privilege-set` (`write` → `canWrite`). **Redirects:** keep Basic credentials only for `icloud.com` hosts (C37/C48 `allowHosts`), never over `http`.
- **Default calendar:** iCloud has no "primary": use `schedule-default-calendar-URL` if the principal has one, else the first writable calendar. **Every account gets a "Default calendar" choice in Settings → Accounts** (iCloud's server default isn't the iPhone's); the card names the calendar.
- **`events`:** `REPORT <calendar>` with a `calendar-query` for `VEVENT` and a `time-range`, plus `<C:expand start end/>` so recurring events come as instances. **First step: check that iCloud honours `expand`.** If it doesn't, expand `RRULE`/`EXDATE`/`RECURRENCE-ID` on the phone (a small pure TypeScript expander for the rules the owner's calendars use: DAILY/WEEKLY/MONTHLY/YEARLY, `INTERVAL`, `COUNT`, `UNTIL`, `BYDAY`; ask the owner before adding a library such as `rrule`).
- **Parsing** (`icloud/ical.ts`, pure): iCalendar `VEVENT` → `CalendarEvent`: `DTSTART`/`DTEND` with `TZID`, `Z` and floating times, all-day `VALUE=DATE` (exclusive end), `DURATION`, `SUMMARY`, `LOCATION`, `UID` (→ `iCalUid`), `STATUS:CANCELLED` (left out); line **folding**; escapes `\,` `\;` `\n`. A `TZID` that isn't an IANA id (Outlook-style "Eastern Standard Time") → map it, else fall back to the calendar's zone; **never read it as UTC (L29)**.
- **`create`:** `PUT <calendar>/<uuid>.ics` with `If-None-Match: *` and a minimal `VCALENDAR`/`VEVENT`: timed events written in **UTC (`Z`)** so no `VTIMEZONE` is needed; all-day with `VALUE=DATE`; `UID`, `DTSTAMP`, `PRODID:-//Edith//EN`. **`update`:** `GET` the resource, change only the asked fields, `PUT` with `If-Match: <etag>`; a 412 → fetch again once and retry; again → "It changed elsewhere; list the events again." **`delete`:** `DELETE` with `If-Match`.
- **Recurring events:** changing/deleting **one occurrence** of a recurring iCloud event is **not supported**: Edith says so and does not change the series instead. A **whole series** can be deleted only when the owner says so (tool param `whole_series`, as Android).

### 3. Errors
CalDAV 401 → `needs_sign_in` ("The app-specific password stopped working: it was revoked, or your Apple Account password changed. Make a new one."); no automatic retry. No network/timeout → `error` with Try again. iCloud may throttle many logins: **one request chain per operation, never a retry loop.**

### 4. Logs
Account id only. **Never** the password, the address, calendar names, event text or the `Authorization` header. The Basic header is built per request from the Keychain value and not stored in a long-lived object.

## Files
New: `icloud/{account,caldav,ical,xml,expand?}.ts`, `docs/icloud-setup.md`, tests with a **pretend iCloud** (multistatus XML following RFC 4791/6638, made up, the partition redirect, a VTODO collection, a read-only calendar). Changed: `accounts` provider list, `ui/AccountsSettings.tsx` (Default calendar), `core/services.tsx`.

## Tests
iCalendar parser: timed with `TZID`, `Z`, floating, all-day, `DURATION`, folding, escapes, cancelled, an Outlook-style `TZID`; writer: `PUT` bodies (timed UTC, all-day), `If-None-Match`/`If-Match`, a 412 refetched once; discovery (partition redirect, `VTODO` skipped, read-only calendars, default calendar); the `calendar-query` body; the add-account check (wrong password, calendar-only); through C60's tool loop: an iCloud account beside Google, labelled; "which account?"; declining sends nothing; single occurrence refused; `whole_series` deletes.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner:** Add account → iCloud → Connected; the calendar this week includes iCloud events (with a recurring one), labelled, matching Apple's Calendar app; "Add a test event tomorrow at 3pm" → which account → iCloud → the card with the calendar's name → it appears in Apple's Calendar; "Delete it" → gone; a revoked password → "stopped working", Sign in again works.

## Risks and unverified assumptions
- Custom HTTP methods in RN `fetch`; the XML parser on Hermes; iCloud honouring `expand`. These three are the first things to prove.
- Apple publishes no CalDAV reference; the rest comes from the RFCs and implementers' reports.

## Not in this chunk
Mail (C65), Reminders, contacts, editing one occurrence.
