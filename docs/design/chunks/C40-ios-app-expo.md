# C40 — Edith for iOS on Expo: the project, chat in Expo Go, then EAS builds

| | |
|---|---|
| Phase | 9 — iOS (Expo) |
| Status | **Stage 1 built** (Expo Go); see "As built (Stage 1)" at the end. The owner's iPhone checks of chat and speech are open. Stage 2 (EAS) is not started: it waits for the Apple Developer membership |
| Depends on | C03, C04, C05, C06, C06b, C07, C13 as **specifications** (no Kotlin code is reused); C37 (plain HTTP to Ollama is the one exception) |
| Unblocks | Every later iOS chunk (tools, voice memos, accounts, …) |
| Size | Large (a new project, chat, and the build pipeline) |
| Read first | 06-decisions **D27**; 00-product; 03-ollama-reference; C03, C04, C05, C06, C06b, C07, C13 (spec **and** As built); 02-standards; 04-lessons L23, L27, L29 |

## Why

On 2026-11-19 the owner asked whether Edith can also be an iOS project, looked at two routes (Kotlin Multiplatform and
Expo), and chose **Expo, with EAS doing the iOS builds** (D27). Their PC is Windows.

The owner then found that their **paid Apple Developer membership expired in May 2026**, so today they have only a
free Apple ID. That splits the work into two stages:
- **Stage 1: Expo Go. Free, and no Apple account at all.** The owner installs Expo Go from the App Store and the app
  runs on the real iPhone from the PC's dev server. Everything in this chunk's chat can be built and tried this way.
- **Stage 2: EAS builds on the iPhone.** These need the **paid Apple Developer Program**, which the owner has to
  renew first (their decision and their money). It gives a standalone app that runs without the PC, and control over
  the iPhone's Info.plist.

This is a **new app written in TypeScript**, not a port of the Kotlin code. **Android stays the Kotlin app.** The
two share the design documents (and, where it helps, Jarvis's TypeScript), not code. Edith keeps running on the
iPhone inside Andarium until this app can replace it.

## Facts checked 2026-11-19 (re-verify; Expo changes quickly)

- **Expo SDK:** Expo's docs list SDKs up to 57. SDK 55 (February 2026) dropped support for the old React Native
  architecture. Use the **latest stable** SDK and pin exact versions. **Expo Go in the App Store supports only the
  newest SDK**, so the project must match it. (<https://expo.dev/changelog/sdk-55>)
- **Streaming:** `expo/fetch` has a documented "Streaming fetch" example that reads `response.body.getReader()`
  (it arrived in SDK 52). In newer SDKs it is also the global `fetch`. React Native's own fetch historically could
  not stream.
- **Free Apple ID versus the paid Program.** Every EAS build that runs on an iPhone is signed with a distribution
  certificate and an ad hoc profile, which **need the paid Apple Developer Program** (about $99 a year). Only
  simulator builds are free, and they are no use on an iPhone. A free Apple ID can install only through Xcode on a Mac
  (7-day expiry, 3 apps), and the owner has no Mac.
  (<https://docs.expo.dev/tutorial/eas/ios-development-build-for-devices.md>,
  <https://expo.dev/blog/apple-developer-program-explained-free-vs-paid>)
- **Expo Go needs no Apple account**, but it can't carry custom native code or custom Info.plist keys (those are
  applied **at build time**). A **development build** from EAS can. (<https://expo.dev/blog/expo-go-vs-development-builds.md>)
- **Plain HTTP to Ollama on the LAN:**
  - In Expo Go this is **unverified**. Apple's App Transport Security note says connections to raw IP addresses are
    generally exempt, but `.local` names aren't. Test it on the iPhone.
  - In a development build, set it in `expo.ios.infoPlist`: `NSLocalNetworkUsageDescription` (one forum report says
    iOS 18.1 requires it) and an ATS exception, most likely `NSAppTransportSecurity` → `NSAllowsLocalNetworking`. **The
    exact structure is unverified.**
- **Installing on the owner's iPhone from EAS** (Stage 2, internal distribution): `eas device:create` gives a link,
  which the owner opens **on the iPhone** to install a profile. Then an **interactive** `eas build` signs in to Apple
  to update the ad hoc provisioning profile. `--non-interactive` can't add devices.
  (<https://docs.expo.dev/build/internal-distribution/>)
- **The EAS free plan:** about 15 iOS builds a month, in a low-priority queue that can wait 90 minutes or more at
  peak. Expo has temporarily disabled free iOS builds under load. Re-check the pricing page.
  (<https://expo.dev/changelog/2023-08-01-eas-free-plan-limits>)
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

**All of them need the paid Apple Developer Program to put a build on a real iPhone.** With a free Apple ID, only
Expo Go works (Stage 1).

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
create accounts or pay for anything.**

**Stage 1 (Expo Go): free, and no Apple account**
1. **Expo Go** installed from the App Store on the iPhone.
2. **Node.js LTS** on the PC (the agent adds the steps to 05-environment).
3. The PC and the iPhone on the **same Wi-Fi**, and Windows' firewall allowing Metro's port (8081) on the private
   network. NordVPN's LAN settings can block it.

**Stage 2 (EAS builds): needs the paid Apple Developer Program. Stage 2 waits until this is done.**
4. **Renew the Apple Developer Program membership** (about $99 a year). It expired in May 2026. The owner renews it
   from the Apple Developer app or developer.apple.com. Check Apple's rules for a membership that has lapsed for
   months. Only the owner can do this.
5. **An Expo account** (the free plan is enough), then `npx eas-cli login` on the PC.
6. **The iOS bundle identifier.** It must be unique across all of Apple. Don't reuse the Android `com.edith.app`
   unless the owner knows it's free. The owner picks it (for example `com.<their name>.edith`).
7. **Developer Mode on the iPhone** (Settings → Privacy & Security → Developer Mode; iOS 16 and later; check).
8. **An EAS build budget.** The free plan has about 15 iOS builds a month. **The agent asks before every EAS
   build.**

## Specification

### 1. The project (`ios-app/`)
- A new Expo project in `ios-app/` of this repo. TypeScript in strict mode, the SDK that Expo Go in the App Store
  supports (the latest stable), and the New Architecture (the default). Choose and record: Expo Router or React
  Navigation, and the Markdown and state libraries. Prefer Expo's own packages where they fit.
- Layout, mirroring the Kotlin app in spirit: `src/ollama/`, `src/chat/` (engine), `src/data/` (SQLite),
  `src/settings/`, `src/ui/`, `src/core/` (errors, logging, secrets). Tests sit next to the code.
- **Rebuild from the specifications**, not from the Kotlin code: C03, C05, C06, C06b, C07, C13 and their **As built**
  sections, which hold the lessons (thinking blocks, tool-call text fallback, time zones in L29, and so on). What the
  owner sees must behave the same.

### 2. The first proof: "Hello Ollama on the iPhone" (do this before anything else)

**Stage 1 — in Expo Go (free).** In order. Stop and report at the first failure.
1. Create the project with one screen; Jest runs.
2. `npx expo start` on the PC. The owner scans the QR code with Expo Go on the iPhone, and the screen appears.
3. The screen sends a prompt to the owner's Ollama and **streams the reply token by token** with `expo/fetch`.
   - The server address is a **setting** (`http://192.168.4.82:11434` today), never hard-coded.
   - Record the time to the first token, and whether chunks arrive **incrementally** rather than all at once.
   - **Whether iOS allows plain HTTP to the LAN address in Expo Go is unverified.** If it blocks it, record the exact
     error. That is the first thing that needs a development build (Stage 2). Don't loosen anything in the repo to
     work around it.
4. If streaming doesn't stream: try `XMLHttpRequest` progress events, then a streaming-fetch library. Record which
   works.

**Done when** the owner sees a reply streamed from their own Ollama, on their iPhone, in Expo Go.

**Stage 2 — an EAS development build (after the owner has renewed the membership).** In order.
1. `eas build:configure`. `eas.json` has `development` (development client, internal distribution), `preview`
   (internal distribution) and `production` (unused for now), with `appVersionSource: remote` and
   `autoIncrement`.
2. Register the iPhone (`eas device:create`, installed **on the phone** by the owner). Then
   `eas build --profile development --platform ios` **interactively, with the owner at the keyboard** for the Apple
   sign-in. Record the queue wait and the build time.
3. The owner installs the build. `npx expo start --dev-client` on the PC; the iPhone connects over the same Wi-Fi.
4. `ios.infoPlist` carries the local-network description and the ATS exception. iOS shows its Local Network permission
   prompt on first use. Verify that plain HTTP to the LAN address now works, and record the exact settings that did.

**Done when** the development build streams a reply from the owner's Ollama with the Local Network permission
accepted.

### 3. Chat (the rest of the chunk; Expo Go is enough for all of it)
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
- **Until the membership is renewed there are no EAS device builds.** Work in Expo Go: the app runs from the PC's dev
  server, so it needs the PC on the same Wi-Fi.
- **After renewal, day to day:** `npx expo start --dev-client` on the PC. JavaScript changes reload on the iPhone
  with **no build**. An EAS build is needed only when native code changes: a new native module, an `app.json` plugin
  or `infoPlist` change, or an SDK upgrade. **Batch those changes**, and **ask before starting any EAS build.**
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

**Stage 1 — Expo Go (free; no Apple account)**
- [x] The owner's setup items 1–3 are done (Expo Go, Node, the same Wi-Fi and firewall).
- [x] **Hello Ollama:** a prompt streams a reply from the owner's Ollama in Expo Go, incrementally, with the
      first-token time recorded. The address comes from Settings. If iOS blocks plain HTTP in Expo Go, the exact
      error is recorded and the item is moved to Stage 2.
- [ ] Chat works end to end on the iPhone: several conversations, streaming, stop, regenerate, titles, the model
      picker, Markdown, light and dark.
- [ ] Spoken replies: sentence by sentence while streaming, Play and Stop, speed.
- [ ] Conversations survive an app restart, and a database-migration test passes.
- [ ] `scripts/ios-check.sh` and the new CI job pass. The Android CI and `scripts/check.sh` are unchanged and green.

**Stage 2 — EAS builds (after the owner renews the Apple Developer Program)**
- [ ] The owner's setup items 4–8 are done.
- [ ] An EAS development build installs on the iPhone and connects to `expo start` on the PC. The Local Network
      prompt appeared and was accepted, and plain HTTP to the owner's Ollama works with the recorded Info.plist
      settings.
- [ ] A `preview` (standalone) build runs without the PC.
- [ ] As built records: the SDK version, the navigation choice, the first-token latency, the number of EAS builds
      used and their queue and build times, and anything that didn't work.

## Implementation notes (traps)
1. **Expo Go can't test the Info.plist keys.** Test the local-network and ATS settings in the development build. And
   the SDK in the project must match the Expo Go in the App Store.
2. **Never log conversation text in release builds** (as on Android).
3. **Apple credentials and two-factor codes are the owner's** to type, and never go in chat or files. EAS stores
   the certificates. The membership renewal and its payment are the owner's alone.
4. **The Windows firewall and the VPN** are the usual reasons the iPhone can't reach Metro or Ollama. Same Wi-Fi,
   private network.
5. **EAS free-plan queues:** plan the builds, and expect waits. Free iOS builds are sometimes disabled for a while.
6. **SDK upgrades** can change things a lot (SDK 55 dropped the old architecture). Pin exact versions and upgrade on
   purpose.
7. **Docs to update in this PR:**
   - README (chunk status), and 01-architecture (a short "iOS app" section: stack, folders, how it differs)
   - 05-environment (Node, Expo Go, running, the firewall; and for Stage 2: `eas login`, `device:create`, the dev
     client)
   - 00-product §3, and AGENTS.md (the iOS commands, and the "ask before an EAS build" rule)

## Roadmap (not designed; the owner picks the order)
After C40 the iOS app grows chunk by chunk, toward what the owner uses on the phone: tools (time, weather, web,
location), then accounts (Google and Microsoft through `expo-auth-session`, which needs iOS OAuth client types),
calendar, mail and to-dos, voice memos and dictation, attachments, the smart home, and Jarvis's feeds (C38, C39).
Several of these need a development build, so the paid membership. Hands-free is limited by iOS. Full parity with the
Android app probably isn't worth it, so the owner decides what matters on iOS. Andarium covers whatever isn't native
yet.

## Not in scope
- Changes to the Android app, Kotlin Multiplatform, a Swift rewrite.
- TestFlight and the App Store; push notifications; background work.
- Any tool, attachment, voice input, account or smart-home feature.

## As built (Stage 1, 2026-11-19)

Built by an AI agent in `ios-app/`. Android (`app/`) is untouched. Where the code differs from the specification above, the code is right.

**Stack and choices**
- **Expo SDK 58** (the latest stable on 2026-11-19; `expo-doctor`: 20/20), React Native 0.88, React 19.3, TypeScript 6 in strict mode, the New Architecture. The text above said "up to 57"; npm's `latest` was 58.0.26 and the owner's Expo Go opened it.
- **Navigation: Expo Router** (file routes in `src/app/`), tabs **Chat | Settings**. The conversation list is a **modal screen** (`/conversations`), not a drawer: a drawer needs `react-native-gesture-handler` and `react-native-reanimated`, which this chunk doesn't otherwise need. Rename uses iOS's `Alert.prompt` (iOS only).
- **No state or Markdown library.** State is plain React plus a tiny observable `Store` (`core/store.ts`) read with `useSyncExternalStore`. The Markdown renderer is about 100 lines of our own (`ui/markdown.ts`, `MarkdownText.tsx`), like the Kotlin one, written without regex lookbehind (Hermes support isn't something to rely on; L25).
- **Layout:** `src/ollama/` (client, UTF-8 decoder, catalog, think policy, the probe), `src/chat/` (persona, prompt, context, engine, titler, current conversation), `src/data/` (SQLite, migrations, repository), `src/settings/`, `src/speech/`, `src/ui/`, `src/core/`. Tests sit next to the code; `src/testing/` holds the fakes.
- **Packages:** `expo-router`, `expo-sqlite` (database and key-value settings), `expo-speech`, `expo-clipboard`, `@react-native-community/slider`, `@expo/vector-icons` (with `expo-asset` and `expo-font`), and `react-dom` pinned to 19.3.0 (a peer of Expo Router). `react-native-keyboard-controller` was tried and removed: it needs Reanimated. The chat uses React Native's `KeyboardAvoidingView`.

**Hello Ollama (the first proof)**, run by the owner on their iPhone in Expo Go, server `http://192.168.4.82:11434`:
- **iOS allows plain HTTP to the LAN address in Expo Go.** No error, and no Info.plist change was needed. (A raw IP address is exempt from App Transport Security, as Apple's note says. A `.local` name was not tested.) Nothing moved to Stage 2 for this.
- **`expo/fetch` streams.** 27 network chunks for 26 tokens, spread over 438 ms: **incremental**. The XHR and streaming-library fallbacks weren't needed.
- **First token 17.0 s** (first network chunk 17.0 s, whole reply 17.5 s) on the first request. That is Ollama loading the model cold, like the Kotlin app's 18.5 s cold figure (C06 As built), not the network. A warm figure isn't recorded yet.
- The proof screen stays in the app as the `/hello` route (not linked from the tabs).

**Differences from the specification**
- **A UTF-8 decoder of our own** (`ollama/utf8.ts`), not `TextDecoder` with `{stream: true}`: whether Hermes' `TextDecoder` supports `stream` isn't something to rely on, and ours is tested at every split point of multi-byte characters.
- **Timeouts.** React Native's fetch has no separate connect timeout, so a chat waits up to 180 s for the first byte (a cold model load) and 180 s of silence between chunks. The quick calls (models, show, version) give up after 8 s with "Can't reach your Ollama server".
- **Drafts** are kept per conversation while the app runs, not across a restart (iOS has no `SavedStateHandle`). There is **no "New" button** for scrolled-up readers: the list is bottom-up, and a reply grows in view. The chat bridges the moment between "reply finished" and "stored reply arrives" so the text doesn't flicker (as C07 As built).
- **The date line** is formatted by hand in English, with the zone and UTC offset (`Current date and time: Thursday, October 29, 2026, 2:30 PM (America/Detroit, UTC-04:00).`), so it is the same on every device and testable.
- **Tools:** none. A prompt section tells the model it has none, so it doesn't pretend.
- **Settings:** `serverUrl` defaults to `http://192.168.4.82:11434` as a first-run starting value and is saved with **Save**, as in the Kotlin Settings. There is **Test connection**, the model list (capability lines, Retry), speak replies, speech speed, Test voice and About.
- **Database:** `edith.db`, schema v2 by `PRAGMA user_version`. A fresh install runs the v1 step, then the v2 step (`ALTER TABLE … ADD COLUMN`, never a rebuild). A new conversation takes SQLite's next id, which may reuse the id of a deleted newest one (as C06b).
- **No Android-only parts:** no local-network permission code (iOS asks by itself when needed; in Expo Go it didn't), and no reply foreground service.

**Verified**
- `scripts/ios-check.sh` from a clean `npm ci`: `tsc` clean, ESLint clean, **107 Jest tests** in 7 suites (log: `build-logs/2026-11-19_20-39-47_ios-check.log`). `expo export --platform ios` bundles, and `expo-doctor` passes 20/20.
- The tests port the Kotlin cases:
  - the client against a fake `fetch`: the request body (`think:false`, `num_ctx` 16384, tools only when given, no `keep_alive`, `gpt-oss` gets `"low"`), a text stream, a tool call, a thinking block, an error line, lines and a UTF-8 character split across chunks, model-not-found, a refused connection, a malformed line, a stream cut short, cancel and timeout
  - the migration of a real v1 file on Node's real SQLite (messages and attachments kept, `foreign_key_check` clean)
  - the repository: separate chats, `updated_at`, title rules, an insert into a deleted chat, enum strings, `<tool_call>` stripping
  - the prompt (persona verbatim, date line, UTC offsets, L29) and the context builder (failed and cancelled excluded, the 10,000-token budget, middle truncation, no leading assistant message)
  - the engine: send, growing text, a failure then the next send, cancel, regenerate, retry, a blank reply, a send the moment a reply ends, a turn finishing in its own chat after a switch, `cancelAndJoin` then delete
  - model selection (L21), the titler and `CurrentConversation`
  - the sentence chunker, speech cleaning and `SpeechOutput` with a fake voice
  - Markdown, times and the chat components (React Native Testing Library)
- **Not verified (the owner's, on the iPhone):**
  - chat end to end: several conversations, stop, regenerate, titles, the model picker, Markdown, light and dark, keyboard and safe areas
  - spoken replies by ear (sentence by sentence, Play and Stop, speed), and **how the ring/silent switch affects `expo-speech`** (unknown; to be recorded here)
  - that conversations survive an app restart
  - a warm first-token time

  The agent never saw the layout or the keyboard behaviour.
- The ios-app CI workflow's first run is on the PR.

**Not started:** Stage 2 (EAS builds, the development build, the `infoPlist` settings and the Local Network prompt), which needs the owner's Apple Developer membership. Plain HTTP already works in Expo Go; whether it still does in a build is for Stage 2 to record.
