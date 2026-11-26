# Android and iPhone parity audit (shared-code app)

**Written 2026-11-26 from the code, not from running anything.** It compares the Android app with the iPhone app built from the same shared code (`shared/`, `ui/`), row by row, using the feature
list in [`kmp-parity-plan.md`](kmp-parity-plan.md) §5 (rows A1 to G8). The old Expo table, [`ios-parity.md`](ios-parity.md), describes the frozen Expo app and is not this.

**How to read the status.** The two apps share the engine, the tools, the chat, Home, Printer, Calendar and Settings screens, so most rows are the same code by construction. What can differ is the
platform part each side adds. Status words:

| Word | Meaning |
|---|---|
| **Same** | The same shared code runs on both; no platform part needed |
| **Built** | The iPhone has its own platform part (a file in `shared/iosMain` or `ui/iosMain` or `iosApp/`); it compiles and CI builds it, **no phone has run it** unless the row says so |
| **By design** | Different on purpose, with the decision that says why |
| **Gap** | Android has it, the iPhone does not, and no decision says it should not |
| **Dead** | The control is on the iPhone's screen and does nothing |

**The method that found finding 1:** compare what Android's `ChatScreen` passes to `ChatContent` with what `SimpleChatScreen` passes. Anything left at its default is a dead or hidden control on the iPhone. Repeat it for each screen pair when a shared component gains an action.

**Checked on the owner's iPhone so far (all of it, 2026-11-25/26):** Microsoft sign-in, Microsoft mail and calendar. Nothing else. Every "Built" row below is unproven on a phone.

## Findings: what needs work

| # | Finding | Where | Size |
|---|---|---|---|
| 1 | **Dead, then fixed (K4.21): three controls the iPhone chat left unwired.** `SimpleChatScreen` (the iPhone's chat glue) passed only some of `ChatActions`; Android's `ChatScreen` passes more. Missing on the iPhone: **View, Save and Share on Edith's file cards** (the default empty `FileActions`), **a photo full screen** (`onOpenImage`: tapping a photo did nothing) and **play/stop on an audio attachment** (`onToggleAttachment`). Now wired: a shared `FileExporter` (View reads the text; the iPhone's `IosFileExporter` opens the Files picker for Save and the share sheet for Share), `ImageViewer` and `FileViewer` dialogs, and the view model's playback toggle. Compiles; never tried on a phone. | C4, C6 | done |
| 2 | **Gap: hands-free settings and the wake word.** The iPhone has Talk instead. The wake word is paused (the owner, 2026-11-26: the ONNX Runtime Swift package is gone). Android's Hands-free settings (sensitivity, Test) have no iPhone counterpart, which is right while there is no wake word. | D6, D8, G3 | paused |
| 3 | **Gap: camera alerts while Edith is closed.** The iPhone alerts only while open, plus the summary on return (D53). Designed (`ios-alerts-while-closed.md`, `jarvis-push-spec.md`), needs Jarvis's side first. | F9 | M + Jarvis |
| 4 | **Closed by this branch (D54, D58):** a reply now asks iOS for extra time when it starts (A10), and the iPhone writes a crash report and shows the banner and "View last crash report" (A11, G8). Both compile; neither has run on a phone. | A10, A11 | done |
| 5 | **Unproven and the most likely to be wrong:** Blink Live (Apple's player may reject the camera's H.264 stream), Ring Live (WebRTC from Swift), Google sign-in (waits on Google applying the bundle id), alerts, Alexa, Meross, the printer, Jarvis, iCloud mail. All built, none tried. | F3 to F11, E2, E7, E8 | owner's checks |
| 6 | **Not compared at all:** whether each shared screen *looks* right on the iPhone. Two overlapped the status bar (Home, Calendar; fixed in PR 85). Chat and the Printer page use a Scaffold and have not been looked at. Only a phone shows this. | all screens | owner's checks |

Nothing else in the plan's list turned up as missing. A list built from the plan is only as good as the plan: a feature Android gained after 2026-11-24 would not be in it (see "How to keep this honest").

## A. Chat and conversations

| Row | Feature | Status | Evidence |
|---|---|---|---|
| A1 | Streaming chat, persona, Stop | Same | `chat/engine`, `ChatContent` (shared) |
| A2 | Regenerate, copy, delete, Play/Stop on a bubble | Same | `ChatComponents` (shared) |
| A3 | Several conversations: list, rename, delete, new, clear | Same | `ChatContent`, `ChatViewModel` (shared); wired in `SimpleChatScreen` |
| A4 | Automatic titles | Same | shared titler |
| A5 | Model picker with "Hears audio" / "Sees images" | Same | `SettingsContent`, `ModelSelection` (shared) |
| A6 | Server address, Test connection, local network | **By design** (D49) | `ServerUrl` with `AtsPlainHttpRule`: the iPhone refuses a plain-http Tailscale number and asks for the `.ts.net` name; iOS asks for local-network access itself |
| A7 | Timestamps, auto-scroll, thinking bubble | Same | shared |
| A8 | Draft per conversation | Same | `ChatViewModel` (shared) |
| A9 | Markdown in replies | Same | `Markdown.kt` (shared) |
| A10 | A reply keeps running after leaving the app | **Built** (this branch, D54) | `ReplyKeepAlive` (shared, 4 tests) + `beginBackgroundTask`; iOS lends about 30 s, so a very slow reply can still be cut off |
| A11 | Crash banner, "View last crash report" | **Built** (this branch, D58) | `IosCrashReporter`: a Kotlin crash writes a report; a crash in Swift or a library is told by a running marker, with where the system's own log is |
| A12 | Bottom bar | Same | `MainViewController` (four tabs, as Android) |
| A13 | Light and dark theme | Same | `EdithTheme` |

## B. Tools

The tool list is identical on both: time, weather, web search, page reading, calendar, mail, to-dos, the smart-home tools and `printer_control` (compare `AppContainer` and `IosContainer`). All are shared code.

| Row | Feature | Status | Note |
|---|---|---|---|
| B1 to B3 | Time, weather, location | Same / **Built** | `IosLocationProvider` for "here" |
| B4 | Web search (DuckDuckGo, optional Ollama key) | Same | settings row is shared |
| B5 | Read a web page | **By design** (D49) | public pages only, as before; plain-http pages no longer open on the iPhone (ATS), https pages do |
| B6 to B8 | Calendar, mail, to-do tools | Same | shared |
| B9, B10 | Smart-home tools, `printer_control` | Same | shared |
| B11 | Confirmation cards; never by voice | Same | shared |
| B12 | Tools that need a permission ask for it | **Built** | location through `IosLocationProvider`; microphone and speech through `IosMicrophoneAccess` |

## C. Attachments

| Row | Feature | Status | Evidence |
|---|---|---|---|
| C1 | Attach photos | **Built** | `IosPhotoPicker`, `IosAttachmentIntake` |
| C2 | Resize to JPEG, vision gate | **Built** | `IosAttachmentIntake`; the gate is shared |
| C3 | Attach audio files, converted to WAV | **Built** | `IosFilePicker`, `AudioToWav` |
| C4 | Viewer, bubbles, player | **Built** (K4.21) | `AttachmentViews` (shared); `IosAttachmentPlayer`; the photo full screen and the audio ▶/■ were unwired on the iPhone until K4.21 (finding 1) |
| C5 | Retention, Delete all | Same | `AttachmentSettingsSection` (shared) |
| C6 | Edith's files: View, Save, Share | **Built** (K4.21) | `IosFileExporter` (Files picker, share sheet, a copy under the file's own name) |
| C7 | Resend an earlier attachment | Same | shared |
| C8 | Edith's own pictures | Same | shared |

## D. Voice

| Row | Feature | Status | Evidence |
|---|---|---|---|
| D1 | Voice memo | **Built** | `IosRecorder` |
| D2 | Live recognition for models that cannot hear | **Built** | `IosSpeechInput` (`SFSpeechRecognizer`) |
| D3 | Dictation into the text box | **By design** | the iPhone keyboard's own key |
| D4 | Spoken replies, speed, Test voice | **Built** | `IosTtsEngine`; Settings → Voice (`IosVoiceSection`). Uses the voice chosen in the phone's Accessibility settings |
| D5 | Edith's own voice, downloads | **By design** (D57) | every iPhone has a speech engine; not planned |
| D6 | Hands-free with "Hey Edith" | **Gap, paused** | Talk button instead (K4.11) |
| D7 | Follow-ups without the wake word | Same | the shared `HandsFree` loop runs on the iPhone |
| D8 | Hands-free settings | **Gap, paused** | nothing to set without a wake word |
| D9 | Voice banner (set up a speech engine) | **By design** | Android-only problem: some phones have no engine |

## E. Accounts, calendar, mail, to-dos

| Row | Feature | Status | Evidence |
|---|---|---|---|
| E1 | Several accounts, default calendar, rename | Same | shared |
| E2 | Google sign-in through the browser | **Built** (iOS client ID is in; not tried) | `IosAuthSession`; waits on Google applying the bundle id |
| E3 | Google Play-services sign-in | **By design** | Android-only |
| E4 | Google Calendar, Gmail, Tasks | Same | shared |
| E5 | Microsoft mail and calendar | **Checked on the phone** | sign-in, mail and calendar worked (2026-11-26) |
| E6 | iCloud calendar | Same | shared |
| E7 | iCloud mail (IMAP, SMTP) | **Built** | `NsStreamTransport` |
| E8 | Work account through Jarvis | **Built** | `DarwinPinnedHttp`, `IosAccounts` |
| E9, E10 | Calendar tab, accounts settings | Same | `IosCalendarScreen`, `AccountsScreen` over shared frames |

## F. Home

| Row | Feature | Status | Evidence |
|---|---|---|---|
| F1, F2 | Home tab, Settings → Home | Same | `IosHomeScreen`, `HomeServicesSection` over shared screens |
| F3 | Meross garage doors | **Built** | MQTT over `NsStreamTransport` |
| F4 | Alexa | **Built** | Amazon's page opens in Safari and the owner pastes the address it ends on (as Android's "Use my browser"); Apple's cookie jar is off for it (K4.9) |
| F5 | Ring: sign-in, devices, snapshots, events | Same | shared `RingService` |
| F6 | Blink: sign-in, cameras, snapshots, motion | Same | shared; cookie and address handling in `DarwinKtorClients` |
| F7 | Clips: chip, list, player | **Built** | `IosClipPlayer` |
| F8 | Live view | **Built, unproven** | Blink: `BlinkStreamRunner` + `LocalHlsServer` + Apple's player; Ring: WebRTC from Swift (`RingRtc.swift`). Both show diagnostic lines under the picture |
| F9 | Camera alerts | **By design** (D53) | while open plus a summary; while closed is finding 3 |
| F10, F11 | 3D printer, printer page through Jarvis | Same | `IosPrinterScreen` over shared screens |

## G. Settings

| Section | Status |
|---|---|
| Server and model | Same |
| Voice | **Built** (no own-voice downloads: D57) |
| Hands-free | **Gap, paused** (finding 2) |
| Web search key | Same |
| Accounts | Same |
| Attachments | Same |
| Home (services, Alerts, hidden, Demo, log) | Same; Alerts text differs (D53) |
| About: versions, last crash report | **Built** (this branch) |

## How to keep this honest
- A row marked **Same** stays same only while no one adds an `app/`-only copy of a screen or a tool. The cheap check: the tool lists in `AppContainer` and `IosContainer` match (they do today), and `ChatScreen` (Android's glue) passes nothing to a shared component that `SimpleChatScreen` (the iPhone's) leaves at its default: finding 1 is exactly that.
- New Android features need a row here in the same PR that adds them.
- A phone check turns **Built** into proven; record it in the row, with the date, as E5 is.
