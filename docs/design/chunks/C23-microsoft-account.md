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
