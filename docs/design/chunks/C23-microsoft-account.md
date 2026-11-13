# C23 — Microsoft account: Outlook mail and calendar

| | |
|---|---|
| Phase | 7 — Accounts |
| Depends on | C22 (accounts, sources, tools), C17b (browser sign-in, loopback, `AppVisibility`) |
| Unblocks | — |
| Size | Medium–large (plus ~15 minutes of owner setup in Microsoft Entra) |
| Read first | C22; C17b (spec, traps **and** As built); 06-decisions **D17**, **D18** (ask the owner), D5, D11; 04-lessons L36 |

## Why

D17: Edith reads and writes Outlook.com / Microsoft 365 mail and calendars next to the owner's Google accounts.
Microsoft's sign-in works like C17b's: a browser page, PKCE, and a code sent back to a loopback address. So most
of C17b is reused, with a different provider configuration.

Facts checked on 2026-11-12 (re-verify; Microsoft changes these):
- Redirect URIs (<https://learn.microsoft.com/en-us/entra/identity-platform/reply-url>):
  - `http` is allowed only for **localhost**. For localhost **the port is ignored** when matching, so one
    registered `http://localhost` covers Edith's random port.
  - Microsoft recommends `127.0.0.1` over `localhost`. However, the portal's Redirect URIs box refuses an `http`
    `127.0.0.1` address; it can only be added in the app manifest (`replyUrlsWithType`).
  - The IPv6 loopback `[::1]` isn't supported in a registered redirect.
  - An app that signs in personal accounts can't have query parameters in its redirect URI.
  - Native apps use the **Mobile and desktop applications** platform. That makes Edith a **public client**: no
    client secret.
- Refresh tokens (<https://learn.microsoft.com/en-us/entra/identity-platform/refresh-tokens>):
  - They last **90 days** for native apps.
  - **Every refresh returns a new refresh token.** Microsoft says to store the new one and delete the old one.
  - The user changing their password revokes them. So does the user (or an admin) revoking them.
  - So there's no weekly sign-in, unlike Google in Testing.

## Goal

Settings → Accounts → **Add account → Microsoft** → Chrome shows Microsoft's sign-in → the owner signs in and
approves → back in Edith, the account is listed as Connected. From then on C22's tools include its calendars and
mail, and new events and emails can be made in it after Edith asks which account (D17).

## Before you start — owner setup (walk the owner through it; write it as `docs/microsoft-setup.md`)
Verify the wording against the current Entra admin center.
1. <https://entra.microsoft.com> (a personal Microsoft account can register apps, and it's free) → **App
   registrations → New registration**.
   - Name **Edith**.
   - Supported account types per **D18**. The recommended default is "Accounts in any organizational directory
     and personal Microsoft accounts".
   - Redirect URI: platform **Public client/native (mobile & desktop)**, `http://localhost`.
2. **API permissions → Add → Microsoft Graph → Delegated**:
   - `offline_access`, `openid`, `email`, `User.Read`
   - `Mail.ReadWrite` (drafts need it; read-only isn't enough)
   - `Mail.Send`
   - `Calendars.ReadWrite`
   No admin consent is needed for a personal account.
3. **Overview** → copy the **Application (client) ID** into `local.properties` as
   `edith.microsoftClientId=…`, then into `BuildConfig`. There's no secret. Don't create one.
4. If the owner will add a **work** account, their organisation may not allow users to approve apps. Then
   sign-in ends with "Need admin approval", and only their IT admin can allow it. Edith shows that message (§5).

## Specification

### 1. Sign-in (`microsoft/`)
- Generalise C17b's `BrowserOAuth` into an `OAuthProvider` config (endpoints, client id, optional secret, scopes,
  extra auth parameters, how to read the address). The PKCE, `state`, loopback, paste fallback, pending sign-in
  and `AppVisibility` hold all stay as they are. Google keeps its current behaviour, and its tests must pass
  unchanged.
- Endpoints: `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize` and `…/token`.
  - `{tenant}` is `common` when D18 = personal and work, `consumers` when D18 = personal only.
- Auth parameters:
  - `response_type=code`, `redirect_uri=http://localhost:<port>`, `scope` (above, space-separated)
  - `code_challenge` / `S256`, `state`
  - `prompt=select_account` when adding; `login_hint=<address>` when signing in again
- **Loopback:** the redirect says `localhost`, so Chrome may try `::1` before `127.0.0.1`. `LoopbackReceiver`
  listens on **both** loopback addresses, on the same port (Google's flow keeps `127.0.0.1`).
- Token exchange and refresh: form POST with `client_id`, no secret.
  - **Store the new refresh token after every refresh** (Google never sends one; Microsoft always does), before
    using the access token.
  - Save it under the account's `account/<id>/refresh_token`.
- The account's address comes from the id_token: `email`, else `preferred_username`. If that's empty, use
  `GET https://graph.microsoft.com/v1.0/me` → `mail` or `userPrincipalName`.
- **Disconnect:** Microsoft has no endpoint to revoke one app's token for the owner. Edith deletes the token and
  the account. The dialog says where to remove Edith's access completely: <https://account.live.com/consent/Manage>
  (personal) or <https://myapplications.microsoft.com> (work; verify).

### 2. `OutlookCalendarSource` (Graph, base `https://graph.microsoft.com/v1.0`)
- `calendars()`: `GET /me/calendars` → `id`, `name`, `isDefaultCalendar`, `canEdit`.
  - Outlook has no "checked" flag like Google's `selected` (D15), so all calendars are read.
  - The Birthdays and Holidays calendars are read-only. Leave them in for reading; they can't be written to.
- `events`: `GET /me/calendars/{id}/calendarView?startDateTime=&endDateTime=&$top=50&$orderby=start/dateTime`.
  - Send the header `Prefer: outlook.timezone="<device IANA zone>"` (verify that Graph accepts IANA names; if
    not, map to the Windows zone name).
  - `calendarView` already expands recurring events.
  - For `text`, filter on the client (subject and location). Graph's `$search` doesn't apply to events.
- `create`: `POST /me/calendars/{id}/events`. `update`: `PATCH /me/events/{id}` (only the changed fields).
  `delete`: `DELETE /me/events/{id}`.
  - All-day events: `isAllDay: true` with midnight-to-midnight times in the event's zone.
- `iCalUId` → C22's duplicate-invite merge.

### 3. `OutlookMailSource`
- `search`: `GET /me/mailFolders/inbox/messages` with `$select=id,subject,from,toRecipients,receivedDateTime,isRead,bodyPreview,internetMessageId`
  and `$top`.
  - `unreadOnly`, `after`, `before` → `$filter=isRead eq false and receivedDateTime ge …`, plus
    `$orderby=receivedDateTime desc`.
  - `from`, `to`, `subject`, `text` → `$search="from:… subject:…"` (KQL).
  - **Graph limits combining `$search` with `$filter`/`$orderby`.** Check the current rules. If a query needs
    both, use `$search` and apply the date/unread conditions on the client. That's correct, only slower.
  - "More match" comes from `@odata.nextLink`.
- `read`: `GET /me/messages/{id}` with `Prefer: outlook.body-content-type="text"`. Then C19's `MailText` removes
  quoted history and caps it at 6,000 characters.
- `draft`, new email: `POST /me/messages` (it lands in Drafts).
- `draft`, reply: `POST /me/messages/{id}/createReply`, then `PATCH` its `body` to exactly the card's text.
  - The `createReply` body includes the quoted original. Replace it, so the draft is exactly what the owner
    confirmed (D5). This matches Gmail, where Edith's replies carry no quote either.
- `send`: make the draft as above, then `POST /me/messages/{draftId}/send`. Graph saves it to Sent Items.
  - Don't use `/me/sendMail` or `/reply` with a `comment`: Graph adds text to those that the card didn't show.

### 4. Errors and limits
- 401 → refresh once and retry; again → NeedsSignIn.
- 403 → the permission is missing → NeedsSignIn ("Grant access").
- 429 → wait `Retry-After` once (at most 10 s), then give up with a plain message.
- `invalid_grant` on refresh (expired after 90 idle days, password changed, revoked) → NeedsSignIn, keeping the
  account in the list.
- `AADSTS65001` / "admin approval required" → "Your organisation's IT admin has to allow Edith before you can add
  this account."
- No network → `Error` with **Try again**, keeping the token (C17b trap 3).

### 5. Settings
- In Add account, **Microsoft** is shown when `edith.microsoftClientId` is set. Otherwise it's shown as "Not set up
  (see docs/microsoft-setup.md)".
- The state texts match C22's. There's no weekly sign-in, so the "about once a week" text is Google's only.

## Tests
- `OAuthProvider` for Microsoft:
  - the auth URL (tenant, prompt, hint, scopes)
  - the exchange and refresh forms, with no secret
  - **the rotated refresh token is stored**
  - the address from the id_token, and from `/me`
  - error mapping, including AADSTS65001
- `LoopbackReceiver` on `::1` and `127.0.0.1`.
- Graph, against MockWebServer with responses that follow Graph's reference (made up):
  - calendars, `calendarView` (timed, all-day, recurring, another zone), create/patch/delete bodies
  - search: filter only, search only, both (client-side filtering), `nextLink`
  - read in text form
  - the reply draft body replaced with exactly the card's text; send makes no other request
- Through C22's tool loop: a Microsoft account and a Google account together, labelled; "which account?" offers
  both; declining the card sends nothing.

## Acceptance criteria
- [ ] Emulator (`Pixel_9_API_37`, debug): Add account → Microsoft → Chrome → sign in → **Connected**, next to the
      Google account.
- [ ] "What's on my calendar this week?" includes the Outlook events, labelled, and matches Outlook on the web.
- [ ] "Any unread email today?" includes Outlook's, labelled.
- [ ] "Add a test event tomorrow at 3pm" → asked which account → the Microsoft one → the card → it appears in
      Outlook. "Delete it" → gone. (Clean up.)
- [ ] "Draft a reply saying thanks" to an Outlook email → the draft is in Outlook's Drafts, with exactly the card's
      text.
- [ ] A restart: still Connected. Disconnect → removed; the dialog's link shows where to remove Edith at Microsoft.
- [ ] `scripts/check.sh --device` passes; release APK on `Edith_Release_Test`; then in Andarium on the owner's
      iPhone.

## Implementation notes (traps)
1. **Rotating refresh tokens:** losing the new one (a crash between the refresh and the save) signs the owner out
   after the old one stops working. Save it before anything else. The old one isn't revoked at once, so a retry
   with the old one still works.
2. **Clock:** Graph's `dateTime` fields have no offset. Their zone is in the `timeZone` field (or the `Prefer`
   header's zone). Read them with that zone, never as UTC (L29).
3. **Personal vs work:** personal accounts have no organisation. `/me/mailFolders/inbox` works for both. Avoid
   anything that needs `Directory.*` permissions.
4. **Off screen:** the code arrives while Chrome is in front, so C17b's `AppVisibility` hold applies unchanged
   (L36).
5. **Docs to update in this PR:** README (chunk status), `docs/microsoft-setup.md` (new), 01-architecture §2/§9,
   06-decisions (D18's answer).

## Not in scope
- Exchange on-premises, or mailboxes shared with the owner (`/users/{id}`).
- Microsoft To Do, OneDrive, Teams.

## As built (2026-11-13)

Where the code differs from the specification above, the code is right. D18 = **personal and work** (tenant
`common`), recorded in 06-decisions.

**Files**
- `google/browser/OAuthProvider.kt` (new): one provider's endpoints, client, scopes, extra sign-in parameters, the
  id_token claims for the address, and whether the redirect is `localhost`. `BrowserOAuth` takes one (Google keeps its
  old constructor, so C17b's code and tests are unchanged). Microsoft's `AADSTS65001` → `ConsentRequired`; an admin
  code in a redirect's `error_description` → `Redirect.AdminApproval`.
- `google/browser/BrowserSignIn.kt` (new): the browser sign-in itself, taken out of `BrowserGoogle` and shared by every
  provider: one at a time for the app, the pending sign-in (`pending_sign_in` with `provider`), the paste fallback and
  the `AppVisibility` hold. `BrowserGoogle` keeps Google's words and its per-account tokens. `Accounts.resumePending`
  resumes whichever provider's sign-in was pending. The three files stay in `google/browser/` (moving them would only
  rename).
- `LoopbackReceiver.openLocalhost`: 127.0.0.1 and `::1` on the same port (it tries a few ports the OS picks until `::1`
  can take the same one; without IPv6 loopback, 127.0.0.1 alone). Google's listener stays on 127.0.0.1.
- `microsoft/`: `MicrosoftOAuth` (the provider), `MicrosoftAccountAuth` (one account's access), `GraphClient`,
  `OutlookCalendarSource`/`OutlookMailSource` (`OutlookSources.kt`), `MicrosoftAccounts` and `MicrosoftConnection`.
- `debug/DebugMicrosoftReceiver.kt` (debug builds only): a few timed Graph requests per Microsoft account, logging the
  status, Graph's error code, its `request-id` and the time, never the token or content:
  `adb shell am broadcast -n com.edith.app.debug/com.edith.app.debug.DebugMicrosoftReceiver`.
- Settings → Accounts: **Microsoft** in Add account ("Not set up (see docs/microsoft-setup.md)" without
  `edith.microsoftClientId`); the waiting dialog names the provider and the `localhost` address to paste; Disconnect
  links to account.live.com/consent/Manage and myapplications.microsoft.com.
- `OutgoingMail.replyToId` (new): the provider's own id of the email replied to (Outlook's `createReply`).

**Decisions made while building**
- **Rotating refresh tokens:** every refresh's new token is saved before the access token is used (trap 1); a check
  right after signing in uses the sign-in's own access token, with no refresh.
- **Graph time zones:** Graph takes Windows zone names and only some IANA names, so Edith asks for times in the
  device's zone by its Windows name (Android's ICU) and reads each `dateTime` in the zone its answer names; without a
  Windows name she asks for UTC and converts. All-day events use their dates only. Writes use the same zone.
- **Mail search:** words go in `$search` (KQL); unread and dates in `$filter` with `receivedDateTime` first (Graph's
  rule for `$orderby`). Graph won't combine `$search` with `$filter`/`$orderby`, so with words the conditions are
  checked on the phone on a 50-message page. Calendar text is matched on the phone (subject and location).
- **Cancellable Graph calls (a fix found live):** Graph once took 21–30 s to answer a request with 502. The calls were
  blocking, so C22's 10 s per-account timeout couldn't stop them, and one stalled account made the whole tool time
  out (20 s), hiding Google's events. Graph calls are now enqueued and cancelled with the coroutine, so a stalled
  Outlook is cut off at 10 s and named, while the other accounts' results still come back. **Google's clients still
  block the same way** (a follow-up for C22's code).
- **429:** waits `Retry-After` once when it's 10 s or less; otherwise a plain "busy" message.
- **Errors** reuse `AppError.GoogleAuth` for account sign-in problems (the tools show only its detail). An outside
  agent's duplicate `MicrosoftAccountAuth.kt` with a new `AppError.MicrosoftAuth` was dropped at the owner's request.

**Verified**
- `scripts/check.sh`: OK (`build-logs/2026-11-13_10-24-45_check.log`), 313 unit tests; lint: no new warnings (the 2
  `ExifInterface` ones are C15's). The run before it (`build-logs/2026-11-13_10-22-13_check.log`) failed once: the
  stalled-Outlook test raced OkHttp's default 10 s read timeout in the test harness against the 10 s per-account
  limit. The app reads for up to 180 s, so the harness's Microsoft client now does too; two forced reruns passed.
- Unit tests (`MicrosoftAuthTest` 13, `OutlookSourcesTest` 13, `LocalhostLoopbackTest` 3, and in
  `MultiAccountToolsTest`: Google and Microsoft labelled together, "which account?" offering both, declining sends
  nothing, a stalled Outlook timing out alone). Google's C17b/C22 tests pass unchanged.
- **Live on `Pixel_9_API_37` (debug), with the owner's Hotmail account:**
  - 2026-11-12: Add account → Microsoft → Chrome → sign-in → the code arrived on the loopback while Chrome was in
    front, Edith waited until she was on screen (L36), **Connected**. Then every Graph request, even `GET /me`, got
    502/504 `UnknownError` after 21–30 s (Microsoft's side: request-ids logged); the owner paused C23.
  - 2026-11-13: the probe got HTTP 200 for every request (the first inbox read took 27.6 s, then all under 0.8 s).
    - "What is on my calendar this week?" → `list_calendar_events` → the Outlook event, labelled with the account.
    - "Any unread email today in my Hotmail account?" → `search_email` with `account` → today's unread Outlook mail.
    - "Add a test event called Edith C23 test tomorrow at 3pm" → Edith asked which account, no card → "The Hotmail
      one" → card "Account: … — Calendar" → Add → **in Outlook** (owner checked). "Delete the Edith C23 test event"
      → found at 3:00–4:00 PM → card → Delete → **gone from Outlook**.
    - Reply draft: Edith first claimed she had drafted it **without calling the tool** (nothing was saved; a model
      slip like L26, with `gemma4:12b`). Asked again, the card (To, "Re: New app(s) connected…", "Thanks") → Save
      draft → in Outlook's Drafts with **only "Thanks"**, no quoted original (owner checked).
    - A restart: the account refreshed its token quietly and was Connected again.
    - Disconnect: the dialog showed both links; the account and its token were removed; Google stayed.
  - No crash from Edith.
- `scripts/check.sh --device`: OK (112 tests, `build-logs/2026-11-13_10-26-16_check.log`).
- **Release APK on `Edith_Release_Test`** (R8, `build-logs/2026-11-13_10-28-04_gradle_app-assembleRelease.log`, not
  published; 33.95 MB, about 135 KB more than v2.0.0-dev.2): installed over the earlier test build; the Google account
  carried over. Add account → Microsoft → Connected; the Outlook calendar and today's unread Hotmail mail came
  through; after a force-stop it reconnected quietly. No crash.

**Not verified yet**
- Andarium on the owner's iPhone (needs the next published build).
- A work or school account (admin approval), 90 idle days, a changed password.
