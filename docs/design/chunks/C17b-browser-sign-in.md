# C17b — Google sign-in through the browser (no Google Play services)

| | |
|---|---|
| Phase | 5 — Google |
| Depends on | C17 (GoogleAuth), C02 (SecretStore, logging), C18/C19 unchanged |
| Unblocks | Google features on devices without Play services: the owner's iPhone, which runs Edith in **Andarium** |
| Size | Medium (plus ~10 minutes of owner setup in Google Cloud) |
| Read first | C17 (spec **and** As built); 06-decisions **D9**, **D16**; `docs/google-setup.md`; 04-lessons L6 |

## Why

The owner's phone is an iPhone. Edith runs there inside **Andarium**, an Android environment for iOS. Andarium
has no Google Play services, so C17's sign-in (Credential Manager + `AuthorizationClient`, both part of Play
services) fails. This was found on 2026-11-11 with the first release, v2.0.0-dev.1. Without it the Calendar and
Gmail tools never turn on.

Play services is part of the operating system. It can't be bundled into an APK. Instead, Edith signs in the way
desktop apps do: Google's sign-in page in a **browser**, the authorization code with **PKCE**, and a **refresh
token** that Edith keeps herself, encrypted (decision D16).

Facts checked by the owner's agent on 2026-11-11 (re-verify; Google changes these):
- In Andarium, **Android Chrome is installed inside the Android environment**, and links open there.
- Google's "OAuth 2.0 for Mobile & Desktop Apps" page
  (<https://developers.google.com/identity/protocols/oauth2/native-app>) says:
  - Custom URI scheme redirects "are no longer supported on Android and Chrome apps".
  - The loopback redirect (`http://127.0.0.1:port`) is **deprecated for Android, iOS and Chrome app client types**,
    and **recommended for Desktop app clients**.
  - Desktop apps are public clients: "Always use PKCE". Their client secret isn't treated as secret.
  - "refresh tokens are always returned for installed applications".
- **Refresh token expiry** (<https://developers.google.com/identity/protocols/oauth2#expiration>): for a project
  whose consent screen is External and in **Testing**, refresh tokens expire after **7 days**. The only exception
  is the basic sign-in scopes, which don't apply here. Edith's project is in Testing, so expect a browser sign-in
  about once a week. Also, a Google account allows 100 refresh tokens per client; the oldest is invalidated
  silently.
- The **device flow** ("TVs and Limited Input devices") is **not usable**: its allowed scopes are only
  openid/email/profile, two Drive scopes and YouTube. No Calendar, no Gmail.
- Google blocks sign-in in embedded **WebViews** (`disallowed_useragent`). Use the real browser.

So the client is a **Desktop app** OAuth client, and the redirect is **loopback to Edith herself**, with a
**paste-the-address fallback** for when the browser can't reach Edith's loopback port.

## Goal

On a device without Play services, the owner taps **Sign in with Google**. Edith opens Google's sign-in page in
the browser. The owner signs in and approves Calendar and Gmail. Edith gets the code back, either automatically or
from an address the owner pastes, and is Connected. From then on the calendar and email tools work exactly as on
a phone with Play services. When the refresh token expires (weekly in Testing), Settings shows **Grant access**,
and one browser round signs her back in.

On a device **with** Play services nothing changes, except a small **Use a browser instead** button for when Play
services sign-in fails.

## Before you start — owner setup (walk the owner through it; add it to `docs/google-setup.md`)
Verify the wording against the current Google Cloud Console.
1. Google Cloud Console → project **Edith** → **Google Auth Platform → Clients → Create client**.
2. Application type **Desktop app**, name **Edith browser sign-in** → **Create**.
3. Copy the **Client ID**. Google also shows a **client secret**: for Desktop clients Google says it isn't
   confidential, but follow the repo rule anyway and never commit it or paste it into chat. The owner adds both to
   `local.properties`:
   ```
   edith.googleDesktopClientId=….apps.googleusercontent.com
   edith.googleDesktopClientSecret=…
   ```
   Check whether Google's token endpoint accepts this client **without** the secret (PKCE only). If it does, don't
   use the secret, and record that in As built. If it doesn't, the secret is needed. Release builds then contain
   it; that's accepted for a Desktop client.
4. The consent screen, scopes and test user from C17 are unchanged. The Desktop client uses the same project, so
   no new scopes.

## Specification

### 1. Where it fits (no change for C18/C19)
`GoogleAuth` already talks to two interfaces, `GoogleSignIn` (who) and `GoogleAuthorizer` (access tokens). C17's
`CredentialManagerSignIn` and `PlayServicesAuthorizer` implement them with Play services. Add:
- **`google/browser/BrowserOAuth.kt`**: Google's endpoints, pure logic, testable on the JVM with MockWebServer.
  - `pkce()`: a random 64-character verifier from `SecureRandom` and its S256 challenge (RFC 7636, base64url
    without padding).
  - `authUrl(redirect, challenge, state, scopes)`: `https://accounts.google.com/o/oauth2/v2/auth` with
    `client_id`, `redirect_uri`, `response_type=code`, `scope` (`openid email` plus every `GoogleScope`),
    `code_challenge`, `code_challenge_method=S256`, `state`, `access_type=offline`, `prompt=consent`, and
    `login_hint` when an email is known.
  - `exchange(code, verifier, redirect)`: POST `https://oauth2.googleapis.com/token`, form `grant_type=
    authorization_code`, `code`, `code_verifier`, `redirect_uri`, `client_id`, and `client_secret` if configured.
    Returns access token, `expires_in`, refresh token, granted `scope`, and the **email from the `id_token`
    payload**. The id_token came straight from Google's token endpoint over TLS, so reading its payload without
    checking the signature is acceptable (OpenID Connect Core §3.1.3.7). Say so in a comment.
  - `refresh(refreshToken)`: same endpoint, `grant_type=refresh_token`. An HTTP 400 with `invalid_grant` means
    expired or revoked: sign in again.
  - `revoke(token)`: POST `https://oauth2.googleapis.com/revoke`, form `token`.
  - `parseRedirect(url, expectedState)`: takes the redirect address. It may be pasted, so trim whitespace and
    accept it with or without `http://`. Returns `Code(code)`, `Denied` (`error=access_denied`),
    `Error(message)` for any other `error=`, or `WrongState`, which is never accepted.
  - Base URLs are constructor parameters, as in `GmailClient`, so tests can point them at MockWebServer.
- **`google/browser/LoopbackReceiver.kt`**: a one-shot HTTP listener on **`127.0.0.1`** only (never `0.0.0.0`),
  port 0 (the OS picks one).
  - `redirectUri` is `http://127.0.0.1:<port>`.
  - `await(timeout)` accepts connections on the IO dispatcher. It ignores requests without `code` or `error` (such
    as `/favicon.ico`), answering them 404, and returns the first request line with `code=`/`error=`.
  - It answers that request with a small HTML page, "Signed in. Go back to Edith.", with a **Back to Edith** link
    (§4).
  - It closes the socket on success, on timeout (5 minutes) and on cancel.
  - Read at most 8 KB per request and don't log query strings: the code is a credential.
- **`google/browser/BrowserGoogle.kt`** implements **both** `GoogleSignIn` and `GoogleAuthorizer`:
  - `signIn(activity, …)`: makes the PKCE pair and `state`, starts the `LoopbackReceiver`, and opens `authUrl` with
    `Intent.ACTION_VIEW` (plain intent; no WebView, no Custom Tabs dependency needed). It then waits for
    **whichever comes first**: the loopback code, a pasted address (§3), cancel, or the timeout. Then it
    exchanges the code, stores the refresh token (§2), keeps the access token in memory with its expiry, and
    returns `SignedIn(email, null)`.
  - `authorize(scopes)`:
    - Uses the cached access token if it's valid for 60 s more **and** its granted scopes include `scopes`.
    - Otherwise refreshes.
    - No refresh token, or `invalid_grant`: `NeedsConsent(intent = null)`.
    - A missing scope (the owner unticked it on Google's granular consent screen): `NeedsConsent(null)`.
    - No network: `Failed`.
  - `fromConsent(data)`: not used by this path. Return `Failed("…")`; it's never called because there's no
    consent intent.
  - `clearToken(token)`: drops the cached access token.
  - `revoke(email)`: revokes the refresh token at Google (failure only logged), then deletes it.
  - `signOut()`: deletes the stored token and the cache.
- **`google/ChoosingGoogle.kt`** implements both interfaces and delegates to Play or Browser:
  - **Browser** when a browser refresh token is stored, or when the last sign-in was through the browser.
  - **Browser** when `GoogleApiAvailability.isGooglePlayServicesAvailable(context) != SUCCESS`.
  - **Play** otherwise.
  - `signInWithBrowser(activity)` forces the browser (the **Use a browser instead** button).
  Keep the choice rule in one small testable function; pass Play-services availability in as a lambda.
- **`GoogleAuth`** changes:
  - It's `NotSetUp` only when **both** client IDs are blank. With only the Desktop ID, browser sign-in is the only
    path.
  - Add `signInWithBrowser(activity)`.
  - **Grant access** in the browser mode (`consentIntent == null` and the chooser is on Browser) runs the browser
    sign-in again.
  - Update the class comment, which today says "tokens … are never stored by the app": in browser mode a refresh
    token is stored, encrypted (D16).
- **`AppContainer`**: build `ChoosingGoogle(CredentialManagerSignIn, PlayServicesAuthorizer, BrowserGoogle(…))` and
  pass it to `GoogleAuth` as both interfaces. `BrowserGoogle` gets `okHttpLazy`, `secretStore`, `dispatchers`,
  `clock`, and `BuildConfig.GOOGLE_DESKTOP_CLIENT_ID` / `_SECRET`.
- **`app/build.gradle.kts`**: read `edith.googleDesktopClientId` and `edith.googleDesktopClientSecret` from
  `local.properties`, as `googleWebClientId` is read, into `BuildConfig`. Empty means browser sign-in isn't set up.
  CI has neither; tests must not need them.

### 2. What's stored
- `SecretStore` key `google_refresh_token`: the refresh token. AES/GCM with the Keystore key; it never leaves
  the device and is never logged (only its length, if anything).
- DataStore `google_account_email`, as in C17, plus `google_sign_in_mode` (`play` / `browser`) so the chooser
  survives restarts.
- The access token stays **in memory only**.
- Disconnect revokes at Google, deletes the refresh token, clears the email and mode, and drops the cache.

### 3. Settings → Google account (UI)
- **Sign in with Google** behaves as today. When the chooser picks Browser, it opens the browser.
- Under it, a text button **Use a browser instead**, shown while signed out or in Error. It's useful whenever Play
  services sign-in fails.
- **While waiting for the browser**, show a dialog:
  - Title **Finish signing in in your browser**.
  - Text: "Sign in and allow access. You'll come back here automatically. If the browser ends on a page that
    won't load, copy its address (it starts with http://127.0.0.1) and paste it here."
  - A text field **Paste the address**, the buttons **Use this address** and **Cancel**, and a spinner.
  - When the loopback gets the code, the dialog closes by itself.
  - A wrong state, `access_denied`, or a timeout shows a clear message and leaves the owner Signed out.
- **Grant access** (NeedsConsent) in browser mode opens the same flow. Its status line says "Google needs you to
  sign in again (about once a week while the Google project is in Testing)".
- The `GoogleSettingsViewModel` gets the waiting state as a `StateFlow` from `BrowserGoogle`/`GoogleAuth`, plus
  `paste(address)` and `cancel()`. Keep Compose out of `google/` (01-architecture §2).

### 4. Coming back to Edith
- The loopback page's **Back to Edith** link is `${applicationId}://signed-in`, for example
  `com.edith.app://signed-in`. Add a VIEW + DEFAULT + BROWSABLE intent filter for that scheme to `MainActivity`
  in the manifest, using the `${applicationId}` placeholder so debug and release don't collide.
- This link only brings the app forward. It is **not** an OAuth redirect and must carry no code.
- Don't try to start the activity from the background yourself: Android blocks background activity starts.

### 5. Errors and honesty (00-product §4.5)
Each failure shows its own clear message in Settings:
- no browser on the device (`ActivityNotFoundException`)
- the owner declined
- a wrong or old pasted address
- timeout
- no network
- Google rejected the client (`invalid_client`: "check the Desktop client ID in local.properties", see
  `docs/google-setup.md`)

Tools keep returning C17's "tap Grant access in Settings" message when a token can't be had.

### 6. Logging and privacy
- Never log the authorization code, `state`, verifier, access token, refresh token or id_token, or any full
  redirect URL.
- `Log.w` lines carry only the step and the HTTP status or OAuth `error` code.
- Add a unit test that runs a full sign-in and checks `LogRingBuffer` for none of those values, as `WebSearchTest`
  does for the Ollama key.

## Tests
JVM (MockWebServer; responses shaped like Google's docs, values made up):
- PKCE: the RFC 7636 Appendix B test vector gives the expected S256 challenge. The verifier is 43–128 characters
  from the allowed set.
- `authUrl`: every parameter. `scope` contains `openid`, `email` and all `GoogleScope` URIs.
- `exchange`: the form fields (with and without the client secret). The refresh token goes to a fake
  `SecretStore`; the email comes from the id_token; the access token is cached with its expiry.
- `authorize`:
  - the cached token is reused
  - an expired one triggers a refresh
  - a refresh response with fewer scopes gives NeedsConsent
  - `invalid_grant` gives NeedsConsent and deletes the token
  - no network gives Failed
- `parseRedirect`: a pasted address with spaces or without `http://`, `error=access_denied`, a wrong state, no
  code.
- `LoopbackReceiver`: a real socket on 127.0.0.1. A `/favicon.ico` request is ignored, then a request with the
  code is returned. The 200 page contains the back link. Timeout and cancel close the port (a reconnect fails).
- `ChoosingGoogle`:
  - Play unavailable gives Browser
  - a stored browser token gives Browser
  - otherwise Play
  - `signInWithBrowser` forces Browser
- `GoogleAuth` with the browser fakes: Grant access re-runs sign-in, disconnect revokes and deletes, and
  `NotSetUp` only when both IDs are blank.
- Logs: no secret value appears (§6).

Device (Compose, `scripts/check.sh --device`):
- `GoogleSettingsTest`: the **Use a browser instead** button, the waiting dialog, a pasted address, and Cancel.

## Acceptance criteria
- [ ] PC emulator (`Pixel_9_API_37`, debug build): **Use a browser instead** → Chrome → sign in → automatically
      back in Settings, showing "Connected as …". "What's on my calendar tomorrow?" and "Any unread email from
      today?" work.
- [ ] Same, but pasting the address instead (cancel Chrome's return and paste the 127.0.0.1 address).
- [ ] Release build in **Andarium on the owner's iPhone**: Sign in with Google → Chrome inside Andarium → Connected;
      the calendar and email questions work; Disconnect revokes (check <https://myaccount.google.com/permissions>).
- [ ] After a restart of the app it's still Connected, with no browser.
- [ ] A Play-services phone or emulator still signs in exactly as before. C17's tests pass unchanged.
- [ ] `scripts/check.sh --device` passes. The release APK is tested on `Edith_Release_Test` first (05-environment
      §6), and R8 rules are added if the release build needs them (lesson from 2026-11-11: ONNX Runtime).

## Implementation notes (traps found while planning)
1. **Edith may be killed while the owner is in Chrome.** On a low-memory device (Andarium runs inside an iPhone
   app), Android can stop Edith's process while the browser is in front. The loopback listener dies with it, and
   so do the PKCE verifier and `state` if they're only in memory, so even the paste fallback would fail. Keep the
   pending `verifier`, `state`, `redirectUri` and start time in `SecretStore` (key `google_pending_sign_in`,
   JSON) until the exchange finishes. Delete it on success, cancel, or after **10 minutes**. On start, if a pending
   sign-in exists, `GoogleAuth`/Settings shows the waiting dialog again so a pasted address still works. The
   `redirect_uri` sent in the exchange must be the one used in `authUrl`, even though the port is gone.
2. **One refresh at a time.** Calendar and Gmail tools can ask for a token in the same turn, and `start()`
   checks at launch too. Guard the refresh-or-cached path in `BrowserGoogle.authorize` with a `Mutex`, so two
   callers get the same new token instead of two refreshes. Every refresh can count against Google's 100
   refresh-token limit.
3. **No network at startup isn't "signed out".** In browser mode `start()` refreshes quietly. An `IOException`
   must give `GoogleState.Error(email, "Couldn't reach Google…")` with **Try again**, keeping the refresh token.
   Only `invalid_grant` means sign in again.
4. **Code that has to change with it:**
   - `GoogleAuthTest`, `CalendarTest`, `GmailTest` and `ErrorMapperTest` construct `GoogleAuth` directly. Give
     the new Desktop-client parameter a default (`""`), so they keep compiling, then add the browser cases to
     `GoogleAuthTest`.
   - The `NotSetUp` text in `GoogleSettings.kt` ("the Web client ID is missing") must mention both clients, and
     `GoogleSettingsTest` must follow.
   - `GoogleAuth`'s class comment and `docs/google-setup.md`'s intro both say the app stores no tokens. Change
     them for D16.
   - `DebugGoogleReceiver` needs **no** change: it calls `GoogleAuth.authorized`, so it checks a real token in
     either mode. Use it to verify browser mode on the emulator.
5. **The way back to Edith.** Chrome may ask "Open in Edith?" before following
   `${applicationId}://signed-in`, or open nothing if the owner ignores it. The page must say "Then switch back to
   Edith", and nothing may depend on the link being tapped: the code arrived on loopback before the page was
   sent.
6. **Things that are *not* problems** (don't spend time on them):
   - Chrome loading `http://127.0.0.1:port` isn't affected by Edith's cleartext or network-security settings;
     those only govern Edith's own requests, which all go to `https://` Google endpoints.
   - Listening on a local port needs only `INTERNET`, which Edith already has.
   - No new library is needed (plain `ServerSocket`, OkHttp, kotlinx.serialization), so no new R8 rules are
     expected. Still test the release APK on `Edith_Release_Test`.
7. **How to test browser mode on the PC.** `Pixel_9_API_37` has Play services, so use **Use a browser instead**
   there. To test the "no Play services" choice itself, the chooser takes availability as a lambda (§1). A unit
   test covers it; there's no need for an emulator without Play.

## Not in scope
- Publishing the Google project to Production (Gmail's restricted scopes would need Google's verification).
  Weekly sign-ins are accepted in Testing.
- Any iOS-native Edith.
- Hands-free limits under iOS: Andarium can't listen in the background. That's a separate topic.

## As built (2026-11-11, in progress)

Where the code differs from the specification above, the code is right.

**Files**
- `google/browser/BrowserOAuth.kt`: PKCE, the sign-in address, exchange, refresh, revoke and `parseRedirect`.
  The base URLs are constructor parameters.
- `google/browser/LoopbackReceiver.kt`: listens on 127.0.0.1 only, port 0. It serves each connection on its own,
  so a spare connection Chrome opens can't hold up the real request. Request lines are never logged.
- `google/browser/BrowserGoogle.kt`: `GoogleSignIn` + `GoogleAuthorizer`, the waiting state, paste and cancel, and
  the pending sign-in in `SecretStore` (`google_pending_sign_in`).
- `google/ChoosingGoogle.kt`: the chooser. The rule is `ChoosingGoogle.chooseBrowser`.
- `GoogleAuth.kt`: a new `GoogleBrowserFlow` interface (the browser's extras), and `NeedsConsent.viaBrowser`.
- DataStore `google_sign_in_mode` (`Settings.googleSignInMode`). `BuildConfig.GOOGLE_DESKTOP_CLIENT_ID` and
  `_SECRET` come from `local.properties`.

**Differences from the spec**
- **Resuming a pending sign-in** is `GoogleAuth.resumeBrowserSignIn()`, which `AppContainer` calls at launch next
  to `start()`. It's not inside `start()`, because Grant access calls `start()` again.
- **Grant access / Try again** moved from the ViewModel into `GoogleAuth.grant(activity, show)`. Browser mode
  rechecks quietly first: after "Couldn't reach Google", Try again just refreshes. If Google still wants the owner,
  it signs in through the browser again, in one tap.
- **Sign-in runs in the app's scope** (main thread), not the ViewModel's. A browser sign-in waits minutes and must
  survive leaving Settings.
- **The browser's input channel exists before the browser opens**, so an early paste or Cancel can't be lost.
- **Use a browser instead** shows only when the build has the Desktop client ID.
- **GoogleAuth's browser cases** are tested in `ChoosingGoogleTest` (GoogleAuth + ChoosingGoogle + a real
  `BrowserGoogle` against MockWebServer), not `GoogleAuthTest`, whose fakes are Play-only. `GoogleAuthTest`,
  `CalendarTest`, `GmailTest` and `ErrorMapperTest` are unchanged and pass.
- A token response that leaves out `scope` is taken as granting everything asked for. A missing scope then shows
  up as Google's 403 (`MissingScope` → NeedsConsent).

**Verified so far**
- `scripts/check.sh`: OK (`build-logs/2026-11-11_18-02-00_check.log`): 244 unit tests, 31 of them new
  (`BrowserOAuthTest` 8, `LoopbackReceiverTest` 5, `BrowserGoogleTest` 11, `ChoosingGoogleTest` 7). Lint: no new
  warnings; the one it found, `UseKtx`, is changed.
  - Covered: RFC 7636's test vector; every sign-in parameter; the exchange form with and without the secret; a full
    loopback sign-in, with no code, state, verifier, token, id_token or redirect in the log; paste; wrong state;
    declined; cancel; timeout; no browser; `invalid_client`; resuming after a killed process (same redirect URI);
    dropping a pending sign-in after 10 minutes; the cached token; refresh when expired; one refresh for 5
    callers; fewer scopes; `invalid_grant` deleting the token; no network keeping it; revoke; the choice rule; Play
    unchanged; disconnect; Grant access signing in again; NotSetUp only when both IDs are missing.

- `scripts/check.sh --device`: OK (`build-logs/2026-11-11_18-03-20_check.log`): 110 device tests, including the new
  `GoogleSettingsTest.browserSignIn` (Use a browser instead, the waiting dialog, a pasted address, Cancel, the
  weekly sign-in text). The crash log has nothing from Edith.

**Not verified yet**
- Everything live: it needs the Desktop client (`docs/google-setup.md` §6). Also open: whether Google's token
  endpoint accepts the Desktop client without its secret, and whether Chrome's **Back to Edith** brings back the
  same `MainActivity` or starts a second one.
- The release APK on `Edith_Release_Test`, and Andarium on the owner's iPhone.
