# C63 — Microsoft account on iOS: Outlook mail and calendar

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **Design — needs an Entra app registration by the owner** |
| Depends on | C59 (accounts, the PKCE/redirect helper), C60 (calendar tools), C61 (mail tools), C58 |
| Unblocks | the owner's Outlook.com / Microsoft 365 mail and calendars in every feature that reads accounts |
| Runs in | the development build |
| Size | Large |
| Ports | C23 (`microsoft/`), D18 |
| Read first | C23 spec **and** As built (Graph calls, `Prefer` headers, the rotating refresh token, the admin-approval error), C17b trap 3, D17, D18 |

## Why
D17/D18: Edith reads and writes Outlook.com / Microsoft 365 mail and calendars next to the Google accounts, **personal and
work or school** (tenant `common`). Microsoft's sign-in is the same shape as Google's (browser page, PKCE, code back to a
redirect): reuse C59's helpers with a provider configuration. **A work account whose organisation lets only its IT admin approve
apps can't be added until the admin allows Edith**: Edith says so (D18).

## Before you start — owner setup (write `docs/microsoft-setup.md` §iOS; verify against the current Entra admin center)
1. entra.microsoft.com → **App registrations → New registration**: name **Edith**; supported accounts per D18 (**any organizational directory and personal Microsoft accounts**).
2. **Authentication → Add a platform → Mobile and desktop applications**: add the redirect URI **`edith://auth`** (a custom scheme: C58 registers `edith`). iOS apps can also use the `msauth.<bundle id>://auth` form; **use `edith://auth`** unless Microsoft refuses (record which). **Public client: no client secret. Don't create one.** Allow public client flows: Yes.
3. **API permissions → Microsoft Graph → Delegated**: `offline_access`, `openid`, `email`, `User.Read`, `Mail.ReadWrite` (drafts need it), `Mail.Send`, `Calendars.ReadWrite`. No admin consent for a personal account.
4. Give the **Application (client) ID** to the build via the EAS plain-text env var `EDITH_MICROSOFT_CLIENT_ID` (as C59 does for Google). It is not a secret.
5. For a work account, the organisation may block user consent: Edith shows the admin-approval message.

## Specification

### 1. Sign-in (`microsoft/auth.ts`, reusing C59's PKCE and `openAuthSessionAsync`)
- Endpoints `https://login.microsoftonline.com/common/oauth2/v2.0/authorize` and `…/token`. Authorize parameters: `response_type=code`, `redirect_uri=edith://auth`, `scope=` the list above, PKCE S256, `state`, `prompt=select_account` when adding, `login_hint=<address>` when signing in again.
- Token exchange and refresh: form POST with `client_id`, **no secret**. **Every refresh returns a NEW refresh token: store it (SecretStore `account.<id>.refresh_token`) BEFORE using the access token it came with** (Google never sends one; Microsoft always does), and refreshes never overlap (per-account mutex from C59). Refresh tokens live 90 days idle for native apps; no weekly sign-in.
- Address from the `id_token` (`email`, else `preferred_username`); if empty `GET https://graph.microsoft.com/v1.0/me` → `mail` or `userPrincipalName`.
- **Disconnect:** Microsoft has no endpoint to revoke one app's token for the owner. Delete the token and the account; the dialog says where to remove Edith's access fully: `https://account.live.com/consent/Manage` (personal) or `https://myapplications.microsoft.com` (work; verify).
- Errors: `AADSTS65001` / "admin approval required" → "Your organisation's IT admin has to allow Edith before you can add this account."; `invalid_grant` on refresh → needs sign-in (expired after 90 idle days, password changed, revoked) keeping the account listed; 401 → refresh once; 403 → permission missing → Grant access; 429 → wait `Retry-After` once (≤ 10 s) then a plain message; no network → `error` with Try again, **keeping the token**.

### 2. `OutlookCalendarSource` (Graph `https://graph.microsoft.com/v1.0`, over `secureFetch` allowing `microsoft.com`, `microsoftonline.com`)
- `calendars()`: `GET /me/calendars` → `id, name, isDefaultCalendar, canEdit`. **No "checked" flag** like Google's (D15): read all; Birthdays and Holidays are read-only.
- `events`: `GET /me/calendars/{id}/calendarView?startDateTime=&endDateTime=&$top=50&$orderby=start/dateTime`; header `Prefer: outlook.timezone="<phone IANA zone>"` (verify Graph accepts IANA names; else map to the Windows zone name); `calendarView` already expands recurring events; `text` filter on the client (subject and location).
- `create`: `POST /me/calendars/{id}/events`; `update`: `PATCH /me/events/{id}` (only changed fields); `delete`: `DELETE /me/events/{id}`; all-day: `isAllDay: true`, midnight-to-midnight in the event's zone. `iCalUId` feeds C60's duplicate-invite merge.

### 3. `OutlookMailSource`
- `search`: `GET /me/mailFolders/inbox/messages?$select=id,subject,from,toRecipients,receivedDateTime,isRead,bodyPreview,internetMessageId&$top=`. `unreadOnly`/`after`/`before` → `$filter=isRead eq false and receivedDateTime ge …` with `$orderby=receivedDateTime desc`; `from/to/subject/text` → `$search="from:… subject:…"` (KQL). **Graph limits combining `$search` with `$filter`/`$orderby`:** check the current rules; if a query needs both, use `$search` and apply the date/unread conditions **on the client**. "More match" from `@odata.nextLink`.
- `read`: `GET /me/messages/{id}` with `Prefer: outlook.body-content-type="text"`, then `mail/mailText.ts` (C61).
- `draft` new: `POST /me/messages`. `draft` reply: `POST /me/messages/{id}/createReply`, then `PATCH` its `body` to **exactly the card's text** (the quoted original is replaced so the draft is what the owner confirmed, D5). `send`: make the draft as above, then `POST /me/messages/{draftId}/send` (Graph saves to Sent Items). **Do not use `/me/sendMail` or `/reply` with a comment**: Graph adds text the card didn't show.

### 4. Settings
**Microsoft** in Add account when `EDITH_MICROSOFT_CLIENT_ID` is set; otherwise "Not set up (see docs/microsoft-setup.md)". State texts match C59's. No "about once a week" text (that is Google's).

## Files
New: `microsoft/{auth,oauthConfig,graph,calendar,mail}.ts`, `docs/microsoft-setup.md`, tests. Changed: `app.config.ts` (nothing needed for `edith` scheme; confirm), `accounts` provider list, `core/services.tsx`.

## Tests
Auth URL (tenant, prompt, hint, scopes, redirect); exchange and refresh forms with no secret; **the rotated token is stored before use** and parallel refreshes → one; address from the id_token and from `/me`; error mapping incl. AADSTS65001; Graph fixtures made from the reference (calendars, `calendarView` timed/all-day/recurring/other zone, create/patch/delete bodies); search with filter only, search only, both (client-side filtering), `nextLink`; reply draft body replaced; send only after the card; 429 once then give up; run through C60/C61's tool loop with a Microsoft account beside Google (labels, which-account, declining sends nothing).

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner:** Add account → Microsoft → sign in (personal and, if possible, work) → Connected; calendar and unread mail include it, labelled; "Add a test event tomorrow 3pm" → asks which account → the card → the event appears in Outlook; "Draft a reply…" → the draft in Outlook Drafts with exactly the card's text; a work account blocked by consent shows the IT-admin sentence.

## Risks and unverified assumptions
- `edith://auth` accepted by Entra as a native redirect (Microsoft says custom schemes are allowed for Mobile and desktop; verify); Microsoft's `localhost` loopback is not usable on iOS.
- Graph's IANA `Prefer` timezone and the `$search`+`$filter` limits (checked at build time).

## Not in this chunk
Microsoft To Do (not planned), contacts, attachments, the work account that only lives in classic Outlook (that is Jarvis, C66).
