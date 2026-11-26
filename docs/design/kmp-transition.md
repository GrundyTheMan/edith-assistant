# The shared-code transition: one Kotlin codebase for Android and iPhone (decision D40)
| K2.6a | **first slice done: the accounts model** | `Account`, `AccountStore` (DataStore), the source interfaces (`CalendarSource`, `MailSource`, `Reads`, `DefaultCalendar`) and `TaskSource`/task types moved to `shared/commonMain` under the same package names (so no caller changed). `SecureRandom` became `kotlin.random.Random` for the account id (a label, not a secret); `IOException` is kotlinx-io's. Five call sites in `app/` needed a local value in place of a smart cast across modules. `DefaultCalendarTest` and `ReadsPatientTest` now run on the desktop and the iPhone simulator. **Second slice:** `AccountEntry`, `AccountDirectory` and the account-naming rules (`AccountNames`, which `Accounts.match` now calls) moved too, with the calendar tools and `WhichAccount`. **Third slice:** task ranking (`TaskRanking`, `RankStore` on kotlinx-io with an atomic move, `TaskRanker`) and the to-do tools moved; the saved-rankings and ranker tests stay in `app/` as `TaskRankerTest` (they use a temporary folder), the pure ranking tests now run everywhere. The iPhone compile found two JVM-only habits (`@Synchronized` in the handle tables, `Integer.toHexString`); both replaced. **Fourth slice:** `MailTools` (search, read, draft, send, handle table on `PlatformLock`) moved; the provider-neutral text rules of Gmail's `MailText` (cap, quoted history, HTML to text) became `MailBody` in `accounts/sources`, used by Outlook and iCloud too, while Gmail's MIME decoding and outgoing format stay in `app/`. `MailBodyTest` runs on the desktop and the iPhone simulator; the "only the tools can send" source scan in `GmailTest` also reads the shared folder. **Left in this item:** `Accounts` itself (it holds the Google, Microsoft, iCloud and Jarvis classes, so it moves with them, and Activity and PendingIntent become a `SignInLauncher`). |
| K2.7 | **first slice done: iCloud calendar logic** | `ICalendar` (reading and writing iCalendar) and `ICloudCalendarSource` moved to `shared/commonMain`; the source now asks a new `DavClient` interface (discover, query, get, put, delete) for the server, and Android's `CalDavClient` implements it unchanged (OkHttp, the password kept to Apple's hosts, a DOM XML reader). The line-folding code counted UTF-8 octets with a JVM call; it now counts them itself and keeps a character outside the Basic Multilingual Plane (an emoji) whole, which the old code did not (a change, not a repair of anything seen). `ICalendarTest` runs on the desktop and the iPhone simulator. **Left:** the iPhone's `DavClient` (Darwin, with the same rule that the password only goes to `icloud.com` hosts, and an XML reader), `ICloudAccounts`, and iCloud mail (IMAP, K4.7; Jakarta Mail stays in `app/`). |
| K2.6c | **first slice done: Outlook mail and calendar over Graph** | `GraphClient` (retry on 429, one refresh on 401, errors worded for the owner) and `OutlookCalendarSource` / `OutlookMailSource` with their Graph types moved to `shared/commonMain`. The client asks a new `GraphAuth` interface (one call, `authorized`) for a token, and `GraphCall` moved with it; Android's `MicrosoftAccountAuth` implements it unchanged. Three JVM-only habits replaced (`java.io.IOException`, `System.currentTimeMillis`, `@Volatile`, and `Charsets`). `GraphClientTest` runs on the desktop and the iPhone simulator; `OutlookSourcesTest` (a fake server) stays in `app/`. **Left:** Microsoft sign-in (`MicrosoftAuth`, `MicrosoftAccounts`: they use the Google browser sign-in and Activity, so they move with `Accounts` and the `SignInLauncher`). |
| K2.6b | **data layer done: Google Calendar, Gmail and Tasks clients** | `CalendarClient`, `GmailClient` (with `MailText`), `TasksClient`, `GoogleTaskSource`, `TaskDue` and `GoogleSources` moved to `shared/commonMain`. They ask a new `GoogleAuth` interface (`authorized` and `tasksAuthorized`) for tokens; `GoogleScope` and `GoogleCall` moved with it, and Android's `GoogleAccountAuth` implements it unchanged. JVM-only code replaced: `java.util.Base64` by Kotlin's `Base64` (URL-safe, MIME and plain), Jsoup by Ksoup for Gmail's snippets, `URLEncoder` by ktor's `encodeURLParameter` for ids in paths, `java.io.IOException`, `System.currentTimeMillis`, `@Volatile`, `Charsets`. The MIME and outgoing-message tests moved to `MailTextTest` (desktop and iPhone simulator), plus a new one for the 76-character wrapping; the fake-server tests stay in `app/` and pass unchanged. **Left:** Google sign-in (`GoogleAccounts`, `GoogleAccountAuth`, `AndroidGoogle`, the browser sign-in): Play services, `Activity` and `PendingIntent` stay in `app/` until `Accounts` and the `SignInLauncher`. |
| K2.6a (core) | **done: the account list is in shared code** | `AccountsCore` (shared) holds everything about the list that needs no provider: the saved accounts and their live connections, each account's state, to-do access, default calendar, rename, disconnect, grant and the consent result, loading at startup, and the lines about accounts in the prompt. A platform subclass supplies `connectionFor` (a provider's connection) and adds accounts with `upsert`. `AccountConnection` moved to shared with three `expect` types, `PlatformActivity`, `PlatformPendingIntent` and `PlatformIntent`, which are type aliases for `Activity`, `PendingIntent` and `Intent` on Android (so no caller changed) and empty on the iPhone and desktop. Android's `Accounts` is now a thin subclass with the same public API and constructor (Google, Microsoft, iCloud and Jarvis, and each way of adding an account), so no screen or test changed. The saved-connection map no longer uses `ConcurrentHashMap`. `AccountsCoreTest` (7 tests: prompt, add, same address again, unsupported provider, rename rules, disconnect, load) runs on the desktop and the iPhone simulator. **Left:** the providers' own sign-in code (`GoogleAccounts` and the browser sign-in, `MicrosoftAccounts`, `ICloudAccounts`, `JarvisAccounts`), each needing a seam of its own, and `jarvisConnection()` until the Jarvis client moves (K2.8). |
| K2.8 | **client done: Jarvis in shared code** | `JarvisAccounts` (probe, pair, keep) and `JarvisConnection` (state, what Jarvis shares, the printer info, grant) and the Jarvis mail, calendar and to-do sources moved to `shared/commonMain`. They take a `PinnedHttp` (already a shared interface, with the certificate pin) instead of an OkHttp client; Android's `OkHttpPinnedHttp` stays, and a function of the same name as the class builds `JarvisAccounts` from an OkHttp client, so no caller or test changed. A few `internal` members became public (the sources, `feedApi`, `printerInfo`, `isPaired`, `refreshShared`), because the app is a different module now. `String.format` for a task's position became `padStart`. The existing Jarvis tests (fake server, in `app/`) pass unchanged. **Left:** nothing in `jarvis/` except the OkHttp pin. The iPhone pairs with Jarvis through a Darwin `PinnedHttp` (a certificate pin on Apple's networking), not built yet, and `Accounts` needs an iPhone subclass (provider connections, `connectionFor`) before the iPhone can show the work account. |
| K2.6b/c (OAuth) | **the OAuth and token code is in shared code** | `OAuthProvider`, `BrowserOAuth` (the sign-in address, PKCE, the code and refresh exchanges, revoking, reading an id token), `BrowserGrant`, `MicrosoftAccountAuth` and `GoogleAccountAuth` (with the `GoogleSignIn` and `GoogleAuthorizer` interfaces) moved to `shared/commonMain`; their `Activity`, `PendingIntent` and `Intent` are the platform types from the accounts core. Three platform gaps closed: a secure random source (`RandomSource`, `systemRandom()`: `SecureRandom` on Android and the desktop, `SecRandomCopyBytes` on the iPhone: PKCE and the OAuth state are secrets, so Kotlin's own `Random` was not an option), a constant-time comparison for the OAuth state, and SHA-256 written in plain Kotlin (the shared code takes no okio or java imports). `Sha256Test` checks the standard vectors, the RFC 7636 PKCE example and the random source on the desktop and the iPhone simulator. A few `internal` members became public (`BrowserGrant` and `MicrosoftGrant`, the id-token helpers). **Left:** the browser sign-in itself (`BrowserSignIn`, `BrowserGoogle`, `LoopbackReceiver`: opening the browser and receiving the redirect need a seam per platform, the iPhone's being `ASWebAuthenticationSession`), `GoogleAccounts`, `MicrosoftAccounts`, `ICloudAccounts`, `AndroidGoogle` (Play services). |
| K2.5 (Alexa) | **done: Alexa's client, parser and service are in shared code** | `AlexaApi` (the Alexa app's sign-in with PKCE, cookies, devices, states and commands), `AlexaParse` and `AlexaService` moved to `shared/commonMain`. The API takes a Ktor client (not OkHttp) and a `RandomSource`; the PKCE hash is the shared `Sha256`. Android's function of the same name builds it from an OkHttp client without redirects, so no caller or test changed. In the service, the concurrent maps and atomic counters became a locked map and results returned from each state batch and put together afterwards, so nothing is shared between coroutines. The fake-server tests stay in `app/` and pass unchanged. **Left:** the iPhone's Alexa sign-in (Amazon's page in a web view, a seam of its own) and an iPhone `HomeService` list. |

| | |
|---|---|
| Status | **Gate G0 passed: GO (owner, 2026-11-23). Phase 1 is next.** Earlier: **Proposed.** The owner approved the direction and asked for this plan on 2026-11-21. Nothing here is built yet except the spike (§3.3). Phase 0 ends in a go / no-go gate (G0); the owner decides there. |
| Replaces | The plan to keep two codebases forever (Kotlin `app/` + Expo `ios-app/`). The Expo app stays the iPhone app **until the new one passes the parity table** (§11). |
| Keeps | `app/` (Android) working and shippable at every step. |
| Stack | Kotlin Multiplatform (KMP) + Compose Multiplatform (CMP), Ktor, Room KMP, DataStore, kotlinx-datetime. The iPhone app is a thin SwiftUI shell around a Kotlin framework. |
| Size | **XXL** (about 35,800 lines of Kotlin to restructure, plus the iPhone-only implementations). Sizes below are S / M / L / XL, never dates. |
| Read first | [`ios-roadmap.md`](ios-roadmap.md) (what the Expo app is and does), [`ios-parity.md`](ios-parity.md) (the acceptance checklist), [`01-architecture.md`](01-architecture.md) (the Android structure), [`02-standards.md`](02-standards.md) (how work is done here). |

> **This repository is public.** Nothing in this plan, its PRs or its CI logs may contain the owner's real name, email addresses, user name,
> home IP addresses or tokens (see `AGENTS.md`). Apple and GitHub secrets are typed by the owner into the vendors' own pages and never appear anywhere here.

---

## 1. Why, in one page

Edith exists twice. Every feature is written in Kotlin for Android, then written again in TypeScript for the iPhone (C41–C76 were about 20,000 lines of TypeScript
plus 12,400 lines of tests, all re-deriving behaviour the Kotlin code already has). That costs twice for every new feature, and the two apps drift: the parity table
exists because they do.

What changed on 2026-11-21 that makes sharing practical:

1. **The Apple Developer membership is renewed**, so signing, TestFlight and real-device installs are possible.
2. **The repository is public**, so GitHub's standard macOS runners (the only way to build iPhone apps without owning a Mac) cost nothing (to re-verify with the first
   free run, §14 U9).
3. **The spike (§3.3) proved the core of the idea**: Kotlin 2.4 + Compose Multiplatform 1.12 + Ktor 3.6 compile for iOS, a shared Compose screen launched in an iPhone
   simulator, shared tests passed on an iOS simulator, and a hand-written Keychain bridge worked.
4. The Android code is more portable than a first look suggests (§3.2): only 54 of 217 files import `android.*`, the dependency injection is plain Kotlin
   (`AppContainer`, no Hilt), and `kotlinx.serialization` and coroutines are already used throughout.

The owner has said the cost of rewriting is not the concern; **keeping both apps correct and in step is.** This plan moves the shared parts (the engine, the tools, the
services and most of the screens) into one Kotlin codebase that both apps are built from, in small steps, without ever breaking Android.

## 2. Goals, non-goals, principles

**Goals**
- G-1. One implementation of Edith's behaviour (chat, tools, home, printer, Jarvis, accounts, attachments) used by Android and iPhone.
- G-2. One set of unit tests for that behaviour, running on the desktop JVM (fast), on Android, and on an iOS simulator (in CI).
- G-3. The iPhone app reaches the parity table (`ios-parity.md`) and then replaces the Expo app.
- G-4. iPhone builds that need no Mac, no EAS quota and no cost: GitHub's macOS runners, TestFlight for real devices.

**Non-goals**
- Rewriting Android features that already work "because it would be nicer": a move is a move; behaviour does not change in the same PR.
- A different UI look on each platform (Material 3 everywhere; small platform adaptations only where users would notice, §4.5).
- Supporting iPad, macOS or Windows apps. (The desktop JVM target exists as a developer tool, not a product.)
- Changing Jarvis, Ollama, or any protocol.

**Principles (binding for every work package)**
1. **Android never regresses.** `scripts/check.sh` stays green; the owner can use the Android build every day. A step that can't be made safe is split.
2. **The Expo app stays the iPhone app, and the reference for behaviour, until parity** (§11). It gets bug fixes only (§11.2).
3. **Behaviour-preserving moves.** A move PR changes where code lives and which libraries it uses, not what it does. Its tests move with it and keep passing.
4. **Shared by default.** Code goes in `commonMain`. A platform difference gets a named **seam** (§4.3): an interface in common code, implemented per platform.
5. **Every phase ends with something the owner can try**, and says which checks only the owner can do.
6. **Never say the iPhone works until it ran on a real iPhone** (TestFlight). A simulator is evidence, not proof.
7. **Small PRs, merge commit, CI green** (`02-standards.md`). Public-repo hygiene in every PR (see the box above).
8. **Gates are the owner's.** The plan stops at G0–G4 and waits.

## 3. Where we are (facts checked 2026-11-21)

### 3.1 The two apps

| | Android `app/` | iPhone `ios-app/` (Expo) |
|---|---|---|
| Language | Kotlin 2.4.21, Jetpack Compose | TypeScript, React Native (Expo SDK 58) |
| Size | 217 files, 35,767 lines (main); 123 unit-test files and 27 instrumented-test files | about 20,000 lines + about 12,400 lines of tests (about 860 tests) |
| State | C01–C39 built (C39 stages 1–2); in daily use | C40–C57, C66a, C67, C72 option A, C76 pass 1 built in Expo Go; the rest needs the development build |
| DI | manual `AppContainer` (`di/`, 610 lines), no Hilt | `core/services.tsx` |
| Build | Gradle 9.8.2, AGP 9.5.0, JDK 17 CI | Expo Go; EAS builds not started |

### 3.2 How portable the Android code is (measured)

Lines of Kotlin per package (main source set):

`printer` 4,760 · `accounts` 3,547 · `home` 3,256 · `voice` 3,004 · `chat` 2,983 · `ui` 2,809 · `google` 2,565 · `tools` 1,795 · `blink` 1,234 · `icloud` 1,159 ·
`alexa` 1,139 · `ring` 939 · `microsoft` 909 · `jarvis` 827 · `attachments` 796 · `meross` 762 · `data` 707 · `di` 610 · `core` 602 · `settings` 460 · `alerts` 433 · `ollama` 373.

What the code touches that is not common Kotlin (files, not lines):

| Dependency | Files | Where | Replacement (§5) |
|---|---|---|---|
| `android.*` (framework) | 54 (25%) | voice 9, ui 7, core 6, attachments 6, home 5, google 5, chat 3, others 1–2 | seams (§4.3) |
| `okhttp3` | 25 | google 7, tools 4, printer 3, icloud 3, microsoft 2, jarvis 2, one each in voice/ring/ollama/meross | Ktor (OkHttp engine on Android, Darwin on iPhone) |
| `java.time` | 42 | everywhere | kotlinx-datetime |
| `androidx.room` | 8 | data 7, home 1 | Room KMP |
| `androidx.datastore` | 4 | settings, printer, home, core | DataStore (already multiplatform) |
| `androidx.compose` | 34 | ui 14, printer 10, chat 6, home 4 | Compose Multiplatform (same API) |
| `androidx.media3` | 2 | home (camera clips player) | AVPlayer on iPhone (seam) |
| `org.webrtc` | 1 | ring | iOS WebRTC (seam) |
| `jakarta.mail` | 2 | icloud | **open problem** (§14 U6) |
| `org.jsoup` | 3 | tools (web reader), google | ksoup |
| `ai.onnxruntime`, `com.k2fsa` (sherpa-onnx) | 3 | voice | seam; ONNX Runtime and sherpa-onnx both ship for iOS |
| `kotlinx.serialization` | 39 | already multiplatform | none |

A first, cruder count said only 18% of the code is import-clean. That counted `java.*` imports, which are mostly mechanical (time, UUID, locale). The better view: about a
quarter of the files need a real platform seam; about a third need a mechanical library swap (OkHttp, `java.time`, Room); the rest move as they are.
This is an estimate: the first phase (§7) measures it for real.

### 3.3 The spike (branch `spike-kmp-ios`, folder `spike-kmp/`, not merged)

A standalone project to measure the route before committing. What it contains: an Ollama streaming chat in common code, a Keychain bridge written in Kotlin, a
microphone probe (written, never run), a desktop target, a SwiftUI shell generated by xcodegen, and two workflows (a no-signing simulator build; a TestFlight upload).

| Run | Result | Real time | What it taught |
|---|---|---|---|
| 1 | failed | 18 s | The Gradle wrapper script lost its executable bit on a Windows checkout (`git update-index --chmod=+x`). |
| 2 | failed | 2 m 18 s | `CFBridgingRetain/Release` live in `platform.Foundation`, not `platform.CoreFoundation`. Everything else (Compose, Ktor, the shared app) compiled for iOS. |
| 3 | tests failed on the simulator | 9 m 10 s | A `flow { emit() }` called from inside the HTTP call worked on the JVM and threw on iOS. `channelFlow` fixed it. **The same code behaved differently per platform: iOS needs its own test runs.** |
| 4 | tests passed (4 of 4); app build failed | 10 m 16 s | Xcode built both simulator chip types while Kotlin built one: `ARCHS=arm64 EXCLUDED_ARCHS=x86_64`. A piped command hid the failure: `set -o pipefail`. |
| 5 | **app built and launched in a simulator; screenshot** | 7 m 47 s | Cold: Kotlin 7 min (with tests) plus Xcode 5 min. |
| 6 | blocked by GitHub billing (private repo, macOS minutes cost 10x) | n/a | The reason the repository was made public. |
| 7 | failed (public repo, free runner) | 30 s | The executable bit on `gradlew` was lost again when the files were copied into the new clone. |
| 8 | failed | 2 m 15 s | **`AVAudioSession.setActive` is not exposed to Kotlin** (unresolved reference). `AVAudioRecorder` activates the session itself, so the call was dropped; a Swift seam would call it (§4.4). |
| 9 | **built, launched; microphone probe worked** | 13 m 25 s | The screenshot read "Recorded 3 s: 24864 bytes": microphone permission, `AVAudioSession` and `AVAudioRecorder` driven from Kotlin (K0.1). |
| 10 | built, launched; all probes worked | 16 m 13 s | Microphone 24,908 bytes; **Room KMP + bundled SQLite** wrote and read a row; **DataStore** counted launch 1; **navigation + ViewModel** went to a second screen (Material icon and Coil image composed) and back (K0.4, K0.5). Cold. |
| 11 | same, cold with the new `~/.konan` cache step | 17 m 52 s | The cache was saved (about 1 minute). |
| 12 | same, **warm** (a comment changed) | **5 m 39 s** | Xcode build 2 m 11 s; simulator boot, launch and screenshot 2 m 28 s (K0.7). |
| 13 | MQTT probe added; screenshot showed the home screen | 9 m 20 s | I first took it for a crash. It was not: the process was alive, no crash report, and the window only appeared 18 s after launch (first-start shader compiling). A fixed 15-second wait is too short; the app now prints each probe result and the workflow waits for them. |
| 14 | **a real crash found** | 10 m | The app's own output: `Uncaught Kotlin exception ... Info.plist doesn't have a valid CADisableMinimumFrameDurationOnPhone entry`. Compose Multiplatform refuses to start on a 120 Hz (ProMotion) iPhone without that key. A real phone would have crashed too. The same output showed every probe passing, **including MQTT over TLS**. |
| 15 | fixed (the key added); app stays running | 9 m | All five probes print as text: microphone 24,876 bytes, Room, DataStore, **MQTT over TLS (`broker.hivemq.com:8883`): received my own message**, navigation + ViewModel. |

Local desktop: shared tests (4) pass in 34 s warm. Versions that worked together: Kotlin 2.4.21, Compose Multiplatform 1.12.2, Ktor 3.6.1, coroutines 1.11.0,
serialization 1.12.0, Gradle 9.8.2, JDK 21, Xcode on GitHub's `macos-15` runner. Added in Phase 0 and working on both the desktop and the iPhone simulator: Room 2.8.5 with KSP 2.3.12 and `sqlite-bundled` 2.6.2, DataStore 1.3.0, okio 3.18.2, JetBrains multiplatform navigation 2.10.0 and lifecycle-viewmodel 2.11.0, Coil 3.6.3, Material icons 1.7.3.

**Not yet shown** (Phase 0, 2026-11-21): a build on a real iPhone (signing, TestFlight: K0.2), streaming from the owner's real Ollama on a phone (K0.3), the database surviving an app restart and opening a copy of the real Android `edith.db` (K0.4 second half, K1.5), sherpa-onnx and ONNX Runtime on iOS (K0.6), VoiceOver and text input by hand (G0).

### 3.4 What the Android documentation says about the Gradle setup (read 2026-11-21: developer.android.com/kotlin/multiplatform/plugin)

- The Android target of a KMP library module is added with the plugin id **`com.android.kotlin.multiplatform.library`** (needs AGP 8.10 or newer; this repository has 9.5.0).
- **A KMP module cannot also be the Android application.** The Android app must be its own Gradle module (explicit on the page). So `app/` stays an Android-only module that
  depends on a new `shared/` module.
- The library plugin has **a single variant: no build types and no product flavors**, and **no `BuildConfig`**. Build types, the `.debug` application id suffix and `BuildConfig`
  stay in `app/`; shared code receives what it needs through an interface (`AppInfo`) passed in by the app.
- Unit and device tests are off by default and renamed: enable `withHostTest { }` and `withDeviceTest { }`; sources go in `src/androidHostTest` and `src/androidDeviceTest`
  (not `src/test` and `src/androidTest`). Android resources are off by default (`androidResources { enable = true }`), no top-level `android {}` block, Java compilation is off unless
  `withJava()`.
- Not stated on the page and therefore **to verify in K1** (§14 U1): the minimum Gradle version, and whether the third-party dependencies below work with it.

## 4. Target architecture

### 4.1 Gradle layout

```
edith/
  app/                 Android application (com.android.application): manifest, MainActivity, services, notifications, DI wiring, build types, BuildConfig. Thin.
  shared/              KMP library (kotlin-multiplatform + com.android.kotlin.multiplatform.library)
    src/commonMain/    everything shared: engine, tools, services, data, UI (package com.edith.app.*, unchanged)
    src/commonTest/    shared tests (kotlin.test, runTest, Ktor MockEngine, shared fixtures)
    src/androidMain/   Android implementations of the seams
    src/iosMain/       iPhone implementations of the seams (Kotlin; Swift ones are injected, §4.4)
    src/jvmMain/       desktop implementations (developer tool: runs the shared UI on Windows, runs tests fast)
  iosApp/              the Xcode shell, generated by xcodegen from project.yml (SwiftUI entry, Swift seam implementations, Info.plist keys)
  desktopApp/          (optional) a tiny JVM entry that shows the shared UI in a window
  ios-app/             the Expo app: frozen (§11.2), removed at parity
  spike-kmp/           the spike, deleted when K4 starts
```

- **Start with one `shared` module** and the package names unchanged (`com.edith.app.*`), so a move is a `git mv` plus import fixes and history is kept. Split into
  `shared-core`, `shared-features`, `shared-ui` only if build times demand it (decision D42).
- Target set for `shared`: `androidTarget`, `jvm()`, `iosArm64`, `iosSimulatorArm64`. (`iosX64`, the Intel simulator, is skipped: GitHub's runners are Apple silicon.)
- Version catalog: the existing `gradle/libs.versions.toml` gains the KMP entries; nothing in `app/` changes version until its step says so.

### 4.2 Dependency rule

`commonMain` depends only on common libraries. Nothing in `commonMain` imports `android.*`, `java.*`, `javax.*`, `okhttp3`, `androidx.room` (the Android-only artifacts) or Apple APIs.
A lint-style test (like the audit tests in `ios-app/src/audit.test.ts`) scans `commonMain` for forbidden imports and fails the build.

### 4.3 The seams

An interface in `commonMain`, implemented in `androidMain` and `iosMain` (or injected from Swift, §4.4), created in the existing `AppContainer`, which becomes
`AppContainer(platform: PlatformServices)` where `PlatformServices` is a plain Kotlin class holding the seam implementations. `expect/actual` is used only for one-line
differences (for example the current platform name), because interfaces are easier to fake in tests.

| Seam | Interface (common) | Android | iPhone |
|---|---|---|---|
| HTTP | `HttpClients` (`plain`, `secure`; the C37 policy) built on Ktor `HttpClient` | Ktor OkHttp engine | Ktor Darwin engine (plain HTTP to LAN needs the App Transport Security exemption, already in the spike's `Info.plist`) |
| Secrets | `SecretStore` | Keystore-backed (existing) | Keychain (spike proved a Kotlin version) |
| Settings | DataStore (multiplatform) | file in app storage | file in app storage |
| Database | Room KMP + bundled SQLite driver | same driver | same driver; file in the app's Documents/Library |
| Clock, zones | kotlinx-datetime `Clock`, `TimeZone` | system | system |
| Files | okio `FileSystem`, `AttachmentFiles` | app storage | app storage (Documents) |
| Pick photo / file | `Picker` | Photo Picker / SAF | PHPicker / UIDocumentPicker (Swift) |
| Record audio | `AudioRecorder` | `AudioRecord` | `AVAudioEngine` / `AVAudioRecorder` (Kotlin probe in the spike; Swift if it grows) |
| Speak | `Speaker` (system voice or Edith's own sherpa voice, D21) | `TextToSpeech` + sherpa | `AVSpeechSynthesizer` + sherpa-onnx iOS |
| Wake word | `WakeWordEngine` | sherpa-onnx (foreground service) | sherpa-onnx or ONNX Runtime; **only while the app is open** (D41) |
| Location | `LocationProvider` | fused/coarse | CoreLocation (when in use) |
| Browser sign-in | `BrowserSignIn` (Google, Microsoft; loopback or custom-scheme redirect) | Custom Tabs + loopback | `ASWebAuthenticationSession` (Swift) with the `edith://` scheme |
| Embedded web page | `WebSignInView` (Alexa) | WebView | `WKWebView` (Swift, in a Compose interop view) |
| Video | `ClipPlayer` | media3 | `AVPlayer` (Swift) |
| Live video | `LiveVideo` (Ring WebRTC, Blink stream) | stream-webrtc | iOS WebRTC xcframework (Swift) |
| Mail over IMAP/SMTP | `ImapClient`, `SmtpClient` | Jakarta Mail (Angus) | **open problem** (§14 U6) |
| HTML to text | `HtmlText` | jsoup | ksoup (common) |
| MQTT (Meross cloud) | `MqttClient` | Paho | a KMP MQTT library or a Swift one (§14 U7) |
| Local notifications / alerts | `Alerts` | foreground service + notifications | local notifications while open, "While you were away" catch-up (C72 option A) |
| Logging / crash reports | `Log` (the `core/log` rules stay: never log conversation text in release) | existing | `os_log` + MetricKit later |
| Keep screen awake, haptics, share sheet, keyboard insets | small `Platform` interface | Android | UIKit |

### 4.4 Kotlin or Swift for a seam

The iPhone shell is built from Kotlin and Swift by the same Xcode build, so a seam can be written in either:
- **Kotlin (`iosMain`)** for short, simple Apple API calls (about 50 lines or fewer): Keychain, file paths, `NSProcessInfo`.
- **Swift (`iosApp/`) injected into Kotlin at startup** for anything complex or delegate-heavy: audio engine, WebRTC, `ASWebAuthenticationSession`, `WKWebView`, `AVPlayer`, PHPicker.
  Kotlin exports the interface; Swift implements it; `iosApp` passes the instance to `AppContainer(platform:)`. Swift is easier to write and debug than Kotlin/Native
  interop for these, and Apple's own documentation is in Swift.

### 4.5 UI

All screens move to Compose Multiplatform in `commonMain` (decision D41: the alternative, native SwiftUI screens over the shared engine, doubles the UI work and is
only chosen if Compose Multiplatform fails an acceptance check in K9: VoiceOver, IME and text selection). Navigation uses the JetBrains multiplatform navigation
library (§14 U3). Platform adaptations are limited to: safe areas, the keyboard, back gestures, haptics, share sheets, status-bar style, and system font scaling.
Accessibility (VoiceOver names and roles) is part of "done" for every screen, as it is on the Expo app today.

### 4.6 Tests

- `commonTest`: `kotlin.test`, `kotlinx-coroutines-test`, Ktor `MockEngine`, and **shared fixtures** (the printer and Jarvis JSON in `ios-app/src/printer/feed/fixtures/` and the Android
  test resources move to one `shared/src/commonTest/resources/` folder that both the Kotlin tests and, until retirement, the Expo tests read).
- Runs: desktop JVM on every PR (fast, Linux), Android host tests (existing job), **iOS simulator tests on a macOS runner when `shared/**` or `iosApp/**` changes**.
- Instrumented Android tests stay in `app/` (`androidDeviceTest` for shared Android seams).
- Compose UI tests use the multiplatform test API on the desktop target (§14 U8).
- The spike's lesson becomes a rule: **a behaviour that touches coroutines, flows, threads, time zones, locale or text encoding needs the iOS test run before it is called done.**

## 5. Library mapping

| Android code today | Shared replacement | Notes |
|---|---|---|
| OkHttp (`okhttp3`, 25 files) | Ktor client (engines: OkHttp / Darwin) | the `secure`/`plain` policy becomes a Ktor plugin; its tests (no plain HTTP to non-allowlisted hosts) are ported first; streaming via `bodyAsChannel` (spike proved it) |
| `java.time` | kotlinx-datetime | formatting for display stays in a platform seam (`DateFormatter`) because locale rules differ |
| Room 2.8.5 | Room KMP (same artifacts, KSP per target) + `androidx.sqlite:sqlite-bundled` | **the existing database file must open unchanged** (§14 U4) |
| DataStore | DataStore (already multiplatform) | same file format |
| `kotlinx.serialization` | unchanged | |
| Coroutines | unchanged | |
| Coil 3 | Coil 3 (multiplatform) | |
| jsoup | ksoup | the web reader's tests decide |
| Jetpack Navigation | JetBrains multiplatform navigation | verify the version that matches Compose Multiplatform 1.12 |
| `androidx.lifecycle` ViewModel | JetBrains multiplatform lifecycle / ViewModel | |
| media3 | seam `ClipPlayer` | |
| stream-webrtc | seam `LiveVideo` | |
| Jakarta Mail / Angus | seam (§14 U6) | |
| Paho MQTT | seam (§14 U7) | |
| sherpa-onnx, ONNX Runtime | seams `Speaker`, `WakeWordEngine` | both ship iOS builds; verify in K0 |
| Google Credentials / Play services sign-in | seam `BrowserSignIn` | Android already uses the browser flow (C17b); iPhone matches it |

## 6. Migration inventory and order

Packages are moved in dependency order, bottom up. **Difficulty** is the work to make the package common, not to write it.

| Wave | Package (lines) | Blockers | Difficulty |
|---|---|---|---|
| W1 foundations | `core` (602), `ollama` (373), `settings` (460), `data` (707), `di` (610) | `java.time`, OkHttp in `ollama`, Room in `data`, Keystore in `core`, 6 `android.*` files | M |
| W2 engine | `chat` (2,983), `tools` (1,795), `attachments` (796) | OkHttp (tools), jsoup, 6 attachment files on Android image APIs, 3 chat files | L |
| W3 services core | `jarvis` (827), `printer` logic (about 3,000 of 4,760; the rest is UI), `home` framework (about 1,500 of 3,256) | OkHttp, DataStore, media3 (2 files in `home`) | M |
| W4 integrations | `meross` (762), `blink` (1,234), `ring` (939), `alexa` (1,139), `google` (2,565), `microsoft` (909), `accounts` (3,547), `icloud` (1,159) | OkHttp, browser sign-in seam, WebView seam, MQTT seam, **IMAP/SMTP** (`icloud`), WebRTC seam (`ring`) | XL |
| W5 platform features | `voice` (3,004), `alerts` (433) | 9 `android.*` files in `voice`; sherpa; foreground service stays Android-only behind `Alerts` | L |
| W6 screens | `ui` (2,809) and the UI halves of `printer`, `home`, `chat` (about 6,500 together) | 34 Compose files: mostly source-compatible; Material icons, previews, navigation | L |

The iPhone app is built **alongside** W2 onward, not after W6: as soon as the chat engine is shared, the iPhone chat screen runs on it, so problems show early.

### 6.1 The standard move (the "strangler" recipe, used for every package)

1. **Add the seam in place** on Android if the package needs one (interface plus the existing implementation behind it), as its own PR. Android behaviour unchanged.
2. **Swap the mechanical libraries in place**: `java.time` to kotlinx-datetime, OkHttp to Ktor, one package at a time. Still in `app/`. Tests prove no change.
3. **`git mv` the package** into `shared/src/commonMain` (history is kept), fix imports, delete the now-empty Android folder. `app/` depends on `shared`.
4. **Move the tests** to `commonTest` (or keep Android-only ones in `androidHostTest`).
5. **CI**: Android check, desktop JVM tests, and (macOS job) iOS simulator tests all green.
6. **Record** what moved and what was learned in the work package's "As built".

Steps 1–2 are done while the code is still Android-only, so every PR is small and the Android build is the proof.

## 7. Phases and work packages

Each work package (K-number) becomes a chunk file in `docs/design/chunks/` when it starts, in the usual format. Sizes: S = a PR or two, M = a handful, L = a phase of its own, XL = split further.

### Phase 0 — Finish the spike and decide (gate G0)

Goal: replace the open questions in §3.3 and §14 with measurements, using free macOS runners. Nothing in `app/` changes.

| WP | Work | Done when | Size |
|---|---|---|---|
| K0.1 | Push the spike to the public repository (it triggers the simulator workflow). Run the microphone probe on a runner (permission pre-granted with `simctl privacy`). | The screenshot shows a recording result or a clear failure; the iterations and times are added to §3.3. | S |
| K0.2 | **Real iPhone via TestFlight.** The owner does the Apple and GitHub setup in `spike-kmp/DEVICE_BUILD.md` (bundle id, App Store Connect app record, API key, secrets). Run the TestFlight workflow. | The spike is installed from TestFlight on the owner's iPhone. | M (owner steps) |
| K0.3 | On the phone: stream a reply from the owner's real Ollama (plain HTTP to a LAN address, the Local Network permission, ATS exemption). | Text streams in on the phone; the exact Info.plist keys that worked are recorded. | S |
| K0.4 | Room KMP + bundled SQLite and DataStore on iOS: create, write, restart, read; open a copy of an existing Android `edith.db` on the desktop target and on iOS. | The Room identity hash and schema load unchanged (§14 U4). | M |
| K0.5 | Navigation, lifecycle/ViewModel, Coil and Material icons from the multiplatform artifacts compile and render on iOS. | A two-screen navigation sample runs in the simulator. | S |
| K0.6 | sherpa-onnx (speech) and ONNX Runtime (wake word) on iOS: build the iOS xcframework path and run one inference. A WebRTC and an IMAP option are researched, not built (§14 U6, U7). | Findings written down; go/no-go for voice on iOS. | M |
| K0.7 | Warm-cache timings: Gradle and `~/.konan` caches on the runner, path filters, concurrency cancel. | Typical PR macOS time recorded (target: under 12 minutes). | S |
| K0.8 | Verify GitHub's macOS minutes are free for this public repository (the first runs show 0 billed). | Recorded in §14 U9. | S |

**Gate G0: passed, GO (owner, 2026-11-23).** The owner decided with these still open, to be settled inside the phases that need them: the database surviving a restart and opening the real `edith.db` (K0.4, K1.5), sherpa-onnx, ONNX Runtime and WebRTC on iOS (K0.6b), MQTT with a login and Meross's broker, and VoiceOver and typing checked by hand (also re-tested in K3.2). If one of them fails, the fallbacks in §10 apply (native screens, Swift libraries, defer a feature).

**Gate G0 (owner), the test as written.** Go if: the spike runs on a real iPhone, streaming and Room/DataStore work on iOS, Compose screens are acceptable by hand (typing, scrolling, VoiceOver reads the
labels), and a warm iOS CI run is acceptable. Otherwise stop here: the Expo plan continues unchanged and this document is archived with what was learned.

**Phase 0 progress (2026-11-21)**

| WP | Status | Result |
|---|---|---|
| K0.1 | **done** | The microphone probe works on an iOS simulator (§3.3 runs 8-9). One Apple call, `AVAudioSession.setActive`, is not exposed to Kotlin. |
| K0.2 | **done (2026-11-23)** | The spike is installed from TestFlight on the owner's iPhone (bundle id `com.edith.app.spike`, builds 1-5). See "Device findings" below. |
| K0.3 | **done (2026-11-23)** | The phone streamed a reply from the owner's Ollama (qwen3.8:27b) on the PC over Tailscale, and the app showed it (build 5). See "Device findings" below. |
| K0.4 | **half done** | Room KMP + bundled SQLite and DataStore work on the desktop and on an iOS simulator (§3.3 run 10). Not yet: survive a restart (needs the phone), open a copy of the real `edith.db` (needs the real entities: K1.5). |
| K0.5 | **done** | Navigation, ViewModel, Coil and Material icons compile, link and run on iOS with Compose Multiplatform 1.12.2 and Kotlin 2.4.21. Not checked by eye: how the Coil image and the icon look. |
| K0.6 | **research done; MQTT probe done** | See "K0.6 findings" below. K0.6b probe 1 (MQTT over TLS) **passed on the desktop and on an iOS simulator** with the MIT-licensed DitchOoM client. The others (sherpa-onnx speech, one ONNX Runtime, WebRTC, SwiftMail) are not yet run. |
| K0.7 | **done** | Cold 17 m 52 s, warm 5 m 39 s with the `~/.konan` and Gradle caches (§3.3 runs 11-12). The simulator launch and screenshot take about 2.5 minutes of that; the shared-code PR job can skip them. Target met (under 12 minutes). |
| K0.8 | **partly** | Every run on the public repository started and ran; none was refused for billing (the private repository was). That the minutes are not billed is for the owner to confirm on GitHub's billing page (§14 U9). |

**Device findings (2026-11-23, builds 1-5 on the owner's iPhone).** What a real phone taught us that no simulator run did:

1. **Apple's upload rules.** Uploads must be built with the iOS 26 SDK, so the workflow selects Xcode 26.3 on the `macos-15` runner. The app needs a 1024 px icon with no transparency, and an iPhone-only build (`TARGETED_DEVICE_FAMILY: 1`) avoids the iPad orientation checks. The App Store Connect API key must be rebuilt as a clean PEM in the workflow (the secret's line breaks are lost).
2. **App Transport Security and Tailscale.** The owner's Ollama is at a Tailscale address (100.x). iOS does not treat that as a local network, so plain HTTP was refused (error -1022). Setting `NSAllowsArbitraryLoads` was not enough while `NSAllowsLocalNetworking` was also present: when that key is present, iOS ignores `NSAllowsArbitraryLoads`. **Only `NSAllowsArbitraryLoads: true` on its own worked.** The simulator workflow reproduces this with a fake streaming server on a non-local hostname. A release app needs a tighter rule (D49 below).
3. **Tailscale on the phone sleeps.** The first requests timed out until the owner opened the Tailscale app. A real app should say "cannot reach your PC; open Tailscale" rather than "timeout".
4. **Thinking models.** qwen3.8 streams its reasoning in `message.thinking` with an empty `content`; a reader that only looks at `content` shows nothing. The app now reads both. The real app needs separate display or a model that does not think.
5. **Dark mode.** Compose without a `Surface` draws black text on the black dark-mode background: the reply was on screen but invisible for four builds. The app now draws on a themed surface. The real app must set its own colours.
6. **The model name matters.** A model that is not installed gives HTTP 404; the app tells the HTTP status in words.
7. **Ollama on the PC.** It listens on all interfaces and the firewall allows it. With a 27 B model the 16 GB GPU is nearly full, so a second model loading at the same moment stalls. Phone requests show in Ollama's `server.log` as `POST /api/chat` lines.

**K0.6 findings (research, 2026-11-21).** What the Android code actually does with each library (read from the source), what the iPhone options are, and how sure we are. A web search showed the option exists; **only a build on a runner proves it works for us.**

| Need | What Android does today | iPhone option | Evidence | Open questions |
|---|---|---|---|---|
| **Speech (text to speech)** | sherpa-onnx `OfflineTts` running Piper (VITS) voices (`voice/own/SherpaTts.kt`) | sherpa-onnx's iOS build: its `build-ios.sh` makes an XCFramework (device and simulator slices, C and C++ headers; minimum iOS 13), and it has a Swift API with a text-to-speech example. Community Swift packages exist but track upstream loosely (one showed a failed iOS build). | Search results (below) | Build our own XCFramework from a pinned release in our macOS CI rather than depend on a community package. Does a Piper voice run in real time on the phone? App size? |
| **Wake word** | A three-stage ONNX pipeline run directly with ONNX Runtime: `melspectrogram.onnx`, `embedding_model.onnx`, then the `hey_edith.onnx` classifier (`voice/handsfree/WakeWord.kt`) | ONNX Runtime for iOS: Microsoft's `onnxruntime-swift-package-manager` (Objective-C API) or the C API in the prebuilt iOS libraries | Search results (below) | **Linking two copies of ONNX Runtime (one inside sherpa-onnx, one for the wake word) may clash; use one copy.** Does the pipeline keep up in the foreground? (Wake word only while the app is open, D41.) |
| **Live video (WebRTC)** | Google's WebRTC API through `stream-webrtc-android`: `PeerConnectionFactory`, `PeerConnection`, `SessionDescription`, `IceCandidate`, a `VideoTrack` on an `EglBase` (`ring/RingRtc.kt`) | `stasel/WebRTC`: precompiled Google WebRTC as a Swift package (XCFramework, iOS 12+, tags follow Chrome milestones, recent tags build) | Search results (below) | Signalling (Ring's API) stays in shared Kotlin; only media goes through a Swift seam. Show the video in Compose (`UIKitView` around a Metal video view): unproven. |
| **Mail over IMAP and SMTP** | Jakarta Mail (JVM only), but only a small part: open a folder, search, fetch a message, set the Seen or Draft flag, append a draft, send over SMTP, build and read a MIME message (`icloud/`, about 1,160 lines) | **SwiftMail** (Cocoanetics): actors with async/await for IMAP and SMTP on SwiftNIO, iOS 14+, active releases. MailCore2 is Objective-C and its last release was in 2020. Apple's `swift-nio-imap` says it is not production-ready. | Search results (below) | SwiftMail's maturity is unverified (the author, not a test of ours, says so). Needs a real iCloud account and an app-specific password to try: an owner step. Fallback: defer iCloud mail on the iPhone (D45). |
| **MQTT (Meross cloud)** | Paho MQTT 3.1.1 over TLS (`ssl://`), connect, subscribe, publish (`meross/MerossBus.kt`) | Kotlin Multiplatform, three candidates. **MQTTastic Client KMP**: MQTT 5 and 3.1.1, iOS with TCP and TLS, but its licence is **GPL-3.0** (Maven metadata), which cannot go into an app that is distributed (D48). **KMQTT**: MIT, but its iOS TLS needs OpenSSL files copied in by hand (its README). **DitchOoM mqtt 3.0.0**: MIT, TLS through Apple's Network framework with no extra setup (its README). **Chosen and proven: DitchOoM mqtt.** Swift options (CocoaMQTT, MQTTNIO) are from memory, not searched. | Search results (below); the probe (§3.3 run 15) | A round trip over TLS with the platform's own trust store works on the desktop and on an iOS simulator (MQTT 3.1.1, no credentials). **Not yet shown:** username and password (Meross needs them; the parameter name was not found in the library's docs) and Meross's real broker (owner step). |

Sources: [sherpa-onnx iOS build guide](https://csukuangfj.github.io/sherpa/onnx/ios/build-sherpa-onnx-swift.html), [sherpa-onnx Swift API](https://k2-fsa.github.io/sherpa/onnx/swift-api/examples.html), [sherpa-onnx SPM (community)](https://swiftpackageregistry.com/willwade/sherpa-onnx-spm), [onnxruntime-swift-package-manager](https://github.com/microsoft/onnxruntime-swift-package-manager), [prebuilt iOS ONNX Runtime libraries](https://huggingface.co/csukuangfj/ios-onnxruntime), [stasel/WebRTC](https://www.github.com/stasel/WebRTC), [SwiftMail](https://github.com/Cocoanetics/SwiftMail), [swift-nio-imap](https://swift.org/blog/swift-nio-imap/), [MQTTastic Client KMP](https://klibs.io/project/meshtastic/MQTTastic-Client-KMP), [KMQTT](https://www.Github.com/davidepianca98/KMQTT), [DitchOoM mqtt](https://github.com/ditchoom/mqtt).

**K0.6b: the probes that would settle it (free runners, no owner steps unless noted)**

1. **MQTT over TLS: done 2026-11-21** (§3.3 run 15). Then Meross with credentials, an owner step.
2. **sherpa-onnx text to speech**: build the XCFramework in CI from a pinned release, link it into the spike through a Swift seam, synthesise one sentence with a Piper voice, report the time. The voice file (tens of megabytes) is downloaded in CI, not committed.
3. **One ONNX Runtime**: with (2) linked, run one tiny model through the same runtime from Swift (the wake-word path) to show the two do not clash.
4. **WebRTC**: add `stasel/WebRTC`, create a `PeerConnectionFactory`, and draw a test video track in a `UIKitView` inside the Compose screen.
5. **SwiftMail** against iCloud: an owner step (app-specific password). Last.

**Checklist for the real iPhone shell (K4.1), from what the spike's crashes and runs showed**

- `Info.plist` must set `CADisableMinimumFrameDurationOnPhone` to true, or Compose Multiplatform throws at start-up on a 120 Hz iPhone (a crash found in run 14).
- Also set `NSMicrophoneUsageDescription`, `NSLocalNetworkUsageDescription`, `NSAppTransportSecurity` with `NSAllowsLocalNetworking`, and `ITSAppUsesNonExemptEncryption` (false); the spike's `project.yml` has them.
- Wait for the app to be ready before judging a simulator screenshot: the first start on a fresh simulator took about 18 seconds. Have the app print its results as text and read the log; check that the process is still running and that there is no crash report.
- Treat any `Uncaught Kotlin exception` in the app's output as a crash to fix, never as noise.

### Phase 1 — Make the Android build ready for sharing (no behaviour change)

Goal: `shared/` exists, builds for Android, desktop and iOS, and the first foundations live in it. `app/` and the Expo app are untouched in behaviour.

| WP | Work | Done when | Size |
|---|---|---|---|
| K1.1 | Create `shared/` (library plugin `com.android.kotlin.multiplatform.library`, targets android, jvm, iosArm64, iosSimulatorArm64) with one trivial class; make `app/` depend on it; add the lint test for forbidden imports in `commonMain`. Verify the Gradle version question (§3.4). | `scripts/check.sh` green; a desktop test and an iOS-simulator test of the trivial class pass in CI. | M |
| K1.2 | CI: `shared-ios.yml` (macOS: iOS simulator tests, framework link, `xcodebuild` simulator build, screenshot), path-filtered, concurrency-cancelled, with Gradle and Konan caches. | Runs only on `shared/**`, `iosApp/**`, and the workflow itself; typical time recorded. | M |
| K1.3 | **Time**: introduce kotlinx-datetime and replace `java.time` package by package in `app/` (42 files); display formatting goes behind `DateFormatter`. | No `java.time` import left in packages marked for W1–W3; tests unchanged and green. | L |
| K1.4 | **HTTP**: port the `HttpClients` policy (C37) to Ktor with an OkHttp engine on Android; move the 25 OkHttp users over package by package, `ollama` first. | No `okhttp3` import outside `androidMain`; the policy tests (HTTPS-only for `secure`; plain only where allowed) pass; streaming behaves as before. | XL |
| K1.5 | **Storage**: Room to Room KMP (same schema and version; `data` 7 files, `home` 1), DataStore to common. | The existing Android database opens unchanged; migrations tests pass. | L |
| K1.6 | Move W1 (`core`, `ollama`, `settings`, `data`, `di`) into `shared/commonMain` with `AppContainer(platform: PlatformServices)`. Android seams: `SecretStore`, `Log`, files. | W1 packages are common; Android works exactly as before; iOS simulator tests of W1 pass. | L |

**Phase 1 progress (2026-11-23)**

| WP | Status | Result |
|---|---|---|
| K1.1 | **done** | `shared/` builds for Android, desktop and iPhone; one trivial class; an import guard keeps `android.*`, `java.*` and OkHttp out of `commonMain` (PR #11). |
| K1.2 | **done** | `shared-ios.yml` runs the shared tests on an iOS simulator (PR #12): 3 m 37 s cold. |
| K1.3 | **done** | No `java.time` is left in `app/src/main`: `Clock` and `formatPattern` (display formatting: java.time on Android and desktop, `NSDateFormatter` on the iPhone, proven on a simulator) are in `shared/`, on kotlinx-datetime 0.8.0 and `kotlin.time.Instant`. Calendar events and mail dates are `Instant`s (the zone they are shown in is passed in, not carried), task due dates are kotlinx `LocalDate`, durations are `kotlin.time.Duration`. `JavaTimeGuardTest` keeps it that way. Tests may still build fixtures with java.time (`FakeClock` takes either). |
| K1.4 | **done on Android** | Every service calls through Ktor 3.6.1, running over the same OkHttp clients (`HttpClients.ktorPlain` / `ktorSecure`, and `HttpClients.over(okHttp)` for services that need an OkHttp hook), so the HTTPS-only policy, certificate pinning and socket tags are untouched; the policy tests also run through Ktor. OkHttp remains only where Ktor has no equivalent of an engine hook, which is Android-only and listed in `OkHttpGuardTest`: Blink's cookie jar and IPv4-only lookup, iCloud's password interceptor, Jarvis's certificate pin, the page reader's private-address check. Differences found: Ktor writes a space in a query as `+` (OkHttp `%20`); Ktor follows only GET and HEAD redirects unless told otherwise (WebDAV needs all); `HttpClients.over` reads the OkHttp client's `followRedirects` and `followSslRedirects`. Not yet done: moving `HttpClients` itself and those hooks behind a seam in `shared/` (an `expect`/`actual` engine: OkHttp on Android, Darwin on iPhone), which comes with K1.6. |
| K1.5 | **done** | The database is in `shared/commonMain` (Room 2.8.5 KMP, KSP per target, schemas in `shared/schemas/`, moved with `git mv`): the entities, DAOs, type converters, migrations (now written against `SQLiteConnection`) and the smart-home command log. Room regenerated `1.json` to `3.json` with the same content, so the identity hash is unchanged and an installed database opens as before (§14 U4 holds at the schema level). Android still opens it through Room's own builder (`EdithDatabase.create(context)`, in `androidMain`); the desktop and the iPhone use `EdithDatabase.open(path)` with the bundled SQLite. Desktop tests: conversations and messages with their relations and cascade, the command log, and a real version-1 database (built from `1.json`) upgrading to version 3 with its rows. Not yet shown: the iPhone build of the same (the macOS job), and the owner's real `edith.db` opened (K0.4's second half). DataStore moves with the packages that use it (K1.6). One hook stays until K1.6: `ListConverters.onUnreadable` stands in for the Android-only `Log`. |
| K1.6 | **done, except what waits for later waves** | In `shared/commonMain`, with the package names unchanged: `core` (`AppDispatchers`, `AppVisibility`, `Error`, `MessageTimes`, the `SecretStore` interface, **logging**), `ollama` (client, models, DTOs), `settings` (`Settings`, `DataStoreSettingsRepository`, `ModelSelection`, `ServerUrl`) and `data/chat/ChatRepository`. Seams, each a small `expect`/`actual`: `Log` (`platformLog`, `logSummary`, `logTrace`; `LogConfig.debug` set by the app at start; the ring buffer is common code over an atomic reference), network error questions (`isSocketTimeout`, `isUnreachable`), the IO dispatcher (the iPhone has none), the database's `inTransaction` and `isConstraintViolation`, and `StoredFiles` (what the chat history asks of the attachment store). The shared code is built for Java 17 like the app. Stays in `app/` for now: `di/AppContainer` (the composition root of every wave, so it can only move when they have), `SettingsViewModel` (screens wave, with Android's permission check), `CrashReporter`, `core/permissions`, `core/net` (`HttpClients`, `TaggedSocketFactory`), `KeystoreSecretStore`, `DataStoreSettingsRepository.create(context)`. Differences found: Kotlin/Native has no public `Dispatchers.IO`; `Charsets` is Ktor's in common code; `MessageTimes` takes a language tag, not a `Locale`; `ServerUrl` parses with Ktor, not `java.net.URI` (its tests pass unchanged). Desktop tests: the log, a message for a vanished conversation (the old Android exception, now a constraint check), and a transaction that rolls back. Not yet shown: the same on an iPhone simulator beyond compiling (the macOS job), and `Settings` and `ModelSelection` tests, which stay in `app/`. **Gate G1 is the owner's.** |

**Gate G1 (owner): passed 2026-11-23.** On the merged master build the emulator opened the owner's existing database and a chat reply streamed through the new Ktor client (after Ollama's own slow model reload, which first looked like a hang); CI green. Not run: instrumented tests (compile only) and the iPhone run of the database tests.

### Phase 2 — Move the engine

| WP | Work | Size |
|---|---|---|
| K2.1 | `chat` (engine, prompt builder, context, streaming, cancel/regenerate) to common; tests moved; the chat UI stays in `app/` for now | L |
| K2.2 | `tools` framework and the tools (weather, time, web search, page reader with ksoup, location seam), tool loop, confirmation cards | L |
| K2.3 | `attachments` (images, audio files, text/PDF extraction), `Picker` and `AttachmentFiles` seams | L |
| K2.4 | `jarvis` client and pairing, `printer/feed` logic (wire types, 33 commands, session, settle), `home` framework (Home, services, `HomeBackend`) | L |

**Phase 2 progress (2026-11-23)**

| WP | State | What moved, and what was found |
|---|---|---|
| K2.1 | **done, except the screens and the service** | To `shared/commonMain`: `chat/engine` (`ChatEngine`, `ContextBuilder`, `ConversationTitler`, `PromptBuilder`, `TurnRunner`), `CurrentConversation`, `tools/framework/Confirmations` and `ToolImage`, `attachments/ReplyFormat`, `PendingAttachment` and the small `AttachmentWriter` (what the engine asks of the attachment store: keep, discard, write, delete), and the `LocalNetworkAccess` interface (Android's check stays in `app/`). `PendingAttachment` now holds a `path` string (Android reads it as a `File` with `.file`). Tests moved to `commonTest`: prompt and context, confirmations, titles. Stays in `app/`: `chat/ui`, `chat/service/ReplyService`, `AttachmentStore` and the rest of `attachments`, the tool loop (K2.2). |
| K2.2 | **done, except the device location and the engine hooks** | To `shared/commonMain`: the `tools/framework` (tool, arguments, registry, guidance, text-call parser, `ToolTurnRunner`), `ToolPermissions` (Android's permission check stays in `app/`), the tools `get_current_time`, weather (Open-Meteo client and tool), web search (Ollama, DuckDuckGo) and the page reader, and the `LocationProvider` interface (`AndroidLocationProvider` stays). **Jsoup became ksoup 0.2.6** in shared code: it compiles and parses as before; the page and search tests (run on Android) pass unchanged except for the document type. Seams: `deviceLanguageTag()` (tools take a language tag, not a `Locale`), `withTimeouts` (now in `core/net`, shared), the page reader's client (`PageReader.over(...)` builds the OkHttp one with the public-address DNS check: that check is still Android-only, K4.5), `isUnknownHost`, and the shared `PrivateNetwork.isPrivateHost` rules. Found: Kotlin/Native rejects `,` in test names; `kotlin.uuid` and `kotlin.io.encoding.Base64` replace `java.util`. Not yet shown on the iPhone simulator: the page reader and search against real pages; the tool-framework tests still run in `app/` (they lean on the app's test fakes). |
| K2.3 | **done, except what only a phone can do** | To `shared/commonMain`: `AttachmentFiles` (what the engine, the context builder and retention ask of the store, with no `File`), `PathAttachmentStore` (the store itself, on kotlinx-io's `SystemFileSystem`: the same code on Android, the desktop and the iPhone), `Retention`, `MediaAttachmentContext` and `AttachmentsSection`. Seams: the audio length is a function the platform gives the store (`MediaMetadataRetriever` on Android); the app's `AttachmentStore` adds `file()` for what plays, shows or shares a file. Desktop tests: keep and read back, session files cleared at start, half-written files, quiet deletes, audio length remembered. Stays in `app/` (platform work, built again for the iPhone in K4): `AttachmentIntake` (pick, resize and turn an image or audio into a prepared file), `AudioToWav`, `AttachmentPlayer`, `FileExport` (share sheet). Not yet shown: the store on the iPhone simulator (the macOS job runs its tests only if they are in `commonTest`; these are desktop tests), and the Android instrumented retention tests (compile only). |
| K2.4 | **done, except what is platform work** | To `shared/commonMain`: the Jarvis client (`JarvisApi`, `JarvisWire`, pairing: address, fingerprint, proof), the printer feed (`printer/feed`: wire types, the 33 commands, session, settle, input, upload rules, `PrinterToHome`), the `home` framework (`Home`, `HomeBackend`, `HomeActions`, `HomeModel`, tools, `HomeService`, `HomePrefs`, the Demo backend) and `alerts/AlertModel`. Seams: `PinnedHttp` (the certificate pin is an engine hook: `OkHttpPinnedHttp` in `app/` on Android, a Darwin pin callback on the iPhone in K4.5; the shared `PinCheck` holds the logic), `sha256` and `hmacSha256` (`MessageDigest` on Android and the desktop, CommonCrypto on the iPhone), `formatDecimal` (replaces `String.format`), `isCertificateFailure`, `CowMap` and `CowList` (replace `ConcurrentHashMap` and `CopyOnWriteArrayList`), `AlertCatalog.demoHome` (set by the app: the shared module has no `BuildConfig`). Upload takes a kotlinx-io `Source`. `PrinterFormat` and `FileList.size` take a language tag, not a `Locale`. Stays in `app/`: `JarvisAccounts` and `JarvisConnection` (Android intents), `JarvisSources` (the accounts sources), `PrinterFeed` (finds the connection), `PrinterService` and Moonraker (the direct printer), `TrackLiveFeed` (Ring's WebRTC types), `DemoPicture` (`Bitmap`), all view models and screens. Tests: shared `commonTest` for the digests (RFC 4231 and SHA-256 vectors), pairing, the pin check, URL building, decimals and the concurrent map, so the iPhone simulator runs them. Not yet shown: the iPhone actuals (`Digests.ios.kt`, `Decimal.ios.kt`) have only been written, not built: only the macOS job compiles them. |
| G2 tests | **evidence gathered, the owner decides** | The shared engine's tests run in three places: **desktop JVM** (`:shared:jvmTest`), **Android host** (`:shared:testAndroidHostTest`) and the **iOS simulator** (`:shared:iosSimulatorArm64Test`, the macOS job). Pure engine tests moved from `app/` to `shared/commonTest` and now run in all three: the tool framework (`ToolFrameworkTest`, `ToolFailureMessageTest`), settings and model selection, the printer feed (command, data, format, input, macro and console, presets, settle, session), the home framework (`HomeTest`, `HomeActionsTest`, `DeviceNamesTest`, `PrinterControlToolTest`), plus the K2.4 tests. With small shared fakes in `commonTest/testing` (`FakeClock`, `FakeSettingsRepository`, `FakeOllamaClient`, `testDispatchers`, and JUnit-style assertions in `Asserts.kt` so a moved test keeps its body). The engine's **end-to-end tests** (`ChatEngineTest`, 15 tests, and `ToolTurnRunnerTest`, 14) now also run on the **desktop** and on the **iOS simulator** over the bundled SQLite (`shared/src/engineTest`, compiled for both; each has its own `inMemoryTestDatabase()`); before this they had only ever been compiled. On the simulator that is 15 and 14 tests, none failing. They stay in `androidTest` too. `AttachmentSendTest` (6 tests) and `RetentionTest` (7; Save and Share stay Android-only) also run on the desktop, over the shared `PathAttachmentStore`. Still Android-only, not run: the Room migration test, the UI tests and Save/Share. Not shown: `AttachmentSendTest` and `RetentionTest` on the iOS simulator (desktop only so far), anything on a real phone. "The Android app is unchanged to use" has only the owner's own use to show it. |

After K2.4 the iPhone app can run the whole engine. **Gate G2:** the shared engine passes its tests on desktop, Android and the iOS simulator, and the Android app is unchanged to use.

### Phase 2b — the rest of the engine (added 2026-11-24, see [`kmp-parity-plan.md`](kmp-parity-plan.md) §7)

| WP | State | What moved, and what was found |
|---|---|---|
| K2.10 | **done on Android** | The platform parts of attachments became interfaces in `shared/commonMain`: `AttachmentIntake` (takes the picked item as an address string; `Result.Ready`/`Refused`) and `AttachmentPlayer` (`playing`, `play(id, path)`, `stop`); `AttachmentLimits` holds the rules as plain code (longest side 1536, JPEG 85, 5 minutes of audio, `scaled`, `withExtension`, which audio is kept as it is, minutes and seconds). Android's classes are now `AndroidAttachmentIntake` and `AndroidAttachmentPlayer` (the code is unchanged apart from `Uri.parse` of the string, and the shared rules); `FileExport` (share, save) stays Android-only until the chat screen moves (K3.2), where it becomes a screen seam. 6 new shared tests (desktop, Android host, simulator). Not done: the iPhone implementations (K4.3). |
| K2.9 | **done on Android** | To `shared/commonMain` (`voice/`): the interfaces that were already platform-free but sat inside Android files, now their own files (`Recorder`, `SpeechInput`, `WakeListener`, `WakeWordDetector` (now `AutoCloseable`), `TtsEngine`; `RecordedAudio` holds a path, not a `File`), and the logic: `VoiceMemos`, `OllamaTranscriber`, `HandsFree` (the state machine), `SpeechOutput`, `ChoosingTts`, `SpeechController`, `SpeechText` (the sentence chunker) and `OwnVoices`. New shared helpers: `PlatformLock` (`ReentrantLock` on Android and the desktop, `NSRecursiveLock` on the iPhone, written blind; the `synchronized` of `SpeechOutput` becomes `lock.synchronized { }`) and `readFileBytes`/`deleteFileQuietly` (kotlinx-io). Native-safe changes: `Base64` is `kotlin.io.encoding`, `AtomicInteger` is `AtomicInt`, `System.nanoTime` is `TimeSource`, `StringBuilder.delete` is `deleteRange`, and `RegexOption.DOT_MATCHES_ALL` (Java-only) became `[sS]` in the file-block pattern (the macOS job is the first check that the iPhone's regex engine reads it the same way; the sentence-chunker tests cover it). The sensitivity-to-threshold rule moved to `WakeWordDetector.threshold`. 42 tests moved to `commonTest` (`HandsFreeTest` 15, `ChoosingTtsTest` 11, `SpeechOutputTest` 16): they pass on the desktop. **Stays in `app/` as the Android implementations:** `AudioRecorder`, `WavWriter`, `LiveSpeech`, `MicWakeListener`, `OpenWakeWord`, `HandsFreeService`, `AndroidTts`. **Not moved, on purpose:** Edith's own voice (`SherpaTts`, `AudioSink`, `VoiceArchive`, `VoiceDownloader`: not planned on the iPhone, decision D57). `VoiceInputTest` stays in `app/` (it uses a temporary folder and files). |
| K2.5 (Meross) | **first slice done: the sign-in and message rules** | `MerossProtocol` (the sign-in request and its signature, the message envelope, topics) and `MerossApi` (the cloud sign-in and device list) moved to `shared/commonMain`. The signature is MD5, which the protocol dictates, so a small `Md5` (RFC 1321, plain Kotlin) joined the shared utilities, with the RFC's test vectors on every platform (`Md5Test`); the nonces are not secrets, so Kotlin's `Random` makes them. `java.util.Base64` and `IOException` replaced. **Left, and waiting on your decision (§14 U7):** the MQTT bus (`MerossBus`, Paho, JVM-only) and so `MerossService`, which uses it; the plan is a swap to a library that has an iPhone build. |
| K2.5 (Ring) | **first slice done: Ring's HTTP client** | `RingApi` (sign-in with two-step codes, devices, snapshots, events, clips, and the live-view calls) moved to `shared/commonMain`; its `IOException` is kotlinx-io's, and its four parsers became public because the app is a different module now. The fake-server tests stay in `app/` and pass unchanged. **Left:** `RingService` (it holds the Android WebRTC types for the live view, which stay Android-only until the iPhone has its own WebRTC, K4.5) and `RingRtc`. |
| K2.12 | **first step done on Android: the engine's container** | New in `shared/commonMain`: `SharedContainer` (the chat engine and what it needs: Ollama client, model picker, chat repository, confirmations, web search, weather, prompt sections, tool loop, titler, retention, current conversation) built from `PlatformServices` (dispatchers, clock, the database, settings, secrets, the HTTP clients, the attachment store, location, tool permissions, the local-network check: all interfaces or plain values) and `KtorClients` (the `plain`/`secure` rule as an interface; Android's `HttpClients` implements it over OkHttp, the iPhone's will over Darwin). `AppContainer` builds one and every property it had still exists under the same name, now passing through to it, so no caller changed. **Still in `app/`, and why:** the tool registry (its tools use accounts, the home and the printer, which are not shared yet; the shared container asks for it through a hook when the first turn needs it), the reply listeners (the titler and Edith's voice), the retention hook, and everything for voice, services and platform work. These hooks go away as K2.5 to K2.8 move the services. `HttpClientsWiringTest` now reads both container files. 4 container tests run on the desktop and the iPhone simulator. **Needs a few days of the owner's own use on Android before the next step (like G1):** this touches how every object is built. |
| K2.5 (printer) | **first slice done: the printer's own client (Moonraker)** | `MoonrakerApi`, `MoonrakerParse` (and `PrinterAddress`), `PrinterPrefs` and `PrinterService` moved to `shared/commonMain`. JVM-only code replaced: `java.net.URI` by ktor's `Url` (with an explicit check for an empty address, because ktor fills in a default host for "http://"), `URLDecoder` and `URLEncoder` by ktor's encoders, `ConcurrentHashMap` by a map behind the shared lock, `Math.round` by `roundToLong`, `Dispatchers.IO` by an injected dispatcher (default `AppDispatchers.Default.io`, so no caller changed). A few top-level `internal` declarations became public (the app is a different module now). The fake-server tests stay in `app/` and pass unchanged. **Left in K2.5:** Alexa, Ring, Blink and Meross (each with its own seam: cookies, WebRTC, MQTT), which wait for decisions of yours where the plan says so (the Meross library swap, the Blink cookie jar). |
| K2.11 | **done (logic)** | `AlertWatcher` and `AlertPrefs` moved to `shared/commonMain` (the alert model was already there). Their 12 watcher tests and the 5 switch/catalog tests now run on the desktop and the iPhone simulator; the Android-only checks (when watching starts, the remembered tap, notification titles) stay in `app/` as `AlertAndroidPartsTest`. `AlertNotifier`, `AlertService`, `AlertTaps` and `AlertKeepAlive` stay in `app/` as the Android notifier and keep-alive. No behaviour change. |

### Phase 3 — Shared screens

| WP | Work | Size |
|---|---|---|
| K3.1 | Theme, typography, shared components (bubbles, cards, sheets) into `commonMain`; desktop window entry to view them on Windows | M |
| K3.2 | Chat screen, conversation list, settings screens | L |
| K3.2 (second slice) | **built; the iPhone chat is in the same PR** | `ChatViewModel` and the pure screen move to `ui/`: `ChatActions`, `ChatContent` (title bar, message list, conversation drawer, rename and delete dialogs) now live in `ChatContent.kt`, and `ChatScreen.kt` in `app/` keeps only the Android glue (permission launchers, pickers, save and share, services), so Android behaves as before and the iPhone has the same screen. The view model takes plain strings for picked files (`attachImages(List<String>)`), a shared `AttachmentFiles` (new `pathOf`) instead of the app's `AttachmentStore`, `SpeechInput.DIDNT_CATCH` (constants moved out of `LiveSpeech`) and no `File`; its Android factory is an extension of the companion in `app/` (`ChatViewModelFactory.kt`). `BackHandler` comes from Compose Multiplatform's `ui-backhandler`. `SimpleChatScreen` wires `ChatContent` for a platform without attachments or voice. 5 new UI tests of `ChatContent`. **Finding:** a typed message field has no accessibility name (a screen reader reads its value, and its placeholder only while empty); the test checks the empty field. **Not moved:** the Android glue, the settings screens, the voice and attachment buttons' platform actions. |
| K4.1 | **first slice merged (#37); TestFlight build 101 checked on the owner's iPhone, 2026-11-24** | New: `iosApp/` (the SwiftUI shell, generated by xcodegen: two Swift files, an icon, `project.yml` with iOS 17 and the Info.plist keys the spike found), the framework `EdithUI` from `:ui`, and on the Kotlin side the iPhone parts of `PlatformServices`: `IosSecretStore` (Keychain, the spike's bridge, readable after first unlock), `DarwinKtorClients` (5 s connect, 180 s silence; the secure client refuses non-https), and `createIosApp()` (database, settings and attachments as files under Application Support). The first screen is the **engine check** (`EngineCheck` in shared, `EngineCheckScreen` in `ui`): type the Ollama address, run the checks (database, Keychain round trip, saved settings, Ollama version and models), then the shared component gallery below. 3 `EngineCheck` tests run on the desktop and the iPhone simulator. Workflows: `ios-shell.yml` (simulator build without signing, start, picture, crash check) and `ios-testflight.yml` (by hand; reuses the spike's secrets, variables and bundle identifier `com.edith.app.spike`; build numbers start at 101 because the spike used 1 to 5). A manual workflow can only be run once it is on master. **Owner's check on a real iPhone (build 101):** all five checks passed on the home Wi-Fi at a 192.168 address, and again at the Tailscale address (a non-local 100.x address over plain HTTP, so the App Transport Security setting of the spike holds), with Ollama 0.40.18-rc0 and 7 models; typing the address into the Compose text field worked; the address was still there after a restart and the database still reported chat number 1 (the restart came before the second run, as described by the owner: an inference, not a separate check); the dark theme looked right. **Not tested:** mobile data, text selection and paste, VoiceOver (the owner turned VoiceOver on and the whole phone stopped answering touch, which is VoiceOver's own gesture mode on every app, not a fault of Edith; the test was dropped for now). Not done: the real bundle identifier (D43), location, microphone, notifications. |
| K4.2 | **chat on the iPhone, text only: build 102 tried by the owner; fixes in the next build** | The iPhone app now has two tabs: Chat (`SimpleChatScreen` over the shared `ChatViewModel`, no attachments, voice or tools beyond the time) and Setup (the engine check, where the Ollama address is typed). At launch it does what Android's container does: clears leftovers, opens the database, picks the open chat. The model is chosen automatically by the engine. Tools on the iPhone so far: the time, the weather in a **named place** and web search (DuckDuckGo, no key yet). **The page reader** follows in the next PR: on Android it refuses names that resolve to a private address inside its DNS lookup, so the iPhone got its own check (`resolveHostAddresses`, `getaddrinfo` on iOS, `InetAddress` elsewhere; `PrivateNetwork.isPrivateAddress` for IPv4, IPv6 and v4-in-v6; 9 shared tests). **Known gap:** the check and the connection are two lookups, so a hostile DNS server could answer differently the second time; Android checks the addresses it actually connects to, the iPhone does not yet. "Here" for the weather needs Core Location. Not done: the real settings screens, the other tools, location, attachments, voice. **Owner's check of build 102 (2026-11-24):** Setup still passes; the time tool worked; new chat, rename, switch and delete all worked. **Two findings, both fixed in the next build:** (1) after Ollama's long first load, the first message or two timed out: Ktor's "infinite" request timeout means "not set" to Apple's networking, so its 60 s idle default applied (Android allows 180 s); the Darwin clients now set 180 s on the session and on each request. (2) The keyboard could not be closed, so it covered the tab bar: dragging or tapping the message list now closes it, and on the Setup page a tap outside the field or the Done key does. Still to be checked on a phone: the new tools (weather, search, page reading and its private-address refusal), and the keyboard fix. |
| K4.2 (location) | **built, not yet tried on a phone: weather "here"** | `IosLocationProvider` is the iPhone's `LocationProvider` on Core Location: kilometre accuracy (all the weather needs, least battery), asked only when the owner says "here", the system's own "while using the app" question the first time (`NSLocationWhenInUseUsageDescription` is in the app's `Info.plist` settings), a 10 s limit for a fix, and the place named with `CLGeocoder`. Refusal or Location Services off gives the model a plain sentence to relay (iPhone wording). The coordinates are never stored, logged or sent to the model. `NoLocation` is gone. Written without a Mac: CI compiles it; the first real run is the owner's phone (ask "what is the weather here?"). |
| K4.3 | **photo attachments built, not yet tried on a phone** | The paperclip on the iPhone goes straight to the system photo picker (`PHPickerViewController`, no permission; `IosPhotoPicker` copies each choice out of the system's short-lived file into a temporary folder). `IosAttachmentIntake` prepares it as on Android (upright, longest side 1536, JPEG 85) and deletes the copy; its folder is emptied at launch. `SimpleChatScreen` takes a `PhotoPicker` seam; `ChatFeatures.audioFiles` is false on the iPhone, so there is no menu with an audio item that cannot work. Two UI tests cover the paperclip. Written without a Mac: CI compiles it; the first real run is the owner's phone. Known weak spot: a photo is decoded at full size before scaling (about 190 MB for 48 megapixels), so an ImageIO thumbnail is the next step if that proves a problem. **Not done:** audio files, voice memos, spoken replies (the rest of K4.3), opening a photo full screen, and whether a sent photo shows in its bubble is unchecked. |
| K4.4 | **built, not tried on a phone** | The iPhone's browser sign-in: `AppSignIn` (shared) runs the same PKCE, `state` and code exchange as Android's loopback flow, but over a custom-scheme redirect, behind an `AuthSession` seam; the iPhone's is `IosAuthSession` (`ASWebAuthenticationSession`, no URL scheme in Info.plist needed). Google's iOS client redirects to its client ID reversed (`com.googleusercontent.apps.<id>:/oauth2redirect`). 9 shared tests with a pretend sheet and token endpoint (wrong state, wrong scheme, cancel, decline, expired code). **Needs the owner:** a Google iOS client ID in Info.plist (`EdithGoogleClientId`, `docs/google-setup.md`); Microsoft's `msauth.<bundle>://auth` redirect is registered the same way when Microsoft joins. |
| K4.5 | **built, not tried on a phone** | `DarwinPinnedHttp`, the iPhone's `PinnedHttp` for the Jarvis client: https only, and the connection is accepted only when `PinCheck` accepts the server's first certificate (the PC's self-signed one), as on Android. Compile-checked only: pinning needs the real PC. ATS decision D49 is still the owner's (the PC's address is an IP, which ATS leaves alone). |
| K4.6 | **built, not tried on a phone** | `IosAccounts` (the shared `AccountsCore` with Google and Jarvis; Microsoft and iCloud accounts read "can't use this kind of account" until their seams exist), `IosGoogleAccounts` (the sheet, a refresh token per account in the Keychain through the shared `RefreshTokenAuthorizer`, and the calendar, Gmail and Tasks clients), an Accounts tab (accounts and their state, add Google, pair the work account) and the read-only tools on the iPhone: calendar events, free time, email search and reading, to-dos. The tools that change something (create, send, complete) wait for confirmation cards on the iPhone. |
| K4.7 | **built, not tried on a phone** | Microsoft on the iPhone: `IosMicrosoftAccounts` (the sign-in sheet, the shared `MicrosoftAccountAuth`, Outlook calendar and mail), an "Add a Microsoft account" button, `EdithMicrosoftClientId` in Info.plist. The redirect is `msauth.<bundle id>://auth`, registered on the app registration's iOS platform (**needs the owner**: `docs/microsoft-setup.md`). The calendar works in UTC until the iPhone maps its time zone to a Windows name. 2 simulator tests for the redirect addresses (Google's and Microsoft's). |
| K4.6b | **built, not tried on a phone** | The tools that change something are on the iPhone too: create, change and delete a calendar event, send and reply to email, add, change, complete, delete and reorder to-dos. Each waits for the owner's tap on the shared confirmation card (the chat already shows it; D5, D11). The to-do ordering helper keeps its saved ranking in a file next to the other app data. |
| K4.8 | **calendar built, not tried on a phone; mail is K4.10** | iCloud on the iPhone, calendar only. `KtorDavClient` is the CalDAV client on Ktor (shared, so it runs on every platform): the password goes on every hop, redirects included, but only to `caldav.icloud.com`'s own origin or an `icloud.com` host over https (Apple redirects to `pNN-caldav.icloud.com`); XML is read with Ksoup by local name. 4 shared tests with a pretend iCloud (discovery over two hosts and a redirect, a wrong password, the query's text intact, the password rule). `IosICloudAccounts` checks the address and app-specific password by discovery, keeps the password in the Keychain, and connects `ICloudCalendarSource`; an Add iCloud calendar form on the More tab. **Not done: iCloud mail**, which needs an IMAP/SMTP client the iPhone does not have (Android uses Jakarta Mail). |
| K4.9 | **built, not tried on a phone** | The smart home on the iPhone: the 3D printer on the home Wi-Fi (Klipper's Moonraker, direct) and Alexa (Amazon's own page opened in Safari, the owner pastes the address it ends on, as on Android's "Use my browser"; the iPhone client keeps Amazon's cookies by hand, so Apple's cookie jar is off for it): `Home` (the shared core), `PrinterService` and the home tools (`home_devices`, `home_control`, `printer_status`, camera and events) are wired in `createIosApp`, the command log is the shared Room table, and the More tab has a Smart home section drawn from each service's own sign-in form (`HomeServicesSection`), which will serve Meross, Ring, Alexa and Blink as they move. Control and cancel go through the shared confirmation cards. Not done: the Home and Printer pages (still Android screens), Jarvis's printer feed on the iPhone, the other services. |
| K4.10 | **built, tested against a pretend server only; never against real iCloud** | iCloud mail on the iPhone (the K4.8 account now has mail and calendar). The protocols are plain shared Kotlin over a small `MailTransport`: `ImapClient` (login, read-only `EXAMINE`, `UID SEARCH`, `UID FETCH` with `BODY.PEEK` so nothing is marked read, `LIST`, `APPEND`; literals read in full), `SmtpClient` (STARTTLS before the password, `AUTH PLAIN`, dot-stuffing, headers with no line breaks, a UTF-8 subject as an RFC 2047 word, the body as base64), `Mime` (encoded words, quoted-printable and base64, Latin and UTF-8 charsets, multipart, plain preferred over HTML, attachments skipped) and `ImapMailSource` (search, read, draft, send; ids `UIDVALIDITY:UID` as on Android). 20 shared tests drive them with a fake server speaking the real protocol, so they run on every platform. Only `NsStreamTransport` is iPhone code: Apple's streams with `NegotiatedSSL`, which checks the chain and host name against the system's trust store (not a hand-made TLS). **Riskiest unproven parts:** that transport on a real phone (the STARTTLS switch in mid-stream above all), and iCloud's actual replies. Search words with non-ASCII letters are matched on the headers afterwards, since the server is only asked in ASCII. |
| K4.11 | **built, not tried on a phone** | Conversation mode on the iPhone: the shared hands-free loop (ask, hear the answer, follow up without a second tap, cut in while Edith speaks) with a **Talk button** in place of the wake word, because the iPhone has no wake-word engine yet (D41's engine is a separate decision). `TapWakeListener` never touches the microphone; `HandsFree(wakeIsTap = true)` shows "Tap Talk, then speak" and the chat shows the Talk button (a "Cut in and talk" one while she speaks). The recognizer is Apple's (`IosSpeechInput`), the cue is the system's begin-recording tone, and the hearing icon on the chat bar turns it on and off. Confirmation cards still need a tap, as on Android. 2 shared UI tests (the button's call, and no button with a wake word). Not done: the wake word itself, and keeping it running with the screen locked or in the background. |
| K4.12 | **built, not tried against a real Ring** | Ring on the iPhone: `RingService` moved to the shared engine (its only JVM-isms were `UUID`, `@Volatile`, `AtomicBoolean` and a `ConcurrentHashMap`), behind a small `RingLive` seam for the live call (shared `RingCall` and `RingLive`; Android's `RingRtc`/`RtcCall` extend them and add the video track, so Android is unchanged and its 10 Ring tests pass). The iPhone made the service with **no live view** (K4.18 added it), so it offered snapshots, recent events and clips; the sign-in (email, password, 2FA code) is the More tab's generic form. Live view needs WebRTC on the iPhone (plan K4.9), the one unknown left. |
| K4.13 | **built; Android's 17 Blink tests pass against the shared code; not tried against a real Blink** | Blink on the iPhone. `BlinkApi`, `BlinkService`, `BlinkParse` and the stream protocol (`BlinkLive`) moved to the shared engine. The sign-in's cookie jar is Ktor's `HttpCookies` (one store for the redirect-following client and the redirect-by-hand one; Apple's own cookie jar is off for this client), the PKCE pair uses the shared `Sha256` and `RandomSource`, and the live stream is behind `BlinkLiveRunner` (Android's `AndroidBlinkLive` is the old TLS-socket `BlinkLiveStream`). Android keeps its IPv4-only route for Blink's 406 through `AndroidBlinkApi` (the shared class takes an optional IPv4 client); **the iPhone has no such route**, so a 406 (which NordVPN caused) is reported as it is: sign in with the VPN off. The iPhone service has **no live view**, only snapshots, events and clips. **Needs the owner:** the cookie-jar sign-in (a code is typed) against the real account, on the phone, since Ktor's `HttpCookies` over Apple's networking is the one part that cannot be checked here. |
| K4.14 | **built; tested against a scripted broker only; never against Meross** | Meross garage doors on the iPhone, without the MQTT library swap that had been waiting for a decision: a small **MQTT 3.1.1 client in shared Kotlin** (`MqttConnection`: CONNECT with user name and password, SUBSCRIBE at QoS 1, PUBLISH at QoS 0, receive publishes and answer QoS 1 ones, DISCONNECT) over the same TLS byte connection as iCloud mail (Apple's streams, which check the broker's certificate and host name), and `MqttMerossBus` on top of it (same conversation as Android's Paho bus: `app:<appId>` client id, `md5(userId + key)` password, signed answers only, one connection per operation). `MerossService` and `MerossBus` moved to shared code; Android keeps Paho as `PahoMerossBus` and its 6 Meross tests pass unchanged. 5 shared tests with a scripted broker (the broker checks the client id, user and password, a signed answer returns, a refused login means "sign in again", a bad signature is ignored, a device error shows its code, MQTT's length encoding). **Not proven:** that Meross's broker on port 443 accepts a plain MQTT 3.1.1 CONNECT over Apple's TLS as it does Paho's (it should: that is what Paho sends), and no keep-alive pings are sent, which is fine for a connection of a few seconds. |
| K4.16 | **built, not tried on a phone** | Camera alerts on the iPhone, under iOS's rules (the plan's option A): the same `AlertWatcher` Android's service runs, in `ForegroundAlerts`, looks every 45 s **while the app is open** and shows new alerts as cards over whatever tab is showing (`AlertBanners`, with a way to the clips and a Dismiss); nothing runs while the app is closed, because iOS suspends it. **While you were away** is a card at the top of the Home tab with what the cameras reported since the app was last opened (at most a day back, the kinds that are on, the newest 20), found once at launch from a saved "last opened" time (`AlertPrefs.lastOpened`, kept in the settings DataStore; the first launch has nothing to go back to). Settings → Camera alerts has a switch per kind for each connected service (Ring: doorbell, person, motion; Blink: motion), all off until switched on. 4 shared tests for the catch-up (the first opening, the kinds that are on, never more than a day, everything off) and 3 for the cards. **Not done:** alerts while the app is closed (needs a push from Jarvis, option B, a design with the owner). |
| K4.17 | **built; the shared parts tested, the iPhone parts never run** | Blink **live view** on the iPhone. Shared and tested (desktop): `BlinkStreamRunner` (the handshake, statistics and keep-alive, the 20 s quiet limit, the "closed early, try again" rule; Android's `BlinkLiveStream` logic written once over a `StreamTransport`, 5 tests with a fake connection), `TsSegmenter` and `HlsPlaylist` (cut the camera's MPEG-TS at PAT packets into 2-6 s HLS segments that each start with the PAT and PMT, and a sliding live playlist; segment lengths come from the wall clock, not the stream; 4 tests) and `HlsLiveSession` (a feed's video as a playlist and segments held in memory, 1 test). iPhone, compiled only: `NsStreamTransport` gained a no-certificate-check TLS option (for Blink's stream servers only) and a raw read; `IosLiveDialog` plays the session with `AVPlayerViewController` from `LocalHlsServer`, a small HTTP server on the phone's own 127.0.0.1 (the first try, an `AVAssetResourceLoaderDelegate` on a made-up `edithlive://` address, showed a crossed-out play icon on the owner's phone on 25 Nov: the player refused it), the dialog shows the player's error, what the camera sent and what the player asked for while this is proven, and `BlinkService` on the iPhone now has `live`, so Blink cameras offer Live. **Not proven:** that AVPlayer accepts this HLS (Blink's H.264 has no access-unit delimiters, which Android's player needed a flag for; Apple's HLS player may or may not cope), the no-check TLS settings key, and delay (a segment is at least 2 s, so the picture lags about 6 s or more). If AVPlayer will not play it, the fallback is a local HTTP server on 127.0.0.1 or converting frames ourselves. Ring's live view (WebRTC) is still not started. |
| K4.18 | **built; the shared steps tested, the Swift and the picture never run** | Ring **live view** on the iPhone. Shared and tested (desktop, 7 tests with a fake peer): `RingPeer` (a receive-only WebRTC connection: offer, accept Ring's answer, lost, close), `PeerRingLive` (the steps of Android's `WebRtcClient` written once: offer, Ring's answer, accept; the peer is closed when a step fails or the caller gives up) and `PeerLiveFeed`. iPhone, compiled only: Swift `RingRtc.swift` makes the connection with Google's WebRTC (the `stasel/WebRTC` Swift package, pinned to 158.0.0 in `iosApp/project.yml`): Ring's two STUN servers, video and audio receive-only (audio muted), candidates gathered for at most 3 s before the offer goes, and an `RTCMTLVideoView` shown in the Live dialog through `UIKitView`. Swift reaches Kotlin through callback interfaces in `ui/.../RtcSeams.kt` (`MainViewController(rtc:)` takes them; null: Ring has no live view). `NSCameraUsageDescription` added (the library references the camera; the app never uses it). **Not proven:** that the Swift compiles against the package (CI will say), that Ring's camera connects from a phone and a picture shows, that closing the dialog ends the call, whether the audio track really stays silent and does not take the audio session, and that App Store checks accept the added camera text. |
| K4.19 | **built; shared logic tested, the Swift never run** | Diagnostics for Ring's live view and the first stage of alerts while Edith is closed. (1) The Ring Live dialog now shows the connection's own words under the picture (connection and ICE state, candidates in the offer, whether Ring's answer was accepted, the picture's size once one arrives, and why it was lost), so a failure on a phone says which step stopped; `RingPeer.status()`, `SwiftRtcPeer.status()`. (2) `alerts/push/PushRegistration` (shared, 9 tests with a fake platform and a fake Jarvis): keeps Jarvis told of the phone's Apple push token and the alert kinds that are on (`ring:doorbell` form), once per change, unregisters when switched off or no kind is left, with the states Settings will show; `JarvisPushFeed` and `JarvisApi.postNoContent`. **Not wired into the app and not usable yet**: Jarvis's side (`jarvis-push-spec.md`) is not built, and the Swift (permission, token, entitlement) and the Settings row are the later stages of `ios-alerts-while-closed.md`. (3) The TestFlight workflow keeps its archive log and prints the first errors when the archive fails (the first failed build lost its compiler message to `tail -60`; its rerun passed, cause unknown). |
| K4.20 | **built; the shared parts tested, the iPhone parts never run** | Parity work (docs/design/kmp-parity-audit.md). (1) Crash reports on the iPhone (D58): `IosCrashReporter` writes `crash/last-crash.txt` from Kotlin's unhandled-exception hook (text from the shared `CrashReportText`, same layout as Android's, 2 tests) and keeps a running marker (written when Edith comes to the front, removed when she goes to the background) so a crash in Swift or a library, which the hook never sees, is told on the next launch with where the system's own crash log is; the shared `CrashBanner` shows it at the top, and Settings → About has "View last crash report". Unproven: that the hook fires and the file survives a real crash, and that Compose's start and stop match the phone's foreground and background (a force-quit from the app switcher may tell a false crash). (2) A reply keeps going after leaving the app (D54): the shared `ReplyKeepAlive` (4 tests) asks iOS for background time while a reply is being written (`beginBackgroundTask`) and gives it back at the end or when iOS says time is up; iOS lends about 30 s, so a very slow reply can still be cut off. (3) The audit itself: finding that Edith's file cards (View, Save, Share) are dead buttons on the iPhone, the rest as the audit lists. |
| K4.21 | **built; never tried on a phone** | Three controls the iPhone chat left unwired, found by the parity audit: View, Save and Share on Edith's file cards, a photo full screen, and play/stop on an audio attachment. `SimpleChatScreen` now passes them (a shared `FileExporter`, the `ImageViewer` and `FileViewer` dialogs, the playback toggle); `IosFileExporter` reads the file as text, opens the Files picker for Save and the share sheet for Share, each with a copy under the file's own name in an emptied temporary folder. Unproven: that the pickers present from the Compose screen, and that a file shared this way keeps its name. |
| K4.3b | **audio player and dictation built, not tried on a phone** | `IosAttachmentPlayer` (`AVAudioPlayer` on the playback session, silences Edith's voice first) plays a voice memo from its bubble. `IosSpeechInput` is dictation into the text field: `SFSpeechRecognizer` over the microphone, partial words as you speak, on the phone when the language supports it (otherwise Apple's servers, like the keyboard's own dictation), with the level meter fed from the same audio; it asks its own permission (`NSSpeechRecognitionUsageDescription` is in Info.plist, which iOS needs or ends the app). Voice memos keep recording and letting the model transcribe. Not done: audio files from the Files app, hands-free, reading replies aloud by themselves (needs a setting). |
| K4.3c | **built, not tried on a phone** | Replies can be read aloud by themselves on the iPhone: `SpeechOutput` is now a reply listener, and a "Read replies aloud" switch in the new More tab turns it on. **It starts off** on the iPhone (the settings store takes per-platform defaults, `DataStoreSettingsRepository(defaults = ...)`; Android's default stays on), so nothing speaks until the owner turns it on. |
| K4.3d | **built, not tried on a phone** | Audio files from the Files app can be attached on the iPhone: the paperclip becomes a menu (Photo, Audio file). `IosAudioPicker` is the Files document picker, set to hand over a copy. WAV and MP3 are kept as they are; anything else the system can decode (an iPhone voice memo's .m4a) is converted to 16 kHz mono WAV with `AVAudioFile` and `AVAudioConverter` (`AudioToWav`), at most 5 minutes, as on Android. The conversion is the riskiest part: it has never run. |
| K4.3 (speech) | **built, not yet tried on a phone: the Play button speaks** | `IosTtsEngine` (Apple's `AVSpeechSynthesizer`, no permission) is the iPhone's `TtsEngine`: the audio session is `playback` with `spokenAudio` and ducking, so another app lowers instead of stopping, and it is let go when Edith is quiet; a call, Siri or an alarm ends the speech. Rate 1.0 maps to Apple's default speed. `SpeechOutput` (shared) drives it, and the Play and Stop buttons on a message use it. **Not a reply listener yet:** nothing reads replies aloud by itself on the iPhone until there is a setting for it (the saved setting defaults to on, and a phone that suddenly talks, past the silent switch, is a worse surprise than a Play button). Written without a Mac: CI compiles it; the first real run is the owner's phone. **Not done:** voice memos (`AVAudioRecorder`), audio files, dictation, hands-free, the audio player for attachments. |
| K4.3 (voice memos) | **built, not yet tried on a phone** | The record button on the iPhone: `IosRecorder` (`AVAudioRecorder`, 16 kHz mono 16-bit WAV, the level meter polled ten times a second, the audio session released after) feeds the shared `VoiceMemos`, which has the model transcribe it (`OllamaTranscriber`) and sends the text as a voice-memo message. The microphone question is asked once through a new `MicrophoneAccess` seam (`IosMicrophoneAccess`, with a way to Settings after a refusal), and `NSMicrophoneUsageDescription` is in the app's `Info.plist` settings: without that key iOS ends the app the moment it asks. Dictation and hands-free are off (`NoSpeechInput`; Apple's `SFSpeechRecognizer` has a permission of its own). Written without a Mac: CI compiles it; the first real run is the owner's phone (the permission question, the meter, the transcript). **Not done:** dictation, hands-free, audio files, the attachment audio player. |
| K3.3 | Home tab and device cards, camera views (clips via `ClipPlayer` seam) | L |
| K3.4 | Printer page (Status, cards, Controls, confirm sheet, macros, console) | L |
| K3.5 | Accounts, calendar, mail, to-do screens | L |
| K3.6 | Navigation to the multiplatform library; Android `MainActivity` now hosts the shared `App()` | M |

**Phase 3 progress (2026-11-24)**

| WP | State | What was found |
|---|---|---|
| K3.0 | **spike done on Android, the desktop and the iOS simulator** | A new module `ui/` (Compose Multiplatform 1.12.2 with the Android library plugin `com.android.kotlin.multiplatform.library`, the same plugin `shared/` uses) builds for Android, the desktop and both iPhone targets, and `app/` depends on it. First shared screen part: `AboutSection` (Settings → About, same words and layout; `SettingsScreen` now calls it) and `EdithTheme` (moved with `git mv` from `app/`, same package, so no import changed). **Answers.** U13 (does Compose Multiplatform coexist with the Android library plugin and `app/`'s own Compose BOM 2026.09.00 and navigation 2.10.2): yes, with no duplicate classes; the debug APK grew by 4.9 KB (101,513,893 to 101,518,861 bytes); `scripts/check.sh` green (lint: 0 errors, 1 old warning). U8 (Compose UI tests on the desktop): `runComposeUiTest` works, 3 tests pass on the desktop; no Android host test for `ui/` (it would need Robolectric). `./gradlew :ui:run` opens a desktop window (ran 90 s without an exception). **Found: a D8 race on this PC.** After the change, dexing `material-icons-extended` failed three times with a different exception each time (`ArrayStoreException`, `NoSuchElementException`, an `ArrayList` null): the PC has 32 cores, and the same build passes with `-XX:ActiveProcessorCount=4` (also in `JAVA_TOOL_OPTIONS` for `check.sh`). Master builds because that dex is in Gradle's cache; CI (few cores) is not affected. If an Android build of this PC fails while dexing with an odd exception, rerun it with that flag. **iPhone:** the macOS job built `ui/` for the simulator and ran the 3 UI tests there (U8 holds on iOS too: `runComposeUiTest` works on the simulator); the whole job took 6 min. Two earlier attempts failed with GitHub's "not acquired by Runner" capacity error, not code. **D50 (UI module):** a separate `ui` module works; recommended answer stands. |
| K3.1 | **first slice built (desktop and Android proven; the iPhone result is in its PR)** | Moved into `ui/` with `git mv`, same packages: `Markdown.kt` (`renderMarkdown`, `parseBlocks`; `Block` and `parseBlocks` made public, and `ChatComponents` reads `block.marker` into a local because a smart cast across modules is refused), `CrashBanner` with `CrashReportDialog` (the copy button uses `LocalClipboardManager`: Compose Multiplatform has no common `ClipEntry.withPlainText`), and the `VoiceBanner` composable (its view model stays in `app/`). New: `ComponentGallery` (every component in light and dark; the desktop window shows it, replacing `@Preview`), and tests that run on the desktop and the iPhone simulator: the 3 Markdown tests (moved from `ChatFormattingTest`), `everyClickableControlInTheGalleryHasAName` (parity plan H3), `textColoursAreReadableInLightAndDark` (WCAG contrast 4.5 or more for six colour pairs per theme, H4), and `IconsTest`: **all 31 Material icons the Android app uses exist in Compose Multiplatform's icon set** (1.7.3; the parity plan's "28" counted names, not icons). Desktop: 9 UI tests pass. Android: `scripts/check.sh` green, debug APK 101,528,060 bytes (master 101,513,893). **Not in this slice:** the bubble, card, sheet and dialog components still live in `ChatComponents` and move with the chat screen (K3.2, where they have their users); the simulator screenshot step (needs the iOS app host, K4.1); the large-font-scale check. |
| K3.2 | **first slice built (the chat pieces; not the screen or the view model)** | Moved into `ui/` (same package `com.edith.app.chat.ui`): `ChatUiState`, `ChatComponents` (message bubbles, in-flight bubble, confirmation card, input row, recording controls, error line) and `AttachmentViews` (chips, thumbnails, file cards, image and file viewers). `java.io.File` left the UI state: `AttachmentUi.file` and `PendingUi.file` are now `path: String?`, shown through Coil as a `file://` address (Coil 3 is multiplatform; `ui` takes `coil-compose`); `ImageViewer` and `onOpenImage` take a path. The Native-safe compile found two things the JVM accepted: `String.format` (now `minutesAndSeconds`) and `System.currentTimeMillis` (now `SystemClock`). The gallery now shows two message bubbles and the input row, so the name and contrast tests cover them. 3 new tests (durations); desktop: 12 UI tests; `scripts/check.sh` green; debug APK 101,531,436 bytes. **Not moved, and why:** `ChatScreen` (606 lines) uses activity-result launchers, `Context`, permission checks, `HandsFreeService`, `ReplyService` and `FileExport`; `ChatViewModel` (546) holds `AttachmentIntake`, `AttachmentPlayer`, `LiveSpeech`, `VoiceMemos`, `HandsFree` and `SpeechController`. They need Phase 2b first (K2.9 voice logic, K2.10 attachment platform interfaces, K2.12 the container split); this is the order the parity plan gives. The conversation list and the core settings screens are next. |
| K3.3 | **Home tab shared and on the iPhone; built, not tried on a phone** | The Home tab moved to `ui/commonMain` (same package `com.edith.app.home.ui`): `HomeViewModel`, `HomeUi`, `HomeChip`, `HomeContent` (chips, device tiles, controls, the lock section and its confirm dialog, the printer tile, the camera picture dialog), the clips list and `LiveUi`. What only Android can show sits behind one seam, `HomeOverlays`: `clipPlayer` (Android: ExoPlayer in `ClipPlayer.kt`) and `live` (Android: WebRTC and the MPEG-TS player in `LiveView.kt`). On the iPhone the defaults apply: a clip says it cannot be played here yet, and there is no live view (no iPhone service makes one). Android keeps `HomeScreen` as a thin wrapper (its view model factory, the camera alert's tap and stopping a live view when the app leaves), and `HomeViewModel.tabActions()` is the one place the actions are wired, for both. The camera picture uses Coil (already shared) instead of Android's `BitmapFactory`. The iPhone gets a **Home** tab between Chat and Setup. The Android instrumented `HomeScreenTest` became a shared `HomeContentTest` that runs on the desktop and the iPhone simulator. **Clips on the iPhone (K3.7):** Apple's player (`IosClipPlayer`, `AVPlayerViewController` over an `AVPlayer`, with the service's sign-in headers for Blink's clips) fills the `clipPlayer` seam; it is built but has never played a real clip. |
| K3.4 | **Printer page shared and on the iPhone; built, not tried on a phone** | The Printer page moved to `ui/commonMain` (`com.edith.app.printer.ui`): `PrinterContent`, the six states, the Status, Temperature, Limits, Mesh, Files, Macros, Console and other cards, the confirm sheet, the Controls bar, the view model and its two data holders (`PrinterCardData`, `PrinterControl`). `PrinterFeed` (finds the paired Jarvis) moved to the shared engine. JVM-only code was replaced: `String.format` with the shared `formatDecimal`, `Base64` and `BitmapFactory` with Kotlin's Base64 and the new `decodeImageBitmap` (an `expect` in `ui` with Android's, the desktop's and the iPhone's actuals), `toColorInt` with a small `hexColor`, `UUID` and the clock with their Kotlin versions, and the upload's `InputStream` with kotlinx.io's `Source`. Android keeps a thin `PrinterScreen` wrapper (its view model factory, the lifecycle, the Files picker for .gcode and the 12/24-hour setting), and `PrinterViewModel.pageActions()` wires the actions for every platform. On the iPhone the Home tab's printer tile opens the page (Details, when a Jarvis is paired) and `printer_control` is a chat tool behind confirmation cards. The 63 instrumented page tests became shared tests that run on the desktop and the iPhone simulator (one asserted a card below the fold, which a test window does not draw, so it now checks the first card); Android's own unit tests are unchanged. **K3.7 closes the two gaps:** Upload opens the Files picker for any file (`IosFilePicker`, a copy; the file is read when Send is pressed, through kotlinx.io), and the clock follows the phone's 12/24-hour setting. Neither has run on a phone. |
| K3.5 | **Calendar and to-dos shared and on the iPhone; built, not tried on a phone** | The Calendar tab moved to `ui/commonMain` (`com.edith.app.accounts.ui`): `CalendarViewModel`, `CalendarContent` (the agenda by day, event details, the per-account labels and the banner for an account that cannot be read), the **Agenda | To-do** frame (`CalendarTabFrame`), `TodoViewModel` and `TodoContent` (sections, ticking with a 5-second Undo, Show done, and the grant banner). The to-do view model's Android types became the shared `PlatformActivity`, `PlatformPendingIntent` and `PlatformIntent` (real Android types on Android, so nothing there changed). Android keeps thin wrappers (its view model factories, and Google's consent screen for **To-dos: Grant access**); on the iPhone, Grant access signs in again in the system sheet, which asks for every scope. The iPhone gets a **Calendar** tab (Chat, Home, Calendar, Setup, More). The instrumented Calendar and To-do tests became shared tests (desktop and simulator); the one instrumented test that needed a shared constant keeps its own copy of it, because the instrumented tests do not see the shared screens. There is no mail screen on Android either (mail goes through Edith), so none was moved. |
| K3.6 | **Settings shared and one page on the iPhone; built, not tried on a phone** | The Settings page's shared part moved to `ui/commonMain`: `SettingsViewModel` and `SettingsContent` (the Ollama address, Test connection, the model picker with what each model can do, the local-network card, the About section; the app version is a parameter instead of Android's `BuildConfig`), and the **Attachments** and **Web search** sections with their view models (`String.format` became the shared `formatDecimal`). Android's `SettingsScreen` is now a thin wrapper that adds what only Android has: the local-network permission request, Voice (its two voices and Edith's own voice download), Hands-free (the foreground service), Accounts (Google's consent screens), Home settings and the crash report; its screen is unchanged. **On the iPhone, one Settings tab replaces the Setup and More tabs** (now Chat, Home, Calendar, Settings): the shared server-and-model section on top, then **Voice** (read replies aloud, speed, test; the voice itself is the one chosen in the phone's Accessibility settings), Attachments, Web search, Accounts (Google, Microsoft, iCloud, the work account), the smart-home services, and a button for the engine check; About is at the bottom. 4 shared tests cover the server section (the address and model line, Test connection, a failed model list's Retry, the local-network card). **Not on the iPhone:** the crash report (there is none to show yet) and hands-free settings (conversation mode has none). |

**Gate G3 (owner):** the Android app, now drawing shared screens, is used for a week. This is the highest-risk step for Android; it can be done screen by screen behind a switch if needed.

### Phase 4 — The iPhone app on shared code

| WP | Work | Owner checks |
|---|---|---|
| K4.1 | `iosApp/` for real: bundle id (D43), `project.yml`, `Info.plist` keys (local network, microphone, photos, location), app icon, launch screen, TestFlight pipeline from the spike | the app installs from TestFlight |
| K4.2 | Chat on iOS: shared engine and screens, Keychain, settings, the Ollama address and models | chat streams on the phone over home Wi-Fi |
| K4.3 | Attachments and voice memos (PHPicker, `AudioRecorder`), spoken replies (`Speaker`) | photo, voice memo, spoken reply |
| K4.4 | Home services on the home network: printer card (direct), Meross, Ring, Blink, Alexa (WebView seam), clips | each service with the real device |
| K4.5 | **Jarvis and the printer page**: pairing, the feed, Controls, `printer_control`. With Ktor's Darwin engine, certificate pinning of Jarvis's self-signed certificate may be possible natively, which would make the second Tailscale listener optional (§14 U10) | pair and use the printer |
| K4.6 | Accounts: Google and Microsoft sign-in (`ASWebAuthenticationSession`), calendar, mail, to-dos | each account |
| K4.7 | iCloud mail (IMAP/SMTP, §14 U6), Meross cloud (MQTT, §14 U7) | |
| K4.8 | Conversation mode, wake word while the app is open (D41), alerts catch-up | microphone checks on a real phone |
| K4.9 | Live view (Blink stream, Ring WebRTC) and camera wall | |

**Gate G4 (owner):** the parity table (`ios-parity.md`) is walked on a real phone.

### Phase 5 — Parity and retirement

| WP | Work |
|---|---|
| K5.1 | Run the parity table against the new iPhone app; every row is "same", "better" or an owner-accepted difference |
| K5.2 | Archive `ios-app/` (delete it from `master`; the history keeps it); move its useful fixtures and docs; delete `spike-kmp/` |
| K5.3 | Docs: `AGENTS.md`, `README`, `01-architecture.md` rewritten for the shared layout; chunks C58–C76 marked superseded (§11.3) |
| K5.4 | Optional clean-ups: split `shared` into modules if builds are slow (D42); consider a Compose Desktop build for the owner's PC |

## 8. Build, CI and release

**Workflows**
- `ci.yml` (existing, Linux): Android build, unit tests, lint. Gains the desktop JVM tests of `shared`.
- `shared-ios.yml` (new, macOS, path-filtered): iOS simulator tests, framework link, `xcodebuild` for the simulator without signing, screenshot, timings in the job summary.
- `ios-testflight.yml` (new, macOS, manual): archive, sign with the App Store Connect API key (cloud-managed signing), upload to TestFlight, build number from `github.run_number`.
- `ios-app.yml` (existing): the Expo tests, until retirement.

**Cost.** On a public repository GitHub's standard runners, macOS included, are free (to re-verify, K0.8). Even so: path filters, `concurrency: cancel-in-progress`, Gradle build cache and the Kotlin/Native
cache (`~/.konan`), and no iOS job for Android-only changes. In the spike an iOS build took about 18 minutes cold and about 6 minutes warm (§3.3 runs 11-12).

**Secrets.** Three secrets and two variables are needed for TestFlight (`spike-kmp/DEVICE_BUILD.md` becomes `docs/ios-build-setup.md`). The owner types them into GitHub's settings page. They are never in the repo, chat, or logs; the workflow writes the key to a temp file and deletes it.

**Releases.** Android releases stay as they are (`scripts/release.sh`, then `scripts/publish.sh` after the owner's OK). iPhone builds go to TestFlight first; an App Store release is a separate owner decision (C75's territory).

## 9. Developer loop

- **Shared logic**: desktop JVM tests (seconds) and the Android emulator.
- **Shared UI**: the desktop window on Windows (and the Android emulator). Most visual work never needs an iPhone.
- **iPhone-only behaviour**: a PR touching `shared/**` or `iosApp/**` gets the macOS job (about 10 minutes); the screenshot and the iOS tests come back in the PR. A real phone is TestFlight.
- **Habits learned in the spike**: pipe nothing that hides a failing exit code (`set -o pipefail`); commit scripts with the executable bit; use `channelFlow` when a producer isn't the collector's coroutine; build simulator apps for `arm64` only;
  import Apple symbols from the module that declares them (`platform.Foundation` for the bridging functions).

## 10. Risks and mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Android regresses during the moves | medium | principle 1; moves are tiny and test-protected (about 18,000 lines of Android tests); gates G1–G3 are owner trials; every PR keeps `check.sh` green |
| The Compose Multiplatform iOS text input, selection or VoiceOver is not good enough | medium | tested at G0 and again in K3.2; fallback D41: native SwiftUI screens over the shared engine |
| iPhone-only bugs (threads, flows, time, locale) found late | high | the iOS simulator test run on every shared change; the spike rule in §4.6 |
| A library has no iOS version (IMAP/SMTP, MQTT, WebRTC) | high | seams from the start; Swift implementations; research in K0.6; iCloud mail and Meross cloud come last (K4.7) |
| Kotlin/Native and Compose build times grow with the code | medium | caches, path filters, optional module split (D42), the desktop loop for most work |
| Voice: background audio and wake word on iOS | medium | wake word only while the app is open (D41, as C69); everything else foreground |
| Version churn (Kotlin, AGP, Compose Multiplatform, Ktor) | medium | versions pinned in one catalog; upgrade as its own PR with the iOS job green; the spike's tested combination is the baseline |
| Apple rules for TestFlight or App Store (privacy strings, review) | low–medium | honest permission strings (as in `app.json` today); TestFlight needs no review for internal testers |
| The Expo app drifts while the new one is built | medium | freeze policy (§11.2) and the parity table as the single target |
| Doing the migration slows new features | certain | accepted by the owner; new Android features during Phases 1–3 are written in the new structure when they touch a moved package |
| The owner's attention: many owner-only steps | medium | every WP lists them; Apple, GitHub and device steps are batched |

## 11. The Expo app, the docs, and the old chunk map

### 11.1 Why keep it

It runs today in Expo Go, it has about 860 passing tests, and the parity table was built from it. It is the iPhone app and the behavioural reference until G4.

### 11.2 Freeze policy (from G0 onward)

Bug fixes and security fixes only; no new features. Owner-visible checks that were pending (Jarvis pairing, printer checks) may still be done on it. If the transition stops at G0, the freeze lifts.

### 11.3 What happens to the planned iOS chunks

| Old chunk | Fate |
|---|---|
| C58 development build (EAS) | superseded by K4.1 (Xcode shell, signing, TestFlight); the Expo dev-build prep is not needed |
| C59–C65 accounts, calendar, mail, to-dos, Microsoft, iCloud | become K4.6 and K4.7, built once in shared code (Android's versions move, not rewritten) |
| C66a/C66b Jarvis connection and work account | K2.4 and K4.5 (the TypeScript client is the behavioural reference) |
| C67 printer through Jarvis | already built twice (Kotlin, TypeScript): K2.4 and K3.4 move the Kotlin version; the TypeScript tests are the acceptance reference |
| C68 audio files, C69 wake word, C70 Meross cloud, C71 live view, C72 alerts, C73 Resideo, C74, C75 standalone builds | K4.3, K4.8, K4.7, K4.9, K4.8, later, done, K4.1 and a later owner decision |
| C76 parity audit | becomes K5.1; `ios-parity.md` is its checklist |

### 11.4 Documentation work (K5.3)

`AGENTS.md`, the architecture doc, `ios-roadmap.md` (archived), `05-environment.md` (new build commands, no EAS), the decisions file (D40–D47 recorded).

## 12. Decisions for the owner

Ask in the PR that needs each; recommended answer first.

| ID | Question | Recommended | Needed by |
|---|---|---|---|
| D40 | Move to shared code (this plan)? | **Yes, as phases with gates**; the go/no-go is G0. **G0: GO, owner, 2026-11-23.** | G0 |
| D41 | iPhone UI toolkit and platform limits: Compose Multiplatform screens everywhere; wake word only while the app is open | **Compose Multiplatform**; fall back to native screens only if K0/K3.2 checks fail | K0.5, K3.2 |
| D42 | One `shared` module, or split it? | **One at first**; split only if builds are slow | K1.1, K5.4 |
| D43 | iPhone bundle identifier | **Decided 2026-11-21: `com.edith.app`, the same as the Android release.** The Phase 0 device test uses `com.edith.app.spike`, so the real identifier stays unused until K4.1 (an identifier cannot be changed once an app ships). Still to check: that Apple accepts `com.edith.app` (identifiers are unique across all developers; if it is taken, fall back to a variant such as `com.edith.app.ios`). | K0.2 |
| D44 | Minimum iPhone OS version | **iOS 17** (recent Compose Multiplatform and SwiftUI features, and the owner's phone is current) | K4.1 |
| D45 | iCloud mail on iPhone (IMAP/SMTP has no iOS-ready library in our stack) | research in K0.6; defer to last (K4.7) | K4.7 |
| D46 | Freeze the Expo app at G0 (§11.2)? | **Yes. In force from 2026-11-23.** | G0 |
| D47 | License for the public repository | the owner decides (for example MIT, or keep all rights reserved) | any time |
| D48 | Licensing rule for dependencies | **Permissive licences only (MIT, Apache 2.0, BSD).** A GPL library cannot go into an app that is distributed through TestFlight or the App Store without making the whole app GPL. K0.6 found one (MQTTastic Client KMP, GPL-3.0) and set it aside. Check the licence of every new dependency in its PR. | K1 onward |
| D49 | How the iPhone app reaches Ollama over plain HTTP (App Transport Security) | The spike needs `NSAllowsArbitraryLoads` alone, which lets the app talk plain HTTP to any host. Before release (K4.5) decide: keep it (simplest, Apple may ask why), or serve Ollama over HTTPS (for example `tailscale serve`) and drop the exemption. | K4.5 |
| D49 decided | **Decided by the owner, 2026-11-26: Tailscale name plus an exception.** `NSAllowsArbitraryLoads` is gone; `iosApp/project.yml` has `NSAllowsLocalNetworking` (the home network) and an exception for `ts.net` names, and everything else is https only. The app refuses an `http://` address that App Transport Security would block (`AtsPlainHttpRule`, with the way out: "use the PC's Tailscale name"), so an address typed as a Tailscale number (100.x) is refused when saved instead of failing later; **an address already saved that way will stop working until it is changed to the `.ts.net` name**. The page reader can no longer open plain-http pages (https ones work). Not tried on a phone: that a `.ts.net` name is allowed over plain http with this plist. | K5 |

## 13. Acceptance for the whole transition

- Android: the existing checks (`scripts/check.sh`, device checks) pass on a build whose screens and engine come from `shared/`.
- Shared: the shared tests pass on the desktop JVM, on Android, and on an iOS simulator in CI.
- iPhone: installed from TestFlight on the owner's phone; every row of `ios-parity.md` is "same", "better" or an owner-accepted difference; the owner walked the Wave-A checks again on the new app (chat over Wi-Fi, photo, voice memo, spoken reply, a home service, the printer through Jarvis, an account).
- No `android.*`, `java.*`, `okhttp3` or Apple import in `commonMain` (the lint test).
- The Expo app is archived, the docs describe the shared layout, and no personal data is in the public repository.

## 14. Unverified, and how each gets checked

| # | Question | Checked in |
|---|---|---|
| U1 | Minimum Gradle version and plugin compatibility of `com.android.kotlin.multiplatform.library` with this Gradle 9.8.2 / AGP 9.5.0 / Kotlin 2.4.21; the third-party Gradle plugins used in `app/` (KSP, Room) with the library plugin | K1.1 |
| U2 | Whether Compose Multiplatform 1.12.2 is the right version for Kotlin 2.4.21 for everything we need (the spike compiled and ran it) | K0.5 |
| U3 | The multiplatform navigation and ViewModel library versions that match Compose Multiplatform 1.12 | K0.5 |
| U4 | Room KMP opens the existing Android `edith.db` unchanged (schema, identity hash, migrations) on JVM and iOS | K0.4, K1.5 |
| U5 | sherpa-onnx and ONNX Runtime on iOS: size, speed, build path | K0.6 |
| U6 | IMAP/SMTP on iPhone: Jakarta Mail is JVM-only. Options: a KMP mail library, a Swift/C library behind a seam, or defer iCloud mail on iPhone | K0.6, K4.7 |
| U7 | MQTT over TLS for Meross cloud on iPhone: a KMP library or a Swift one | K0.6, K4.7 |
| U8 | Compose UI tests on the desktop target (the multiplatform test API's status) | K0.5, K3.1 |
| U9 | GitHub's macOS runner minutes are free for this public repository | K0.8 (first free run) |
| U10 | Ktor Darwin engine: pinning a self-signed certificate (Jarvis) natively, and plain HTTP to LAN addresses under App Transport Security | K0.3, K4.5 |
| U11 | The microphone and `AVAudioSession` behaviour on a real phone and under interruptions (calls, Siri) | K0.1, K4.3 |
| U12 | App Store and TestFlight requirements for this app's permission strings and background modes | K4.1 |

### Status of the unverified items (2026-11-21)

| # | Status |
|---|---|
| U2 | Holds for what was tried: Compose Multiplatform 1.12.2 with Kotlin 2.4.21 compiled and ran Material 3, navigation, ViewModel, Coil and icons on an iOS simulator. |
| U3 | Answered: JetBrains navigation 2.10.0 and lifecycle-viewmodel 2.11.0 work with Compose Multiplatform 1.12.2. They match the generation of the Android app's navigation 2.10.2 and lifecycle 2.11.0. |
| U4 | Half: Room KMP generated and ran on the desktop and on iOS with the bundled driver. Whether the real Android `edith.db` opens unchanged is still open (K1.5). |
| U8 | Not tried. |
| U9 | Partly: runs on the public repository were not refused for billing; billing page to confirm. |
| U11 | Partly: the microphone and `AVAudioSession` setup work on a simulator; a real phone, interruptions and a call are not tried. |
| U5 | Researched, not proven (K0.6): sherpa-onnx ships an iOS XCFramework build and a Swift API; the one-copy-of-ONNX-Runtime question is open. |
| U6 | Researched, not proven: SwiftMail is the best Swift option found (about 1,160 lines of Android mail code only use a small part of IMAP/SMTP); the fallback is to defer. |
| U7 | **Mostly answered**: DitchOoM mqtt (MIT) does a TLS round trip on an iOS simulator. Still open: username and password, and Meross's own broker. |
| U10 | **Plain HTTP answered**: Ktor's Darwin engine streams plain HTTP to a Tailscale address once `NSAllowsArbitraryLoads` stands alone in App Transport Security (see "Device findings"). Pinning a self-signed certificate (Jarvis) is not tried. |
| U1, U12 | Not started. |

## 15. How to start

1. The owner reads this plan and answers D40, D43, D46 (and D47 when convenient).
2. Open the first PR: this document (already done if you are reading it on `master`).
3. Start Phase 0 with K0.1 (push the spike, run the microphone probe), then K0.2 with the owner's Apple setup.
4. Record every result in §3.3 and §14 in the same PRs; stop at G0 and ask.
