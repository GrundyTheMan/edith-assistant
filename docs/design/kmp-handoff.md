# Handoff: moving Edith to one shared Kotlin codebase (written 2026-11-23)

You are picking up Phase 1 of [`kmp-transition.md`](kmp-transition.md). That plan is the authority on *what* and *why*; this
page says *where it stands*, *how to work here without tripping*, and *what to do next, in order*. Read it before touching
anything, then read the plan's §3.3 (spike), §4 (target architecture), §6 (migration order) and §7 Phase 1.

## 1. Where things stand

The owner said **go** at gate G0 on 2026-11-23 (D40) and froze the Expo iPhone app (D46: bug and security fixes only).
Phase 0 proved Kotlin + Compose Multiplatform runs on a real iPhone (TestFlight build 5, chat with the owner's Ollama over
Tailscale). Phase 1 makes the Android app ready for sharing without changing what it does.

| Work package | State | PRs |
|---|---|---|
| K1.1 the `shared/` module (android, jvm, iosArm64, iosSimulatorArm64), an import guard for `commonMain` | **done** | #11 |
| K1.2 iPhone-simulator tests of `shared/` in CI (`shared-ios.yml`) | **done** | #12 |
| K1.3 `java.time` replaced by kotlinx-datetime everywhere in `app/src/main` | **done** | #13, #14 |
| K1.4 OkHttp replaced by Ktor | **in progress**: Ollama, web search, weather, Moonraker, page reader, voice download done | #15, #16, #17 |
| K1.5 Room to Room KMP, DataStore to common | not started | |
| K1.6 move W1 packages (`core`, `ollama`, `settings`, `data`, `di`) into `shared/commonMain` | not started | |
| Gate G1 (the owner decides) | after K1.6 | |

Phase 0 leftovers that do not block Phase 1 are in the plan's §7 progress table (K0.4 second half, the K0.6b probes: sherpa-onnx
speech and ONNX Runtime on iOS, WebRTC, SwiftMail, MQTT with a login against Meross's broker).

## 2. The ground rules (read `AGENTS.md` too)

- **The public repo is the home**: `GrundyTheMan/edith-assistant`. The old private `GrundyTheMan/edith` is **archived, read-only**.
  Never push anything to it, and never bring its commits into the public repo (the public history was scrubbed of the owner's
  name, emails and addresses). Transplant changes as fresh commits instead.
- **Commit identity**: `GrundyTheMan <188627129+GrundyTheMan@users.noreply.github.com>`, set repo-locally. The owner's global git
  identity is their real email: a commit authored with it must not go public. (The tooling blocks such a push; that is what happened
  with a first K1.1 attempt, see §6.)
- **Ask before committing, pushing, merging, tagging or publishing**, as `AGENTS.md` says. During this series the owner said
  "feel free to merge in everything" for the K1.x pull requests: that was for that run, not a standing permission. Ask again.
- Branch `k1-N-short-name`, one PR per slice, merge commit (`gh pr merge N --merge`), plan and docs updated in the same PR. Check CI
  yourself; the owner does not want to watch it.
- Never run `connectedDebugAndroidTest`, `adb uninstall` or `pm clear` on the owner's emulator without asking: they wipe its data.
  The instrumented tests are kept compiling (`:app:compileDebugAndroidTestKotlin`) but have not been run in this series.
- No secrets in the repo, logs or chat. Apple identifiers in the TestFlight workflow (team `5JS9KEU39T`, key id, issuer id) are not secrets;
  the key itself is in GitHub secrets.

## 3. How to work (the traps that cost time)

**Where.** Do not work in `D:\Dev Projects\edith`: that is the archived repo's clone and holds one redundant local commit
(`k1-1-shared-module`). Make a worktree of the public clone: `git -C "D:\Dev Projects\edith-assistant" worktree add ..\edith-assistant-NAME -b k1-N-name origin/master`.
`edith-assistant-tf` is the one used so far; `edith-assistant-spike` holds the spike branch (`spike-kmp-ios`, not merged).

**Building.**
- A new worktree needs `app/libs/sherpa-onnx-*.aar` (git-ignored, 38 MB): run `scripts/fetch-sherpa.sh`, or copy the file from another worktree. Without it Gradle fails at configuration.
- `scripts/check.sh` (from Git Bash: `& "C:\Program Files\Git\bin\bash.exe" scripts/check.sh`) is clean build + unit tests + lint, about 1.5 to 3 minutes. Run it before every commit; it writes `build-logs/`.
- **Also run the unit tests with the PC's time zone set to UTC**, which is what CI uses: the owner's PC is in Detroit and hid two bugs:
  `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew --no-daemon :app:testDebugUnitTest --rerun-tasks`. Tests must not depend on the machine's zone or locale.
- Shared code: `./gradlew :shared:jvmTest :shared:testAndroidHostTest`. The iPhone target cannot build on Windows (`kotlin.native.ignoreDisabledTargets=true` skips it); only the macOS CI job runs it.

**CI.** `ci.yml` (Ubuntu, `check.sh`) runs on every PR. `shared-ios.yml` (macOS) runs when `shared/**`, `gradle/**`, the Gradle files or itself change.
GitHub's macOS runners are sometimes unavailable: a job that fails after exactly 15 minutes with "The job was not acquired by Runner … capacity" is not a code failure:
`gh run rerun <run-id> -R GrundyTheMan/edith-assistant --failed`. Wait for CI with a background polling loop, never chained `sleep`s.

**Editing on this Windows setup.**
- Many source files have CRLF line endings; a Node script that matches `\n` text will silently miss. Normalise (`replace(/\r\n/g,'\n')`, edit, convert back) and make the script throw when a search string is missing, so nothing half-applies.
- Long inline scripts with quotes break the Bash tool. Write the script to a file with the Write tool, then run it. There is no Python.
- Git warnings "LF will be replaced by CRLF" are harmless.

## 4. The patterns this series established (follow them)

**Time (K1.3).** Use `kotlin.time.Instant` and `kotlin.time.Duration`, kotlinx-datetime `TimeZone`, `LocalDate`, `LocalDateTime`. `Clock` and `SystemClock` live in
`shared/commonMain` (`com.edith.app.core.time`). A moment shown to the owner is an `Instant` plus the zone it is shown in, **passed in** (`CalendarFormat.whenText(start, end, allDay, zone)`),
never carried inside the value. Display formatting goes through `formatPattern(instant, zone, "EEE h:mm a", "en-US")` (java.time on Android and desktop, `NSDateFormatter` on iPhone; proven equal on a simulator).
`JavaTimeGuardTest` fails if `java.time` appears anywhere in `app/src/main`. Tests may still build fixtures with java.time: `FakeClock` accepts both kinds.
Quirks: `LocalDateTime.toString()` drops zero seconds (use `LocalDateTime.Formats.ISO`); `Instant.parse` wants an offset with a colon; `TimeZone.of` throws `IllegalTimeZoneException`;
`instant.plus(1, DateTimeUnit.DAY, zone)` is calendar arithmetic, `+ 24.hours` is not. The calendar tools take their zone from `calendarTools(dir, handles, zone)`, which the app wires to the clock.

**HTTP (K1.4).** `HttpClients` (`core/net/`) keeps the OkHttp `plain` and `secure` clients (C37 policy: `secure` is HTTPS-only and never follows https to http) and offers Ktor views over the
**same** OkHttp clients: `ktorPlain`, `ktorSecure`. So the policy, the pool, certificate pinning and the traffic-tag socket factory are unchanged; only call sites change.
- `HttpClients.over(okHttp, followHttpsToHttp = false)` builds a Ktor client over a given OkHttp client (tests use `HttpClients.over(OkHttpClient())`); it installs `HttpTimeout` with no limits so a call can say `timeout { requestTimeoutMillis = … }`; `HttpClients.withTimeouts(connect, read, call)` gives a client fixed limits.
- Ktor follows redirects itself (its OkHttp engine switches OkHttp's off): `secure` refuses https to http, `plain` allows it, as OkHttp did. Both are tested.
- The wiring test `HttpClientsWiringTest` **text-scans `AppContainer.kt`**: exactly three lines use the plain client (Ollama, page reader, Moonraker), and the credential-carrying services must use the secure one. Both spellings (`secure` / `ktorSecure`) are accepted until the rename in step 6 of §5.
- Differences found: Ktor writes a space in a query as `+` (OkHttp `%20`; the same to a server); all Ktor network and timeout exceptions are `IOException`s but a bad URL is `URLParserException` (an `IllegalStateException`), so catch both; read bodies with `bodyAsText()` / `bodyAsChannel()`, and use `prepareGet(...).execute { }` for anything streamed, so the response is closed when the block ends.
- Cancelling the coroutine cancels the request (Ollama then stops generating). Run a request as a child coroutine when something else must be able to cut it (see `VoiceDownloader.fetch`).
- The page reader keeps one OkHttp-only hook: the DNS check that refuses private addresses (`PublicOnlyDns`). The iPhone will need its own check (K4.5).

## 5. Next steps, in order

**K1.4 remainder: move the other 20 files that still import `okhttp3`/`okio`** (the page reader keeps its one DNS hook; `HttpClients` and `AppContainer` keep building the OkHttp clients until step 6) (list: `grep -rlE "okhttp3|okio" app/src/main`). One package per PR; each PR passes `check.sh` and the UTC run.
For each: the service takes `Lazy<HttpClient>` from `httpClients.ktorSecure` in `AppContainer`; tests build `HttpClients.over(OkHttpClient())` (MockWebServer keeps working); a test that asserted an exact URL spelling should accept `+` for a space.

1. **`google/`** (`CalendarClient`, `GmailClient`, `TasksClient`, `BrowserOAuth`, `GoogleAccounts`; `BrowserSignIn` and `LoopbackReceiver` only use `toHttpUrlOrNull`, replace with `io.ktor.http.Url`). Shared helper `GoogleCall`. Done when no `okhttp3` import remains in `google/`.
2. **`microsoft/`** (`GraphClient`, `MicrosoftAccounts`; OAuth goes through `BrowserOAuth`).
3. **`icloud/`**: `CalDavClient` uses WebDAV methods (`PROPFIND`, `REPORT`, `PUT`, `DELETE`): in Ktor `HttpMethod("PROPFIND")`; keep its trusted-host redirect rule. `ICloudCalendarSource` and `ICloudAccounts` need only small changes. The mail side (Jakarta Mail over IMAP/SMTP) is not OkHttp and is a separate problem (plan K0.6, K4.7).
4. **`meross/`, `ring/`, `blink/`, `alexa/`** (`MerossApi`, `RingApi`, `BlinkApi`, `AlexaApi`). Meross has its own redirect and `trusted` checks: keep them and their tests (they were added because two services once forgot the HTTPS rule).
5. **`jarvis/` last, with the printer feed and upload** (`JarvisApi`, `JarvisAccounts`, `PrinterFeedApi`, `PrinterUpload`). `JarvisApi.pinned(fingerprint)` builds an OkHttp client whose `X509TrustManager` accepts only the pinned self-signed certificate: keep that, and put Ktor over it with `HttpClients.over(pinned)`. `exchange`, `getJson`, `postJson`, `getBytes`, `postBytes` take OkHttp `RequestBody`s today; `PrinterUpload.body` streams an `InputStream` with progress and a fixed length (64 MB cap): rebuild it as Ktor `OutgoingContent` (a `WriteChannelContent` with `contentLength`). Risk to check: how a pin mismatch surfaces through the Ktor engine (`unreachable(e, pinMismatch)` reads exception types), and the long-poll read timeouts (`readTimeoutS`).
6. **Finish K1.4.** Move every OkHttp-specific piece (building the clients, `TaggedSocketFactory`, `pinned`, `PublicOnlyDns`) into Android-only files; no `okhttp3` import outside them. Rename `ktorPlain` to `plain` and `ktorSecure` to `secure`; update `HttpClientsWiringTest` and the doc comments. Add a test (like the `commonMain` import guard) that fails on `okhttp3` in packages meant for `shared/`. Record in the plan's §3.3 and §7 progress table.

**K1.5 Storage.** Room to Room KMP with the bundled SQLite driver (the spike proved it on iOS; `data` has 7 files, `home` 1); DataStore to common. Same schema and version; `app/schemas/…/1.json` to `3.json` are the proof the existing database opens unchanged (plan §14 U4): add a test that opens a copy of the real schema, and keep the migration tests green. K0.4's second half (survives an app restart on the phone; opens a copy of the owner's real `edith.db`) lands here.

**K1.6 Move W1 into `shared/commonMain`.** `core`, `ollama`, `settings`, `data`, `di` with `AppContainer(platform: PlatformServices)`; Android seams for `SecretStore`, `Log`, files. `git mv` with the package names unchanged, so history is kept. The iPhone-simulator tests of W1 must pass (extend `shared-ios.yml`'s task list). Then tell the owner gate **G1** is theirs: Android used a few days on the new build with no regression. Do not start Phase 2 before they say go.

After G1: plan §7 Phase 2 (the engine: `chat`, `tools`, `attachments`), then Phase 3 shared screens, Phase 4 the iPhone app proper (the Expo app stays frozen until parity), Phase 5 parity and retirement. Each has its own gate; the plan lists the work packages.

## 6. Things waiting on the owner (do not do these for them)

- **Run the new Android build on the phone against their Ollama**: chat (the Ollama client now runs on Ktor), the Printer page, the voice download. The tests pass, nothing here has been run on a device.
- Delete the App Store Connect API key `edith-spike-ci` once the spike is no longer needed (the TestFlight workflow `spike-kmp-testflight.yml` uses it).
- Decisions still open in the plan's §12: **D47** (licence for the public repo), **D49** (how the iPhone app reaches Ollama over plain HTTP: the spike used a blanket App Transport Security exemption; decide before K4.5), and the bundle id check for `com.edith.app` (D43).
- Confirm on GitHub's billing page that macOS minutes are free for the public repo (K0.8, plan §14 U9).
- Tidy-up they may want: the old private clone `D:\Dev Projects\edith` (one redundant local commit), the extra worktrees, the paused Expo prep (`c58-dev-build-prep`, uncommitted in the c67 worktree).

## 7. History worth knowing

- A first K1.1 attempt was committed in the old clone with the owner's real email and then carried toward the public repo; the tooling refused the push. It was redone as a fresh commit under the no-reply identity (PR #11). Do not repeat it.
- The first CI run of K1.3 found two tests that only fail in UTC; the first CI run of K1.4's voice slice found a downloader test that passed or failed by luck (it cut the connection before any data arrived). Both were test faults, fixed in their PRs: when a test fails in CI and not locally, suspect the machine's zone, locale, timing or file-system case first.
- TestFlight (Phase 0): the spike runs from TestFlight as `com.edith.app.spike`; the findings (App Transport Security with Tailscale, thinking models, dark mode, Xcode 26) are in the plan's "Device findings".
