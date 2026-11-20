# Edith for iOS — roadmap, architecture guide and rules for implementing agents

**Status:** written 2026-11-20, after C40–C47 were built and merged. This file is the entry point for **every agent that
works on `ios-app/`**. Read it first, then read the one chunk you were given (C48–C76, in `chunks/`). It repeats nothing
that the Android design already says well: where an iOS chunk ports an Android chunk, the iOS chunk says which Android
sections to read and then spells out **only what is different or new on iOS**, plus every file, test and acceptance step.

Contents: 1 What exists · 2 Rules · 3 How the app is built · 4 Platform limits (what Expo Go can and cannot do) ·
5 The chunk map · 6 Build order and parallel work · 7 Owner prerequisites · 8 Decisions to record · 9 Not planned ·
10 Android work that is still open.

---

## 1. What exists today (merged to `master`)

`ios-app/` is a new Expo (React Native, TypeScript) app, run on the owner's iPhone with **Expo Go** (no Apple account).
Android is a separate Kotlin app in `app/`; the two share the **design documents**, not code (D27).

| Chunk | What the iPhone app has |
|---|---|
| C40 | Chat with the owner's Ollama (streaming, stop, regenerate, several conversations, titles written by Edith, Markdown, light/dark), spoken replies (`expo-speech`), Settings (server, model, speak, speed) |
| C41 | The tool loop (`runToolTurn`), `get_current_time`, `get_weather` (Open-Meteo), phone location |
| C42 | `search_web` (DuckDuckGo) and `read_web_page` |
| C43 | Photo attachments (picker, JPEG shrink, vision gate, bubbles, viewer) |
| C44 | Voice memos (record a WAV, the model transcribes it, sent as `voice_memo`) |
| C45 | Confirmation cards (`Tool.confirmation`, `ChatEngine.resolveConfirmation`) |
| C46 | The smart-home core: model, registry (`Home`), name matching, `home_devices` / `home_control`, Home tab, Demo home |
| C47 | Photos kept on disk across restarts, retention (newest N, default 10), Settings → Attachments |

Test count at the time of writing: 226 Jest tests. `scripts/ios-check.sh` is the gate (npm ci, tsc, ESLint, Jest); CI
runs the same (`.github/workflows/ios-app.yml`).

**Not on iOS yet** (everything below is designed in the chunks that follow): any real smart-home service, Edith's own
files and resends, hands-free, Google / Microsoft / iCloud accounts, calendar, mail, to-dos, the Jarvis work account and
printer, audio attachments, camera pictures, live view, alerts, development and standalone builds.

---

## 2. Rules for every iOS chunk

1. **One chunk, one branch, one PR.** Branch `cNN-short-name`, PR titled `CNN: …`, merge commit. The repo's rules in
   `AGENTS.md` and `02-standards.md` apply. **Ask the owner before committing, pushing, merging, tagging or publishing**
   unless they have said (in the current conversation) that you may. Never merge a PR whose CI is not green. Never merge
   someone else's PR unless asked.
2. **Start from a fresh `master`** (`git fetch origin` first; `origin/master` can be stale): `git worktree add
   .worktrees/cNN -b cNN-name origin/master`, then `cd .worktrees/cNN/ios-app && npm ci` (about 3 minutes; run it in
   the background). **Do not `ln -s node_modules`** on Windows Git Bash: it copies the whole folder. A stray `npm ci`
   or copy can lock files: check running processes before deleting `node_modules`.
3. **Gate before every commit:** `bash scripts/ios-check.sh` (from the worktree root). Report the real numbers.
   Also run `cd ios-app && npx expo-doctor` when you add a package or a config plugin (it must stay 20/20 or better).
4. **Update the design in the same PR:** add/adjust `docs/design/chunks/CNN-*.md` ("As built" and "Not verified"
   sections), the row in `docs/design/README.md`, and one sentence in `AGENTS.md` ("C## … is built"). Line endings:
   the repo's text files are CRLF in the working tree; Git normalises, so ignore "LF will be replaced by CRLF" warnings,
   but when you patch a file with a script, `replace(/\r\n/g, '\n')` first.
5. **Say what you could not verify.** Nothing in this app has run on the iPhone unless a chunk's "As built" says the
   owner checked it. Write the owner's checks as unchecked acceptance boxes. Never claim a real service works because a
   pretend one did.
6. **Never handle secrets.** Never type, log, print or commit a password, token, key or code. The owner types
   credentials into the app's own forms or the service's own page. Secrets live only in `expo-secure-store` (see §3.6).
   No secrets in `app.json`, `EXPO_PUBLIC_*` variables or the repo. Conversation text, email text and tokens are never
   logged (`core/log.ts` has `sensitive`, which prints only in development).
7. **Never start an EAS build, create an account, pay for anything or touch Apple/Expo credentials.** Ask the owner
   first (D27). The free plan allows about 15 iOS builds a month.
8. **Network rules (C37 spirit):** a new service uses HTTPS only. The only plain-HTTP destinations are Ollama (the
   owner's LAN address), the 3D printer / Meross devices on the LAN, and the web-page reader. A new service never builds
   its own fetch wrapper: use `core/network.ts` (`httpFetch`) and add a `secureFetch` wrapper in C48 that refuses
   `http://` and redirects to another host when a credential is attached.
9. **Hermes limits** (the JavaScript engine): no regex lookbehind, no `\p{…}` classes; `TextDecoder` streaming is
   unreliable (we use `ollama/utf8.ts`); `Intl` time-zone support must be verified, not assumed (L29). Prefer plain
   string code to clever regexes.
10. **Tests** are Jest (`jest-expo`) next to the code. Use fakes, never the network or the real phone modules (see
    §3.9). `@testing-library/react-native` in this repo is the version where **`render`, `rerender` and `fireEvent`
    return promises: `await` them.** Importing `core/services.tsx` in a test pulls in native modules that don't exist
    under Jest (`expo-audio`, `expo-sqlite`): mock `../core/services` at the top of the test file instead.
11. **Ask the owner** every decision the chunk lists under "Decisions". Record the answer in `06-decisions.md` in the
    same PR (next free ID: D28, see §8).
12. **Report honestly in the PR:** what you built, the exact check output, what is not verified, and what the owner must
    try on the phone. Keep the PR description under 40 lines.

---

## 3. How the app is built (so you can add to it without re-deriving anything)

All paths are under `ios-app/src/`. Everything is plain TypeScript classes with **injected dependencies** so tests can
use fakes; React components are thin.

### 3.1 Wiring: `core/services.tsx`
`createServices()` runs once at startup and returns the `Services` object: `settings`, `repo` (ChatRepository), `catalog`
(ModelCatalog), `selection` (ModelSelection), `engine` (ChatEngine), `current` (CurrentConversation), `speech`
(SpeechOutput), `tools` (ToolRegistry), `attachments` (AttachmentStore), `retention`, `voice` (VoiceMemos), `home` (Home).
Components read it with `useServices()`; observable values with `useStore(store)`. **To add a service:** construct it in
`createServices`, add it to the `Services` interface and the return value. Order matters: things that others need come
first (repo, settings), then tools, then the engine.

### 3.2 Observable state: `core/store.ts`, `core/useRepoQuery.ts`
`Store<T>` holds a value, `get()`, `set()`, `subscribe()`. `useStore(store)` is `useSyncExternalStore`. `useRepoQuery(repo,
fetch, deps, initial)` re-runs a repository query when the repository reports a change (`repo.changed()`); pass extra
deps (e.g. `store.version.get()`) to refresh on other signals.

### 3.3 Data: `data/`
`expo-sqlite`. `migrations.ts` has the schema (v2: conversations, messages, attachments with `stored_path`, `removed_at`,
`resend_of`, `direction`). A schema change = a new migration entry (`version: N, sql`) plus a test in `data/data.test.ts`
(it runs migrations on `node:sqlite` through `testing/nodeSqlite.ts`). `chatRepository.ts` is the only SQL. Settings are
in the key-value store (`settings/settings.ts`, keys in `KEYS`); add a setting by extending `Settings`, `DEFAULT_SETTINGS`,
`KEYS` and `current()` (and the literals in `settings.test.ts`).

### 3.4 Chat engine: `chat/`
`ChatEngine.send(conversationId, text, source, attachments)` stores the user message, builds the context
(`contextBuilder.buildContext(history, system, budget, media)`), runs `runToolTurn`, stores the reply. State is a `Store`
with `idle | working{label, partialText, confirmation?} | failed`. `PromptBuilder` assembles the system prompt from
`PromptSection`s (`dateTimeSection`, `toolGuidanceSection(tools)`, `attachmentsSection`); **to teach the model something,
add a section in `core/services.tsx`'s `PromptBuilder` list**. `ReplyListener`s (speech, titler) hear deltas and the final
text. The turn is never stored with its tool exchange (L3): only the final text and the names of the tools used.

### 3.5 Tools: `tools/`
`Tool { spec, statusLabel, displayName, maxResultChars?, enabled?(), confirmation?(args, ctx), execute(args, ctx) }`.
`ToolSpec.parameters` are `{name, type, description, required?, enumValues?, aliases?}`. `ToolArgs` coerces what small
models send (`string`, `int`, `number`, `bool`; a missing required parameter throws `ArgumentError`, which the runner turns
into text for the model). `ok(text)` / `failed(text)` build results. `ToolRegistry` offers tools only to models with the
`tools` capability and only when `enabled()` is true; it repairs near-miss tool names. **To add a tool:** write it, add it
to the `ToolRegistry([...])` in `createServices`, test it with `ToolArgs` + a fake client (see `tools/tools.test.ts`,
`home/home.test.tsx`). A tool that changes something outside the app **must** define `confirmation` (D11, C45).
The runner allows 5 rounds, 20 s per tool, then a last round without tools.

### 3.6 Secrets (to be built in C48): `core/secrets.ts`
`expo-secure-store` (Keychain). Keys are `account/<id>/…` or `home/<service>`. A `SecretStore` interface
(`get/set/delete`) with an in-memory fake for tests. **Never** put a token in the key-value settings store, a log, an
error message or a test fixture that looks real.

### 3.7 Home: `home/`
`Home` is the registry (`setBackends`, `refresh`, `visible`, `command`, favourites/hidden/chip/log in the key-value store).
A service implements `HomeBackend { id, name, isHub?, devices(), state(id), command(id, cmd) }`. `actions.ts` holds the
action rules and the card rules (`confirmationFor`); `names.ts` the name matching; `homeTools.ts` the two tools;
`demoBackend.ts` the Demo home. The Home tab is `ui/HomeScreen.tsx`; Settings → Home is `ui/HomeSettings.tsx`.

### 3.8 UI conventions
Expo Router: tabs in `app/(tabs)/` (`index` Chat, `home`, `settings`); screens are thin wrappers around components in
`ui/`. Theme via `useTheme()` (`ui/theme.ts`: `background, surface, text, textMuted, primary, onPrimary, border, error,
edithBubble, onEdithBubble, code`). Icons: `@expo/vector-icons` Ionicons. Every pressable has an `accessibilityLabel`
(tests find controls by label). Dialogs: `Alert.alert` (use the `ask` injection pattern in `HomeScreen.runFromScreen` so
tests can drive them). Safe areas: `SafeAreaView` from `react-native-safe-area-context`.

### 3.9 Testing patterns
- HTTP: `testing/fakeHttp.ts` (`fakeFetch(respond)`, `jsonResponse`, `streamResponse`, `ndjson`) returns an `HttpFetch` that
  records requests. `OllamaClient` takes it. Services you write take an `HttpFetch` the same way.
- Database: `testing/nodeSqlite.ts` (`migratedDb()`), `FakeClock`.
- Settings: `memoryStore()` from `settings/settings.ts`.
- Phone modules (microphone, files, location, picker) are behind small interfaces (`Recorder`, `AudioFiles`,
  `AttachmentFiles`, `ImageProcessor`, `LocationProvider`) with a fake in the test and the Expo-backed class in its own
  file (`expo*.ts`) that tests never import.
- Timers/delays are injected (`setTimer`, `now`) when the code waits.
- A feature is not finished until the **screen** has a test too (render, press by label, check the store/engine).

### 3.10 Where the Android design lives
`chunks/Cnn-*.md` for each Android chunk; its **"As built"** section holds protocol facts, traps and lessons. The iOS
chunk tells you which to read. `04-lessons-learned.md` (L-numbers) and `03-ollama-reference.md` apply to iOS unchanged.

---

## 4. Platform limits: Expo Go, development build, standalone

Three ways to run the app. **Most remaining features need the second.**

| | **Expo Go** (today, free) | **Development build** (EAS, needs the paid Apple Developer Program, ~$99/yr) | **Standalone / TestFlight** (EAS) |
|---|---|---|---|
| Runs from | The PC's Metro server, same Wi-Fi | Metro, same Wi-Fi (`expo start --dev-client`) | Itself; no PC |
| Native modules outside Expo Go's set | No | **Yes** (any, via config plugins) | Yes |
| `Info.plist` / URL schemes / entitlements | No (Expo Go's) | **Yes**, at build time | Yes |
| Custom URL scheme redirects (OAuth) | No | **Yes** | Yes |
| Raw TCP/TLS sockets (`react-native-tcp-socket`) | No | Yes | Yes |
| Certificate pinning / custom trust | No | Yes (native module) | Yes |
| WebRTC, ONNX Runtime, speech recognition | No | Yes | Yes |
| Background modes (audio, fetch) | No | Yes (`UIBackgroundModes`) | Yes |
| Costs an EAS build when native code changes | — | Yes (~15/month free) | Yes |

**What Expo Go is enough for** (Wave A, C48–C57): anything that is HTTPS or plain-LAN-HTTP `fetch`, WebSocket, WebView
sign-in, `expo-secure-store`, `expo-video`, `expo-audio`, `expo-sharing`, `expo-file-system`, `expo-keep-awake`, local
notifications while the app is running.

**What needs the development build** (Wave B, C58 onward): Google and Microsoft sign-in (a registered custom scheme),
iCloud mail (IMAP/SMTP sockets), the Jarvis feed (pinned self-signed TLS), the wake word (ONNX Runtime + background audio),
Meross cloud MQTT (TCP/TLS), live view (WebRTC and raw TLS), audio-file conversion, and anything in the background.

**iOS facts that shape the design** (from the agent's knowledge on 2026-11-20; each chunk that depends on one re-verifies it):
- There are **no foreground services**. An app is suspended shortly after it leaves the screen, apart from audio
  playback/recording, location, VoIP and push. Therefore **camera alerts cannot poll in the background** as on Android
  (C72 designs the options), and hands-free can only listen while the app is open and the screen on, or with an active
  background audio session (C69).
- Remote push needs APNs, which needs the paid membership and a sender. Edith has no server; Jarvis (on the PC) could be
  the sender (C72 option).
- Plain HTTP to a **raw LAN IP** works (Ollama does, in Expo Go) because App Transport Security exempts IP addresses;
  hostnames (`.local`) are not exempt. Local-network access shows an iOS permission prompt.
- The iPhone's own **keyboard dictation** (the mic key) already fills the text field in any app, so a separate in-app
  dictation button (Android's C12 part 2) is **not needed on iOS** and is not planned.
- iOS has built-in text-to-speech voices, so **Edith's own voice (C36, D21) is not needed on iOS**.

---

## 5. The chunk map

Status of every row is **Design** (not started). "Needs" says what must exist outside the code. Sizes: S ≈ half a day,
M ≈ a day, L ≈ two or three days, XL ≈ a week (an agent's working time).

### Wave A — buildable now in Expo Go, no owner setup beyond using the phone

| ID | Chunk | Ports | Depends on | Needs | Size |
|---|---|---|---|---|---|
| [C48](chunks/C48-ios-home-services.md) | Home services framework: `SecretStore`, `HomeService`, Settings → Home service rows and sign-in dialog, `secureFetch` | C25 §7, C26 generic parts, C37 | C46 | — | M |
| [C49](chunks/C49-ios-edith-attachments.md) | Edith's attachments foundation: `from_edith` rows and files, per-direction retention (O1), pictures from tools (`camera_snapshot`) | C16 §1–2, C25 §4 | C46, C47 | — | M |
| [C50](chunks/C50-ios-meross.md) | Meross garage door openers (sign-in, devices, LAN control) | C26 | C48 | the owner's Meross account | M |
| [C51](chunks/C51-ios-ring.md) | Ring doorbell and cameras (sign-in with 2FA, devices, snapshots, events) | C27 part 1 | C48, C49 | the owner's Ring account | L |
| [C52](chunks/C52-ios-blink.md) | Blink cameras (sign-in with 2FA, snapshots, events) | C29 | C48, C49 | the owner's Blink account | L |
| [C53](chunks/C53-ios-camera-clips.md) | Camera clips: the Clips chip, list and player for Ring and Blink | C27 part 2, C29 | C51 or C52 | — | M |
| [C54](chunks/C54-ios-alexa.md) | Alexa devices (WebView sign-in, devices, state, commands) | C30 | C48 | the owner's Amazon account | L |
| [C55](chunks/C55-ios-printer-direct.md) | 3D printer card (Moonraker, direct on the LAN) and `printer_status` | C34 | C48 | the printer on the home Wi-Fi | M |
| [C56](chunks/C56-ios-edith-files.md) | Edith's files: `<file>` blocks, file cards (View / Save / Share), resending attachments | C16 §3–5 | C49 | — | M |
| [C57](chunks/C57-ios-conversation-mode.md) | Conversation mode: hands-free without a wake word (listen, answer, listen again) | C14 (reduced) | C44, C13 | — | L |

### Wave B — needs the development build (the owner's Apple Developer membership)

| ID | Chunk | Ports | Depends on | Needs | Size |
|---|---|---|---|---|---|
| [C58](chunks/C58-ios-development-build.md) | **Stage 2: the EAS development build** (`eas.json`, bundle id, Info.plist, local network, permissions) | C40 Stage 2 | C40 | membership, Expo account, iPhone registered | M |
| [C59](chunks/C59-ios-accounts-google-sign-in.md) | Accounts core and Google sign-in | C22 §1–2, C17b | C58, C48 | Google Cloud iOS client | L |
| [C60](chunks/C60-ios-google-calendar.md) | Calendar tools across accounts and the Calendar tab | C18, C22 §3–4 | C59 | — | L |
| [C61](chunks/C61-ios-gmail.md) | Mail tools (search, read, draft, send) | C19, C22 | C59 | — | L |
| [C62](chunks/C62-ios-todos.md) | To-dos: Google Tasks, eight tools, Agenda / To-do switch | C35 | C60 | Tasks API enabled | L |
| [C63](chunks/C63-ios-microsoft.md) | Microsoft account: Outlook mail and calendar | C23 | C60, C61 | Entra app registration | L |
| [C64](chunks/C64-ios-icloud-calendar.md) | iCloud account, part 1: calendar over CalDAV | C24 | C60 | app-specific password | L |
| [C65](chunks/C65-ios-icloud-mail.md) | iCloud account, part 2: mail over IMAP/SMTP | C24 | C61, C64 | TCP/TLS module | XL |
| [C66a](chunks/C66a-ios-jarvis-connection.md) | **Jarvis connection (Expo Go, no development build): pairing and the authenticated client over a Tailscale-certificate listener** | C38 (core) | C48 | the Jarvis change (`jarvis-tailscale-listener-spec.md`), Tailscale HTTPS on | M |
| [C66b](chunks/C66-ios-jarvis-work-account.md) | Work account through Jarvis (mail/calendar/to-dos read-only) | C38 | C66a, C58, C60–C62 | Jarvis feed running | XL |
| [C67](chunks/C67-ios-jarvis-printer.md) | 3D printer through Jarvis: Printer page, Controls, chat control | C39 stages 1–2 | **C66a**, C55 | Jarvis printer feed | XL |
| [C68](chunks/C68-ios-audio-attachments.md) | Audio files as attachments (convert to WAV) | C15 audio | C58 | native audio decode | M |
| [C69](chunks/C69-ios-wake-word.md) | "Hey Edith" wake word while the app is open | C14 | C57, C58 | the trained model file | XL |
| [C70](chunks/C70-ios-meross-cloud.md) | Meross cloud control (MQTT over TLS) so the garage works away from home | C26 | C50, C58 | — | L |
| [C71](chunks/C71-ios-live-view.md) | Live camera view (Blink stream, Ring WebRTC) and the camera wall | C32 | C52, C51, C58 | WebRTC module | XL |
| [C72](chunks/C72-ios-camera-alerts.md) | Camera alerts under iOS's rules | C33 | C53, C58 | owner decision D31 | L |
| [C73](chunks/C73-ios-resideo.md) | Honeywell/Resideo thermostat | C28 | C48, C58 | developer app approved | M |
| [C74](chunks/C74-ios-philips-lock.md) | Philips lock | C31 | C48 | not needed: the lock works through Alexa (D38) | M |
| [C75](chunks/C75-ios-standalone-builds.md) | **Stage 3: standalone builds, TestFlight, OTA updates, versioning** | C40 §4 | C58 | membership | M |
| [C76](chunks/C76-ios-parity-audit.md) | Parity audit and polish: close every gap against the Android app | all | everything above | — | M |

---

## 6. Build order and parallel work

```
C48 ──┬─ C50 ─────────────────────────────────────────────── C70
      ├─ C54
      ├─ C55 ─────────────────────────────────────────────── C67
      ├─ C51 ─┬─ C53 ──────────────────────────────────────── C72
C49 ──┤       └────────────────────────────────────────────── C71
      ├─ C52 ─┘
      └─ C56
C44,C13 ── C57 ── C69
C58 ── C59 ── C60 ──┬─ C61 ── C65
        │           ├─ C62
        │           ├─ C63
        │           └─ C64 ── C65
        ├─ C66b (needs C60–C62)
C48 ── C66a ──┬─ C67 (with C55)
              └─ C66b
        ├─ C68, C73, C74, C75
C76 last.
```

- **Do first, in this order:** C48, C49 (they unblock everything in Wave A), then in parallel: C50, C54, C55, C56, C57;
  then C51 and C52 (both need C49), then C53.
- **Parallel-safe sets** (different folders; coordinate only on `core/services.tsx`, `ui/HomeSettings.tsx`,
  `docs/design/README.md` and `AGENTS.md`, which every chunk touches: **rebase and resolve, never overwrite**):
  {C50, C54, C55}, {C56}, {C57}, then {C51, C52}.
- **Wave B waits for C58**, which waits for the owner. Once C58 is done: C59 first, then C60/C61 together, then C62–C65 and
  C66 in parallel pairs; C68–C75 are independent of the accounts work.
- **A chunk may be split into two PRs** when its doc says so (C65, C66b, C67, C71). **C66a and C67 need no development build (D39); they wait for the Jarvis change instead.**

---

## 7. Owner prerequisites (what only the owner can do)

| For | What | Chunk |
|---|---|---|
| everything in Wave B | Renew the Apple Developer Program (~$99/yr; expired May 2026); create an Expo account; pick the bundle id; register the iPhone; Developer Mode on | C58 |
| Google | A Google Cloud **iOS** OAuth client for the bundle id; add test users; (Tasks API) | C59, C62 |
| Microsoft | An Entra app registration, "Mobile and desktop applications", redirect `edith://auth` | C63 |
| iCloud | An app-specific password at appleid.apple.com | C64, C65 |
| Meross, Ring, Blink, Amazon | Their own account credentials, typed into Edith's forms; for Blink and Ring a 2FA code | C50–C54, C70 |
| Resideo | A developer app approved at developer.honeywellhome.com | C73 |
| Jarvis | Jarvis's feed switched on and a pairing code; Tailscale on both devices (for away from home) | C66, C67 |
| Wake word | The trained `hey_edith.onnx` (already in the Android assets) | C69 |
| Alerts | Decision D31 (see C72) | C72 |

---

## 8. Decisions to record (next free ID is D28)

Each is "ask the owner, recommended default first". Record the answer in `06-decisions.md` in the PR that needs it.

| ID | Question | Recommended | Needed by |
|---|---|---|---|
| D28 | Accounts on iOS (Google, Microsoft, iCloud mail) wait for the development build (C58)? | **Yes**: no workable Expo Go route (custom URL schemes, sockets) | C58–C65 |
| D29 | How does the phone trust Jarvis's self-signed certificate? | **Superseded by D39**: B first (a Tailscale-issued certificate on a second Jarvis listener, no pinning). A (pin it with a native TLS module, a development build, no change to Jarvis) stays the fallback if B is refused. C: install a profile/CA by hand (not recommended). | C66 |
| D30 | Hands-free on iOS: conversation mode first (no wake word), wake word later? | **Yes** | C57, C69 |
| D31 | Camera alerts on iOS: (A) while the app is open only, plus a local-notification summary on return; (B) Jarvis on the PC sends push through APNs (needs the membership and a Jarvis change); (C) both | **A now, B only if the owner wants away-from-home alerts** | C72 |
| D32 | Google on iOS: keep the 7-day weekly sign-in (project in Testing), or publish the OAuth consent screen ("In production", unverified, up to 100 users, no weekly expiry for non-sensitive scopes only; Calendar/Gmail are sensitive and then show a warning screen)? | **Keep Testing + weekly sign-in** (as Android, D16) | C59 |
| D33 | Meross away from home: cloud MQTT in the development build (C70), or LAN-only on iOS? | **Build C70** | C70 |
| D34 | iOS bundle identifier | the owner picks, e.g. `com.<name>.edith` | C58 |
| D35 | Wake word on iOS: listen only while Edith is open with the screen on, or keep the microphone running with the screen locked (`UIBackgroundModes: audio`, orange microphone indicator, battery cost)? | **Only while open**, then try the other if wanted | C69 |
| D36 | Standalone distribution: internal (ad hoc) builds, or TestFlight? | **Internal first** | C75 |
| D37 | Over-the-air JavaScript updates with EAS Update? | **Yes** (fewer builds) | C75 |
| D38 | Philips lock on iOS: build C74's unofficial path, or use the Alexa route? | **Alexa route; C74 is not built.** The owner confirmed on 2026-11-20 that the lock is linked to Alexa and that locking and unlocking it works from the Home tab (C54). The Philips path would add an unofficial signing key for no gain. The unlock rule is unchanged: always a card, never approved by voice. Reopen only if Alexa stops covering it. | C74 |
| D39 | Jarvis from the iPhone without a development build? | **Yes: Jarvis gets a second listener with a Tailscale-issued certificate** (`jarvis-tailscale-listener-spec.md`); the iPhone uses ordinary HTTPS to the PC's `*.ts.net` name. The owner asked for it on 2026-11-20. C66 is split: **C66a** (the connection: pairing and the client, Expo Go) and **C66b** (the work account's mail, calendar and to-dos, which still need the development build for the accounts UI); **C67 (the printer) depends on C66a only and runs in Expo Go.** Needs a change in the Jarvis repository and the owner's Tailscale HTTPS switched on. | C66a, C66b, C67 |

---

## 9. Not planned

- **Edith's own voice (C36/D21)** — iOS has voices. **A dictation button (C12 §4)** — the iOS keyboard has dictation.
- **Kotlin Multiplatform, a Swift rewrite, sharing code with Android** (D27).
- **The Android foreground services** (reply service D13, alerts service D24): iOS has no equivalent; replies stop when the
  app is suspended (documented in C40's As built); alerts see C72.
- **August locks** (no API; C31), **Home Assistant** (D10), **Jarvis's scheduled tasks** (D19).
- **A camera capture button** in chat (the picker already offers the camera roll; a camera shot is a small addition for C76
  if the owner wants it).

---

## 10. Android work that is still open (not part of the iOS roadmap)

Already designed in their own chunk files; listed so nobody thinks they were forgotten:
C28 and C31 on Android (design only: Resideo needs a developer key; Philips needs the owner's acceptance of an unofficial
path), C32 on Android (the Ring live view, the camera wall and the chat button remain), C39 stages 3–4 (extras and
only-if-asked: `C39-printer-parity.md`, `C39-stage3-handoff.md`), and the owner's live checks of C14, C27, C29, C30, C33,
C36, C38 (listed in each chunk's acceptance). The iOS chunks above port some of these and say so.
