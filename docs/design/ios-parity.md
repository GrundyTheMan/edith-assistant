# iOS parity table (C76)

One row per user-visible feature of the Android app, and where the iOS app (`ios-app/`, Expo) stands. Kept current: **update the row in the same PR that
changes it.** First written 2026-11-20 from `00-product.md` §1–6, the chunk table (`README.md`), every built chunk's "As built" and "Not verified", and a read of the
Android app's screens and settings. **The Android emulator was not clicked through for this first pass**: rows below come from the Android source and docs, so a feature
the docs never mention may be missing from this table. Add it when found.

**Statuses**
- **Built** — in the code, tested; "*owner check open*" means nothing has been tried on the iPhone yet (almost everything: see each chunk's "Not verified").
- **Partial** — part of it is built; the missing part and its chunk are named.
- **Blocked** — designed, waiting for the development build (C58: the owner's Apple Developer membership, an Expo account, the iPhone registered).
- **Different** — done another way on iOS, with the reason.
- **Not planned** — needs a reason the owner has accepted. *None has been accepted yet: every "not planned" row below is a proposal for the owner to confirm.*

## Chat and conversations

| Android | iOS | Notes | Chunk |
|---|---|---|---|
| Chat with streaming replies, Edith's persona, Stop | Built — *owner check open* | Same persona text and tool-call protocol. | C40 |
| Regenerate the last reply; Copy; Play / Stop speech | Built — *owner check open* | | C40, C13 |
| Conversations: list, rename, delete, new, "Clear" | Built — *owner check open* | | C40 |
| Automatic chat titles | Built — *owner check open* | | C40 |
| Model picker, "Hears audio" / vision capability lines | Built — *owner check open* | Settings → Model. | C40 |
| Server address, Test connection, local-network permission | Built — *owner check open* | iOS asks for local network itself the first time; whether Expo Go shows the prompt is unconfirmed. | C40 |
| Timestamps, auto-scroll, "thinking" bubble with a status label | Built — *owner check open* | | C40 |
| Per-conversation draft kept | Built | | C40 |
| Bottom bar: Chat, Calendar, Home, Settings | **Partial** | Chat, Home, Settings. **Calendar tab is Blocked** (accounts need the development build). | C60 |
| Crash report banner and "View last crash report" | **Not planned** (proposal) | Android writes its own crash report. Expo Go and a standard build give no hook for a JavaScript-and-native crash report; Apple's own crash logs (Xcode Organizer, TestFlight feedback) replace it. The owner's rule "crashes are never acceptable" still applies: investigate from those logs. | — |

## Tools

| Android | iOS | Notes | Chunk |
|---|---|---|---|
| Date and time, weather (here / a place), location | Built — *owner check open* | Location permission string in `app.json`. | C41 |
| Web search | **Different** | Android uses Ollama's web-search key (Settings → Web search). iOS uses DuckDuckGo with no key. Whether to add Ollama's key as an option is the owner's call. | C42 |
| Read a web page | Built — *owner check open* | | C42 |
| Calendar tools (read, add, change) across accounts | Blocked | Needs accounts. | C59, C60 |
| Mail tools (search, read, draft, send) | Blocked | | C61 |
| To-do tools (Google Tasks) and the Agenda / To-do switch | Blocked | | C62 |
| Smart-home tools: `home_devices`, `home_control`, `camera_snapshot`, `recent_events`, `printer_status` | Built — *owner check open* | Every unlock, open, close and mode change waits for a tap on a card, never a voice. | C46, C49, C51, C55 |
| Confirmation cards before any change | Built | Conversation mode never approves by voice. | C45, C57 |

## Attachments

| Android | iOS | Notes | Chunk |
|---|---|---|---|
| Attach photos | Built — *owner check open* | | C43 |
| Photos kept across restarts (setting, default 10 per direction), Delete all | Built — *owner check open* | | C47, C49 |
| Edith's files (`<file>` blocks), file cards, share, copy, resend an attachment | Built — *owner check open* | | C56 |
| Attach audio files | **Blocked** | Converting to WAV needs native code. | C68 |
| Camera button in the attach menu | Not on Android either | Optional, only if the owner asks (C76 §4). | — |

## Voice

| Android | iOS | Notes | Chunk |
|---|---|---|---|
| Voice memo: record, transcribe with the model, send | Built — *owner check open* | First real-microphone use of anything. | C44 |
| Live dictation into the text box | **Different** | The iOS keyboard's own dictation key does it. Nothing to build. | — |
| Spoken replies with the phone's voice, speed, Test voice | Built — *owner check open* | Loudspeaker vs earpiece and the silent switch are unchecked. | C40, C13 |
| Edith's own on-device voice (phones with no TTS) | **Not planned** (proposal) | iPhones have a voice. It exists for Andarium only. | — |
| Hands-free: wake word, talk, follow-up | **Partial** | **Conversation mode is built** (tap once, talk, follow-up). The wake word and barge-in are Blocked. | C57 built; C69 |
| Hands-free settings (sensitivity, follow-up window, Test) | Built (conversation mode) | The wake-word test is C69. | C57 |

## Accounts (all Blocked on the development build)

| Android | iOS | Notes | Chunk |
|---|---|---|---|
| Google accounts (several), sign-in, rename, default calendar | Blocked | | C59 |
| Google through the browser (no Play services) | **Not planned** (proposal) | iOS has a real browser sign-in; the workaround existed for Andarium. | — |
| Microsoft (Outlook mail and calendar) | Blocked | | C63 |
| iCloud calendar; iCloud mail | Blocked | Mail needs a TCP/TLS module. | C64, C65 |
| Work account through Jarvis (mail, calendar, to-dos) | Blocked | Needs certificate pinning (native). | C66 |

## Home

| Android | iOS | Notes | Chunk |
|---|---|---|---|
| Home tab: chips, tiles by room, favourites, hide, locks section, command log, Demo home | Built — *owner check open* | | C46 |
| Services in Settings → Home: sign in, sign in again, disconnect | Built — *owner check open* | | C48 |
| Meross garage doors | **Partial** | Control **on the home Wi-Fi**. Away from home needs the cloud MQTT (Blocked); the local protocol is unverified. | C50 built; C70 |
| Alexa devices (lights, plugs, fans, thermostats, locks) | Built — *owner check open* | The first load worked on the owner's iPhone; Lock/Open buttons unverified. | C54 |
| Ring (devices, snapshots, events) | Built — *owner check open* | | C51 |
| Blink (devices, snapshots, motion) | Built — *owner check open* | Turn the VPN off for the first sign-in. | C52 |
| Clips chip and player | Built — *owner check open* | Whether the player sends Blink's header in Expo Go is unknown. | C53 |
| Live camera view, camera wall | Blocked | Needs WebRTC. | C71 |
| Camera alerts | **Partial** | While Edith is open plus a catch-up on return. Away-from-home alerts need Jarvis to send a push (option B, not built). iOS has no foreground service. | C72 |
| 3D printer card (Moonraker, on the home Wi-Fi) | Built — *owner check open* | | C55 |
| Printer page, Controls, macros, console, `printer_control` through Jarvis | Blocked | Needs the Jarvis connection. | C67 |
| Honeywell/Resideo thermostat | Blocked | | C73 |
| Philips lock | **Not built.** The lock is also in Alexa: try that route first (C74). | | C74 |

## Settings

| Android section | iOS | Notes |
|---|---|---|
| Server & model | Built | |
| Voice (speak, speed, Test; own voice) | Built (own voice: not planned, above) | |
| Hands-free | Built as **Conversation** | |
| Web search key | Different (above) | |
| Accounts | Blocked | |
| Attachments | Built | |
| Home (services, Alerts, hidden devices, Demo home, log) | Built | |
| About (version, Ollama version, last crash report) | Partial | Crash report: not planned (above). |

## Things found by the code audit (this PR) and not left as rows

Fixed here: two switches that VoiceOver could not name (Speak Edith's replies; Speak replies during conversation); the picture viewer's close area had no role; one log line printed a stringified storage error (now only the error's name). Standing checks that keep it so are in `ios-app/src/audit.test.ts`.
Accepted, not changed: a few messages shown on screen come straight from a service or the player (Blink's own error words, the video player's error text); they carry no secrets, but they are not always plain. A wording pass belongs with the owner's walk.
