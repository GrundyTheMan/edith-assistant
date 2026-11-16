# C37 — HTTPS-only by construction: separate HTTP clients

| | |
|---|---|
| Phase | Hardening (cross-cutting) |
| Depends on | C03 (the shared client), and the services that use it: C17–C19, C22–C24, C26, C35, C36 |
| Unblocks | Every later network service (C27–C34): the safe choice becomes the default |
| Size | Small to medium: one new class, one wiring change, tests, docs |
| Read first | 01-architecture §9; C24 and C26 As built (the two bugs this removes); 04-lessons L30–L35 for how the release check works |

## Why

On 2026-11-14 a review of C17b–C36 found the **same bug twice**:
- **Meross (C26):** sign-in followed a region redirect to whatever address the answer named, `http://` included.
- **iCloud (C24):** the app-specific password was attached to any `icloud.com` host, over `http://` too.

Both happened because **cleartext HTTP is allowed app-wide** (`network_security_config.xml`) and **every service shares
one OkHttp client** (`AppContainer.okHttpLazy`). Cleartext is there for exactly one reason: Ollama lives at a LAN IP
the owner types, and `domain-config` can't match IP ranges. 01-architecture §9 says "all other traffic is HTTPS", but
nothing enforced it. Each service had to remember, and two forgot. Each was fixed on its own (Meross: `isTrustedDomain`,
PR #59; iCloud: `carriesPassword`). That doesn't stop the next service, such as the ones C27–C34 add.

## Goal

Make HTTPS the **property of the client**, not something each service has to check. A service that carries a token, key
or password gets a client that **cannot** make a cleartext request, whatever a server tells it. The two places that
really need cleartext are named, and are the only ones that get the other client.

## Specification

### 1. `core/net/HttpClients.kt`
One class, built once by `AppContainer` from the existing base client (timeouts and `TaggedSocketFactory` as in C03 §5):

```kotlin
/** The app's two HTTP clients (C37). Both share one connection pool, dispatcher and settings. */
class HttpClients(base: () -> OkHttpClient) {
    /** HTTPS only. Anything that carries a token, key, password or personal data, or talks to a named online service. */
    val secure: Lazy<OkHttpClient>
    /** Plain HTTP allowed. Only for an address the owner typed (Ollama on the LAN) or a public web page. Carries no credentials. */
    val plain: Lazy<OkHttpClient>
}
```
- `plain` is the base client as it is today.
- `secure` = `base.newBuilder()` with:
  - `connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))`: OkHttp then refuses any `http://` request with
    `UnknownServiceException` ("CLEARTEXT communication not enabled for client"). That is an `IOException`, so every
    service's existing "couldn't reach it" handling shows its normal message.
  - `followSslRedirects(false)`: an `https` → `http` redirect isn't followed. It comes back as an ordinary 3xx answer,
    which the services already report as an HTTP error.
- `newBuilder()` shares the connection pool and dispatcher, so there's no second pool and no extra TLS cost beyond
  the first connection. Keep both lazy and first-used on an IO thread (C03 §5: building TLS takes ~190 ms).

### 2. Wiring (`AppContainer`)
- Remove the single `okHttpLazy` and the public `okHttp` val. Add `val http: HttpClients`. A service can no longer be
  handed "the" client without choosing, and the compiler shows every site.
- **`plain`, and only these two:** `OkHttpOllamaClient` (the owner's address, http or https), and `PageReader` (public
  pages, no credentials; it already follows redirects by hand and refuses private addresses, C10).
- **`secure`, everything else:**
  - Google: `BrowserOAuth`, `CalendarClient`, `GmailClient`, `TasksClient`, the account sources
  - Microsoft: `BrowserOAuth`, `GraphClient`
  - iCloud: `ICloudAccounts` and `CalDavClient`
  - Meross: `MerossApi`
  - `OllamaWebSearch` (it sends the owner's Ollama API key to `ollama.com`)
  - `DuckDuckGoSearch`, `OpenMeteoClient`
  - `VoiceDownloader` (C36)
  - the two debug receivers (`DebugGoogleReceiver`, `DebugMicrosoftReceiver`), which read `container.okHttp` today
- Constructors keep taking `Lazy<OkHttpClient>`: no new wrapper type, so the ~hundreds of tests that pass
  `lazy { OkHttpClient() }` to a stand-in MockWebServer don't change.
- **A new service takes `http.secure`** unless it must speak plain HTTP to a LAN device (C34's printer would), and then
  its PR says why. Put this sentence in 01-architecture §9 and in AGENTS.md.

### 3. What stays
- `network_security_config.xml` is unchanged (`cleartextTrafficPermitted="true"`): the `plain` client still needs it.
  Update its comment: the secure client refuses cleartext by itself.
- The per-service checks stay as defence in depth: Meross's `isTrustedDomain` and iCloud's `carriesPassword` now guard
  against a credential going to the wrong *host*, not only the wrong scheme.
- The loopback redirect (`127.0.0.1`, C17b) isn't an HTTP *client* request: it's a listener, so it's unaffected.
- MQTT (Meross) isn't OkHttp. It already uses `ssl://`, and Paho 1.2.5 verifies the server's hostname by default (§4).

### 4. Tests
JVM (`HttpClientsTest`, MockWebServer; add **`okhttp-tls`** `5.5.1` as a `testImplementation` for a TLS test server. It's
OkHttp's own artifact at the version already pinned, `libs.versions.toml`):
- `secure` refuses a plain-`http` server: `execute()` throws an `IOException` (it's thrown at connect time, not at
  `newCall`, so the test must execute). The server sees **no request**.
- `plain` talks to the same server fine.
- `secure` talks to a TLS server (a self-signed `HandshakeCertificates` the test trusts).
- **An `https` → `http` redirect isn't followed:** the TLS server answers 302 to the plain server, and the plain server
  sees no request. (`plain` follows the same redirect: the control.)
- `secure` and `plain` share one connection pool and one dispatcher, and keep the base client's timeouts and socket factory.
- **The guard** (`HttpClientsWiringTest`): reads `AppContainer.kt` as text, like `GmailTest`'s "only `GmailTools` calls
  `client.send(`". It asserts that `okHttpLazy` and `container.okHttp` no longer exist, and that `http.plain` appears
  **exactly twice**: on the `OkHttpOllamaClient(` line and the `PageReader(` line. Any other use fails the test with a
  message that says to use `http.secure` or justify it in the PR.
- **MQTT pin:** extract the connect options into `internal fun connectOptions(creds)` in `MerossBus.kt` and assert
  `isHttpsHostnameVerificationEnabled` is `true`, with the username and password set and the version 3.1.1. If Paho's
  default ever changes, this fails.
- Every existing test still passes unchanged (they build their own clients).

Device and release (05-environment §6, L30):
- Install the **release** APK on `Edith_Release_Test`. Chat with Ollama (`plain`), weather (`secure`), read an
  `http://` web page (`plain`: `http://example.com`), Sign in with Google reaches the browser, and the crash log stays empty.
- On the owner's iPhone in Andarium (their step): an Outlook/iCloud/Google check still works, since only the client has changed.

## Implementation notes (traps)
1. `UnknownServiceException` comes from the **connection**, not the call setup: assert on `execute()`, not on `newCall`.
2. Don't set `followSslRedirects(false)` on `plain`: a LAN Ollama behind a proxy may redirect `http` → `https`, and
   `PageReader` follows redirects by hand.
3. `ConnectionSpec.MODERN_TLS` (TLS 1.2 and 1.3) is fine for Google, Microsoft, Apple, Meross and `ollama.com`. Don't
   add `CLEARTEXT` back "for tests": tests pass their own plain clients.
4. The existing handlers for `IOException` in each service map it to a message such as "Couldn't reach Google…". Check
   that none catches only `SocketTimeoutException` or `UnknownHostException` and lets `UnknownServiceException` crash
   (crashes are never acceptable, 00-product §4.6). If one does, widen it and add a test.
5. The debug receivers are in `src/debug`. They compile only in debug, so run a debug build too, not only the unit tests.
6. `okHttp` was a public val on `AppContainer`. Search the whole repo, `src/debug` and `androidTest` included, before
   deleting it.

## Not in scope
- Certificate pinning (Google, Microsoft and Apple rotate; not worth the breakage).
- Restricting cleartext at the platform level: it can't be done for a LAN IP (see "Why").
- Making Ollama use HTTPS, or the page reader refuse `http` pages.

## Acceptance criteria
- [ ] `scripts/check.sh --device` passes, with the new tests in it. (`scripts/check.sh` passed; the device tests were
      not run: see As built.) [x] The new tests fail if `secure` is replaced by a plain client: checked by hand.
- [x] No service except Ollama and the page reader can make a cleartext request (the wiring test).
- [ ] Release APK checked on `Edith_Release_Test`: chat, weather, an `http` page, Sign in with Google, empty crash log.
- [x] 01-architecture §9 says what is enforced and where; AGENTS.md has the "new services take `httpClients.secure`" rule.

## As built (2026-11-14)

Where the code differs from the specification above, the code is right.

- **`core/net/HttpClients.kt`**: `plain` is the base client; `secure` is `plain.newBuilder()` with
  `connectionSpecs(MODERN_TLS)` and `followSslRedirects(false)`. One pool and dispatcher. Both lazy.
- **`AppContainer`**: `okHttpLazy` and the public `okHttp` are gone; `val httpClients`. `plain` is used twice
  (`OkHttpOllamaClient`, `PageReader`); everything else, and the two debug receivers, takes `secure`.
- **`MerossBus.kt`**: the connect options moved to `connectOptions(creds, timeout)`, with
  `isHttpsHostnameVerificationEnabled = true` set explicitly.
- **Tests (13 new)**: `HttpClientsTest` (7, with a TLS `MockWebServer` and a throwaway self-signed certificate; the new
  `okhttp-tls` 5.5.1 test dependency), `HttpClientsWiringTest` (4, reading `AppContainer.kt` and every main source as
  text), `MerossBusOptionsTest` (2).
- **Trap 4 checked:** every service on the `secure` path catches `IOException` (`UnknownServiceException` is one). The
  only narrower handlers are the loopback listener (not OkHttp) and `PageReader` (on `plain`). No change was needed.

**Verified**
- `scripts/check.sh`: OK (`build-logs/2026-11-14_14-31-42_check.log`). **514 unit tests** (501 before, 13 new), 0
  failures, lint 0 errors and 5 warnings (the earlier baseline; the debug build compiled, so `src/debug` did too).
- **The tests can fail.** With `secure` replaced by the plain client and Meross handed `plain`, 3 of the 7
  `HttpClientsTest` and 2 of the 4 `HttpClientsWiringTest` fail. Both changes were reverted.
- **A real TLS probe** (a throwaway test, deleted, not committed): an unauthenticated `HEAD` through the real `secure`
  client to the 14 hosts the app uses: Open-Meteo (forecast and geocoding), `ollama.com`, DuckDuckGo, Google
  (Calendar, Gmail, Tasks, OAuth token and consent), Microsoft (Graph, sign-in), iCloud CalDAV, Meross, and the voice
  download on GitHub. All 14 connected over **TLS 1.3** (11 on HTTP/2, 3 on HTTP/1.1), so `MODERN_TLS` suits every
  server. The answers were 200, 401, 404 or 405 as expected for requests with no credentials.
- The debug build installed over `Pixel_9_API_37` (data kept), launched and chatted with Ollama (`plain`), and the crash
  log stayed empty.

**Not verified**
- **Weather, web search and reading an `http` page on the emulator.** Both emulators' name lookup was failing
  (`ping: unknown host`; the PC's DNS is fine, so it's the emulators' stale forwarder, probably since NordVPN's DNS came
  up), and the weather call timed out. The services' behaviour was checked above instead.
- **`scripts/check.sh --device`** and the **release APK on `Edith_Release_Test`**: not run.
- **Real sign-ins** (Google, Microsoft, iCloud, Meross) through `secure`, and Android's own TLS stack: the probe ran on a
  JVM. Android 8 to 9 (minSdk 26) negotiate TLS 1.2 (TLS 1.3 starts at Android 10); `MODERN_TLS` covers 1.2.
- **The Andarium check** on the owner's iPhone.
