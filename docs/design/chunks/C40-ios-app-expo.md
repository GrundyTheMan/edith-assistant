# C40 — Edith for iOS on Expo: the project, EAS builds, and chat

| | |
|---|---|
| Phase | 9 — iOS (Expo) |
| Depends on | C03, C04, C05, C06, C06b, C07, C13 as **specifications** (no Kotlin code is reused); C37 (plain HTTP to Ollama is the one exception) |
| Unblocks | Every later iOS chunk (tools, voice memos, accounts, …) |
| Size | Large (a new project, the build pipeline, and chat) |
| Read first | 06-decisions **D27**; 00-product; 03-ollama-reference; C03, C04, C05, C06, C06b, C07, C13 (spec **and** As built); 02-standards; 04-lessons L23, L27, L29 |

## Why

On 2026-11-19 the owner asked whether Edith can also be an iOS project, looked at two routes (Kotlin Multiplatform and
Expo), and chose **Expo, with EAS doing the iOS builds** (D27). The owner's PC is Windows, and they have an Apple
developer account. Expo lets them work from Windows and see changes on the real iPhone, and EAS builds the iOS
app in the cloud.

This is a **new app written in TypeScript**, not a port of the Kotlin code. **Android stays the Kotlin app.** The
two share the design documents (and, where it helps, Jarvis's TypeScript), not code. Edith keeps running on the
iPhone inside Andarium until this app can replace it.

## Facts checked 2026-11-19 (re-verify; Expo changes quickly)

- **Expo SDK:** Expo's docs list SDKs up to 57. SDK 55 (February 2026) dropped support for the old React Native
  architecture. Use the **latest stable** SDK and pin exact versions.
  (<https://expo.dev/changelog/sdk-55>)
- **Streaming:** `expo/fetch` has a documented "Streaming fetch" example that reads `response.body.getReader()`
  (it arrived in SDK 52). In newer SDKs it is also the global `fetch`. React Native's own fetch historically could
  not stream.
- **Expo Go isn't enough.** Custom native code and Info.plist keys are applied **at build time**, so they don't show
  in Expo Go. Edith needs a **development build** from EAS.
  (<https://expo.dev/blog/expo-go-vs-development-builds.md>)
- **Installing on the owner's iPhone** (internal distribution, ad hoc): `eas device:create` gives a link, which the
  owner opens **on the iPhone** to install a profile. Then an **interactive** `eas build` signs in to Apple to update
  the ad hoc provisioning profile. `--non-interactive` can't add devices.
  (<https://docs.expo.dev/build/internal-distribution/>)
- **The EAS free plan:** about 15 iOS builds a month, in a low-priority queue that can wait 90 minutes or more at
  peak. Expo has temporarily disabled free iOS builds under load. Re-check the pricing page.
  (<https://expo.dev/changelog/2023-08-01-eas-free-plan-limits>)
- **Plain HTTP to Ollama on the LAN:** in `expo.ios.infoPlist`, set `NSLocalNetworkUsageDescription` (one forum
  report says iOS 18.1 requires it) and an App Transport Security exception, most likely
  `NSAppTransportSecurity` → `NSAllowsLocalNetworking`. **The exact structure is unverified; test it in the
  development build.**
- **iOS limits, from the agent's knowledge and not re-checked:** third-party apps can't keep listening for a wake
  word in the background, and there are no foreground services. Hands-free and camera alerts are out of this chunk
  and will be limited later.

## Build services considered (2026-11-19) — EAS chosen

The owner asked whether CircleCI, or any other service with a free offer, would be better than EAS for the iOS builds.
Compared from the vendors' own pages and a few third-party listings (re-check; allowances change):

| | Free iOS allowance | Apple signing | Installing on the iPhone |
|---|---|---|---|
| **EAS Build** (chosen) | about **15 iOS builds** a month, low-priority queue | managed by EAS: the owner signs in to Apple once, interactively | built in: an install link and QR code (internal distribution) |
| **Codemagic** | **500 macOS (M2) minutes** a month on a personal account, one build at a time: about 25 builds of 20 minutes (one source's estimate) | managed by Codemagic from an App Store Connect API key the owner makes (not verified for Expo) | its own build page, or publish to TestFlight (not verified) |
| **Xcode Cloud** (Apple) | **25 compute hours** a month with the Apple Developer Program: the largest | managed by Apple | TestFlight. **Needs Xcode on a Mac to create the first workflow** (inferred from Apple's docs, not stated outright), and an Xcode project generated from the Expo app |
| **CircleCI** | 30,000 credits a month on macOS; the free plan's `m4pro.medium` costs 200 credits a minute: about **150 minutes** | **you manage it**: CircleCI's code-signing API, or fastlane `match` | none built in: host the IPA yourself, or upload to TestFlight (agent's knowledge) |
| **GitHub Actions** | 2,000 free minutes, but macOS counts 10× on a private repo: about **200 minutes** | you manage it | none built in |
| **Bitrise** | a Hobby plan of about 300 credits a month (sources disagree on the details) | not checked | not checked |

(<https://expo.dev/changelog/2023-08-01-eas-free-plan-limits>, <https://docs.codemagic.io/billing/pricing/>,
<https://developer.apple.com/news?id=ik9z4ll6>,
<https://support.circleci.com/en/articles/15091231-are-free-credits-available-for-macos-open-source-projects>,
<https://circleci.com/docs/ios-codesigning/>)

**Why EAS first.** It's built for Expo: the development client, config plugins, SDK versions and Apple credentials all
fit it, and it runs from the owner's Windows PC with no Mac. Registering the iPhone and installing the build are
handled with a link. The other services are general CI with a real Mac, so the signing certificates, provisioning
profiles, device registration and the install step would be set up and maintained by hand, and `eas build --local` on
a CI machine has been reported to hit signing problems. And about 15 builds a month is enough, because day-to-day
JavaScript changes don't need a build (§4).

**If the EAS free quota or queue ever binds, in this order** (later, not now):
1. **Codemagic**: the largest free allowance that needs no Mac. The owner makes the account and an App Store Connect
   API key. Check its Expo and development-client support first.
2. **Xcode Cloud**: the most free hours, but only if the owner gets Mac access for the first setup.
3. Expo's paid Starter plan (about $19 a month, per Expo), or a hand-run `eas build --local` on a Mac.

The existing Android and Linux CI stays on GitHub Actions: it is fast and well inside its free minutes, so moving it
would cost effort for nothing.

## Before you start — owner setup

**The owner does all of these. The agent never handles Apple or Expo passwords, or two-factor codes, and doesn't
create accounts.**
1. **Confirm the Apple account is the paid Apple Developer Program** (ad hoc installs on an iPhone need it). The
   agent assumes so from "I already have a developer account".
2. **An Expo account** (expo.dev; the free plan is enough to start), then `npx eas-cli login` on the PC.
3. **The iOS bundle identifier.** It must be unique across all of Apple. Don't reuse the Android `com.edith.app`
   unless the owner knows it's free. The owner picks it (for example `com.<their name>.edith`).
4. **Developer Mode on the iPhone** (Settings → Privacy & Security → Developer Mode; iOS 16 and later; check).
5. **Node.js LTS** on the PC (the agent adds the steps to 05-environment).
6. **An EAS build budget.** The free plan has about 15 iOS builds a month. **The agent asks before every EAS
   build.**

## Specification

### 1. The project (`ios-app/`)
- A new Expo project in `ios-app/` of this repo. TypeScript in strict mode, the latest stable SDK, the New
  Architecture (the default). Choose and record: Expo Router or React Navigation, and the Markdown and state
  libraries. Prefer Expo's own packages where they fit.
- Layout, mirroring the Kotlin app in spirit: `src/ollama/`, `src/chat/` (engine), `src/data/` (SQLite),
  `src/settings/`, `src/ui/`, `src/core/` (errors, logging, secrets). Tests sit next to the code.
- **Rebuild from the specifications**, not from the Kotlin code: C03, C05, C06, C06b, C07, C13 and their **As built**
  sections, which hold the lessons (thinking blocks, tool-call text fallback, time zones in L29, and so on). What the
  owner sees must behave the same.

### 2. The first proof: "Hello Ollama on the iPhone" (do this before anything else)
In order. Stop and report at the first failure.
1. Create the project with one screen; Jest runs.
2. EAS: `eas build:configure`. `eas.json` has `development` (development client, internal distribution), `preview`
   (internal distribution) and `production` (unused for now), with `appVersionSource: remote` and
   `autoIncrement`.
3. Register the iPhone (`eas device:create`, installed **on the phone** by the owner). Then
   `eas build --profile development --platform ios` **interactively, with the owner at the keyboard** for the Apple
   sign-in. Record the queue wait and the build time.
4. The owner installs the build on the iPhone. `npx expo start --dev-client` on the PC; the iPhone connects over the
   same Wi-Fi. (Windows' firewall must allow Metro's port, 8081, on the private network. NordVPN's LAN settings
   can block it.)
5. The screen sends a prompt to the owner's Ollama and **streams the reply token by token** with `expo/fetch`.
   - The server address is a **setting** (`http://192.168.4.82:11434` today), never hard-coded.
   - `ios.infoPlist` carries the local-network description and the ATS exception. iOS shows its Local Network
     permission prompt on first use.
   - Record the time to the first token, and whether chunks arrive **incrementally** rather than all at once.
6. If streaming doesn't stream: try `XMLHttpRequest` progress events, then a streaming-fetch library. Record which
   works.

**Done when** the owner sees a reply streamed from their own Ollama, on their iPhone, in a build that EAS made.

### 3. Chat (the rest of the chunk)
- **Ollama client:** the model list (`/api/tags`), a model's capabilities (`/api/show`: tools, vision, audio,
  thinking), and streaming chat (`/api/chat`, NDJSON). Cancel with `AbortController`, timeouts, and plain error
  messages. A **stream-aware UTF-8 decoder** (`TextDecoder` with `{stream: true}`), because a chunk can end in the
  middle of a character and lines split across chunks.
- **Storage:** `expo-sqlite` with the Kotlin app's schema v2 (conversations, messages, and the attachments table
  for later), migrations by `PRAGMA user_version`. Settings in SQLite's key-value store. Secrets, when there are
  any, in `expo-secure-store` (the Keychain).
- **Chat engine:** Edith's personality text **unchanged** (A5, 00-product §5), the context budget (C06), streaming
  into the UI, stop, regenerate, titles written by Edith (D12), and several conversations (D6).
- **UI:** bottom tabs **Chat | Settings** (more tabs come with later chunks). Purple user bubbles on the right
  and grey Edith bubbles on the left, timestamps, an action row (copy, play, regenerate), the input row, a
  conversation list, Markdown rendering, and light and dark themes. Safe areas and keyboard handling must feel
  native.
- **Spoken replies:** `expo-speech` (the iPhone's own voices, so C36's own voice isn't needed). Sentence by sentence
  while a reply streams, using C13's chunker and text cleaning, with Play and Stop, a speed slider, and silence when
  the owner sends a message. Check how the ring/silent switch affects speech and record it.
- **Settings:** the server address and **Test connection**, the model, speak replies, and speech speed.
- **Not in this chunk:** tools (the model is told it has none), voice memos and dictation, attachments, hands-free,
  Google, Microsoft or iCloud accounts, smart home, Jarvis's feeds.

### 4. Builds and releases
- **Day to day:** `npx expo start --dev-client` on the PC. JavaScript changes reload on the iPhone with **no
  build**. An EAS build is needed only when native code changes: a new native module, an `app.json` plugin or
  `infoPlist` change, or an SDK upgrade. **Batch those changes**, and **ask before starting any EAS build.**
- **A standalone build** (the `preview` profile, internal distribution) runs without the PC, installed from a link
  on the iPhone.
- **Not in this chunk:** TestFlight, the App Store, push notifications and background tasks.
- **Versions:** iOS needs a numeric `x.y.z`, so use `2.0.0` with EAS's auto-incremented build number.
- **No secrets in the repo.** `eas.json` holds none. `EXPO_PUBLIC_*` variables are **public**, so nothing secret
  goes in them. Never commit `credentials.json`.

### 5. CI (`.github/workflows/ios-app.yml`, a new workflow)
- A **Linux** runner, only on changes under `ios-app/**`: `npm ci`, `tsc --noEmit`, ESLint and Jest. **No macOS
  runner and no EAS in CI.** EAS builds are started by hand.
- `scripts/ios-check.sh` runs the same checks locally. Like every script it logs to `build-logs/` (`lib.sh`'s
  `start_log`).
- The Android CI job and `scripts/check.sh` are not changed.

## Tests
- Jest with React Native Testing Library. Port the **cases** from the Kotlin tests, not the code:
  - the NDJSON parser, from real Ollama output: a plain reply, a tool call, a thinking block, an error line, lines
    split across chunks, and a UTF-8 character split between chunks
  - the Ollama client against a fake `fetch`; cancel; timeout
  - the prompt and context builder (the same outputs as the Kotlin cases, including the date line and UTC offset)
  - the sentence chunker and text cleaning (C13's cases)
  - the SQLite migration, and the engine's state machine
- On the iPhone, by hand: the acceptance list below.

## Acceptance criteria
- [ ] The owner's setup is done: the Apple Developer Program confirmed, an Expo account, a bundle identifier, the
      iPhone registered, and Developer Mode on.
- [ ] An EAS development build installs on the iPhone and connects to `expo start` on the PC.
- [ ] **Hello Ollama:** a prompt streams a reply from the owner's Ollama, incrementally, with the first-token time
      recorded. The Local Network prompt appeared and was accepted. The address comes from Settings.
- [ ] Chat works end to end on the iPhone: several conversations, streaming, stop, regenerate, titles, the model
      picker, Markdown, light and dark.
- [ ] Spoken replies: sentence by sentence while streaming, Play and Stop, speed.
- [ ] Conversations survive an app restart, and a database-migration test passes.
- [ ] `scripts/ios-check.sh` and the new CI job pass. The Android CI and `scripts/check.sh` are unchanged and green.
- [ ] A `preview` (standalone) build runs without the PC.
- [ ] As built records: the SDK version, the navigation choice, the number of EAS builds used and their queue and
      build times, the first-token latency, and anything that didn't work.

## Implementation notes (traps)
1. **Expo Go can't test the Info.plist keys.** Test the local-network and ATS settings in the development build.
2. **Never log conversation text in release builds** (as on Android).
3. **Apple credentials and two-factor codes are the owner's** to type, and never go in chat or files. EAS stores
   the certificates.
4. **The Windows firewall and the VPN** are the usual reasons the iPhone can't reach Metro or Ollama. Same Wi-Fi,
   private network.
5. **EAS free-plan queues:** plan the builds, and expect waits. Free iOS builds are sometimes disabled for a while.
6. **SDK upgrades** can change things a lot (SDK 55 dropped the old architecture). Pin exact versions and upgrade on
   purpose.
7. **Docs to update in this PR:**
   - README (chunk status), and 01-architecture (a short "iOS app" section: stack, folders, how it differs)
   - 05-environment (Node, `eas login`, `device:create`, the dev client, running, the firewall)
   - 00-product §3, and AGENTS.md (the iOS commands, and the "ask before an EAS build" rule)

## Roadmap (not designed; the owner picks the order)
After C40 the iOS app grows chunk by chunk, toward what the owner uses on the phone: tools (time, weather, web,
location), then accounts (Google and Microsoft through `expo-auth-session`, which needs iOS OAuth client types),
calendar, mail and to-dos, voice memos and dictation, attachments, the smart home, and Jarvis's feeds (C38, C39).
Hands-free is limited by iOS. Full parity with the Android app probably isn't worth it, so the owner decides what
matters on iOS. Andarium covers whatever isn't native yet.

## Not in scope
- Changes to the Android app, Kotlin Multiplatform, a Swift rewrite.
- TestFlight and the App Store; push notifications; background work.
- Any tool, attachment, voice input, account or smart-home feature.
