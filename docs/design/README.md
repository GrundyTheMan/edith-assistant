# Edith rebuild — design documents

This folder is the complete design for **Edith v2.0**, a from-scratch rebuild of Edith, a personal-assistant
Android app powered by the owner's local Ollama server. It is written so that **different AI models, working in
different applications, can each implement one chunk independently** and hand the result back as a pull request.

## How to use this (for an implementing AI)

1. Read the **shared context** in order. It's long on purpose; it encodes decisions the owner already made and
   mistakes v1 already made.
   1. [`00-product.md`](00-product.md) — what Edith is, v1 scope, the owner's binding decisions, Edith's persona.
   2. [`01-architecture.md`](01-architecture.md) — stack, packages, DI, data flow, database schema, settings keys, errors, security.
   3. [`02-standards.md`](02-standards.md) — definition of done, code and test standards, owner preferences, git/PR rules.
   4. [`03-ollama-reference.md`](03-ollama-reference.md) — **verified** Ollama API behaviour with real JSON (tool calling, streaming, audio).
   5. [`04-lessons-learned.md`](04-lessons-learned.md) — what went wrong in v1 and the rule each lesson produced.
   6. [`05-environment.md`](05-environment.md) — the owner's machine, emulator runbook, `adb` recipes, Windows gotchas.
   7. [`06-decisions.md`](06-decisions.md) — decided items and **open questions you must ask the owner** before building on them.
2. Take **one chunk** whose dependencies are merged. Read its file completely.
3. Before writing code: check `git status` (other agents may have left work — don't discard it), pull `master`,
   branch `cNN-short-name`, and ask the owner any open decision your chunk lists.
4. Implement, test, verify on the emulator, update docs, open a PR (`02-standards.md` §1, §5–6).
5. Mark the chunk's status in the table below in the same PR.

If something in a chunk is wrong or impossible, don't silently diverge: explain it in the PR and update the
design in the same PR, or ask the owner.

## Chunks

| ID | Chunk | Phase | Depends on | Status |
|---|---|---|---|---|
| [C01](chunks/C01-project-reset-and-skeleton.md) | Project reset and skeleton | 1 Foundation + chat | — | Done |
| [C02](chunks/C02-core-infrastructure.md) | Core infrastructure (DI, errors, logging, crash reports, secrets) | 1 | C01 | Done |
| [C03](chunks/C03-ollama-client.md) | Ollama client (streaming, tool-call DTOs, capabilities) | 1 | C02 | Done |
| [C04](chunks/C04-settings-and-model-picker.md) | Settings store, server setup, model picker | 1 | C02, C03 | Done |
| [C05](chunks/C05-conversation-storage.md) | Conversation storage (Room, full v1 schema) | 1 | C02 | Done |
| [C06](chunks/C06-chat-engine.md) | Chat engine (prompt, context, streaming, cancel, regenerate) | 1 | C03, C04, C05 | Done |
| [C06b](chunks/C06b-multiple-conversations.md) | Multiple conversations: storage (schema v2 + migration) and engine | 1 | C05, C06 | Done |
| [C07](chunks/C07-chat-ui.md) | Chat UI (bubbles, action row, input row, timestamps, conversation list) | 1 | C06, C06b | Done |
| [C08](chunks/C08-tool-framework.md) | **Tool framework and tool loop** (+ `get_current_time`) | 2 Tools | C03, C06, C07 | Done |
| [C09](chunks/C09-weather-tool.md) | Weather tool (Open-Meteo) | 2 | C08 | Done |
| [C10](chunks/C10-web-search-and-reading.md) | Web search and web page reading | 2 | C08 | Done |
| [C11](chunks/C11-device-location.md) | Device location ("here") | 2 | C08, C09 | Done |
| [C12](chunks/C12-voice-input.md) | Voice memos and dictation | 3 Voice | C03, C06, C07 | Done |
| [C13](chunks/C13-spoken-replies.md) | Spoken replies (phone voice, streaming sentences) | 3 | C06, C07 | Done |
| [C14](chunks/C14-hands-free.md) | Hands-free mode (wake word, follow-ups) | 3 | C12, C13, C06 | Done (real-mic check pending) |
| [C15](chunks/C15-attachment-intake.md) | Attachments in (photos, audio files) | 4 Attachments | C05, C06, C07 | Done |
| [C16](chunks/C16-attachment-storage-and-edith-files.md) | Storage, retention (default 10), Edith's files, resends | 4 | C15, C05, C07, C13 | Done |
| [C17](chunks/C17-google-account.md) | Google account sign-in and authorization | 5 Google | C02, C04, C08 | Done |
| [C17b](chunks/C17b-browser-sign-in.md) | Google sign-in through the browser (no Play services: Andarium on the owner's iPhone) | 5 | C17 | Done |
| [C18](chunks/C18-google-calendar.md) | Google Calendar tools and Calendar tab | 5 | C17, C08 | Done |
| [C19](chunks/C19-gmail.md) | Gmail search, read, draft, send | 5 | C17, C08 | Done |
| [C20](chunks/C20-smart-home-architecture.md) | Smart home architecture (**v2, design only**) | 6 Deferred | C08, C17 | Done (D10: direct from the phone; v2 split into C25–C31) |
| [C21](chunks/C21-smart-home-service-notes.md) | Smart home service notes (**v2 research**) | 6 Deferred | — | Done (services checked 2026-11-12) |
| [C22](chunks/C22-several-accounts.md) | Several accounts: the account model, and several Google accounts (D17) | 7 Accounts | C17, C17b, C18, C19 | Done |
| [C23](chunks/C23-microsoft-account.md) | Microsoft account: Outlook mail and calendar | 7 | C22 | Done |
| [C24](chunks/C24-icloud-account.md) | iCloud account: iCloud Mail and Calendar | 7 | C22 | Done |
| [C25](chunks/C25-home-core.md) | Smart home core: device model, tools, Home tab (Jarvis's chips, favourites, hiding), command log (fake backend) | 8 Smart home (v2) | C08, C14, C22 | Done |
| [C26](chunks/C26-meross-garage.md) | Meross garage door opener | 8 | C25 | Done |
| [C27](chunks/C27-ring.md) | Ring doorbell and cameras (snapshots, events, clips) | 8 | C25 | Built: sign-in, devices, snapshots, events and Clips (the owner's live Ring check, and a clip played in a release build, are open) |
| [C28](chunks/C28-resideo-thermostat.md) | Honeywell/Resideo thermostat (official API; needs a developer key) | 8 | C25, C23 | Design |
| [C29](chunks/C29-blink.md) | Blink cameras (fragile; clips) | 8 | C25 | Built; verified live on Andarium 2026-11-15 (sign-in, cameras, clips, snapshots) with the VPN off; live view is C32 |
| [C30](chunks/C30-alexa.md) | Alexa devices: Jarvis's full control, thermostats and locks too (fragile, D19) | 8 | C25 | Built: sign-in, devices, states (part 1, merged) and commands (part 2). The owner's first sign-in and live commands are open |
| [C31](chunks/C31-philips-lock.md) | Philips smart lock (fragile; unlock needs the owner's OK) | 8 | C25 | Design |
| [C32](chunks/C32-live-view.md) | Live camera view and camera wall (Ring, Blink) | 8 | C27, C29 | Built on branch `c32-live-view` (not merged): Blink (seen on the emulator with a made-up stream) and Ring (WebRTC; nothing has run a real call) live view with a Live button. The camera wall and the chat button remain |
| [C33](chunks/C33-camera-alerts.md) | Camera alerts: doorbell, person, motion notifications | 8 | C27, C29 | Built: Ring and Blink alerts from a foreground service every 45 s (D24), with a picture and tap-to-Clips; the owner's live check with a real doorbell and camera is open |
| [C34](chunks/C34-printer.md) | 3D printer card (Moonraker) | 8 | C25 | Built (the owner's check against the real printer is open) |
| [C35](chunks/C35-google-tasks.md) | To-dos: Google Tasks, as in Jarvis (D20) | 7 Accounts | C22, C08, C03 | Done |
| [C36](chunks/C36-own-voice.md) | Edith's own voice: on-device speech for phones with no voice, such as Andarium (D21) | 3 Voice | C13, C14, C17b | Built (the Andarium check on the iPhone is the owner's) |
| [C37](chunks/C37-https-only-clients.md) | HTTPS-only by construction: a `secure` HTTP client for everything that carries credentials, `plain` only for Ollama, the 3D printer and public pages | Hardening | C03 and every service using the shared client | Built, device tests pass (the release-APK check and the real sign-ins are pending) |
| [C38](chunks/C38-work-account-via-jarvis.md) | Work account through Jarvis: the work Outlook mail, calendar and to-dos that only the PC can reach (D25), read-only plus email drafts | 7 Accounts | C22, C35, C37 | Built against the spec and a pretend Jarvis; the first pairing with the real Jarvis, and over Tailscale, is the owner's check |
| [C39](chunks/C39-printer-parity.md) | The 3D printer as in Jarvis, through Jarvis: a Printer page with every card, Controls with confirmations, chat control, print alerts; works away from home (D26). Jarvis side: [`jarvis-printer-feed-spec.md`](jarvis-printer-feed-spec.md) | 8 Smart home | C34, C38 (and C33 for alerts) | **Stages 1 and 2 built** (E1-E10b: the Printer page, Controls with confirmations, the cards' controls, macros, the console, the presets editor, and `printer_control` in chat); stages 3-4 not started. Not yet tried against the real Jarvis and printer |
| [C40](chunks/C40-ios-app-expo.md) | Edith for iOS on Expo: chat with Ollama and spoken replies, first in Expo Go (free), then EAS builds once the Apple membership is renewed (D27) | 9 iOS (Expo) | C03–C06b, C07, C13 (as specs), C37 | Design |

### State of the build (written at the 2026-10-29 handoff, kept up to date since)
- `master` has the design and the C01 skeleton with its build/deploy tooling (`05-environment.md` §2, §6). Tag
  `edith-v1` = the old app. Branch `rebuild-design` is an already-merged leftover — ignore it, don't build on it.
- **Phase 1 is done** (C01–C07): the owner can chat with Edith in the app, across multiple conversations.
- **The tool framework is done (C08).** Edith uses real tools through `ToolTurnRunner`.
- **Weather is done (C09)**, from Open-Meteo, with units automatic by locale (D7).
- **Web search is done (C10)**: DuckDuckGo with no key, and an optional Ollama API key (D3). Put instructions the
  model must follow on a result in the result itself (L27).
- **Device location is done (C11)**: "here" uses approximate location, asked with Android's dialog (D14). Tools that
  need a runtime permission declare it (`Tool.permission`); the tool loop asks through `ToolPermissions`.
- **Phase 2 (tools) is done.** New tools go in `AppContainer.toolRegistry`: implement `Tool`, and test on the device
  (L25).
- **Voice input is done (C12)**: voice memos (transcribed by the model, or live recognition for models that can't
  hear) and dictation. `AppContainer.voiceMemos` is what C14's hands-free mode builds on. To test with real speech
  on the emulator, push a WAV through the debug hook (`DebugVoiceMemoReceiver`).
- **Spoken replies are done (C13)**: the phone's own voice, sentence by sentence as a reply streams.
  `AppContainer.speechOutput` is the voice. Its `speakingMessageId` is non-null while Edith is talking, which C14
  needs so it doesn't listen to her. Speech stops when the owner sends, records or dictates.
- **Hands-free is done (C14)**, apart from a check with a real microphone (a phone, or the emulator's host mic):
  openWakeWord on the phone (D4), then Android's recognizer, Edith's answer spoken, and follow-ups without the wake
  word.
  - The "Hey Edith" model was trained on 2026-11-11 and is in `app/src/main/assets/wakeword/hey_edith.onnx`.
    How it was made, how good it is and how to retrain it: `docs/wake-word-training.md` and `scripts/wakeword/`.
  - On the emulator, speech gets in through `DebugHandsFreeReceiver`.
- **Phase 3 (voice) is done.**
- **Attachments are done (C15, C16)**: photos and audio in, labels and media in the context, and a capability check
  before sending. Files are kept in `filesDir/attachments` (`FileAttachmentStore`), 10 each way by default (O1,
  `Retention`). Edith writes files with `<file>` blocks and sends attachments back with `<attachment number="N"/>`
  (`ReplyFormat`).
- **Phase 4 (attachments) is done.**
- **Google sign-in is done (C17)**: `GoogleAuth` (`google/`). Signing in uses Credential Manager; access uses
  `AuthorizationClient`. The app stores only the email. Google tools (C18, C19) set `integrationId = "google"` and call
  APIs through `googleAuth.authorized(scopes) { token -> … }`, which handles 401s and re-consent. Debug builds are
  `com.edith.app.debug`. The Web client ID is in `local.properties` (`docs/google-setup.md`); the debug hook
  `DebugGoogleReceiver` checks a real token.
- **Google Calendar is done (C18)**: five tools in `google/calendar/` (list, find free time, create, update,
  delete; the last three ask for confirmation) and the Calendar tab. Calendar times are local: use
  `ToolArgs.localTime` (L29).
- **Gmail is done (C19)**: four tools in `google/gmail/` (search, read, draft, send). Drafts and sends always show
  the whole email on a card first (D5, D11).
- **Phase 5 (Google) is done on Android phones.**
- **Google sign-in through the browser is done (C17b)** and works on the owner's iPhone (v2.0.0-dev.2: signed in,
  calendar and email from chat, added an event). Andarium has no
  Google Play services, so there Edith signs in in Chrome (D16): a Desktop OAuth client (`edith.googleDesktopClientId`
  and `…Secret` in `local.properties`, same Cloud project as the others), PKCE, the code back on 127.0.0.1 or pasted,
  and a refresh token encrypted in `SecretStore`. `ChoosingGoogle` picks Play services or the browser; Play-services
  phones work as before, with **Use a browser instead**. Expect no network while Edith is off screen (L36).
  `MainActivity` is `singleTask`.
- **Several accounts are done (C22, D17)** for Google: `accounts/` holds the account list (`Accounts`, the integration
  `accounts`), provider-neutral `CalendarSource`/`MailSource`, and the calendar and email tools, which read every
  account in parallel and label the results. For a new event or email the "which account?" rule is in code
  (`WhichAccount`): Edith asks, with no card and no request. Each Google account uses Play services (named with
  `setAccount`) or the browser; extra accounts must be test users in the Cloud project (`docs/google-setup.md` §7).
  The existing account was carried over without signing in (`AccountMigration`). `Tool.confirmation` now suspends.
- **Microsoft is done (C23, D18: personal and work)**: Outlook mail and calendars through Microsoft Graph, signed in
  through the browser like C17b (the shared `BrowserSignIn`, a `localhost` redirect on both loopback addresses), with
  a refresh token that changes on every refresh. Setup: `docs/microsoft-setup.md` (`edith.microsoftClientId`). Graph
  calls are cancellable, so a stalled account is cut off at 10 s; Google's calls still block (a follow-up).
- **iCloud is done (C24)**: an app-specific password (`docs/icloud-setup.md`), IMAP/SMTP with Jakarta Mail (keep rules
  for R8), CalDAV with OkHttp; iCloud expands repeating series itself. Every account has a **Default calendar**
  choice in Settings → Accounts (iCloud's server default isn't the iPhone's), and a whole repeating iCloud series
  can be deleted when the owner asks (`whole_series`).
- **To-dos are done (C35, D20)**: Google Tasks in every Google account, eight tools (`list_tasks` … `apply_task_order`)
  with cards, and an **Agenda | To-do** switch in the Calendar tab. The scope (`GoogleScope.Tasks`) is asked for only when
  a Tasks call needs it, so accounts signed in before C35 keep calendar and mail and show **To-dos: Grant access**.
  Due dates are dates only (`TaskDue`); the suggested order is Jarvis's, from the local model, saved in
  `filesDir/task-ranks.json`. Setup: enable the Tasks API and add the `tasks` scope (`docs/google-setup.md`).
- The first release, v2.0.0-dev.1, was published on 2026-11-11; v2.0.0-dev.2 (with C17b) on 2026-11-12. Chat with Ollama works from Andarium over the
  home Wi-Fi.
- **Smart home (v2):** D10 is decided (each service directly from the phone, 2026-11-12) and v2 is split into C25–C34
  (the core first, then Meross, Ring, Resideo, Blink, Alexa, Philips; then live view, alerts and the printer). D19
  (2026-11-13): match Jarvis's home features; Alexa gets Jarvis's full control, which also reaches the August locks
  (a direct August chunk is still out: Yale/August require a partner key, C21). The core (C25) is done, built against the
  Demo home, and the first real service, Meross (C26), is done and live on the owner's account (the shed door opened and
  closed). The owner should apply for a Resideo developer key early (C28).
- Judge tool reliability with `gemma4:12b`; `e4b` often skips tools (L26).
- Owner decisions still open are in `06-decisions.md`. Ask early about the ones your chunk lists; D8 (`keep_alive`) is
  still open: C03 shipped with the recommended default (not sent) and the owner hasn't confirmed it. D23 (where user-facing
  strings live) is open too.
- Releases: `v2.0.0-dev.1` (2026-11-11) is the first, published with `scripts/release.sh` + `scripts/publish.sh`
  as in 05-environment §6. Test the release APK on `Edith_Release_Test` with hands-free on before publishing
  (L30). Every published APK needs a higher `edith.versionCode` than the last one (`gradle.properties`; `v2.0.0-dev.5` is 5).
- The "Hey Edith" model is trained and in the app (`docs/wake-word-training.md`). Judging it with a real voice is
  still open.

### Parallelism
After C01–C02: C03 and C05 can run in parallel. After C06–C06b–C07: C08, C12, C13 and C15 can run in parallel
(they touch different packages; coordinate on `AppContainer` and the input row). C09, C10, C11 can run in
parallel after C08. C17 can start any time after C08; C18 and C19 in parallel after C17.

## Glossary

| Term | Meaning |
|---|---|
| Owner | The one person Edith is built for; makes all product decisions |
| Ollama | Local LLM server on the owner's PC (`http://<pc>:11434`) |
| Turn | One user message and Edith's complete reply, including any tool rounds |
| Tool round | One model call that returns tool calls, plus executing them |
| Tool exchange | The assistant tool-call messages and `role:"tool"` results of a turn — never persisted |
| Capability | What a model can do per `/api/show`: `completion`, `tools`, `vision`, `audio`, `thinking` |
| Media window | The last 10 messages, whose images/audio are re-sent to the model |
| Integration | An external account Edith connects to (Google now; smart home in v2) |
| Jarvis | The owner's separate Electron desktop assistant (`D:\Dev Projects\jarvis`), a useful reference |
| v1 | The previous Edith, tag `edith-v1`; abandoned; no code reused |
