# C22 — Several accounts: the account model, and several Google accounts

| | |
|---|---|
| Phase | 7 — Accounts |
| Depends on | C17, C17b, C18, C19 (all Done) |
| Unblocks | C23 (Microsoft), C24 (iCloud) |
| Size | Large |
| Read first | 06-decisions **D17** (and D5, D11, D15, D16); C17b (spec **and** As built); C18 and C19 As built; 04-lessons L6, L27, L29, L36 |

## Why

The owner asked on 2026-11-12 for several inboxes and calendars at once: more than one Google account, plus
Microsoft and iCloud (D17). Today everything assumes **one** Google account: `GoogleAuth` holds one state, the
settings hold one `google_account_email`, `SecretStore` holds one `google_refresh_token`, and the calendar and
email tools talk to Google's APIs directly.

This chunk builds the provider-neutral layer (accounts, calendar and mail sources, the "which account?" rule) and
moves Google onto it, with **any number of Google accounts**. C23 and C24 then only add a provider each.

## Goal

- Settings → **Accounts** lists every connected account: its label (the owner's name for it, e.g. "work"), its
  address, its provider, and its state. **Add account → Google** adds another one. Each account has its own
  **Grant access** / **Sign in again** and its own **Disconnect**.
- "What's on my calendar tomorrow?", "Am I free Friday?", "Any unread email today?" cover **every** account. Each
  item says which account it's from when more than one is connected.
- "Add lunch with Sam Friday at noon" → Edith **asks which account** (D17), then shows the card, which names the
  account. "…on my work calendar" → no question. With one account, no question.
- Replying to an email, or changing or deleting an event, stays in that item's own account. No question; the card
  shows the account.
- The owner's existing Google account (v2.0.0-dev.2) carries over **without signing in again**.

## Before you start — owner setup
1. Google Cloud Console → project **Edith** → **Google Auth Platform → Audience → Test users**: add every extra
   Google account the owner will connect. The project is in **Testing**, so Google refuses accounts that aren't
   test users ("Access blocked"). The limit is 100. No new client and no new scopes are needed.
2. Each Google account gets its own weekly sign-in (Testing, D16), at different times.

## Specification

### 1. Accounts (`accounts/`)
```kotlin
enum class Provider { Google, Microsoft, ICloud }           // Microsoft: C23, ICloud: C24
data class Account(
    val id: String,          // random, stable ("a1b2c3"); never the address (it goes in logs and secret keys)
    val provider: Provider,
    val address: String,     // the account's email address, from the provider
    val label: String,       // the owner's name for it; defaults to the address; unique, case-insensitive
    val signIn: String?,     // Google only: "play" or "browser" (C17b's mode, now per account)
)
sealed interface AccountState { Checking; Connected; NeedsSignIn(reason: String); Error(message: String) }
interface AccountAuth { suspend fun <T> authorized(scopes: Set<Scope>, call: suspend (token: String) -> Call<T>): Outcome<T>; … }
```
- `AccountStore`: the list in DataStore, key `accounts` (JSON). Order = the order the owner added them; the first
  is listed first everywhere.
- `Accounts` (in `AppContainer`): `accounts: StateFlow<List<Pair<Account, AccountState>>>`, `add…`,
  `rename(id, label)`, `disconnect(id)`, `byName(text)`. `byName` matches a label or an address,
  case-insensitively, and also a unique prefix of either ("work" → "Work (Contoso)"). It returns no match, one, or
  several.
- **Secrets** per account: `account/<id>/refresh_token`, and for C24 `account/<id>/password`. The pending
  browser sign-in (C17b's `google_pending_sign_in`) becomes `pending_sign_in` with `provider` and `accountId`
  (null when adding a new account) in its JSON. One pending sign-in at a time; starting another cancels it.

### 2. Google, per account (`google/`)
- `GoogleAuth` becomes `GoogleAccountAuth(account)`; `GoogleState` maps to `AccountState`. The Play-services and
  browser code from C17/C17b stays. What changes is that it's keyed by account.
- **Play services path:** `AuthorizationClient` authorizes the account it chooses. Check whether
  `AuthorizationRequest.Builder.setAccount(Account)` (play-services-auth) lets Edith name the account. If it does,
  each Google account can use Play services. If it doesn't, **only the first Google account** uses Play services,
  and every further one uses the browser (C17b), on every phone. Record which in As built.
- **Adding another Google account (browser):** the auth URL gets `prompt=select_account consent` and no
  `login_hint`, so Google offers the account chooser. The account's address comes from the id_token (C17b). If it
  matches an account that's already there, that account's token is replaced: no duplicate.
- **Grant access / sign in again** for an existing account sends `login_hint=<address>` (C17b already does).
- Disconnect revokes that account's token only (C17/C17b) and removes it from the list.

### 3. Sources (`accounts/sources/`)
```kotlin
interface CalendarSource {   // one per connected account that has a calendar
    val account: Account
    suspend fun calendars(): Outcome<List<CalendarInfo>>          // id, name, isDefault, canWrite
    suspend fun events(from: Instant, to: Instant, text: String?): Outcome<List<CalendarEvent>>
    suspend fun create(calendarId: String?, draft: EventDraft): Outcome<CalendarEvent>   // null → the default
    suspend fun update(event: CalendarEvent, change: EventChange): Outcome<CalendarEvent>
    suspend fun delete(event: CalendarEvent): Outcome<Unit>
}
interface MailSource {
    val account: Account
    suspend fun search(q: MailQuery, max: Int): Outcome<MailSearchResult>   // messages + whether more matched
    suspend fun read(id: String): Outcome<MailMessage>
    suspend fun draft(m: OutgoingMail): Outcome<Unit>
    suspend fun send(m: OutgoingMail): Outcome<Unit>
}
data class MailQuery(val from: String?, val to: String?, val subject: String?, val text: String?,
                     val unreadOnly: Boolean, val after: Instant?, val before: Instant?)
```
- `CalendarEvent` and `MailMessage` carry `accountId` and the provider's own ids, plus `iCalUid` for events.
- `GoogleCalendarSource` wraps C18's `CalendarClient`, and `GmailSource` wraps C19's `GmailClient`. Their
  behaviour doesn't change: checked calendars only (D15), recurring events as instances, MIME handling, the
  6,000-character cap. `GmailSource` turns `MailQuery` into Gmail's search syntax, using C19's
  `GmailQuery.localDates` for exact local days.
- `MailText` (C19) and the confirmation cards stay shared by every provider.

### 4. Tools (one integration, `accounts`)
Every calendar and email tool gets an optional **`account`** parameter (String: "the account's name or address,
only when the owner named one").

| Tool | Change |
|---|---|
| `list_calendar_events` | All accounts unless `account` is given. Lines end `[calendar · account label]`; the label only with more than one account. **The same event in two accounts** (an invite, matched by `iCalUid` + start) is listed once, with both labels. |
| `find_free_time` | Busy in any account = busy. |
| `create_calendar_event` | New optional `calendar` (a calendar's name, from the list). See **Which account** below. The card adds a row: **Account**: "work (sam@contoso.com) — Calendar". |
| `update_calendar_event`, `delete_calendar_event` | Unchanged parameters. The handle (`e3`) knows its account and calendar. The card shows the account. |
| `search_email` | **Parameters change** (Gmail's syntax doesn't work for Microsoft or iCloud): `from`, `to`, `subject`, `text`, `unread_only` Boolean, `after`, `before` (date or `today`/`yesterday`, as C19), `max_results` = 5, `account`. All accounts unless `account`. Lines end `[account label]` with more than one account. When more matched than were shown, the result says "more match" (closes C19's "still open" undercount). |
| `read_email` | Unchanged; the handle (`m2`) knows its account. |
| `draft_email`, `send_email` | A reply (`reply_to_message_id`) goes from the account the email came to. A new email: see **Which account**. The card's first row is **From**: "work (sam@contoso.com)". |

**Which account (D17), decided in code, not left to the model.** For `create_calendar_event` and a new
`draft_email`/`send_email`:
1. `account` given and it matches one account → that one. If it matches none or several, the tool returns
   "There's no account called 'x'. The accounts are: personal (a@gmail.com), work (sam@contoso.com)." and makes
   no request.
2. `calendar` given and only one account has a calendar of that name → that account.
3. Only one connected account can do it (has a calendar / can send) → that one.
4. Otherwise the tool returns, with no card and no request: "Ask the owner which account to use: personal
   (a@gmail.com) or work (sam@contoso.com). Then call this tool again with `account`." (L27: the instruction is
   in the result.)
The card always names the account, so a wrong guess by the model can still be cancelled.

**Partial failure.** When one account fails during a read (needs signing in, no network), the others' results
are still returned, followed by "Couldn't read work (sam@contoso.com): it needs signing in again — Settings →
Accounts." Edith must say so (L6).

**Prompt line** (C17's `connectedNote`): "Accounts: personal — a@gmail.com (Google: calendar and email);
work — sam@contoso.com (Google: needs signing in again)." The tools are offered while **at least one** account
is connected. An account that needs signing in is named, so Edith can tell the owner instead of guessing.

### 5. Settings → Accounts (replaces Settings → Google account)
- One row per account: provider icon, label, address, state. Tapping it opens: **Rename**, **Grant access** /
  **Sign in again** (when needed), **Disconnect** (confirm dialog: "Disconnect work? Edith won't see its email or
  calendar.").
- **Add account** → a sheet: **Google** (this chunk), **Microsoft** (C23), **iCloud** (C24). A provider appears
  once its chunk is done. The "Not set up" texts stay per provider, e.g. Google with no client IDs.
- C17b's waiting dialog stays app-wide. It now says which provider ("Finish signing in to Google in your browser").

### 6. Calendar tab
All accounts' events, merged by time, with the account label on each event when there's more than one account.
An account that can't be read shows a one-line banner with **Settings** (as C18 does for one).

### 7. Carrying over the existing account (migration, runs once at startup)
If `accounts` is missing and `google_account_email` is set: create one Google account (new id, `label` =
`address`, `signIn` = `google_sign_in_mode` or `play`). Move `google_refresh_token` to
`account/<id>/refresh_token`. Then remove the old keys. The owner stays Connected, with no browser. It must be
idempotent and safe if the app is killed half-way: write the new keys first, delete the old ones last.

## Tests
- `AccountStore` round trip; `byName` (label, address, prefix, ambiguous, none); unique labels.
- Migration: from C17 (Play) and from C17b (browser with a refresh token); killed half-way; run twice.
- Which-account rule: every branch above, through the real tool loop. **No card and no request** when Edith must
  ask (MockWebServer sees none).
- Merged reads over two fake sources: ordering, labels only when there are two, the invite shown once, one source
  failing (the others still listed, plus the failure line).
- `find_free_time` across two accounts.
- `GmailSource` query translation (each `MailQuery` field; `today`; exact local days).
- Handles keep their account: a reply goes from the receiving account; update/delete go to the right account.
- Prompt line with Connected / NeedsSignIn accounts; tools offered while at least one account is connected.
- Device (Compose): Accounts list, Add account sheet, rename, disconnect confirm.
- `GoogleAuthTest`, `CalendarTest`, `GmailTest`, `GoogleSettingsTest` move to the per-account API. Their cases
  stay.

## Acceptance criteria
- [ ] Install over v2.0.0-dev.2 (release emulator): the existing Google account is still Connected, with no sign-in.
- [ ] Add a second Google account (the owner's choice, added as a test user first) → both listed, both Connected.
- [ ] "What's on my calendar this week?" lists both accounts' events, labelled; matches Google Calendar for each.
- [ ] "Any unread email today?" covers both, labelled.
- [ ] "Add a test event tomorrow at 3pm" → Edith asks which account → "work" → the card shows the account → it
      appears in that account only. "Delete it" → no question, deleted from that account. (Clean up.)
- [ ] "Draft a reply to <an email in account 2>" → the card's From is account 2 → the draft is in account 2.
- [ ] Disconnect account 2 → account 1 still works; account 2 is gone from its Google permissions page.
- [ ] After a restart both are still Connected. `scripts/check.sh --device` passes; release APK tested on
      `Edith_Release_Test`, then in Andarium on the owner's iPhone.

## Implementation notes (traps)
1. **Migration is the riskiest part.** A bug signs the owner out of Google or loses the refresh token. Test the
   install-over on the release emulator with real v2.0.0-dev.2 data before anything else ships.
2. **The model will try to pick an account itself.** The which-account rule is in the tool code for that reason.
   Don't move it into the prompt.
3. **Labels in chat.** The owner will say "work", "my Gmail", "personal". `byName` matches labels and addresses;
   anything else gets the "no account called…" answer listing the real ones. Don't try fuzzy matching beyond
   prefixes.
4. **Handles across accounts:** `e1`/`m1` stay one sequence for the whole app run (not per account), so a handle
   is never ambiguous.
5. **Concurrency:** reads across accounts run in parallel (one coroutine per source, each with its own timeout,
   10 s); C17b's per-account `Mutex` for refresh stays per account.
6. **Release logs** use the account `id`, never the address or label.
7. **Docs to update in this PR:** 01-architecture §2 (`accounts/`), §5.3 (keys `accounts`, removed
   `google_account_email` / `google_sign_in_mode`), §9 (per-account secrets); `docs/google-setup.md` (test users
   for extra accounts); C18/C19 headers pointing here for the new parameters.

## Not in scope
- Microsoft (C23) and iCloud (C24).
- Choosing which calendars to read per account inside Edith. Google still uses the calendars checked in Google
  Calendar (D15).
- Unified "reply from a different account than it was sent to".
