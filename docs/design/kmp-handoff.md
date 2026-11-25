# Handoff: Edith as one shared Kotlin codebase (Android + iPhone) (written 2026-11-23, rewritten 2026-11-26 after K4.16)

The engine, the screens and the iPhone app are **on shared Kotlin code**, and the iPhone app is on TestFlight (built from master). Almost every iPhone feature is
**built and compile-checked but has not run on a phone**: the owner's phone is the next check. [`kmp-transition.md`](kmp-transition.md) is the authority on *what* and *why*
(each work package has a row in its progress tables, with what is done and what is not); this page says *where it stands*, *how to work here without tripping*, and *what is next*.
Read it before touching anything, then the plan's §4 (target architecture) and §6.1 (the standard move).

## 1. Where things stand (2026-11-26)

| Phase | State |
|---|---|
| Phase 0 the spike, gate G0 | **done** (2026-11-23); the Expo iPhone app is frozen (D46: bug and security fixes only) |
| Phase 1 Android ready for sharing (K1.1 to K1.6), gate G1 | **done**, passed 2026-11-23 |
| Phase 2 and 2b the engine moved to `shared/commonMain` (K2.1 to K2.12 and the service moves), gate G2 | **done**; what stays platform-specific is listed in each row (Android's Paho MQTT, WebRTC, the Blink stream socket, Play services, the foreground services) |
| Phase 3 shared screens (`ui/`): theme, chat (K3.1, K3.2), Home (K3.3), Printer page (K3.4), Calendar and To-do (K3.5), Settings (K3.6), clips, upload and 12/24-hour (K3.7) | **done**; Android keeps thin wrappers (view model factories, permissions, Google's consent screens, ExoPlayer, WebRTC) |
| Phase 4 the iPhone app on shared code (K4.1 to K4.16) | **built**: chat with tools; photos, audio files, voice memos, dictation, read-aloud and conversation mode (a Talk button, no wake word); Google, Microsoft, iCloud (mail and calendar) and the Jarvis work account; Home, Printer, Calendar and Settings tabs; Alexa, Ring, Blink, Meross and the printer; camera alerts while the app is open and While you were away. **Not tried on a phone** |
| Phase 5 parity and retirement | not started; waits on the phone checks |

**What the iPhone does not have yet:** a wake word (an engine and a decision), live camera views (Ring's WebRTC, Blink's stream: the iPhone has no player for either), the
crash banner, hands-free settings, and notifications while the app is closed (iOS allows them only with a push from a server, which is a separate design). Sign-in for Google and
Microsoft needs the owner's client IDs in `iosApp/project.yml` (`EdithGoogleClientId`, `EdithMicrosoftClientId`) and, for Microsoft, an app registration with the bundle id.

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
- The wiring test `HttpClientsWiringTest` **text-scans `AppContainer.kt`**: exactly three lines use the plain client (Ollama, page reader, Moonraker), and the credential-carrying services must use the secure one. Both spellings (`secure` / `ktorSecure`) are accepted: the OkHttp ones remain for the services above.
- Differences found: Ktor writes a space in a query as `+` (OkHttp `%20`; the same to a server); all Ktor network and timeout exceptions are `IOException`s but a bad URL is `URLParserException` (an `IllegalStateException`), so catch both; read bodies with `bodyAsText()` / `bodyAsChannel()`, and use `prepareGet(...).execute { }` for anything streamed, so the response is closed when the block ends.
- Cancelling the coroutine cancels the request (Ollama then stops generating). Run a request as a child coroutine when something else must be able to cut it (see `VoiceDownloader.fetch`).
- Services that need an OkHttp hook (cookie jar, address lookup, interceptor, certificate pin) take an `OkHttpClient` and run Ktor over it with `HttpClients.over(okHttp)`; `over` reads the client's own `followRedirects` / `followSslRedirects`, so a client built with `followRedirects(false)` gives a Ktor client that does not redirect. `HttpClients.over(okHttp, redirectAnyMethod = true)` also follows redirects for PROPFIND and REPORT (iCloud). Never close a Ktor client made over a shared OkHttp client: closing it shuts the OkHttp dispatcher down.
- `OkHttpGuardTest` lists the only files allowed to import `okhttp3`/`okio`. Add nothing to it: a new service takes the Ktor client.

## 5. Next steps, in order

**The owner's phone comes first.** Every iPhone slice since K4.3 is checked only by CI (it compiles, and the shared logic has tests on a simulator). Ask for a TestFlight check of the
areas in the plan's K4.x rows, and fix what breaks before building more: the sign-in sheets and the pinned Jarvis connection (K4.4 to K4.6), iCloud mail's TLS connection
(K4.10), Meross's MQTT (K4.14), Blink's cookie-jar sign-in (K4.13), clip playback and the Files picker (K3.7), conversation mode (K4.11).

Then, in this order, none needing a decision unless noted:
1. **An Android regression check on the emulator.** Phase 3 moved the Home, Printer, Calendar and Settings screens under Android's wrappers. The unit tests and the shared UI tests pass,
   but the Android instrumented tests are not run in CI. They need the emulator, which wipes its app data: **ask the owner first.**
2. **Live camera view on the iPhone.** Ring needs a WebRTC library (K0.6b probe), Blink needs a TLS socket to the stream server (its certificate is not checked, as on Android) and a way to
   play MPEG transport stream bytes (AVPlayer wants HLS or a file: a small local server or a segmenter). The seams are ready: `RingLive` and `BlinkLiveRunner` in `shared/`, `HomeOverlays.live` in `ui/`.
3. **Notifications while the app is closed** (C72 option B): Jarvis sends a push. Needs a design with the owner (Apple push credentials, Jarvis's side).
4. **The wake word** ("Hey Edith"): an engine decision (D41) first; `WakeListener` is the seam, `TapWakeListener` the stand-in.
5. **Phase 5**: retire the Expo app once the phone checks pass, remove the Android copies that the shared code replaced, update `AGENTS.md`'s design index.

## 6. Things waiting on the owner (do not do these for them)

- Try the TestFlight build and report what breaks (see §5).
- The Google iOS client ID and the Microsoft app registration (client ID, the iOS platform with the bundle id) so those sign-ins can be tried; the iCloud app-specific password.
- Decisions open in the plan's §12: D47 (licence), D49 (App Transport Security for plain-HTTP Ollama: the build still has a blanket exemption), the bundle id (D43), D50 to D60.
- Delete the App Store Connect API key `edith-spike-ci` once the spike is no longer needed.
- Tidy-up: the old private clone `D:\Dev Projects\edith`, the extra worktrees, the paused Expo prep.

## 7. What the iPhone phase learned (apply it)

**Process.**
- **One rolling branch per batch of related work, one PR, one CI run.** Eleven stacked PRs were a mess to land: every merge made the next one conflict (the same few files: `IosContainer.kt`,
  `iosApp/project.yml`, the plan's tables) and restarted a 25-minute macOS run each time. Stack only what truly depends on another PR, and merge a stack in one pass.
- **Merge only when all three checks (`check`, `ios-tests`, `simulator`) pass on the PR's current head**, and ask the owner first (the permission system refuses a merge the owner did not approve in that
  session). A check that shows "fail" with a cancelled job and no failing step is the runner, not the code: `gh run rerun <id> --failed`. Changing a PR's base branch restarts its CI: do not merge on the old result.
- A TestFlight build is `gh workflow run ios-testflight.yml --ref master` (about 20 to 30 minutes); the workflow builds whichever ref it is given. Apple allows a limited number of certificates: if it fails with
  "maximum number of certificates", the owner revokes some.

**Windows tooling.** Long `sed` scripts, `sed -i 'a\'` with several lines, and heredocs with apostrophes break or silently flatten code: use the Write and Edit tools for anything longer than a line or two.
A `sed` whose search found nothing (`n=$(grep ...)` empty) can append after **every** line: check the variable first. `compose.` removed with `sed` also strips `androidx.compose.` from imports.
`git reset --hard` is refused by the permission system: merge or checkout instead.

**Kotlin/Native and the iPhone.**
- Compile the iPhone code (and its tests) on Windows: `./gradlew :shared:compileKotlinIosSimulatorArm64 :ui:compileKotlinIosSimulatorArm64 :shared:compileTestKotlinIosSimulatorArm64 :ui:compileTestKotlinIosSimulatorArm64`.
  The JVM compile does not catch `System`, `Math`, `java.*`, `String.format`, `UUID`, `@Volatile` or `Dispatchers.IO` in `commonMain`: the iOS one does. Replacements: `kotlin.time.Clock`, `roundToLong()`, `formatDecimal`, `Uuid.random()`,
  `@kotlin.concurrent.Volatile`, `PlatformLock`, `Dispatchers.Default` or an injected dispatcher, kotlinx-io's `Source` for streams, `Base64` from `kotlin.io.encoding`.
- Apple API quirks met: a category's methods (and `setActive`) need an explicit import; delegate overloads with the same Kotlin signature need `@ObjCSignatureOverride`; `NSStream.getStreamsToHostWithName` is not in the iPhone SDK
  (use `CFStreamCreatePairWithSocketToHost` and the toll-free bridge); `AVURLAsset.URLAssetWithURL` instead of the constructor; `NSHTTPCookieAcceptPolicy` is an enum class; `Url("https://")` in Ktor invents a host.
- `ASWebAuthenticationSession` for sign-in (a custom-scheme redirect, no URL type needed in `Info.plist`), `SFSpeechRecognizer` and `AVAudioRecorder` and `CLLocationManager` each need a usage-description key in `iosApp/project.yml`
  or iOS ends the app the moment it asks.
- Ktor's Darwin client: redirects are Ktor's; Apple's own cookie jar must be off for a client that keeps cookies by hand (`HTTPShouldSetCookies = false`, `HTTPCookieAcceptPolicy`); HTTPS-only is a plugin.

**Patterns.** A platform class gets a **thin wrapper**, not a fork: the shared class takes an interface (`PinnedHttp`, `MailTransport`, `RingLive`, `BlinkLiveRunner`, `HomeOverlays`, `AuthSession`), Android's `AndroidXxx` factory
or subclass builds it with its OkHttp or its Activity, and the iPhone's wiring is in `IosContainer.kt` and `MainViewController.kt`. Shared Compose screens take state and actions (`HomeContent(ui, actions, overlays)`) and each
platform adds its view model factory; the tests of a shared screen run on the desktop and the simulator, which Android's instrumented tests could not do in CI.

## 8. History worth knowing

- A first K1.1 attempt was committed in the old clone with the owner's real email and then carried toward the public repo; the tooling refused the push. It was redone as a fresh commit under the no-reply identity (PR #11). Do not repeat it.
- The first CI run of K1.3 found two tests that only fail in UTC; K1.4's voice slice found a downloader test that passed or failed by luck. Both were test faults: when a test fails once in CI and never locally, suspect the test.
- TestFlight (Phase 0): the spike ran as `com.edith.app.spike`; the findings (App Transport Security with Tailscale, thinking models, dark mode, Xcode 26) are in the plan's "Device findings".
- The 2026-11-25 overnight run opened eleven stacked PRs; landing them took most of a day (see §7, Process). The rolling-branch rule came from that.
