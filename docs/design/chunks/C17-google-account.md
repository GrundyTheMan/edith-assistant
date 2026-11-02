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
4. Credentials: an **Android** OAuth client (package `com.edith.app.debug` since C17 — see As built — and the SHA-1 of the debug keystore — get it with
   `gradlew signingReport`) and a second Android client with the **release** SHA-1 from `05-environment.md` §6 **and** a **Web application** client (its client ID is
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
- [x] With the owner's Cloud setup: sign in on the emulator (Google Play image) → "Connected as …".
- [x] `accessToken(CalendarEvents)` succeeds (debug log of token length only, never the token).
- [x] Disconnect removes access; tools disappear from the prompt/request (check request JSON in a debug log).

## As built (2026-11-01)

Where the code differs from the specification above, the code is right.

**D9 (the owner, 2026-11-01):** a new Google Cloud project "Edith", set up by the owner with `docs/google-setup.md`
(the checklist for "Before you start"). v1 never had an OAuth client.

**Files** (`google/`)
- `GoogleAuth.kt`: `GoogleScope`, the `GoogleSignIn` and `GoogleAuthorizer` interfaces (faked in tests),
  `GoogleState`, `GoogleCall`, and `GoogleAuth` (the `Integration`).
- `AndroidGoogle.kt`: `CredentialManagerSignIn` (Credential Manager, `GetSignInWithGoogleOption`) and
  `PlayServicesAuthorizer` (`Identity.getAuthorizationClient`: authorize, consent result, `clearToken`,
  `revokeAccess`).

Also:
- `ui/settings/GoogleSettings.kt` (Settings → Google account).
- `Integration.connectedNote(tools)` and `ToolRegistry.connectedNotes()` (C08's framework): the prompt says what a
  connected integration can actually do.
- Debug builds log the tools offered on each request (`Offering tools: …`).
- Libraries: play-services-auth 22.0.0, credentials 1.6.0, googleid 1.2.1, kotlinx-coroutines-play-services.
- `BuildConfig.GOOGLE_WEB_CLIENT_ID` comes from `local.properties` (`edith.googleWebClientId`), not committed.
  Without it (CI, other machines) Google shows "Not set up in this build".

**Differences from the spec**
- **Debug builds are `com.edith.app.debug`** (`applicationIdSuffix`); release stays `com.edith.app`.
  - Google refused the debug OAuth client: `com.edith.app` with the debug SHA-1 was "already in use" by a client
    the owner couldn't find in any of their projects. Each package + SHA-1 pair can belong to one client in all of
    Google Cloud.
  - A debug package of its own makes a new pair, and lets debug and release builds sit side by side.
  - Scripts (`DEBUG_APP_ID`, `MAIN_ACTIVITY` in `scripts/lib.sh`), tests and docs were updated.
  - On the emulator the debug app started fresh; the pre-C17 app (`com.edith.app`) keeps its data, separately.
- **States** (`GoogleState`): NotSetUp (no Web client ID), SignedOut, Checking, Connected, NeedsConsent, Error.
  `Integration.status` is Connected only in Connected, so Google's tools (C18, C19) are offered only then.
- **Signing in** asks for every scope at once (Calendar events, Gmail read, Gmail compose) and connects if they're
  granted; otherwise it's NeedsConsent with **Grant access**, which shows Google's consent screen.
- **At startup** a saved account is checked quietly (no screen); if Google wants approval again, it's NeedsConsent.
- **`authorized(scopes) { token -> … }`** is how C18/C19 call Google: a 401 clears the token and tries once more; a
  second 401 means NeedsConsent, with "Edith needs your permission again…". Only the token's length is logged.
- **Disconnect** revokes Edith's access (all scopes), clears Credential Manager's state and forgets the email,
  after a confirmation.
- **The prompt:**
  - Connected: "Google: connected as x (calendar and email)."
  - Connected without Google tools: "…but you have no calendar or email tools yet; if asked, say they're coming."
    This is the case until C18/C19 land, so Edith can't pretend (L6).
  - Not connected: "Google (calendar and email) isn't connected… say the owner can connect it in Settings → Google
    account."
- **Grant access is in Settings only for now.** The chat's Grant access belongs with the first tool that needs it
  (C18).

**Verified**
- `scripts/check.sh --device`: OK (`build-logs/2026-11-02_10-57-14_check.log`, as `com.edith.app.debug`). 180 unit tests (13 new in
  `GoogleAuthTest`), lint clean, 105 device tests (1 new: `GoogleSettingsTest`).
  - `GoogleAuthTest` covers:
    - no client ID → NotSetUp
    - sign-in → Connected with only the email saved; needs consent → granted → Connected; consent refused
    - cancelled and failed sign-in
    - the startup check, with and without a saved account
    - a token before sign-in → `GoogleNotConnected`
    - a 401 retried once; two 401s → NeedsConsent
    - disconnect (revoke, sign out, email cleared)
    - tools offered only while connected, and the prompt lines (with and without tools, after disconnect)
    - **no token in the settings file** (a real DataStore written, then its bytes searched)
- **Live on the emulator** (the owner's Cloud project, `com.edith.app.debug`):
  - **Without the client ID:** Settings → Google account says "Not set up in this build…".
  - **Sign in with Google** → Google's account chooser → "Signed in to Google" → "owner.one@example.com needs your
    permission".
  - **Grant access** → Google's "hasn't verified this app" warning (expected in Testing; the owner chose Continue) →
    the permissions → "Google access granted" → **Connected as owner.one@example.com**.
    - The owner clicked through Google's screens themselves.
  - **A real token:** the debug hook (`DebugGoogleReceiver`) got a Calendar token, logged as "322 characters" (never
    the token), and one Calendar request (`events?maxResults=1&fields=kind`) returned **HTTP 200**.
  - **Edith with Google connected but no Calendar tool yet:** "I'm still waiting on those tools to be hooked up". No
    invented events. Offered tools: time, weather, web.
  - **Disconnect** (confirmed) → "Google disconnected" → Not connected.
    - The debug hook then got no token ("Your Google account isn't connected yet").
    - Edith said she has no access and pointed to Settings → Google account (the not-connected line).
    - The revoke logged no error.
  - **Found and changed:** after a consent attempt came back cancelled, **Grant access** needed two taps (the first
    only re-checked quietly). It now asks Google again and shows its screen in one tap (`GoogleSettingsViewModel.grant`).
- **Not verified:**
  - **The release client** (a release build signing in): the release SHA-1 is registered but no release build was
    signed in.
  - **Google asking for approval again after a week** (Testing apps) and **a 401 from a real API**: both are
    unit-tested (`GoogleAuthTest`); C18 will see them in use.
