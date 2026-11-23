# Edith — notes for AI agents

Edith is the owner's personal-assistant Android app (Kotlin, Jetpack Compose). Her "brain" is a local Ollama
server on the owner's Windows PC; she chats (streaming), uses tools (weather, web, calendar, email), takes voice
memos and attachments, and speaks her replies. She is being rebuilt from scratch: the old app is only in git
history (tag `edith-v1`) — don't reuse its code.

## Read first
**Moving to one shared Kotlin codebase (Android + iPhone)? Start with [`docs/design/kmp-handoff.md`](docs/design/kmp-handoff.md)**: Phase 1 is in progress (K1.1 to K1.3 done, K1.4 partly), and it says where it stands, how to work, and what is next. The plan is [`docs/design/kmp-transition.md`](docs/design/kmp-transition.md).

**[`docs/design/README.md`](docs/design/README.md)** — the complete design, split into chunks (C01–C36) with
dependencies and status. Read the shared context it lists, then take one chunk whose dependencies are merged.
C01–C19, C17b and C22–C24 are done (phases 1–5, including Google Calendar and Gmail, Google sign-in through the browser for Andarium on the owner's iPhone, and several Google accounts at once in `accounts/` (D17); hands-free still needs a real-mic check). C23 (Microsoft Outlook mail and calendar, `microsoft/`, D18) is done too. C24 (iCloud, `icloud/`) is done too. C25 (the smart-home core, `home/`, with a Demo home in debug builds) is done too. C26 (Meross garage door openers, `meross/`) is done too. C35 (to-dos: Google Tasks as in Jarvis, D20; `accounts/tasks/`, `google/tasks/`) is done too. C36 (Edith's own on-device voice for phones with no text-to-speech, `voice/own/`, D21) is built; its check in Andarium on the owner's iPhone is the owner's. C33 (camera alerts for Ring and Blink from a foreground service, `alerts/`, D24) is built; the owner's live check is open.  C38 (the work account read through Jarvis on the PC, `jarvis/`, D25) is built and in use. C39 (the 3D printer as in Jarvis, through Jarvis's feed, D26) has stages 1 and 2 built (the Printer page with Controls, confirmations, macros and the console, `printer/feed/` and `printer/ui/`, and `printer_control` in chat, `home/PrinterControlTool.kt`; stages 3-4 are not started); its Jarvis side is `docs/design/jarvis-printer-feed-spec.md`. C40 (the iOS app on Expo, built in the cloud by EAS and starting with chat; D27) is designed; it lives in `ios-app/` and changes nothing in `app/`. Stage 1 runs in Expo Go (no Apple account). EAS device builds need the owner's Apple Developer Program membership, which expired in May 2026 and is theirs to renew. Ask the owner before starting an EAS build: the free plan has about 15 iOS builds a month. C41 (tools on iOS: the tool loop, time, weather and location, `ios-app/src/tools/`) is built; its iPhone check is the owner's. C42 (web search and page reading on iOS, `ios-app/src/tools/web/`) is built too. C43 (photo attachments on iOS, `ios-app/src/attachments/`) is built; its iPhone check is the owner's. C44 (voice memos on iOS, `ios-app/src/voice/`) is built; its iPhone check is the owner's. C45 (confirmation cards before a tool changes something, on iOS) is built. C46 (the smart-home core on iOS with a Demo home, `ios-app/src/home/`) is built; its iPhone check is the owner's. C47 (photos kept across restarts on iOS) is built. C48 (the home services framework on iOS: `SecretStore`, `secureFetch`, `HomeService`, `ios-app/src/home/service.ts`) is built. C49 (Edith's own pictures: tool images stored as `from_edith` attachments, `camera_snapshot`, retention per direction) is built. C55 (the 3D printer card on the home Wi-Fi, `ios-app/src/printer/`) is built; its check against the real printer is the owner's. C50 (Meross garage doors over the home Wi-Fi, `ios-app/src/meross/`) is built; its check with the real doors is the owner's. C56 (Edith's files and resending attachments on iOS: `<file>` blocks, file cards, `<attachment number>` resends, `ios-app/src/chat/replyFormat.ts`) is built. C54 (Alexa devices on iOS: Amazon's page in a WebView, devices, states and commands, `ios-app/src/alexa/`) is built; its first sign-in and live commands are the owner's. C51 (Ring doorbells and cameras on iOS: sign-in with 2FA, snapshots, `recent_events`, `ios-app/src/ring/`) is built; its sign-in and check with the real Ring are the owner's. C52 (Blink cameras on iOS: sign-in with a code, snapshots, motion events, `ios-app/src/blink/`) is built; its sign-in (VPN off) and check with the real Blink are the owner's. C53 (camera clips on iOS: the Clips chip, list and player, `ios-app/src/ui/ClipsScreen.tsx`) is built; its check with real clips is the owner's. C57 (conversation mode on iOS: tap once, talk, hear the answer, follow up; `ios-app/src/voice/conversation.ts`) is built; its first real-microphone check is the owner's. C72 option A (camera alerts while Edith is open plus a "While you were away" catch-up, `ios-app/src/alerts/`) is built; option B (Jarvis sends the push) is not; the owner's check is open. C76 pass 1 (the parity table `docs/design/ios-parity.md` and a code-level audit with standing tests in `ios-app/src/audit.test.ts`) is done; the rest of C76 needs the owner and the phone. C66a (the Jarvis connection: pairing and the authenticated client over a Tailscale-certificate listener, `ios-app/src/jarvis/`, D39) is built against a pretend Jarvis; the Jarvis side is `docs/design/jarvis-tailscale-listener-spec.md`, not built yet. C67 (the printer through Jarvis: the Printer page, Controls and chat) is in progress: E1 (wire types and the 33 commands), E2 (the client), E3 (the session), E4 (the Printer page skeleton and Status card, `ui/printer/`) E5 (the read-only cards, `printer/feed/cards.ts`, `ui/printer/Cards.tsx`) E6 (the Home tile and `printer_status` fall back to Jarvis, `printer/feed/toHome.ts`) E7 (Controls, the confirm flow and Emergency stop, `printer/feed/control.ts`, `ui/printer/ControlViews.tsx`) E8 (the cards' controls, `ui/printer/CardControls.tsx`, `printer/feed/input.ts`) E9 (macros, the console input and the presets editor, `ui/printer/MacrosAndConsole.tsx`) and E10 (`printer_control` in chat, `home/printerControlTool.ts`; cancelling a print needs a tap, never a voice) are built in `ios-app/src/printer/feed/`. **iOS agents: read `docs/design/ios-roadmap.md` first**; C48–C76 (the rest of the iOS app, in build order) are designed in `docs/design/chunks/`. The table says what's next.

## Build, test, run (Git Bash; from PowerShell: `& "C:\Program Files\Git\bin\bash.exe" scripts/check.sh`)
```bash
scripts/check.sh            # clean build + unit tests + lint — same as CI; run before every commit
scripts/check.sh --device   # also the Compose UI tests on the emulator
scripts/fetch-sherpa.sh     # the sherpa-onnx library (not in Git); the scripts above run it for you
scripts/emulator.sh         # start the Pixel_9_API_37 emulator if needed (--cold if the screen is black)
scripts/run.sh              # build, install and launch the debug app on the emulator; fails on a crash
```
- Debug builds are `com.edith.app.debug` (since C17; release is `com.edith.app`), so use that with `run-as` and `am`.
- Never run `connectedDebugAndroidTest` or `adb uninstall`/`pm clear` on the owner's emulator without asking —
  they wipe the owner's app data.
- Releases (signed APK on GitHub Releases) only when the owner asks: `scripts/release.sh`, then
  `scripts/publish.sh` after their explicit OK. Signing key lives in `C:\Users\USER\.edith\` — never copy it.
  Full runbook: [`docs/design/05-environment.md`](docs/design/05-environment.md) §2 and §6.
- **Every build is recorded** in `build-logs/` (newest: `build-logs/latest.log`): scripts, plain `gradlew` and Android
  Studio builds alike. The owner reviews them; your reported results must match the log.
- Ollama runs on the PC (port 11434); from the emulator it's `http://10.0.2.2:11434`.
  On Android 17 the app also needs the runtime permission `ACCESS_LOCAL_NETWORK`, or every request times out
  (04-lessons L23). Live check against the real server: see C03 "As built".

## iOS app (C40, `ios-app/`; separate from `app/`)
```bash
scripts/ios-check.sh            # npm ci, tsc, ESLint, Jest — same as the ios-app CI workflow (needs Node 24)
cd ios-app && npx expo start --lan   # dev server for Expo Go on the owner's iPhone
```
- **Ask the owner before any EAS build** (free plan: about 15 iOS builds a month), and never handle Apple or Expo passwords or
  two-factor codes: the owner types them. No secrets in the repo or in `EXPO_PUBLIC_*` variables. Never log conversation text
  in release builds (`core/log.ts`). Stage 2 (EAS) waits for the owner to renew the Apple Developer membership.

## Layout
- `app/` — the single Android module, package `com.edith.app` (layout planned in `01-architecture.md` §2).
- `gradle/libs.versions.toml` — every library version; app version in `gradle.properties`.
- `scripts/` — build/deploy scripts above. `.github/workflows/ci.yml` — CI on every PR and push to master.
- `docs/design/` — the design. Keep it current in the same PR when the design changes.

## This repository is public
- `GrundyTheMan/edith-assistant` is **public**: everything committed, every commit message, every PR and every CI log can be read by anyone.
  Never commit or write the owner's real name or email addresses, the Windows user name, real IP addresses or Tailscale names, tokens, keys, crash logs
  (`hs_err_*`, `replay_*`) or recorded build logs; use placeholders (`owner@example.com`, `192.168.x.x`, `USER`). Commits use the GitHub no-reply identity
  (`GrundyTheMan <188627129+GrundyTheMan@users.noreply.github.com>`): check `git config user.email` in a new clone before the first commit.
- Never push or merge history from the old private repository: its commits carry personal data. Move changes over as new commits.
- No license has been chosen yet (all rights reserved); the owner decides.

## Working with the owner (details: `docs/design/02-standards.md` §4–6)
- Honest, direct feedback; say plainly what isn't done or couldn't be verified, and ask about it.
- Call something a "fix" only when it repairs broken code; otherwise it's a "change".
- Crashes are never acceptable: investigate every one.
- Branch `cNN-short-name`, one PR per chunk titled `CNN: …`, merge commit. Ask before committing, pushing,
  merging, tagging or publishing anything. Don't discard uncommitted work you didn't create — ask.
- Secrets (keys, tokens, passwords) never go in the repo, logs or chat.
- Network code: a new service takes `httpClients.secure` (HTTPS only, C37), never its own `OkHttpClient`.
  `httpClients.plain` is only for Ollama, the 3D printer and the web-page reader; a LAN device that needs plain HTTP
  says why in its PR.
