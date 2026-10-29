# C17 — Google account sign-in and authorization

| | |
|---|---|
| Phase | 5 — Google |
| Depends on | C02, C04, C08 (integration interface) |
| Unblocks | C18, C19 |
| Size | Medium (plus owner setup time in Google Cloud) |
| Read first | 01-architecture §9, §12; 06-decisions **D9**; 04-lessons L6 |

## Goal

The owner connects their Google account once; Edith gets short-lived access tokens for Calendar and Gmail
scopes on demand. No passwords in the app, no tokens stored by the app, and an `Integration` whose status
drives which tools are offered.

## Before you start — owner setup (write these as a checklist in the PR and walk the owner through it)
Verify each step against Google's current documentation; console wording changes.
1. Google Cloud Console → create (or pick, decision D9) a project "Edith".
2. Enable **Google Calendar API** and **Gmail API**.
3. OAuth consent screen: External, app name "Edith", the owner's email as support/developer contact, publishing
   status **Testing**, add the owner's Google account as a **test user**. Scopes:
   `https://www.googleapis.com/auth/calendar.events`, `https://www.googleapis.com/auth/gmail.readonly`,
   `https://www.googleapis.com/auth/gmail.compose`, `openid`, `email`.
   Note for the owner: Gmail read scopes are "restricted"; in Testing mode that's fine for their own account,
   but Google may require re-consent periodically (refresh tokens for Testing apps expire after about 7 days —
   verify). The app must handle re-consent gracefully.
4. Credentials: an **Android** OAuth client (package `com.edith.app`, SHA-1 of the debug keystore — get it with
   `gradlew signingReport`; add the release SHA-1 later) **and** a **Web application** client (its client ID is
   the `serverClientId` Credential Manager needs).
5. Put the Web client ID in `local.properties` (`edith.googleWebClientId=…`) → `BuildConfig` field. Never commit it
   to the repo if the owner prefers (it isn't secret, but keep it out of history by default).

## Specification

### 1. Sign-in (identity) — Credential Manager
`androidx.credentials` + `googleid`: `GetSignInWithGoogleOption(serverClientId)` from a button in Settings →
Google account ("Sign in with Google"). On success keep the email and display name (DataStore
`google_account_email`). Sign out: `ClearCredentialStateRequest` + clear the email.

### 2. Authorization (scopes and tokens) — `AuthorizationClient`
```kotlin
class GoogleAuth(context, settings, dispatchers) : Integration {
    override val id = "google"; override val displayName = "Google"
    override val status: StateFlow<IntegrationStatus>        // NotConfigured / Connected(email) / NeedsConsent / Error(msg)
    suspend fun accessToken(scopes: Set<GoogleScope>): Outcome<String>   // silent if granted; NeedsConsent otherwise
    fun consentIntent(): PendingIntent?                                  // when the user must approve scopes
}
enum class GoogleScope(val uri: String) { CalendarEvents(…), GmailReadonly(…), GmailCompose(…) }
```
- `Identity.getAuthorizationClient(context).authorize(AuthorizationRequest.builder().setRequestedScopes(…).build())`.
  If `result.hasResolution()` → status `NeedsConsent`; Settings and the chat show **Grant access** which launches
  the `PendingIntent` (`StartIntentSenderForResult`) and then retries. Otherwise use `result.accessToken`.
- Tokens are requested per call (Play services caches them); the app stores no tokens.
- On HTTP 401 from Google APIs: invalidate (call authorize again); repeated 401 → `NeedsConsent`.
- Integration status drives tool offering (C08): tools of `google` only when `Connected`.
- Prompt line (C08 integration lines) when connected: `Google: connected as owner@gmail.com (calendar and email).`
  When not: nothing (tools not offered) — Edith must not pretend (L6).

### 3. Settings → Google account
States: "Not connected" [Sign in with Google] · "Connected as x@gmail.com" [Disconnect] · "Needs permission" [Grant access] ·
error text. Short explanation of what Edith can do with access (read and add calendar events; read email and create drafts — never sends).

## Tests
- Status transitions with a fake authorization client: not configured → connected; needs consent → granted → connected; 401 → reauthorize.
- Tools of the google integration are offered only when connected (registry test with a fake integration).
- No token persisted (DataStore contains only the email — test).

## Acceptance criteria
- [ ] With the owner's Cloud setup: sign in on the emulator (Google Play image) → "Connected as …".
- [ ] `accessToken(CalendarEvents)` succeeds (debug log of token length only, never the token).
- [ ] Disconnect removes access; tools disappear from the prompt/request (check request JSON in a debug log).
