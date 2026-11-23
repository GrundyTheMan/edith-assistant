# The shared-code transition: one Kotlin codebase for Android and iPhone (decision D40)

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

**Gate G1 (owner).** Android is used for a few days on the new build with no regression; the tests listed in K1.1–K1.6 pass in CI.

### Phase 2 — Move the engine

| WP | Work | Size |
|---|---|---|
| K2.1 | `chat` (engine, prompt builder, context, streaming, cancel/regenerate) to common; tests moved; the chat UI stays in `app/` for now | L |
| K2.2 | `tools` framework and the tools (weather, time, web search, page reader with ksoup, location seam), tool loop, confirmation cards | L |
| K2.3 | `attachments` (images, audio files, text/PDF extraction), `Picker` and `AttachmentFiles` seams | L |
| K2.4 | `jarvis` client and pairing, `printer/feed` logic (wire types, 33 commands, session, settle), `home` framework (Home, services, `HomeBackend`) | L |

After K2.4 the iPhone app can run the whole engine. **Gate G2:** the shared engine passes its tests on desktop, Android and the iOS simulator, and the Android app is unchanged to use.

### Phase 3 — Shared screens

| WP | Work | Size |
|---|---|---|
| K3.1 | Theme, typography, shared components (bubbles, cards, sheets) into `commonMain`; desktop window entry to view them on Windows | M |
| K3.2 | Chat screen, conversation list, settings screens | L |
| K3.3 | Home tab and device cards, camera views (clips via `ClipPlayer` seam) | L |
| K3.4 | Printer page (Status, cards, Controls, confirm sheet, macros, console) | L |
| K3.5 | Accounts, calendar, mail, to-do screens | L |
| K3.6 | Navigation to the multiplatform library; Android `MainActivity` now hosts the shared `App()` | M |

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
