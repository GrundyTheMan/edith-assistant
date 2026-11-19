# C40 — iOS feasibility spike (Kotlin Multiplatform on a cloud Mac)

| | |
|---|---|
| Phase | 9 — iOS (exploratory) |
| Depends on | C03 (Ollama client), C05 (Room), C06 (chat engine), C37 (`httpClients`: Ollama is the plain-HTTP exception) |
| Unblocks | A go / no-go decision on a native iOS Edith (D27) |
| Size | Medium–large; **time-boxed by a CI-minute budget** |
| Read first | 06-decisions **D27**; 00-product §3 (non-goals) and §4.2; C03, C05, C06 (spec **and** As built); 01-architecture §1, §2, §5 |

## Why

On 2026-11-19 the owner asked whether Edith can also be built as an iOS project. Today she runs on their iPhone
inside **Andarium**, an Android environment for iOS, and that works (C17b, C35, C36 were checked there). A real iOS
port has two parts, and only one is known:
- **Known:** it's a large project. About a third of Edith's files use Android APIs, and several libraries are
  Android- or JVM-only. See "What we know" below.
- **Unknown:** whether Kotlin Multiplatform with Compose Multiplatform handles the parts that *should* move easily,
  with our versions, in our repo, built from a Windows-only owner's workflow. That's what this spike answers,
  cheaply, before anyone commits (D27).

**This spike changes nothing in the Android app and ships nothing to the owner.** Its product is a report.

## What we know (measured on `master`, 2026-11-19)

215 Kotlin files, about 35,500 lines in `app/src/main`.
- **Portable as they are:** 37 files use Compose (Compose Multiplatform has been stable on iOS since 1.8.0, May
  2025), 39 use kotlinx.serialization, 112 use coroutines.
- **Need replacing:** 59 files import `android.*`; 28 import OkHttp (Android/JVM only, so Ktor); 117 import `java.*`
  (`java.time`, `java.util.concurrent`, `java.io`: not in common code).
- **Library gaps:** jsoup, Jakarta Mail, Paho MQTT, commons-compress, media3, stream-webrtc, ONNX Runtime *Android*,
  Play services and Credential Manager are JVM/Android-only.
- **The best candidates to move first**, by what they import:

  | Part | Files | Lines | What blocks it |
  |---|---|---|---|
  | `ollama/` (client, DTOs, model catalog) | 3 | ~375 | OkHttp in `OllamaClient`; `java.io`, `SocketTimeoutException`, `AtomicBoolean`, `ConcurrentHashMap` |
  | `chat/engine/` (prompt, context, turn runner, engine, titler) | 5 | ~620 | `java.time` in `PromptBuilder`; `AtomicBoolean`, `Collections` |
  | `data/` (Room entities, DAOs, database, repository) | 7 | — | Room's Android API; hand-written migrations |

- **iOS limits that no tool fixes** (from the owner's agent's knowledge, not re-checked here): third-party apps can't
  listen for a wake word in the background, so hands-free would still need the app on screen, as in Andarium. There
  are no foreground services, so camera alerts (C33) would need push notifications, which need a server.
- **Building needs macOS and Xcode.** The owner's PC is Windows, and Apple's licence forbids macOS in a VM on
  non-Apple hardware. So builds run on a **cloud Mac runner** (GitHub's macOS runners, 10× minutes on a private
  repo; or another service). A free Apple account is enough for the simulator; a device or TestFlight needs more
  (later).

## Before you start — owner questions (ask; record the answers in D27's notes)

1. **Do you have, or can you borrow, a Mac?** Without one, the spike is verified only by CI on a simulator (the
   plan below). With one, the same project can also be run on a simulator and an iPhone by hand.
2. **CI minute budget.** Which GitHub plan is the repo on, and what is the most **macOS minutes** the spike may use?
   (GitHub bills macOS at 10×: a free plan's 2,000 minutes is about 200 macOS minutes.) The default is **at most 100
   macOS minutes in total**. Stop and ask at 80%. Another service (Codemagic has a free macOS allowance) is an
   option, but **the owner makes any new account**.
3. Nothing else is needed from Apple for the spike: simulator builds are unsigned. No Apple Developer account.

## Specification

### 0. Rules of the spike
- **`app/` is not edited.** New Gradle modules only (`shared/`, and an Xcode project in `iosApp/`). Nothing in
  `app/` depends on them. `scripts/check.sh`, `scripts/release.sh` and the existing CI job behave as before, and
  `scripts/check.sh` must still pass.
- **Copy, don't move.** Code is copied into `shared/` and adapted. The originals stay. Record each file's changes in
  As built (what was replaced, how many lines changed).
- **Stop at the first blocker in each part**, write down exactly why, and go on to the next part. A blocked part is
  a result, not a failure.
- **Budget:** the owner's macOS-minute cap (above). Log the minutes each run used.

### 1. The shared module (`shared/`)
- A Kotlin Multiplatform library with targets **`jvm()`**, **`iosArm64()`** and **`iosSimulatorArm64()`**.
  - `jvm()` is there so the common tests run **on the owner's Windows PC** (`:shared:jvmTest`), where the iOS
    targets can't be built. Gradle disables the iOS targets on Windows with a warning. The module must still
    configure and `:shared:jvmTest` must pass there.
  - **No `androidTarget` in the spike.** AGP 9.5 changed how Android is added to a KMP module (a separate
    Android-KMP plugin). The follow-up chunk decides that; the spike must not depend on it. Check the current
    guidance before choosing plugins.
- Versions follow `gradle/libs.versions.toml` (Kotlin 2.4.21, coroutines, kotlinx.serialization). Add to the version
  catalog: Ktor (client core, `Darwin` engine for iOS, `MockEngine` for tests), kotlinx-datetime, atomicfu or a
  `Mutex` for the `AtomicBoolean` / `ConcurrentHashMap` uses, Room (KMP) with the **bundled SQLite driver**, and
  the Compose Multiplatform plugin at a version **compatible with Kotlin 2.4.21** (check JetBrains' compatibility
  table; if none exists yet, that's the first finding).
- Logging: a tiny `Log` interface in common code (the app's `core/log` isn't portable).

### 2. What to port, in this order
1. **`ollama/OllamaDtos.kt`, `ModelCatalog.kt`**: serialization is already portable.
2. **`ollama/OllamaClient.kt`**: OkHttp → Ktor. **Same public API**, so the chat engine ports unchanged.
   - Streaming chat is NDJSON read **line by line as it arrives**, with cancel, and with connect/read timeouts
     (`HttpTimeout`, not `SocketTimeoutException`).
   - Errors map to the same `AppError`s (`java.io.IOException` → the multiplatform `IOException`).
   - Ollama is plain HTTP on the LAN (C37's exception). On iOS that needs an App Transport Security exception;
     see §5.
3. **`chat/engine/*`**: `java.time` → kotlinx-datetime in `PromptBuilder` (keep its output **identical**: same
   date line, same zone offset, L29), `AtomicBoolean` / `Collections` → common equivalents.
4. **`data/` (Room)**: entities, DAOs, `EdithDatabase`, converters, `ChatRepository`, on the bundled SQLite driver.
   - The hand-written `MIGRATION_1_2` (C06b) is written against Android's `SupportSQLiteDatabase`. Room KMP's
     migrations use `SQLiteConnection`. **Port it and prove it** (§3, test 4). This is the riskiest part.
   - On iOS the database file lives under the app's documents directory (`NSFileManager`), through a small
     `expect/actual` for the path.

### 3. Tests (the proof)
The existing unit tests for these parts are ported to `commonTest` (Ktor `MockEngine` instead of MockWebServer).
They run on **`jvmTest`** (on Windows) **and `iosSimulatorArm64Test`** (in CI). **A test that passes on one and
fails on the other is a finding.**
1. **Fixtures:** real Ollama NDJSON streams (plain reply, a tool call, a thinking block, an error line), taken from
   C03's tests, replayed through the Ktor client.
2. **Streaming really streams on iOS:** a test starts a small local HTTP server **in the test process** that sends
   NDJSON lines **with delays** and asserts that the first line reaches the client **before** the server sends the
   last. (A classic trap: a client that buffers the whole body and delivers it at the end.)
3. **Cancel and timeout:** cancelling mid-stream stops reading; a stalled server times out with the right error.
4. **Room:** create the database on the simulator, insert a conversation and messages, query them, and run
   `MIGRATION_1_2` on a v1 database file (a fixture taken from the Android schema export). The data must match what
   the Android migration test expects.
5. **Chat engine:** the ported engine runs a streamed turn against the fake client and produces the same stored
   messages as on Android.

### 4. The iOS app shell (`iosApp/`)
- A minimal Xcode project: SwiftUI `@main` hosting a Compose `ComposeUIViewController` from `shared`. (Use
  JetBrains' current KMP project layout as the reference; check it.)
- **One screen:** Edith's chat with her real theme (purple user bubbles on the right, grey Edith bubbles on the
  left, the input row), fed by a **fake `ChatEngine` that streams canned text**. No Ollama (CI can't reach the
  owner's home network), no other tab.
- It builds with `xcodebuild` for the simulator, **unsigned**.
- This answers: does Compose Multiplatform render our Material 3 theme and bubbles acceptably, and does the shared
  `ViewModel`/state code work on iOS?

### 5. CI (`.github/workflows/ios-spike.yml`, a new workflow)
- Runs on **`workflow_dispatch` and on pushes that touch `shared/**`, `iosApp/**` or the workflow itself. Never on
  every PR**: macOS minutes are the budget.
- Steps: set up JDK and Xcode (pin the macOS image and Xcode version; check the current runner labels) →
  `./gradlew :shared:jvmTest :shared:iosSimulatorArm64Test` → `xcodebuild` for the simulator → boot a simulator,
  install, launch → `xcrun simctl io booted screenshot` → upload the test report and the screenshot as artifacts.
- Cache Gradle and Kotlin/Native (`~/.konan`). Pin third-party Actions by commit SHA.
- **After each run, record the billed macOS minutes** (`gh run view` / the run's timing) in a table in As built.
- No secrets and no `local.properties` on this runner. The spike needs none.

### 6. Research deliverable: Android → iOS (no code; mark each row *checked in docs* or *from memory*)
A table of every Android-only part of Edith, its iOS equivalent, and a verdict (easy / medium / hard / blocked),
including at least:

| Edith today | iOS equivalent to check |
|---|---|
| `AudioRecord` voice memos, dictation | `AVAudioEngine`, the Speech framework |
| Hands-free wake word (ONNX Runtime Android) | ONNX Runtime's iOS builds (Swift Package / CocoaPods) through Kotlin/Native `cinterop`; background listening limits |
| `TextToSpeech`, C36's own voice | `AVSpeechSynthesizer` has voices built in, so C36 isn't needed there |
| `SecretStore` (Keystore) | Keychain |
| Foreground services: reply, hands-free, camera alerts | none; background execution limits; push needs APNs and a server |
| Notifications, `singleTask` deep link | `UNUserNotificationCenter`, URL schemes or universal links |
| Google and Microsoft sign-in (C17b loopback) | `ASWebAuthenticationSession` and an iOS OAuth client type for each provider |
| `ACCESS_LOCAL_NETWORK`, plain-HTTP Ollama | Local Network privacy prompt (`NSLocalNetworkUsageDescription`), ATS exception for the LAN host |
| jsoup, Jakarta Mail, Paho MQTT, commons-compress | Kotlin/Native-friendly libraries, or Swift libraries through `cinterop` |
| media3 video, WebRTC live view | `AVPlayer`, native iOS WebRTC |
| sherpa-onnx (C36) | its iOS framework (not needed if `AVSpeechSynthesizer` is used) |
| Room, DataStore, Coil, Navigation | KMP versions (Room is tested here) |

### 7. The report (this chunk's "As built" → **Go / no-go**)
- A results table: each question below → **yes / partly / no**, with evidence (CI run links, build logs).
- The numbers: macOS minutes used and per run, build time, the framework size, lines in `commonMain` vs lines that
  needed `expect/actual` or a replacement, and how many of the ported tests passed on each target.
- A screenshot of the Android chat and the iOS chat side by side, and a short honest note on differences.
- **A recommendation:** *go* (with the outline of the first three follow-up chunks, **not** written yet), *no-go*
  (why), or *go, but needs a Mac* (why). Plus the rough size of the full port, as chunks, from what was learned.
- The decision itself is the owner's. Don't start a follow-up.

**Go signals:** the Ollama client, chat engine and Room database compile for iOS and their tests pass on the
simulator with only the planned replacements; streaming arrives incrementally (test 2); `MIGRATION_1_2` works; the
chat screen renders acceptably; one CI run costs a budget the owner accepts.
**No-go signals:** Compose Multiplatform has no release for Kotlin 2.4.x; Ktor streaming buffers on iOS with no
workaround; Room KMP can't run our migration; the simulator build can't run on a runner the owner will pay for.

## Acceptance criteria
- [ ] The owner's two questions are answered and recorded (Mac, minute budget).
- [ ] `:shared:jvmTest` passes on the owner's Windows PC, and `scripts/check.sh` still passes unchanged.
- [ ] CI: `:shared:iosSimulatorArm64Test` runs on a macOS runner, and every ported test result is compared with
      `jvmTest`.
- [ ] Tests 1–5 above each have a result (pass, fail with the reason, or blocked with the reason).
- [ ] The iOS app shell builds for the simulator, launches, and a screenshot of the chat screen is saved as a CI
      artifact and linked in As built.
- [ ] The Android → iOS table is filled, each row marked checked or from memory.
- [ ] The report (§7) is written, with the minutes used, and it ends with a recommendation. The macOS-minute total is
      within the owner's cap.
- [ ] No edits to `app/`, and the Android release path (`scripts/release.sh`, the existing CI job) is untouched.

## Implementation notes (traps)
1. **AGP 9.5 and Kotlin Multiplatform:** the old `kotlin("multiplatform")` + `androidTarget` setup doesn't fit AGP 9.
   The spike has no Android target on purpose. Record what the follow-up would need.
2. **Windows can't build iOS targets.** Keep `shared` configurable on Windows (the iOS targets are skipped with a
   warning), keep `:shared:jvmTest` green there, and put nothing macOS-only on the default build path.
3. **KSP and Room KMP:** KSP runs per target and the Room compiler must be added for each. Expect the setup to be
   fiddly, and write down what worked.
4. **Ktor on iOS:** use the `Darwin` engine. Check that the response body is read as a stream, not all at once (test
   2). Check cancellation releases the connection.
5. **App Transport Security:** the shell's `Info.plist` needs an exception for plain-HTTP Ollama (for example
   `NSAllowsLocalNetworking`, or a per-host exception), and `NSLocalNetworkUsageDescription` for the local-network
   prompt. The CI simulator can't reach the owner's Ollama, so this is configured and **not** verified; say so.
6. **Cost discipline:** don't re-run the whole workflow for a small change. Fix on the Windows PC (`jvmTest`) first,
   and push to CI only when the iOS result is what's needed.
7. **Don't commit** generated Xcode user data, `DerivedData`, or `.konan`. Add them to `.gitignore`.
8. **The honest limits of this spike**, to repeat in the report: no real device, no Ollama on iOS, no keyboard,
   no background behaviour, no signing, no performance numbers.
9. **Docs to update in this PR:** README (chunk status), D27's notes (the owner's answers, the result),
   01-architecture (a note under extension points on `shared/`), 05-environment (the iOS workflow, how to run
   `:shared:jvmTest`), 00-product §3 (a pointer to this chunk and the report).

## Not in scope
- Changing the Android app to use `shared/`, or any UI beyond the one chat screen.
- Signing, TestFlight, the App Store, a real device (unless the owner has a Mac and asks).
- Voice, hands-free, Google or Microsoft sign-in, smart home, camera alerts on iOS.
- A native Swift rewrite (not planned, D27).
- Starting the follow-up chunks. The report recommends; the owner decides.
