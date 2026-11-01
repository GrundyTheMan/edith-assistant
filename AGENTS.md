# Edith — notes for AI agents

Edith is the owner's personal-assistant Android app (Kotlin, Jetpack Compose). Her "brain" is a local Ollama
server on the owner's Windows PC; she chats (streaming), uses tools (weather, web, calendar, email), takes voice
memos and attachments, and speaks her replies. She is being rebuilt from scratch: the old app is only in git
history (tag `edith-v1`) — don't reuse its code.

## Read first
**[`docs/design/README.md`](docs/design/README.md)** — the complete design, split into chunks (C01–C21) with
dependencies and status. Read the shared context it lists, then take one chunk whose dependencies are merged.
C01–C15 are done (phases 1–3: chat, tools and voice, and attachments in; hands-free still needs a real-mic check); the table says what's next.

## Build, test, run (Git Bash; from PowerShell: `& "C:\Program Files\Git\bin\bash.exe" scripts/check.sh`)
```bash
scripts/check.sh            # clean build + unit tests + lint — same as CI; run before every commit
scripts/check.sh --device   # also the Compose UI tests on the emulator
scripts/emulator.sh         # start the Pixel_9_API_37 emulator if needed (--cold if the screen is black)
scripts/run.sh              # build, install and launch the debug app on the emulator; fails on a crash
```
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

## Layout
- `app/` — the single Android module, package `com.edith.app` (layout planned in `01-architecture.md` §2).
- `gradle/libs.versions.toml` — every library version; app version in `gradle.properties`.
- `scripts/` — build/deploy scripts above. `.github/workflows/ci.yml` — CI on every PR and push to master.
- `docs/design/` — the design. Keep it current in the same PR when the design changes.

## Working with the owner (details: `docs/design/02-standards.md` §4–6)
- Honest, direct feedback; say plainly what isn't done or couldn't be verified, and ask about it.
- Call something a "fix" only when it repairs broken code; otherwise it's a "change".
- Crashes are never acceptable: investigate every one.
- Branch `cNN-short-name`, one PR per chunk titled `CNN: …`, merge commit. Ask before committing, pushing,
  merging, tagging or publishing anything. Don't discard uncommitted work you didn't create — ask.
- Secrets (keys, tokens, passwords) never go in the repo, logs or chat.
