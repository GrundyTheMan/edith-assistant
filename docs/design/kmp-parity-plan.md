# Edith on iPhone: the plan to reach feature parity with Android

| | |
|---|---|
| Status | **Proposed, 2026-11-24.** Written after gate G2 was cleared and the owner asked for Phase 3 to start. Nothing in it was built when it was written; since then K3.0 (the UI module spike, PR 31) and the first slice of K3.1 (components, gallery, quality tests, PR 32) are built and merged, which answered U13 and U8 and held the recommended answer to D50 (see the transition plan's Phase 3 progress table). The owner reviews it and answers the decisions in §12 before work starts. |
| Scope | Everything between today (engine shared, no shared screens, no iPhone app) and "the iPhone app does what the Android app does", plus retiring the Expo app. It **expands** Phases 2b, 3, 4 and 5 of [`kmp-transition.md`](kmp-transition.md) into work packages you can pick up cold. It does not change that plan's principles, gates or decisions D40 to D49. |
| Read first | [`kmp-handoff.md`](kmp-handoff.md) (how to work in this repository), [`kmp-transition.md`](kmp-transition.md) (the authority on what and why), [`ios-parity.md`](ios-parity.md) (the Expo app's parity table, used here as a source of rows). |
| Sizes | S / M / L / XL, never dates. S = a PR or two, M = a handful, L = a phase of its own, XL = split before starting. Line counts are measured, not estimated, unless marked. |
| Honesty rule | Every claim about the iPhone is **unverified until it ran on a real phone** (principle 6 of the plan). "Measured" means counted from the source on 2026-11-24. "To verify" means a statement from memory or documentation that no run of ours has shown. |

> **This repository is public.** No real names, email addresses, user names, home IP addresses, Tailscale names, tokens or keys in this document, its PRs or its CI logs (`AGENTS.md`).

---

## 1. The plan in one page

**Goal.** One Kotlin codebase draws the same Edith on Android and on iPhone. When the owner walks the parity checklist (Appendix A) on a real iPhone, every row is "same", "better" or a difference they accepted in writing. Then the Expo app is archived.

**Where we are.** The *engine* is shared and tested on three platforms: chat, tools, attachments, the Jarvis client, the printer feed, the home framework (8,500 lines in `shared/commonMain`; 267 desktop tests, 234 on the iOS simulator). The *screens* (about 9,900 lines), the *services behind the home tab and accounts* (about 12,000 lines), *voice* (3,000 lines) and the *app wiring* (about 600 lines) are still Android-only in `app/`. There is no iPhone app of ours yet; the one on the owner's phone is the Expo app, which has the Expo-era feature set (`ios-parity.md`), and a one-screen spike from Phase 0.

**How, in four moves.**

1. **Move the rest of the services into `shared`** (Phase 2b, new in this document). Same recipe as K2.1 to K2.4: `git mv`, swap Java types, put a named seam where the platform differs. Android keeps running on it, so every PR is proven on a device the owner already uses. About twelve small PRs.
2. **Add a shared UI module** (Phase 3). First a spike that proves Compose Multiplatform builds beside our Android setup (K3.0), then the theme and components, then the screens one area at a time. Android adopts each screen only after it has been seen working.
3. **Put the first slice on a phone early** (a reordering of Phase 4, §8). The real `iosApp/` shell and TestFlight pipeline come right after the shared chat screen, not after all screens. Each later slice (attachments and voice, home, printer, accounts, voice modes) lands on the phone as soon as it exists. This is how the problems the simulator cannot show (typing, VoiceOver, permissions, audio, background) get found while they are cheap.
4. **Walk, fix, retire** (Phase 5): the parity walk on a real phone, the decisions about what iOS cannot do, the Expo app removed.

**What is hardest** (so it is attacked first, §11): Compose Multiplatform beside the Android-library plugin (K3.0); typing, selection and VoiceOver in Compose on iOS (first real answer comes from the chat slice); live camera video (Blink's raw TLS stream and Ring's WebRTC have no ready iOS answer); IMAP/SMTP for iCloud mail; anything that must work while the app is not on screen (alerts, wake word, a reply that outlives the screen).

**What only the owner can decide** (§12): where the UI code lives, how Android adopts the shared screens, what iOS may do differently (background replies, alerts away from home, wake word, own voice), and which Google sign-in client iOS uses.

---

## 2. What "parity" means

### 2.1 The rule

A row of the parity matrix (§6) is **done** when the iPhone app does for the owner what the Android app does, judged by walking the scenario in Appendix A **on a real iPhone installed from TestFlight**. Pixel likeness is not required; Material 3 everywhere is the plan (non-goal in §2 of the transition plan). What must match: what the feature does, what it asks the owner to confirm, what it says when it fails, and that it is usable with VoiceOver.

### 2.2 Three kinds of rows

| Kind | Meaning | How it is closed |
|---|---|---|
| **S** (same) | Same behaviour from the same shared code. Most rows. | Proof level L3 (below). |
| **I** (iOS rules) | The same *outcome* under rules iOS imposes (no foreground services, no background microphone, permission prompts, sign-in sheets). | The difference is written in the row, the owner accepts it (decision in §12), then L3. |
| **N** (not planned) | Android does it for a reason that does not exist on iPhone (Edith's own voice for phones with no speech engine; Play-services sign-in). | A one-line reason the owner accepts. |

A row can also be **B** (better): shared code removes a limit the Expo app had (for example the web-search key, §6 row B4). It needs no acceptance, but it needs a test.

### 2.3 Proof levels (what "done" needs)

| Level | Proof | Who |
|---|---|---|
| L0 | Compiles for every target. | CI |
| L1 | A unit test passes on the **desktop, the Android host and the iOS simulator** (the G2 standard). | CI |
| L2 | The screen renders and responds in a UI test on the desktop target, and a simulator screenshot is attached to the PR (§9.3). | CI |
| L3 | The owner (or the owner's phone, driven by them) ran the Appendix A scenario on a TestFlight build. | Owner |

Rows that touch coroutines, flows, threads, time zones, locale or encodings need L1 on the simulator before they are called done (rule from the spike, plan §4.6). Rows that touch hardware (microphone, speaker, camera roll, location, network permissions, Keychain) are **not** done at L2: only L3 closes them. Nothing is called "works on iPhone" below L3.

---

## 3. Where we are (measured 2026-11-24)

### 3.1 What is shared

`shared/commonMain` holds 8,542 lines: `alerts` (the model only) 65, `attachments` 450, `chat` 723, `core` 496, `data` 727 (Room database, chat repository), `home` 1,888 (the framework: `Home`, `HomeBackend`, tools, the Demo backend), `jarvis` 463 (client, pairing), `ollama` 383, `printer` 1,429 (the feed: wire types, 33 commands, session, settle), `settings` 228, `tools` 1,684 (framework, time, weather, web search, page reader). Per-platform actuals live in `androidMain`, `iosMain`, `jvmMain` (11 small files each: digests, decimals, dispatchers, locale, log, time formatting, database builder, transactions, error questions, host lookups).

Tests: 267 on the desktop (`:shared:jvmTest`), 205 on the Android host, 234 on the iOS simulator (29 suites; includes the 29 engine end-to-end tests since PR 29).

### 3.2 What is still only in `app/` (27,849 lines)

| Area | Lines | What it is | Portability of the code |
|---|---:|---|---|
| **Screens and view models** | ~9,900 | `chat/ui` 2,132; `ui` 2,808 (settings screens 2,425, theme, navigation, banners); `home/ui` 1,339; `printer/ui` 2,671; `accounts/ui` 713; `settings/SettingsViewModel` 228 | Compose: source-compatible with Compose Multiplatform apart from the points in §4.6. Only 4 `R.drawable` references, `strings.xml` holds a single string, and 3 files use `stringResource`/`getString`; 35 icon usages (31 distinct Material icons). 7 files launch Android activity results (pickers, permissions); 3 embed an Android view with `AndroidView` (the clip player, live video, the sign-in web page); `ViewModels.kt` uses `AndroidViewModel`. |
| **Home services (data side)** | ~4,100 | Alexa 1,149; Blink 1,275; Ring 938; Meross 753 | Ktor-based already. Android-specific: Blink's cookie jar and IPv4 lookup (OkHttp hooks), Blink's raw TLS stream (`BlinkLiveStream`), Ring's WebRTC (`RingRtc`), Meross's Paho MQTT, Alexa's WebView sign-in. |
| **Accounts and tools** | ~2,850 | `Accounts` 433, stores, sources, task ranking, the calendar, mail and to-do tools (1,574) | Mostly common Kotlin. `Accounts` holds Android `Activity` and `PendingIntent` for the sign-in launch (3 files). |
| **Google, Microsoft, iCloud** | ~4,700 | clients (Calendar, Gmail, Tasks, Graph, CalDAV, iCalendar), browser OAuth with PKCE, iCloud mail over Jakarta Mail | Clients: common. Sign-in: shared OAuth logic plus an Android loopback receiver and Custom Tabs. Mail: Jakarta Mail is JVM-only (the open problem U6). |
| **Voice** | ~3,000 | capture (recorder, live recognition, memos), hands-free (state machine, wake word, service), Edith's own voice (sherpa), speech output | Logic (state machines, sentence splitting, downloader) is common-able, about 1,500 lines. The rest touches `AudioRecord`, `SpeechRecognizer`, `TextToSpeech`, sherpa-onnx, ONNX Runtime and a foreground service. |
| **Jarvis, alerts, attachments, tools** | ~1,400 | `JarvisAccounts`/`JarvisSources`/pin 425; alerts 370; attachment intake, WAV conversion, player, export 430; device location and the page reader's DNS check 174 | Seams, as K2.3 and K2.4 recorded. |
| **Printer direct (Moonraker)** | ~660 | `MoonrakerApi`, parse, prefs, `PrinterService` | Ktor over the plain client: common-able. |
| **Wiring and core** | ~1,240 | `AppContainer` 589, `ViewModels`, `MainActivity`, `EdithApp`, `ReplyService`, `core` 295 (`CrashReporter`, `HttpClients`, `KeystoreSecretStore`, permissions) | Splits into a shared container and a platform part (§4.2). |

### 3.3 What is *not* yet shown on any iPhone

Written plainly because the plan's gates depend on it. Nothing below has run on a device of ours: the shared Room database on a phone (K0.4 second half); the real `edith.db` opening; Compose screens beyond the spike; Keychain with the real `SecretStore`; text input, selection and VoiceOver in Compose (G0's deferred check); speech synthesis through sherpa-onnx or `AVSpeechSynthesizer`; ONNX Runtime for the wake word; WebRTC; MQTT with a login against Meross; SwiftMail; a Swift-implemented seam injected into Kotlin (the spike's Keychain bridge was written in Kotlin; **no Swift-to-Kotlin injection has been built**, §4.5); certificate pinning on the Darwin engine; the Compose-in-SwiftUI shell with the real container; background and interruption behaviour of audio. Each is listed as an experiment in §11 with an exit test.

---

## 4. Target architecture for the rest

### 4.1 Modules (decision D50)

```
edith/
  shared/        engine + services (exists). commonMain, androidMain, iosMain, jvmMain, tests. No Compose.
  ui/            NEW (recommended): Compose Multiplatform screens, theme, components, view models, navigation.
                 Depends on :shared. Targets: android (library plugin), jvm, iosArm64, iosSimulatorArm64.
  app/           Android application: manifest, services, notifications, platform implementations, AppContainer's Android half.
  iosApp/        the Xcode shell (xcodegen project.yml): SwiftUI entry, Swift seam implementations, Info.plist, entitlements.
  desktopApp/    a JVM window that runs the shared UI on Windows (developer tool, not a product).
  ios-app/       the Expo app: frozen (D46), removed at K5.2.
```

**Why a separate `ui` module** (the transition plan says "one `shared` module at first", D42): (1) engine builds and the 267 desktop tests stay fast and Compose-free; engine PRs do not recompile Compose for iOS on the macOS runner; (2) the one real unknown of Phase 3, Compose Multiplatform beside `com.android.kotlin.multiplatform.library` (K3.0), is contained in a module nothing else depends on, so a failure costs nothing already built; (3) the iOS CI can run engine tests on every `shared/**` change and UI tests only on `ui/**` changes. **Cost:** one more module to wire. **Fallback** if K3.0 shows the library plugin cannot host Compose: use the Kotlin Multiplatform plugin with a plain `androidTarget()` for `ui` only. Decided in K3.0 by what builds, not in advance.

Dependency rule (extends plan §4.2): `shared` knows nothing of `ui`; `ui` knows `shared` and the `PlatformServices` interfaces; `app` and `iosApp` know both. The import guard test gains a second scan: no `android.*`, `java.*`, `okhttp3`, `androidx.activity` or Apple import in `ui/commonMain`.

### 4.2 The container and the platform services

`app/di/AppContainer.kt` (589 lines) builds dozens of objects. It splits in two:

- **`SharedContainer(platform: PlatformServices, appInfo: AppInfo)`** in `shared/commonMain`: everything that is common code today or after Phase 2b: database, repositories, Ollama, tool registry, engine, accounts, home services, retention, alerts watcher, printer feed. Same lazy structure, same names, so call sites change by one prefix.
- **`PlatformServices`**: a plain Kotlin class of interfaces (below). `app/` builds `AndroidPlatformServices(app)` from the objects `AppContainer` builds today (Keystore store, TTS, recorder, loopback receiver, Paho, foreground services). `iosApp/` builds `IosPlatformServices` from Kotlin `iosMain` classes and Swift classes passed in at startup (§4.5).

Sketch (the real signatures are written when each seam's WP starts; **suspend or callbacks only on the Swift side**, §4.5):

```kotlin
class PlatformServices(
    val appInfo: AppInfo,                 // version, debug flag: replaces BuildConfig reads
    val files: PlatformDirs,              // durable dir, cache dir, database path
    val secrets: SecretStore,             // Keystore | Keychain
    val http: HttpEngines,                // plain/secure engines + the hooks below
    val pinned: PinnedHttp,               // Jarvis certificate pin
    val media: MediaServices,             // Picker, ImageProcessor, AudioConverter, AudioPlayer, FileExport
    val speech: SpeechServices,           // Speaker, SpeechRecognizerFactory, WakeWordEngine, own-voice engine
    val capture: AudioRecorder,
    val location: LocationProvider,
    val permissions: PermissionAsker,     // microphone, location, notifications, photos
    val signIn: SignInServices,           // BrowserSignIn (OAuth), WebSignInView (Alexa, Blink page)
    val live: LiveServices,               // ClipPlayer, LiveVideo (WebRTC), RawTlsSocket
    val mail: MailServices?,              // ImapClient, SmtpClient (null: not available)
    val notifications: Notifier,          // alerts, reply-in-progress
    val keepAlive: KeepAlive,             // foreground service | background task
    val system: SystemServices,           // clipboard, share sheet, open URL, haptics, keep screen on
    val crash: CrashReporter,
)
```

A seam that a platform lacks is `null` or a no-op, and the screen hides the control. `ChatFeatures(attach, record, dictation)` already works this way in `app/` today and moves with the chat view model.

### 4.3 View models, state, navigation

- 15 view models use `androidx.lifecycle.ViewModel` and `viewModelScope`; the multiplatform artifacts (`org.jetbrains.androidx.lifecycle`, 2.11.0, proven in K0.5) have the same API. `collectAsStateWithLifecycle` (14 uses) and `viewModel()` (9) have multiplatform versions (to verify the exact artifact in K3.0). Two files use `SavedStateHandle` (to verify: multiplatform saved state).
- View models move **with their screen** into `ui`. They take interfaces from `SharedContainer`, not the container itself, so a screen's tests build a view model from fakes (the ChatViewModel tests already do).
- Navigation: `EdithNavHost` (154 lines) and `Destinations` (13) move to JetBrains navigation 2.10.0 (proven in K0.5). Bottom bar tabs: Chat, Calendar, Home, Settings. The iOS back swipe and the predictive back on Android come from the library (to verify on both).
- One state holder per screen, unidirectional, as today. No change of pattern in a move PR (principle 3).

### 4.4 Platform rules that change behaviour

| Android today | iOS reality | Design answer (decision in §12) |
|---|---|---|
| `ReplyService`: a foreground service keeps the network up so a reply finishes after the owner leaves the app (D13, L24). | An app is suspended seconds after it leaves the screen; its sockets die. | **D54.** Ask for background execution time when a reply starts (`UIApplication.beginBackgroundTask`, about 30 s on iOS today, to verify) and say so in the reply bubble if the stream was cut ("Edith was interrupted; tap to continue", regenerate). Not parity with Android; the owner accepts a shorter promise, or chooses Jarvis-hosted replies later (out of scope here). |
| `AlertService`: a foreground service polls Ring and Blink every 45 s (D24). | No foreground services. | **D53.** Option A (already designed for Expo, C72): alerts while the app is open, a "While you were away" catch-up when it returns, local notifications from a best-effort background refresh. Option B: Jarvis sends an Apple push (needs a Jarvis change and an APNs key, owner steps). |
| `HandsFreeService`: a foreground service holds the microphone for "Hey Edith". | No background microphone without a continuous audio session and an orange indicator. | **D52.** Wake word only while Edith is on screen (D41 already says this); tap-to-talk conversation mode everywhere. |
| Own voice (sherpa Piper) exists because some Android phones have no speech engine. | Every iPhone has `AVSpeechSynthesizer`. | **D57.** Not planned; keep the `Speaker` seam so it can be added. |
| Crash report written by `CrashReporter`, banner and "last crash report" in Settings. | `NSSetUncaughtExceptionHandler` and Kotlin's unhandled-exception hook can write the same file; Apple's own logs (TestFlight, Organizer) show native crashes. | **D58.** Same banner, same file, fed by Kotlin's `setUnhandledExceptionHook`; native crashes through TestFlight feedback. "Crashes are never acceptable" (owner rule) stays: every report is investigated. |
| `ACCESS_LOCAL_NETWORK` runtime permission (L23). | iOS asks for Local Network itself on the first local connection. Tailscale addresses (100.x) are not "local" to iOS (device finding 2). | Info.plist string; the "can't reach your PC, open Tailscale" message from device finding 3 becomes the standard unreachable message. D49 (App Transport Security) decided in K4.2. |

### 4.5 How a Swift seam works (to prove before relying on it)

Kotlin/Native exports a Kotlin interface to Swift as an Objective-C protocol; a Swift class implements it; `iosApp` passes the instance into `PlatformServices` at startup. Rules, from how the export behaves (to verify in K4.1's injection proof): `suspend` functions arrive in Swift as `async` or completion handlers; **`Flow` does not export usefully**, so a seam that streams (audio levels, wake-word hits, live video frames) takes callbacks or a small `Listener` interface, and a Kotlin adapter in `iosMain` turns it into a `Flow`; exceptions cross as `NSError` only if annotated (`@Throws`), so seam methods return a result type rather than throw; generic types lose their parameters; keep seam types simple (`ByteArray`, `String`, `Long`, enums, small data classes). K4.1 includes **one** Swift seam end to end (the share sheet or haptics) with a test, before any real one is written.

Short Apple calls (about 50 lines) stay in Kotlin `iosMain` (Keychain, file paths, `NSProcessInfo`, `NSDateFormatter`: the pattern K1 to K2 used). Delegate-heavy or UI-bound work (`ASWebAuthenticationSession`, `PHPicker`, `WKWebView`, `AVPlayer`, `AVAudioEngine`, WebRTC, `Network.framework`) is Swift.

### 4.6 UI conventions that make the move mechanical

- **Theme.** `ui/theme/Color.kt` (11 lines) and `Theme.kt` (28) are tiny; the Android dynamic colours (if used) become a platform parameter. Dark mode must set explicit surface colours: device finding 5 (black text on a black background in dark mode) is the failure to avoid.
- **Icons.** 31 distinct Material icons are used (25 filled, 4 auto-mirrored, 2 outlined). **Checked in K3.1 (PR 32): all 31 exist in the Compose Multiplatform set (`material-icons-extended` 1.7.3) and compile on the desktop, Android and the iPhone simulator** (`IconsTest`); how they draw is checked when a screen first shows them.
- **Resources.** Android resources are almost unused by the UI (4 `R.drawable` references, all launcher or notification icons that stay in `app/`; one string resource; notification text lives in the services). So **no Compose Resources migration is needed**; UI strings are Kotlin literals today and stay so. A language pass (localisation) is out of scope and would be its own chunk.
- **Fonts.** None bundled; system fonts on both, with system font scaling honoured (plan §4.5).
- **Insets and keyboard.** `WindowInsets` (2 files) and the input row's IME padding move to the multiplatform `WindowInsets.ime`/`safeDrawing` forms; checked by hand on a phone with the keyboard open (K4.2).
- **Interop views (`AndroidView`, 3 files: `ClipsScreen`, `LiveView`, `PageSignInDialog`).** Become `expect`/`actual` composables or an interface that supplies a platform view: `PlatformVideo(clip)`, `PlatformLiveVideo(handle)`, `PlatformWebView(url, onResult)`. iOS side is `UIKitView` around a Swift-made view.
- **Activity results (7 files).** Pickers and permission requests become calls on the seams (`Picker.pickImages()`, `PermissionAsker.ask(...)`), not launchers inside composables.
- **Previews.** `@Preview` (Android tooling) is replaced by the desktop window's gallery screen (K3.1) so the owner can see components on Windows.

---

## 5. The seam catalogue

Each row: what the seam does, the Android class that does it today, the shared interface it becomes, the iPhone implementation, the WP that builds it, and how it is proven. "K" is Kotlin `iosMain`; "S" is Swift in `iosApp/`. Rows marked **R** are research-grade: no run of ours shows the iPhone answer yet.

| # | Seam | Android today | iPhone answer | Lang | WP | Proof / owner check |
|---|---|---|---|---|---|---|
| 1 | HTTP engines and policy (`plain`/`secure`, C37) | `core/net/HttpClients` over OkHttp, `TaggedSocketFactory` | Ktor Darwin engine; the policy (HTTPS-only for `secure`, redirect rules) rebuilt as a Ktor plugin and tested in common | K | K2.12 | L1 policy tests on all three; L3 chat over Wi-Fi |
| 2 | Plain HTTP to Ollama over Tailscale | cleartext allowed in the manifest | `NSAllowsArbitraryLoads` alone (device finding 2); D49 decides whether to keep it | plist | K4.1/K4.2 | L3 |
| 3 | Secrets | `KeystoreSecretStore` | Keychain (`SecItem*`), accessible after first unlock, no iCloud sync | K | K2.12 | L1 round trip on simulator; L3 sign-in survives restart |
| 4 | Jarvis certificate pin | `OkHttpPinnedHttp` | Darwin server-trust challenge calls the shared `PinCheck.accepts(der)` (U10); Tailscale listener (D39) as fallback | K or S | K4.5 | **R**; L1 `PinCheck`, L3 pair with the real Jarvis |
| 5 | Blink cookie jar | OkHttp `CookieJar` | Ktor `HttpCookies` plugin (common) with a persistent store | K | K2.5 | L1 against a fake server |
| 6 | Blink IPv4-only lookup | OkHttp `Dns` | Darwin cannot filter DNS answers: connect by a pre-resolved IPv4 literal with the host header, or drop the filter if iOS does not need it (to verify against the real Blink) | K | K2.5 | **R**; L3 |
| 7 | iCloud trusted-host password interceptor | OkHttp interceptor | Ktor `DefaultRequest`/auth plugin that sends the credential only to listed hosts (common code, with the existing test) | K | K2.7 | L1 |
| 8 | Page reader private-address check | OkHttp `Dns` + `PrivateAddress` | Resolve through `getaddrinfo` (seam `resolveHost`), refuse private ranges with the shared `PrivateNetwork` rules, then connect to the literal (watch for TOCTOU: re-check after redirect) | K | K2.12 | L1; L3 reading a public page |
| 9 | Raw TLS socket (Blink live stream accepts any certificate) | `java.net.Socket` + trust-all `SSLContext` | `NWConnection` with a verify block that accepts, or ktor-network sockets if TLS options allow (to verify) | S | K4.9 | **R**; see experiment E5 |
| 10 | Pick photos / files | Photo Picker, `ActivityResult` | `PHPickerViewController` (no photo permission needed); document picker for printer upload | S | K4.3 | L3 |
| 11 | Image resize to JPEG | `AttachmentIntake` (`BitmapFactory`) | `UIImage` + `jpegData`, `CGImageSource` thumbnail for large photos (memory) | K | K2.10 | L1 on sizes; L3 a 12 MP photo |
| 12 | Audio to WAV | `AudioToWav` (`MediaCodec`) | `AVAssetReader`/`AVAudioConverter` to 16 kHz mono PCM | S | K4.3 | L3 an audio file from Files |
| 13 | Record audio | `AudioRecorder` (`AudioRecord`) | `AVAudioEngine` tap or `AVAudioRecorder` (works from Kotlin, spike run 9); `AVAudioSession` configuration for playback + record; interruptions (U11) | K/S | K4.3 | L3 voice memo, with a phone call interrupting |
| 14 | Play attachment audio | `AttachmentPlayer` | `AVAudioPlayer` | S | K4.3 | L3 |
| 15 | Spoken replies | `AndroidTts`, `ChoosingTts`, `SpeechOutput` | `AVSpeechSynthesizer` behind `Speaker`; sentence streaming and speed are shared logic | S | K4.3 | L3 loudspeaker vs earpiece, silent switch |
| 16 | Live recognition (models that cannot hear) | `LiveSpeech` (`SpeechRecognizer`) | `SFSpeechRecognizer` (on-device where available); needs a speech-recognition permission string | S | K4.3 | L3 |
| 17 | Own voice (sherpa Piper) | `SherpaTts`, `AudioSink`, `VoiceDownloader` | not planned (D57); `Speaker` seam kept | n/a | none | decision |
| 18 | Wake word | `WakeWord` (ONNX Runtime, three models), `WakeListener` | ONNX Runtime iOS (`onnxruntime-swift-package-manager` or C API), foreground only (D52); one copy of ONNX Runtime if sherpa is ever added | S | K4.8 | **R**; E6; L3 real microphone |
| 19 | Location | `AndroidLocationProvider` | `CLLocationManager` when in use, approximate accuracy | K/S | K4.2 | L3 "weather here" |
| 20 | Browser sign-in (Google, Microsoft) | `BrowserSignIn`, Custom Tabs, `LoopbackReceiver` | `ASWebAuthenticationSession`, custom-scheme redirect, PKCE; shared `BrowserOAuth` does the rest. Needs an **iOS Google client** and a Microsoft redirect `edith://auth` (owner steps, D55) | S | K4.6 | **R**; L3 each account |
| 21 | Embedded sign-in page (Alexa; Blink and Ring pages as today) | `PageSignInDialog`, WebView | `WKWebView` in a `UIKitView`, cookie harvesting through `WKHTTPCookieStore` | S | K4.4 | **R**; L3 Alexa first load |
| 22 | Clip playback | media3 `ExoPlayer` (`ClipsScreen`, `LiveView`) | `AVPlayer` with request headers (the Expo parity table says Blink's clips may need one; unverified on iOS); `PlatformVideo` composable | S | K4.4 | L3 a real clip |
| 23 | Ring live video | `RingRtc` (stream-webrtc) | `stasel/WebRTC` XCFramework behind `LiveVideo`; signalling stays in shared Kotlin | S | K4.9 | **R**; E4; L3 real doorbell |
| 24 | Blink live video | `BlinkLiveStream` + `FeedDataSource` into ExoPlayer with MPEG-TS flags | AVPlayer cannot take a raw TS byte feed. Options: demux H.264 from the TS in Kotlin and display with `AVSampleBufferDisplayLayer`; or repackage to local HLS; or defer (snapshots and clips only) | S | K4.9 | **R**; E5 decides |
| 25 | IMAP and SMTP | Jakarta Mail (`ICloudMailSource`) | SwiftMail behind `ImapClient`/`SmtpClient` (D45, D56); or deferred | S | K4.7 | **R**; E7; needs the owner's iCloud app password |
| 26 | MQTT (Meross cloud) | Paho (`MerossBus`) | DitchOoM mqtt 3.0.0 (MIT, proven in the spike for TLS). Swap Paho for DitchOoM **on Android first** (K2.5) so one implementation runs everywhere | K | K2.5, K4.7 | L1 fake broker; **R** login and Meross's real broker, L3 |
| 27 | Notifications and keep-alive | `AlertNotifier`, `AlertService`, `ReplyService`, `HandsFreeService` | `UNUserNotificationCenter` for local notifications; background task for catch-up; `beginBackgroundTask` for a reply (D53, D54) | S | K4.8 | L3 |
| 28 | Clipboard, share sheet, open URL, haptics, keep screen on | `LocalClipboard`, `FileProvider`, `Intent` | `UIPasteboard`, `UIActivityViewController`, `UIApplication.open`, `UIImpactFeedbackGenerator`, `isIdleTimerDisabled` | S (tiny) | K4.1 | L3 |
| 29 | Permissions | `ToolPermissions.android`, `rememberLauncherForActivityResult` | `PermissionAsker` interface; iOS asks at first use; **a denied permission is not re-askable** on iOS: the screen must send the owner to Settings with a plain sentence | K/S | K4.2 | L3 |
| 30 | Crash reports | `CrashReporter` | Kotlin `setUnhandledExceptionHook` writing the same file; banner shared (D58) | K | K3.2 | L3 force a test crash in a debug build |
| 31 | App info | `BuildConfig` reads (3 files) | `AppInfo` filled from the bundle | K | K2.12 | L1 |
| 32 | Local state files (task ranking store, voices dir) | `java.io.File` under `filesDir` | kotlinx-io `SystemFileSystem` paths from `PlatformDirs` | K | K2.9, K2.12 | L1 |

---

## 6. The parity matrix

The rows are the Android app's user-visible features, taken from the code (`app/`), `ios-parity.md`, `00-product.md` and the chunk table. **Kind** is §2.2 (S same, I iOS rules, N not planned, B better). **Engine** says what is shared today. **WP** is the work package in §7. **Req** is the proof level the row needs. The matrix is a living table: a row's status is updated in the PR that changes it, as `ios-parity.md` does today; a feature found missing from the table is added when found (the Expo table says the same of itself).

### A. Chat and conversations

| # | Feature (Android) | Kind | Engine today | iPhone work | WP | Req |
|---|---|---|---|---|---|---|
| A1 | Streaming chat, Edith's persona, Stop | S | shared (engine, prompt, titler) | chat screen, input row, `ChatViewModel` | K3.2, K4.2 | L3 |
| A2 | Regenerate, copy, delete a message, Play/Stop speech on a bubble | S | shared | bubble actions in `ChatComponents` | K3.2 | L3 |
| A3 | Several conversations: list, rename, delete, new, clear | S | shared repository | list screen | K3.2 | L3 |
| A4 | Automatic chat titles | S | shared titler | none | K3.2 | L1 |
| A5 | Model picker with "Hears audio" and "Sees images" lines | S | shared `ModelSelection`, `ModelCatalog` | settings screen | K3.2 | L3 |
| A6 | Server address, Test connection, local-network permission | I | shared `ServerUrl` | settings screen, iOS permission text, Tailscale-sleeping message | K3.2, K4.2 | L3 |
| A7 | Timestamps, auto-scroll, "thinking" bubble (reads `message.thinking` too) | S | shared | `ChatComponents` | K3.2 | L2 |
| A8 | Draft kept per conversation | S | in the view model | view model | K3.2 | L1 |
| A9 | Markdown in replies | S | none | `Markdown.kt` (126 lines, custom renderer) | K3.1 | L2 |
| A10 | A reply keeps running after the owner leaves the app | I | engine only | D54 design | K4.8 | L3 |
| A11 | Crash banner, "View last crash report" | I (D58) | none | shared banner + file, Kotlin hook | K3.2 | L3 |
| A12 | Bottom bar: Chat, Calendar, Home, Settings | S | none | nav host | K3.6 | L2 |
| A13 | Light and dark theme | S | none | theme | K3.1 | L2 |

### B. Tools

| # | Feature | Kind | Engine today | iPhone work | WP | Req |
|---|---|---|---|---|---|---|
| B1 | `get_current_time` | S | shared | none | done | L1 |
| B2 | Weather here / a place, units by locale (D7) | S | shared (`formatDecimal`, locale seam) | check units on iOS locale | K4.2 | L3 |
| B3 | Location ("here") | S | `LocationProvider` interface | `CLLocationManager` | K4.2 | L3 |
| B4 | Web search: DuckDuckGo plus the optional Ollama key | **B** | shared | settings row (Expo lacked the key: shared code removes the difference) | K3.2 | L1 |
| B5 | Read a web page (public pages only, private addresses refused) | S | shared (K2.2) | `resolveHost` seam (row 8) | K2.12 | L1 |
| B6 | Calendar tools (read, add, change) across accounts | S | in `app/` (`CalendarTools` 456) | move | K2.6a | L1 |
| B7 | Mail tools (search, read, draft, send) | S | in `app/` (`MailTools` 265) | move | K2.6a | L1 |
| B8 | To-do tools and task ranking by the model | S | in `app/` (`TaskTools` 643, `TaskRanking` 296) | move; ranking store on kotlinx-io | K2.6a | L1 |
| B9 | Smart-home tools (`home_devices`, `home_control`, `camera_snapshot`, `recent_events`, `printer_status`) | S | shared framework; service tools in `app/` | per service WP | K2.5 | L1 |
| B10 | `printer_control` in chat | S | shared (K2.4) | confirm sheet UI | K3.4 | L3 |
| B11 | Confirmation cards before a tool changes anything; never by voice | S | shared `Confirmations` | card UI | K3.2 | L3 |
| B12 | Tools that need a permission ask for it | I | shared `ToolPermissions` | `PermissionAsker` | K4.2 | L3 |

### C. Attachments

| # | Feature | Kind | Engine today | iPhone work | WP | Req |
|---|---|---|---|---|---|---|
| C1 | Attach photos (picker) | S | shared engine + store | `Picker` | K4.3 | L3 |
| C2 | Resize to JPEG, vision gate | S | gate shared; resize in `app/` | `ImageProcessor` | K2.10, K4.3 | L3 |
| C3 | Attach audio files, converted to WAV | S | gate shared | `AudioConverter` | K2.10, K4.3 | L3 |
| C4 | Attachment viewer, bubbles, player | S | none | `AttachmentViews` (250) | K3.2 | L3 |
| C5 | Retention (default 10 per direction), Delete all | S | shared `Retention` | settings screen (`AttachmentSettings` 176) | K3.2 | L3 |
| C6 | Edith's files (`<file>` blocks), file cards, Save, Share, Copy | S | shared | `FileExport` seam (share sheet, save to Files) | K2.10, K4.3 | L3 |
| C7 | Resend an earlier attachment | S | shared | none | done | L1 |
| C8 | Edith's own pictures (`camera_snapshot`), per-direction retention | S | shared | none | K4.4 | L3 |

### D. Voice

| # | Feature | Kind | Engine today | iPhone work | WP | Req |
|---|---|---|---|---|---|---|
| D1 | Voice memo: record, the model transcribes, send as a message | S | `VoiceMemos` in `app/` | move logic; `AudioRecorder` seam | K2.9, K4.3 | L3 |
| D2 | Live recognition for models that cannot hear | S | `LiveSpeech` in `app/` | `SFSpeechRecognizer` | K4.3 | L3 |
| D3 | Dictation into the text box | I | n/a | the iOS keyboard's own dictation key (Expo table says the same) | none | accepted |
| D4 | Spoken replies, sentence by sentence, speed, Test voice | S | `SpeechOutput` in `app/` | move logic; `Speaker` seam | K2.9, K4.3 | L3 |
| D5 | Edith's own voice, voice downloads | N (D57) | in `app/` | none | none | accepted |
| D6 | Hands-free: "Hey Edith", talk, answer aloud | I (D52) | `HandsFree` state machine in `app/` | move; `WakeWordEngine`; foreground only | K2.9, K4.8 | L3 |
| D7 | Follow-ups without the wake word; the answer is not heard as speech | S | in `HandsFree` | same | K4.8 | L3 |
| D8 | Hands-free settings, sensitivity, Test | S | none | `HandsFreeSettings` (206) | K3.2, K4.8 | L3 |
| D9 | Mic banner while recording | S | none | `VoiceBanner` | K3.1 | L2 |

### E. Accounts, calendar, mail, to-dos

| # | Feature | Kind | Engine today | iPhone work | WP | Req |
|---|---|---|---|---|---|---|
| E1 | Several accounts, default calendar, rename | S | `Accounts`, `AccountStore` in `app/` | move; container | K2.6a | L1 |
| E2 | Google sign-in through the browser (no Play services) | S | `BrowserOAuth`, `OAuthProvider` in `app/` | shared OAuth + `ASWebAuthenticationSession`; **iOS client** (D55) | K2.6b, K4.6 | L3 |
| E3 | Google Play-services sign-in | N | `GoogleAccountAuth`, `AndroidGoogle` (Android-only) | none (already N in the Expo table) | none | accepted |
| E4 | Google Calendar, Gmail, Tasks clients | S | in `app/` (`google/`) | move | K2.6b | L1 |
| E5 | Microsoft: Outlook mail and calendar | S | in `app/` | move; redirect `edith://auth` | K2.6c, K4.6 | L3 |
| E6 | iCloud calendar (CalDAV, iCalendar) | S | in `app/` | move | K2.7 | L1 |
| E7 | iCloud mail (IMAP read, drafts, SMTP send) | S | Jakarta Mail in `app/` | `ImapClient`/`SmtpClient` seam; SwiftMail (D56) | K2.7, K4.7 | L3 |
| E8 | Work account through Jarvis (mail, calendar, to-dos; read-only plus drafts) | S | `JarvisSources`, `JarvisAccounts` in `app/` | move; pairing UI; pin (row 4) | K2.8, K4.5 | L3 |
| E9 | Calendar tab with the Agenda / To-do switch | S | none | `CalendarScreen` 314, `TodoScreen` 399 | K3.5 | L3 |
| E10 | Accounts settings (add, sign in again, remove) | S | none | `AccountsSettings` 603 | K3.5 | L3 |

### F. Home

| # | Feature | Kind | Engine today | iPhone work | WP | Req |
|---|---|---|---|---|---|---|
| F1 | Home tab: chips, rooms, favourites, hide, locks section, command log, Demo home | S | shared framework | `HomeScreen` 412, `HomeViewModel` 340 | K3.3 | L3 |
| F2 | Settings → Home: sign in, sign in again, disconnect, hidden devices | S | none | `HomeSettings` 399 | K3.3 | L3 |
| F3 | Meross garage doors (cloud MQTT) | S | `meross/` in `app/` | move; DitchOoM | K2.5, K4.7 | L3 |
| F4 | Alexa devices (lights, plugs, fans, thermostats, locks) | S | `alexa/` in `app/` | move; `WKWebView` sign-in | K2.5, K4.4 | L3 |
| F5 | Ring: sign-in with 2FA, devices, snapshots, events | S | `ring/` in `app/` | move | K2.5, K4.4 | L3 |
| F6 | Blink: sign-in with a code, cameras, snapshots, motion | S | `blink/` in `app/` | move; cookie and IPv4 hooks | K2.5, K4.4 | L3 |
| F7 | Clips chip, list, player | S | `ClipsScreen` 193 | `PlatformVideo` | K3.3, K4.4 | L3 |
| F8 | Live view: Ring (WebRTC) and Blink (raw TLS + TS) | S | `LiveView` 257, `LiveFullScreen` 137, `RingRtc`, `BlinkLiveStream` | `LiveVideo` seams (rows 23, 24) | K3.3, K4.9 | L3 |
| F9 | Camera alerts: doorbell, person, motion | I (D53) | `AlertWatcher` etc. in `app/` | move logic; local notifications; catch-up | K2.11, K4.8 | L3 |
| F10 | 3D printer card, direct to Moonraker on the home Wi-Fi | S | `printer/` (663) in `app/` | move | K2.5, K3.4 | L3 |
| F11 | Printer page through Jarvis: every card, Controls, confirmations, macros, console, presets, upload | S | shared feed (K2.4) | 2,671 lines of UI | K3.4, K4.5 | L3 |
| F12 | Camera wall and the chat "look" button | n/a | open on Android too (C32) | not a parity row until Android has it | none | n/a |
| F13 | Resideo thermostat, Philips lock | n/a | design only on Android; the lock works through Alexa | not a parity row | none | n/a |

### G. Settings

| # | Section (Android) | Kind | WP | Req |
|---|---|---|---|---|
| G1 | Server & model | S | K3.2 | L3 |
| G2 | Voice (speak, speed, Test; own voice) | S / N (own voice) | K3.2 | L3 |
| G3 | Hands-free | I | K3.2, K4.8 | L3 |
| G4 | Web search key | S (B above) | K3.2 | L3 |
| G5 | Accounts | S | K3.5 | L3 |
| G6 | Attachments | S | K3.2 | L3 |
| G7 | Home (services, Alerts, hidden, Demo, log) | S / I | K3.3 | L3 |
| G8 | About: version, Ollama version, last crash report | I | K3.2 | L3 |

### H. Quality properties that apply to every row

| # | Property | Where the proof lives |
|---|---|---|
| H1 | HTTPS-only for anything carrying credentials; `plain` only for Ollama, the 3D printer and public pages (C37) | policy tests in common (L1) + the wiring text-scan test |
| H2 | Never log conversation text in release builds; no secrets in logs | `LogConfig`, existing tests |
| H3 | VoiceOver names and roles on every control (the Expo audit standard, `audit.test.ts`) | a UI test that fails on an unnamed clickable (K3.1) + L3 walk with VoiceOver |
| H4 | Dark mode readable everywhere | UI test with a dark theme (K3.1) + L3 |
| H5 | Dynamic type: layouts survive the largest system font scale | L2 test + L3 |
| H6 | Errors in plain words, one message per cause (the Android wording moves with the code) | L1 |
| H7 | Cold start time and memory with 1,000 messages | measured in K4.2, no target invented here; the owner judges |
| H8 | Keyboard never covers the input row | L3 |
| H9 | Large lists (messages, events) scroll smoothly | L3 |

---

## 7. Work packages

Numbering continues the transition plan: **K2.5 to K2.12** are a new "Phase 2b" (the rest of the engine); **K3.x** and **K4.x** keep their numbers but are broken down; K5 is unchanged. Every WP follows the standard move (plan §6.1) unless it says otherwise: seam in place, swap libraries in place, `git mv`, move tests, CI, record "As built" in the plan's progress table. Every WP states an owner check or says "none".

### Phase 2b: the rest of the engine into `shared` (Android is the proof)

Order inside the phase is mostly free; K2.12 must come last because it needs everything to exist.

**K2.5 Home services (data side). Size L.** Alexa (1,149), Blink without live (1,088), Ring without WebRTC (779), Meross (753), Moonraker direct (663). Steps: (1) swap Paho for DitchOoM on Android in place, behind the existing `MerossBus` interface; (2) replace Blink's cookie jar with Ktor `HttpCookies` over the same OkHttp engine on Android (PR on its own, device-checked with the owner's real Blink); (3) move each service with its tests; seams: `WebSignInView` result types (cookies as data), `LiveVideo` interface only declared. Android hooks that cannot be common (Blink IPv4 lookup) stay in `androidMain` behind `HttpEngines`. Done when: those packages are in `shared/commonMain`, `OkHttpGuardTest` allow-list shrinks, tests pass on three platforms. **Owner check:** the Home tab on Android still reaches the real devices (they will notice).

**K2.6a Accounts core, sources and the three tool families. Size L.** `Account`, `AccountStore` (DataStore, already common), `Accounts` (replace `Activity`/`PendingIntent` by a `SignInLauncher` interface returning a result), sources, task ranking, `CalendarTools`, `MailTools`, `TaskTools`, `WhichAccount` (about 2,850 lines). Replace `ConcurrentHashMap` by `CowMap` (K2.4 pattern). **K2.6b Google (2,600).** Clients (Calendar, Gmail, Tasks) and `BrowserOAuth` + `OAuthProvider` move; `BrowserSignIn` becomes an interface driven by `AuthSession` (open URL, receive redirect); `LoopbackReceiver`, `GoogleAccountAuth` and `AndroidGoogle` stay in `app/` as the Android implementation. **K2.6c Microsoft (917)** the same with the Microsoft provider. Jsoup in Gmail's snippet becomes ksoup (the last Jsoup use). Done: three platforms' tests for URL building, token exchange against a fake server (the C59 test list is the specification: PKCE vector from RFC 7636, state check, rotated refresh token stored before use, parallel refreshes become one).

**K2.7 iCloud. Size M.** CalDAV client, iCalendar, calendar source move (1,000 lines); the iPhone cannot use Jakarta Mail, so the mail source is written against `ImapClient`/`SmtpClient` interfaces with the Android implementation (Jakarta) staying in `app/`. The trusted-host password rule moves into a Ktor plugin with its test (row 7). Redirects for PROPFIND/REPORT stay as in K1.4 (`redirectAnyMethod`).

**K2.8 Jarvis, the rest. Size S.** `JarvisAccounts` (Android intents become `SignInLauncher`), `JarvisSources`, `PrinterFeed` (finds the connection). The pin stays `PinnedHttp`.

**K2.9 Voice logic. Size M.** Move, with their tests, the state machines and helpers: `HandsFree` (276), `SpeechOutput` (220), `SpeechText` (199), `SpeechController`, `ChoosingTts`, `VoiceMemos` (193), `Transcriber`, `WavWriter`, `OwnVoices`, `VoiceDownloader` (309; `StorageManager` and `File` become `PlatformDirs` and kotlinx-io), `VoiceArchive`. Interfaces added: `Speaker`, `SpeechRecognizerFactory`, `AudioRecorder`, `WakeWordEngine`. Android classes (`AndroidTts`, `LiveSpeech`, `AudioRecorder`, `WakeWord`, `WakeListener`, `HandsFreeService`, `SherpaTts`, `AudioSink`) stay in `app/` as implementations. Done: three-platform tests of the sentence splitter, the hands-free transitions, the memo flow with fakes.

**K2.10 Attachment platform parts. Size M.** `ImageProcessor`, `AudioConverter`, `AudioPlayer`, `FileExport` interfaces; the Android classes become their implementations. The retention and store already moved (K2.3).

**K2.11 Alerts logic. Size S.** `AlertWatcher` (80), `AlertPrefs` (43) common; `AlertNotifier`, `AlertService`, `AlertTaps` stay as the Android `Notifier` and `KeepAlive`.

**K2.12 The container and the HTTP engines. Size L.** `SharedContainer` + `PlatformServices` + `AndroidPlatformServices`; `HttpEngines` interface (OkHttp on Android, Darwin on iOS) so `commonMain` asks for a Ktor client; move `HttpClients` policy into common with the policy tests; `KeystoreSecretStore` and `SecretStore` stay; `CrashReporter` behind an interface; `AppInfo` replaces `BuildConfig` reads; `resolveHost` seam for the page reader. Done: `app/di` is under 150 lines of Android-only wiring; `AppContainerWiringTest` (the text scan) is replaced by a typed test; **gate: owner uses Android for a few days** (like G1) because this touches everything.

*Phase 2b gate ("G2b"): everything the iPhone needs to *run* is in `shared`, tested on three platforms; Android unchanged to use.* Proposed as a soft gate (owner notified, no wait) except after K2.12.

### Phase 3: shared screens

**K3.0 UI foundation spike. Size S to M. Do this before anything else in Phase 3.** Create `ui/` with a theme and one real screen (the Settings "About" page, no logic) and a desktop entry; make `app/` display it from its existing nav host; build it for Android, desktop and both iOS targets in CI. Answers: does Compose Multiplatform 1.12.2 build in a module using `com.android.kotlin.multiplatform.library` (U13); which Material 3, icons, navigation, lifecycle and `collectAsStateWithLifecycle` artifacts resolve on Android without conflicting with `app/`'s Compose BOM (2026.09.00) and navigation 2.10.2; does `app/` build size or method count jump; do Android Compose UI tests still run against shared composables; what the macOS job's time becomes. **Exit:** one PR, CI green on all jobs, the numbers in the plan's table. **Fail:** use the fallback in §4.1, or put UI in `shared` (D50), or stop and report.

**K3.1 Theme, components, the gallery, the UI test harness. Size M.** Theme and colours, typography, `Markdown`, `VoiceBanner`, `CrashBanner`, bubble, card, sheet and dialog components extracted from `chat/ui`, `ui/components`, `printer/ui`; a gallery screen in the desktop app that shows each component in light and dark and at 2x font scale (this replaces `@Preview`); the 31-icon check (§4.6, done in K3.1's first slice); the UI test harness: multiplatform UI tests (`runComposeUiTest`: works on the desktop and the iPhone simulator, shown in K3.0) on the desktop target, including the "every clickable has a name" test (H3) and a dark-theme contrast check; screenshot step in `shared-ios.yml` (§9.3). Done when the gallery runs on Windows and the same components render in the simulator screenshot.

**K3.2 Chat, conversation list, core settings. Size L.** Move `ChatScreen` (606), `ChatComponents` (471), `AttachmentViews` (250), `ChatUiState` (133), `ChatViewModel` (546), the conversation list, `SettingsScreen` (294), `SettingsViewModel` (228), `VoiceSettings`, `WebSearchSettings`, `AttachmentSettings`, `HandsFreeSettings`, the About page, `CrashBanner`. Platform calls go through `PlatformServices` (picker, recorder, permissions, clipboard, share). `ReplyService` stays in `app/` and is reached through `KeepAlive`. Android adopts by D51. Done: the screens render in the desktop window with the fake container; L2 tests for send, stop, regenerate, long press menu, empty and error states; the Android emulator shows the chat unchanged. **Owner check (Android):** one day of normal chat use on the shared screen.

**K3.3 Home: the tab, its settings, clips, live view containers. Size L.** `HomeScreen` (412), `HomeViewModel` (340), `ClipsScreen` (193), `LiveView` (257) and `LiveFullScreen` (137) as containers around `PlatformVideo`/`PlatformLiveVideo`, `HomeSettings` (399), `PageSignInDialog` (244) around `PlatformWebView`, `JarvisDialog` (122). `DemoPicture` (`Bitmap`) becomes a drawn vector so the Demo home works everywhere.

**K3.4 Printer page. Size L.** `printer/ui` (2,671): Status, temperatures, value and data cards, card controls, macros, presets, Controls bar, confirm sheet, console, upload (file picker seam). The Expo app's tests of the same page (C67 E4 to E10) are the acceptance reference; port their fixtures into `shared/commonTest` where the Kotlin tests do not already cover them.

**K3.5 Accounts, calendar, mail, to-do screens. Size L.** `CalendarScreen` (314), `TodoScreen` (399), `AccountsSettings` (603). Sign-in launch goes through `SignInLauncher`.

**K3.6 Navigation and the Android switch. Size M.** `EdithNavHost` and the bottom bar move; `MainActivity` hosts the shared `App()` (an `App(container)` composable in `ui`); the old Android screens are deleted **after** the gate. Per D51 the Android adoption has happened screen by screen before this point; K3.6 removes the duplicates and the temporary switch.

**Gate G3 (owner).** The Android app, drawing shared screens, used for a week. Highest risk to Android (plan).

### Phase 4: the iPhone app (in vertical slices, §8)

**K4.1 The shell, the pipeline, the first real build. Size L.** `iosApp/` from the spike's `project.yml` (bundle id D43: `com.edith.app`; deployment target iOS 17, D44); SwiftUI entry hosting the shared Compose `App` through `ComposeUIViewController`; the injection proof (§4.5) with the share sheet; Info.plist keys (Appendix B); the real `ios-testflight.yml` (moved from the spike's, build number from `github.run_number`, cloud-managed signing with the App Store Connect key in GitHub secrets, typed by the owner); the app icon and launch screen; Xcode 26.x on `macos-15` (device finding 1). **Owner steps (batch):** check the `com.edith.app` identifier is accepted (D43); App Store Connect app record; secrets; install from TestFlight.

**K4.2 Slice 1, chat on the phone. Size L.** `IosPlatformServices` (Keychain `SecretStore`, `PlatformDirs`, `PermissionAsker`, `LocationProvider`, Darwin engines, database file in Library/Application Support); the Ollama address and model flow; D49 settled; the unreachable-Tailscale message. **Owner check (the G0 questions finally answered on a phone):** chat streams over Wi-Fi and over Tailscale; typing, selection, paste, IME with dictation and emoji; scrolling a long chat; VoiceOver reads the controls; dark mode; keyboard never covers the input. A failure here triggers the plan's fallback D41 (native SwiftUI screens over the shared engine) for the failing part only; this WP exists to find out early.

**K4.3 Slice 2, attachments, voice memos, spoken replies. Size L.** Seams 10 to 16. **Owner checks:** a photo to a vision model; an audio file; a voice memo, including a call interrupting the recording; a spoken reply on loudspeaker and with the silent switch on; a file Edith writes, saved to Files and shared.

**K4.4 Slice 3, the home services. Size L.** Alexa through `WKWebView`; Ring; Blink (with the VPN off for sign-in, as the Expo notes say); Meross local control if proven (the Expo table says the local protocol is unverified) and cloud MQTT in K4.7; the direct Moonraker card; clips through `AVPlayer`; Edith's pictures. **Owner checks:** each service with the real device or account, one at a time.

**K4.5 Slice 4, Jarvis and the printer page. Size L.** Pairing, the pin (E3 experiment), the feed, the Printer page, Controls, `printer_control`. **Owner checks:** pair with the real Jarvis (needs the Jarvis side of the Tailscale listener if the Darwin pin fails, `jarvis-tailscale-listener-spec.md`); read the printer; one harmless control; confirm that cancelling a print needs a tap and never a voice.

**K4.6 Slice 5, accounts. Size L.** `AuthSession` with `ASWebAuthenticationSession`; the iOS Google client and `edith://auth` for Microsoft (D55); Google, Microsoft, calendar, mail, to-dos on the phone. **Owner steps:** create the Google iOS OAuth client and register the redirect in the Microsoft app (the Expo chunks C59 and C63 already describe both); sign in. **Owner checks:** each account; the account chooser; "sign in again".

**K4.7 Slice 6, iCloud mail and Meross cloud. Size L, optional.** SwiftMail behind `ImapClient`/`SmtpClient` (E7), MQTT login against Meross's real broker. Either can be cut with the owner's acceptance (D56).

**K4.8 Slice 7a, the voice modes and alerts. Size L.** Wake word through ONNX Runtime (E6), conversation mode, alerts (D53), reply continuation (D54), local notifications. **Owner checks:** real-microphone use for a day; alerts with a real doorbell while the app is open and the catch-up when returning.

**K4.9 Slice 7b, live view. Size L, optional.** `LiveVideo` for Ring (E4) and Blink (E5). Cut with acceptance if both experiments fail.

### Phase 5: parity and retirement

**K5.1 The walk. Size M, the owner's.** Appendix A on a real phone. Each row becomes S, B or an accepted I/N difference, recorded in `ios-parity.md` (kept, rewritten for the new app).
**K5.2 Retire the Expo app and the spike.** Remove `ios-app/`, `spike-kmp` workflows, Expo CI; move useful fixtures; update chunk statuses (plan §11.3).
**K5.3 Docs.** `AGENTS.md`, `README.md`, `01-architecture.md` (new layout), `05-environment.md` (no EAS), decisions file.
**K5.4 Optional.** Module split for build time (D42), a desktop build for the owner's PC.

---

## 8. Order, slices and what can run in parallel

### 8.1 The ordering change (decision D51)

The transition plan runs Phase 3 (all screens) before Phase 4 (iPhone). This document recommends **interleaving**, because the screens are worth more once a phone can show them, and the biggest unknowns (Compose input and VoiceOver on iOS, Swift seam injection, background rules) can only be answered on a phone:

```
K2.5 .. K2.12  (engine, Android-proven)         ← can run in parallel with K3.0 to K3.2
K3.0  spike ─ K3.1 theme/components ─ K3.2 chat ─┬─► K4.1 shell+pipeline ─► K4.2 chat on the phone  [slice 1: TestFlight "0.1"]
                                                  │
                                                  └─► Android adopts chat (D51) ─ owner uses it
K3.3 home ─► (K2.5 done) ─► K4.3 attachments/voice [slice 2] ─► K4.4 home services [slice 3]
K3.4 printer ─ (K2.8 done) ─► K4.5 Jarvis + printer [slice 4]
K3.5 accounts screens ─ (K2.6, K2.7 done) ─► K4.6 accounts [slice 5] ─► K4.7 iCloud mail + Meross cloud [slice 6]
K4.8 voice modes, alerts [slice 7a]     K4.9 live view [slice 7b]
K3.6 nav + remove old Android screens ─► G3 ─► K5.1 walk ─► K5.2 retire
```

### 8.2 Slices (what the owner can try, in order)

| Slice | TestFlight build | Rows closed (matrix) | Owner can… |
|---|---|---|---|
| 1 | 0.1 | A1–A8, A12, A13, B1, B2, B4, B11, G1, G4, G8 | chat with Edith from the phone, switch models, see weather |
| 2 | 0.2 | A9, B12, C1–C8, D1, D2, D4, G2, G6 | send photos, audio, voice memos; hear replies; get files from Edith |
| 3 | 0.3 | B9, C8, F1–F7, F10, G7 | use Home, Ring, Blink, Alexa, the printer card |
| 4 | 0.4 | B10, E8, F11 | read and control the printer through Jarvis |
| 5 | 0.5 | B6–B8, E1–E2, E4–E6, E9–E10, G5 | use calendar, mail, to-dos, all accounts |
| 6 | 0.6 | E7, F3 | iCloud mail, Meross when away |
| 7 | 0.7 | A10, A11, D6–D8, F8, F9 | hands-free, alerts, live view |
| 8 | 1.0 | all | the walk (K5.1) |

### 8.3 What can run in parallel

Phase 2b WPs touch different packages and can run in separate worktrees (one writer per set of files; AGENTS.md rule). K3.0 to K3.2 can overlap K2.5 to K2.11. Do not run two WPs that both edit `AppContainer` (K2.5, K2.6, K2.8, K2.9 each add lines to it): either serialise them or do K2.12 first for the new objects. The owner's own steps (Apple, Google, Microsoft, Jarvis side) are batched and listed in §13 so they can happen while agent work continues.

---

## 9. Testing and proof

### 9.1 What each WP must leave behind

- Moved logic: its existing tests moved to `commonTest` and green on desktop, Android host and the iOS simulator (the G2 standard; test names with letters, digits, spaces, apostrophes and hyphens only).
- New seam: an interface, a fake in `commonTest/testing`, and a test that a screen or service behaves correctly with the fake. The Android and iOS implementations are covered by L3 owner checks plus, where cheap, an Android instrumented test and an iOS simulator test.
- New screen: an L2 UI test of its states (empty, loading, error, long text, confirm) and a simulator screenshot in the PR.
- Anything with coroutines, time or locale: an iOS simulator run before "done".

### 9.2 Jobs and budgets

| Job | Runs on | Trigger | Budget (measured now, to re-measure) |
|---|---|---|---|
| `check` (Linux): build, Android unit tests, lint | every PR | all | about 1.5 to 3.5 min |
| `ios-tests` (macOS): shared tests on the simulator | `shared/**`, Gradle files | engine PRs | 2 to 4 min warm |
| `ios-ui` (macOS, **new**): `ui` tests, screenshot, `xcodebuild` of `iosApp` | `ui/**`, `iosApp/**`, `shared/**` | UI PRs | unknown; Compose for Native compiles slowly. Target under 12 minutes (K0.7's target); if exceeded: split engine and UI jobs, cache `~/.konan` (already), build only `iosSimulatorArm64`, skip `xcodebuild` unless `iosApp/**` changed |
| `ios-testflight` (macOS, manual) | owner-triggered | after a slice | about 15 to 18 min cold in the spike |
| Android instrumented | owner's emulator only; **never** run `connectedDebugAndroidTest` without asking (it wipes data) | | compile only in CI |

### 9.3 Seeing the UI without a phone

Desktop window (`:desktopApp:run`) for every screen with a fake container; a simulator screenshot step (`xcrun simctl io booted screenshot`) in `ios-ui` that attaches PNGs to the run, so the owner sees the iPhone rendering in the PR (the spike did this). Wait for the app's own "ready" log line before the screenshot (device finding: first launch took 18 s) and read the log for `Uncaught Kotlin exception`: a crash is a failure, never noise.

### 9.4 What simulators cannot show (always L3)

Real microphone and speaker, interruptions, the silent switch, Keychain after a reboot, a real Tailscale route, push, performance on a real chip, the keyboard's actual behaviour, VoiceOver gestures, thermal behaviour during a long reply.

---

## 10. Keeping Android safe while all this happens

1. Principle 1 of the plan holds: `scripts/check.sh` and the UTC run stay green on every PR; the owner can ship Android at any step.
2. **Adoption of shared screens (D51).** Recommended: **one screen per PR, flipped by that PR**, with `git revert` as the rollback; the previous Android screen file is deleted only in K3.6 after G3 (so a revert is one PR, not a rebuild). Chat is the exception: it ships behind a hidden "new chat screen" toggle in debug builds for one owner-use day before becoming the default, because it is used every day. Alternative: one switch at K3.6 (fewer PRs, but ten thousand lines change owner-visibly at once; not recommended).
3. The 27 Android instrumented test files (compile-only in CI, never run in this series; not all test screens) are re-pointed at shared composables where they test screens; they keep compiling every PR. Before G3 the owner should let one session run them on the emulator (they wipe data: ask first).
4. Android binary size and startup: measured in K3.0 and again at K3.6 (APK size, cold start on the emulator); a jump above what the owner accepts is a finding, not a surprise.
5. Anything removed from Android (the Play-services Google sign-in, the loopback receiver) is **not** removed in this plan; it stays as the Android implementation of its seam.

---

## 11. Risks and the experiments that retire them

Ordered by (risk × cost of finding it late). Each experiment is a throwaway or a small PR with an exit test; a failure triggers the named fallback and goes to the owner.

| # | Risk | Experiment (exit test) | Fallback | When |
|---|---|---|---|---|
| E1 | Compose Multiplatform does not coexist with `com.android.kotlin.multiplatform.library` or `app/`'s Compose BOM (U13) | K3.0: a shared screen builds on four targets and runs on the emulator and desktop | KMP plugin with `androidTarget()` for `ui` only; or UI inside `shared`; or native SwiftUI screens (D41) | first |
| E2 | Compose text input, selection, IME or VoiceOver on iOS is not good enough (G0's open check) | K4.2 on a real phone: the Appendix A chat scenarios with VoiceOver and a hardware-free keyboard | native SwiftUI for the chat input only, or whole chat (D41) | first phone build |
| E3 | The Swift-to-Kotlin seam injection does not behave as §4.5 assumes | K4.1: the share sheet seam end to end, including a callback and an `@Throws` error | Kotlin-only seams with `platform.*` APIs, accepting their gaps | K4.1 |
| E4 | Ring WebRTC video in a Compose `UIKitView` | spike: `stasel/WebRTC` renders a test track in the app | defer live view for Ring; snapshots and clips remain | before K4.9 |
| E5 | Blink's raw TLS + MPEG-TS feed has no AVPlayer path | spike: demux H.264 from the TS in Kotlin, feed `AVSampleBufferDisplayLayer`; second option: local HLS repackaging | defer live view for Blink | before K4.9 |
| E6 | Wake word through ONNX Runtime on iOS keeps up in the foreground; one ONNX copy | spike (K0.6b 2 and 3 are still open): the three-model pipeline runs on 16 kHz PCM from `AVAudioEngine` | conversation mode only (D52) | before K4.8 |
| E7 | SwiftMail against iCloud (U6) | spike with the owner's app-specific password | defer iCloud mail on iPhone (D56) | before K4.7 |
| E8 | Darwin certificate pin for the self-signed Jarvis cert (U10) | spike: a server-trust challenge handler calls `PinCheck` and accepts only the right cert | Tailscale-certificate listener (D39, the Jarvis spec exists) | before K4.5 |
| E9 | Background behaviour: how long a reply survives backgrounding; local notification catch-up | K4.8 measurements on a real phone | tell the owner the real number (D54) | K4.8 |
| E10 | Kotlin/Native memory and speed with a large chat (a 1,000-message conversation) and 12 MP photos | K4.2/K4.3 on a real phone | thumbnail sampling, paging in the repository | K4.2 |
| E11 | Build time of Compose for iOS makes the PR loop slow | measured in K3.0 | job split (§9.2), module split (D42) | K3.0 |
| E12 | Meross: username and password on DitchOoM, the real broker, and a local protocol | owner step with the real account | cloud only, or defer | K4.7 |
| E13 | Android regression from replacing OkHttp-adjacent hooks (Blink cookies, Paho) | device-check each swap on the emulator and, for Blink and Meross, with the owner's real accounts | revert the swap PR | K2.5 |
| E14 | The Google iOS client or Microsoft redirect is refused | owner steps; compare the scheme with the provider's current page (C59 text) | there is no loopback fallback on iOS and Google forbids sign-in inside an embedded web view: report to the owner; that account would be unavailable on iPhone | K4.6 |

Other risks (from the plan, still true): version churn (upgrades are their own PRs with `ios-ui` green); Apple's rules for TestFlight (honest permission strings, Appendix B); the Expo app drifting (frozen, D46); owner attention (owner steps are batched, §13).

---

## 12. Decisions for the owner

Recommended answer first. Decisions D40 to D49 are in the transition plan; these continue the numbering.

| ID | Question | Recommended | Needed by |
|---|---|---|---|
| D50 | Where does the UI code live? | **A separate `ui` module**, decided finally by K3.0's result; fall back to `shared` if the module cannot host Compose | K3.0 |
| D51 | How does Android adopt shared screens, and does the iPhone app come before all screens are shared? | **Interleave** (§8): iPhone slice 1 right after the shared chat screen; Android flips **one screen per PR**, chat behind a debug toggle for a day | before K3.2 |
| D52 | Wake word on iPhone | **Only while Edith is on screen** (D41 already says so); conversation mode (tap once, talk) everywhere | K4.8 |
| D53 | Camera alerts away from home | **Option A** (alerts while open, a catch-up on return, best-effort local notifications); option B (Jarvis pushes through Apple) only if the owner wants real alerts and accepts a Jarvis change and an APNs key | K4.8 |
| D54 | A reply when the owner leaves the app | **Request background time and show a clear "interrupted, tap to continue" if cut**; do not promise Android's behaviour | K4.8 |
| D55 | Google sign-in on iPhone | **An iOS OAuth client (public, PKCE, no secret, reversed-client-ID redirect)**, not the Desktop client Android uses; Microsoft with `edith://auth` | K4.6 |
| D56 | iCloud mail and Meross cloud on iPhone if the experiments fail | **Defer them** and mark the rows accepted differences; do not block parity on them | K4.7 |
| D57 | Edith's own on-device voice on iPhone | **Not planned** (iPhones have a voice); keep the seam | K4.3 |
| D58 | Crash report parity | **Same banner and file** fed by Kotlin's unhandled-exception hook, plus Apple's logs for native crashes | K3.2 |
| D59 | Features the Expo app has that Android lacks (check at the walk; the Expo conversation mode and its tap-to-start may differ from Android's hands-free) | **Keep what is harmless, list it as B, do not remove for symmetry** | K5.1 |
| D60 | UI languages and localisation | **Out of scope here** (strings are literals today) | any time |

Existing decisions this plan depends on and that are still open: **D47** (licence), **D49** (App Transport Security, K4.2), **D43** (bundle id check), **D45** (iCloud mail).

---

## 13. Owner-only steps (batched)

Nothing here can be done by an agent; each is listed so it can be done while code work continues. None involves typing a secret into chat.

1. **K4.1:** confirm the bundle identifier `com.edith.app` is accepted by App Store Connect and create the app record; add the App Store Connect API key and signing secrets in GitHub (the spike's `DEVICE_BUILD.md` is the checklist); delete the spike's key when the spike is retired; confirm on GitHub's billing page that macOS minutes are free for the public repository (U9).
2. **K4.2:** decide D49; install each TestFlight build; run the slice's checks (Appendix A rows named in §8.2).
3. **K4.5:** the Jarvis side, only if the Darwin pin fails (E8); pair with the real Jarvis.
4. **K4.6:** create the Google **iOS** OAuth client and add it to the project's consent screen test users; register `edith://auth` in the Microsoft app; sign in each account.
5. **K4.7:** an iCloud app-specific password for E7; the Meross account for E12.
6. **K4.8:** a real doorbell event; a day of real-microphone use.
7. **Gates:** G2b (after K2.12), G3, G4 (the walk), and decisions D50 to D60.
8. Before G3: allow one run of the Android instrumented tests on the emulator (they wipe its data; ask first).

---

## 14. Documentation to change, and inconsistencies found

**Change with the work:** the plan's progress table (each WP, in its PR); `ios-parity.md` (rewritten around this matrix at K5.1; until then each Expo row stays as the reference); `kmp-handoff.md` (the "next steps" section moves to this plan's order once the owner approves); `AGENTS.md` (one sentence: Phase 3 started, link here); `README.md` of `docs/design` (a link under the KMP banner); `05-environment.md` (Compose desktop run, the TestFlight steps) at K4.1; `01-architecture.md` at K5.3.

**Inconsistencies noticed while writing (not fixed here; flagged so nobody trusts the wrong line):**
- The transition plan says `AppContainer` is 610 lines; it is 589 now (DI total with `ViewModels` 618).
- `docs/design/README.md` lists C32 (live view) as built on an unmerged branch and the camera wall as open, while `app/` already has `LiveView`, `LiveFullScreen`, `RingRtc` and `BlinkLiveStream`. This plan assumes the code in `app/` is the truth and that the camera wall and the chat "look" button are not yet on Android either (rows F8, F12).
- The plan's Phase 4 table has K4.4 "Home services ... clips" and K4.7 "iCloud mail, Meross cloud" as pure iPhone work; the Android code for those services is not yet in `shared` (K2.5, K2.7 here). The plan's wave W4 covers it, but no K number does; this document adds them.
- `ios-parity.md` marks two rows "Not planned (proposal)" that no owner decision has accepted (crash report, Google through the browser); here they are D58 and D55. The Expo "Google through the browser: not planned" row was written for Expo and does not apply: the iOS browser flow is exactly what E2 builds.
- The handoff's "Things waiting on the owner" still lists the Android phone check of the Ollama-on-Ktor change; it is unknown whether that was done.

---

## Appendix A. The parity walk (scenarios the owner runs on a TestFlight build)

Run each as one short scenario on the real phone, with the real services where the row names one. The owner marks pass, fail or accepted difference. Run with VoiceOver for the ones marked (VO).

1. **Chat over Wi-Fi:** type, send, watch a reply stream, Stop, send again, Regenerate (VO).
2. **Chat over Tailscale** with the app freshly opened after Tailscale slept: the message says what to do.
3. **Conversations:** new, rename, delete, clear, titles appear on their own.
4. **Model and settings:** change model, see "Sees images"; Test connection; change server address; dark mode and the largest font size.
5. **Tools:** "what time is it", "weather here" (location prompt), "weather in <a place>", a web search, "read this page <public URL>"; a private address is refused.
6. **Confirmation:** a request that changes something shows a card; approve and cancel; nothing proceeds by voice.
7. **Photo:** attach, ask about it; a very large photo; Delete all attachments.
8. **Audio:** attach an audio file; send a voice memo; interrupt a recording with a call.
9. **Spoken reply:** loudspeaker, earpiece, silent switch on; change speed; Test voice; Stop.
10. **Edith's file:** ask for a file; open it; Save to Files; Share; ask to resend an earlier attachment.
11. **Home (Demo):** chips, rooms, favourites, hide, locks section, command log.
12. **Alexa, Ring, Blink, Meross, printer card:** sign in each (VPN off for Blink); control one thing; sign in again; disconnect.
13. **Clips:** open the Clips chip, play one.
14. **Live view** (if built): Ring and Blink.
15. **Jarvis:** pair; read the printer; one harmless control; start a print and confirm that cancelling needs a tap; the console; a macro; an upload.
16. **Accounts:** Google, Microsoft, iCloud, Jarvis work account: add, rename, sign in again, remove; default calendar.
17. **Calendar tools:** "what is on tomorrow", add an event, change it; the Calendar tab and the To-do switch.
18. **Mail tools:** search, read, draft, send (confirm card).
19. **To-dos:** list, add, complete, the ranking by the model.
20. **Hands-free** (D52): conversation mode; wake word with the app open.
21. **Alerts** (D53): enable, trigger, see the catch-up on return.
22. **Reply while leaving the app** (D54): start a long reply, switch away, return.
23. **Crash report** (D58): cause a debug crash; banner appears; last report shown.
24. **Restart:** kill and reopen; everything is still signed in; conversations and photos are there.
25. **Large chat:** a 1,000-message conversation scrolls; cold start is acceptable.

## Appendix B. The iPhone shell: Info.plist and capabilities (from the spike's findings and the seams)

`CADisableMinimumFrameDurationOnPhone: true` (without it Compose throws at start on a 120 Hz phone, spike run 14); `ITSAppUsesNonExemptEncryption: false`; `NSMicrophoneUsageDescription` (voice memos, conversation mode); `NSSpeechRecognitionUsageDescription` (live recognition); `NSLocalNetworkUsageDescription` (Ollama, printer, devices on the home network); `NSLocationWhenInUseUsageDescription` (weather here); `NSPhotoLibraryUsageDescription` only if the picker or saving needs it (PHPicker does not); `NSAppTransportSecurity` per D49 (`NSAllowsArbitraryLoads` alone, never together with `NSAllowsLocalNetworking`, device finding 2); `CFBundleURLTypes` for `edith` and the Google reversed client ID scheme; `UIBackgroundModes` only if D52 or D53 choose audio or fetch; `aps-environment` entitlement only if D53 chooses option B; Keychain access group not needed (one app); `UISupportedInterfaceOrientations` portrait; `TARGETED_DEVICE_FAMILY: 1`; deployment target iOS 17 (D44); the 1024 px opaque icon; honest permission sentences (the Expo `app.json` strings are the starting text).

## Appendix C. Where each `app/` package goes

| Package | Lines | To `shared` as is or mechanically | Stays in `app/` as an implementation | To `ui` |
|---|---:|---|---|---|
| `accounts` | 3,563 | account model, store, sources, tasks, tools (K2.6a) | sign-in launch glue | `CalendarScreen`, `TodoScreen` |
| `alerts` | 370 | `AlertWatcher`, `AlertPrefs` (K2.11) | `AlertNotifier`, `AlertService`, `AlertTaps` | |
| `alexa` | 1,149 | all (K2.5) | WebView sign-in view | |
| `attachments` | 430 | interfaces (K2.10) | `AttachmentIntake`, `AudioToWav`, `AttachmentPlayer`, `FileExport`, `AttachmentStore` wrapper | |
| `blink` | 1,275 | API, parse, service (K2.5) | IPv4 lookup hook; `BlinkLive` raw socket (seam 9) | |
| `chat` | 2,283 | none left (engine done) | `ReplyService` | screen, components, markdown, view model |
| `core` | 295 | policy tests, `HttpClients` policy (K2.12) | Keystore, permissions, tagged socket factory, `CrashReporter` | banners |
| `di` | 618 | `SharedContainer` | `AndroidPlatformServices` | |
| `google` | 2,600 | clients, `BrowserOAuth`, `OAuthProvider` (K2.6b) | `LoopbackReceiver`, Play-services auth, Custom Tabs | |
| `home` | 1,377 | `TrackLiveFeed` types, Demo picture as a vector | | `HomeScreen`, `HomeViewModel`, clips, live containers |
| `icloud` | 1,183 | CalDAV, iCalendar, calendar source (K2.7) | Jakarta Mail implementation | |
| `jarvis` | 425 | `JarvisSources`, accounts logic (K2.8) | `OkHttpPinnedHttp` | `JarvisDialog` |
| `meross` | 753 | all, over DitchOoM (K2.5) | | |
| `microsoft` | 917 | all but the redirect receiver (K2.6c) | | |
| `printer` | 3,355 | Moonraker direct, prefs (K2.5) | | all of `printer/ui` (K3.4) |
| `ring` | 938 | API, service (K2.5) | `RingRtc` (seam 23) | |
| `settings` | 237 | | | `SettingsViewModel` |
| `tools` | 174 | | `DeviceLocation`, `PageReaderAndroid` | |
| `ui` | 2,808 | | | settings screens, theme, navigation, banners |
| `voice` | 2,998 | logic (K2.9) | recorder, recognizer, TTS, wake word, sherpa, services | settings screens (already counted in `ui`) |
