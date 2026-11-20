# C67 — The 3D printer through Jarvis on iOS: the Printer page, Controls, and chat control (stages 1–2)

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **In progress** — ten work packages, one PR each (E1–E10): E1 and E2 built (see "As built"); E3–E10 to do |
| Depends on | **C66a** (the Jarvis connection: pairing and the client), C55 (the direct printer card and `printer_status`), C45 (cards) |
| Unblocks | stages 3–4 on iOS (upload/start/delete/queue-add, alerts, recordings, live camera): **not designed yet because Android has not built them** (`C39-printer-parity.md` §12 E11–E14); add chunks C77+ when Android does |
| Runs in | **Expo Go** (decision D39: ordinary HTTPS to Jarvis's Tailscale name; `react-native-svg` is in Expo Go); it waits for the Jarvis change, not for a development build |
| Size | **XL** (ten packages) |
| Ports | C39 stages 1 and 2 (E1–E10), D26 |
| Read first | **`C39-printer-parity.md` in full** — it is the normative design for the feature (the contract on one page §2, the model §4, the session §6, Controls and confirmation §7, the page and every card with its **final strings** §8, settings §9, chat tools §10, tests §11, work packages §12, acceptance §13, **As built §15 and the Jarvis-side review §16**), and **`jarvis-printer-feed-spec.md`** (the Jarvis side, incl. §14 "As built on the Jarvis side" and §15) |

## Update 2026-11-20 (D39): the transport is C66a's, ordinary HTTPS
Everything below that says `PinnedHttp`, "C66's pinned transport", `requestBinary` or a hand-written HTTP client is **replaced by C66a's `JarvisApi` (over `secureFetch`)**: a long poll is an ordinary `fetch` that takes up to `wait` seconds (set the timeout to `wait + 10 s`), a camera frame is `res.body` read as bytes (`core/bytes.ts` `readBytes`) then Base64. Verify early that `expo/fetch` keeps a request open for 25 s on iOS. The package order, the strings, the safety model and the tests are unchanged.

## How this chunk relates to the Android design
C39 was written for Kotlin/Compose. **Its words, rules and screens are binding on iOS too** ("What the owner sees must behave the
same"): copy its **strings verbatim** (§6.2 the six page states, §7.5 the error words, §8.4 every card's text), keep its
**safety model** (§7 and the Jarvis spec §8: per-phone arming "Controls" that turns itself off, the server-enforced
confirmation, the cancel guard, emergency stop) and its **work-package order**. What changes is only the implementation
language and platform. Everything in this document is the translation table; **where this file is silent, C39 rules.**

## Translation table (Kotlin → this app)
| C39 (Android) | iOS (TypeScript, `printer/feed/` and `ui/printer/`) |
|---|---|
| `PrinterFeedWire.kt`, `PrinterCommand.kt` | `printer/feed/wire.ts`, `printer/feed/commands.ts`: **types copied from Jarvis's `src/shared/printer.ts` and `printerCommands.ts`** (read them; do not guess), a `serialize(command)` per command that emits the **literal JSON** the validator `parseCommand` accepts (a table test per command) |
| `JarvisApi` printer routes | `jarvis/printerApi.ts` over C66's `PinnedHttp` (one function per route; returns `FeedResult<T>` = `ok` / typed error) |
| `PrinterSession` (long-poll loop, backoff) | `printer/feed/session.ts`: `Store<PageState>`; loops `GET /v1/printer/state?after=<rev>&wait=<s>` (the long poll; **a request may take up to `wait` seconds**, so the transport timeout is `wait + 10 s`), backs off 1 s → 30 s on failure, resumes on foreground; **stops when the Printer page is left or the app goes to the background** (`AppState`) |
| `PrinterViewModel`, `PrinterScreen` | `ui/printer/PrinterScreen.tsx` (a stack screen under the Home tab: `app/printer.tsx`), view-model hook `usePrinterPage()` |
| Compose cards `cards/*.kt` | `ui/printer/cards/*.tsx` (one file per card); **charts** with `react-native-svg` (`npx expo install react-native-svg`; in Expo Go and dev builds) for the 20-minute temperature chart and the bed mesh |
| `ConfirmSheet` | a modal bottom sheet (`Modal` with a slide animation) with C39 §7.2's rules: **no button labelled OK/Yes**; the danger sheet for cancel has **Keep printing** as the default; the sheet shows the server's own question text |
| `PrinterSettle` | `printer/feed/settle.ts` (port of Jarvis's `commandSettled`/`settleTimeout`; pure, tested) |
| camera snapshot polling (about 1/s while visible) | an `<Image source={{uri: dataUri}}>` updated by a 1 s loop that **stops when the card is collapsed, the page left, or the app backgrounded**; frames fetched through the pinned transport as binary: **extend `PinnedHttp` with `requestBinary` returning `Uint8Array`**, then Base64 for the Image (keep the frame small) |
| `ActivityResultContracts` file pick (stage 3) | not in this chunk |
| DataStore prefs | the key-value settings store (`printer_collapsed_cards`, etc., C39 §9.2) |

## Work packages (each is a PR; each ends with `scripts/ios-check.sh` green and the "As built" lines updated)
Run them against **the pretend feed** first: extend `scripts/fake-jarvis.mjs` with the printer routes exactly as C39 §11.3 (E1)
— if the Android agent already did (check the file), reuse it and fix gaps; **do not duplicate the script**.

- **E1 Wire, fixtures, pretend feed.** `wire.ts`, `commands.ts`, fixtures (C39 §11.1: copy from `app/src/test/resources/printer/` if present), the pretend feed routes. *Done when:* every fixture parses; all **33 commands serialise to the literal JSON**; the pretend feed answers every route of the contract.
- **E2 The client.** `jarvis/printerApi.ts`, `Shared.printer` in the account's `/v1/shared` mapping (`name, control, alerts, armMinutes, presets, cameras`; **no entry = not shared**), error mapping to **C39 §7.5's words**. *Done when:* every route is callable against the scripted transport; old Jarvis routes still pass.
- **E3 The session.** `session.ts` (§6.1: the loop, `rev`, backoff, foreground/background rules). *Done when:* it publishes `Live` and follows changes within a second of the fake changing; backoff and recovery are unit-tested with an injected clock and transport.
- **E4 The page skeleton and the Status card.** `PrinterScreen`, `StatusCard`, `format.ts` (C39 §8.2: **copy Jarvis's formatting rules**; unit-tested), the six states of §6.2 (`Live`, `Connecting`, `JarvisAway`, `NoJarvis`, `NotShared`, `PrinterOff`), the printer tile's **Details** button on the Home tab, collapsed-card prefs. *Done when:* Home → tile → Details → a live Status card; stopping the pretend feed shows the Jarvis-away banner; no Jarvis account shows `NoJarvis`; not shared shows `NotShared`.
- **E5 The read-only cards.** Temperatures with the chart, Sensors, Fans (values), Slots, Limits (values), System, Bed mesh, History, Files (**no request for the list until "Load files" is pressed**), Console (read), Objects (view), Queue (view), Camera. *Done when:* every card draws from the pretend feed; the chart moves; the camera updates ~1/s and stops when collapsed/left/backgrounded (check the fake's request log); history/console/files are **not requested until their card is opened**.
- **E6 Home and chat fall back to Jarvis; settings text.** The Home tile and `printer_status` (C55) use the direct printer first and **fall back to the Jarvis feed ("via Jarvis")** only when direct is unreachable; **no feed call when direct works**. Settings → Home → 3D printer text per C39 §9.1. **Stage 1 ships here.**
- **E7 Controls and the confirm flow.** The **Controls** switch (arms this phone for `armMinutes`, turns itself off: a visible countdown, **off when the app leaves the foreground or the page is left**, off on `CONTROLS_OFF`), the confirm sheet for every command, pending spinners via `settle.ts`, `quick` gating, **Emergency stop** (works with Controls off, asks first), status buttons Pause/Resume/Cancel print. *Done when:* Controls on/off with countdown; backgrounding turns Controls off (the fake's `arm` shows it); Cancel shows the danger sheet.
- **E8 Temperatures, toolhead, fans, limits, sensors, objects, queue, system controls.** Each control sends the right command and shows **the server's refusal inline**. Number inputs validate (empty = disabled). *Done when:* every control of C39 §8.4 stage 2 sends the literal command (view-model test with a fake api); jog before home → "Home the printer first".
- **E9 Macros and the console input.** Grouping/fold/filter, the parameter form → `macro(name, params)`, console history (50, newest first), 200-character limit, the sheet shows the exact line. *Done when:* a parameterised macro runs through the sheet with the right params.
- **E10 Chat: `printer_control` and the richer `printer_status`.** `home/printerControlTool.ts` (C39 §10; **screen-only cards** for risky commands — "cancel the print" must show a card that waits for a tap; **there is no voice approval on iOS yet, but C57/C69 must keep this**), registration, the richer `printer_status` (temperatures). *Done when (real chat model, pretend feed):* "pause the print" shows a card with the server's wording and pauses it; "cancel the print" shows a card; "turn the printer light on" works; "How's the print going?" mentions temperatures. **Stage 2 ships here.**

## Rules that bite (from C39 §15–16; read them again before E7)
- **The server enforces confirmation** (`CONFIRM_REQUIRED` with the check) and Controls; the phone never decides a command is safe. Send `dryRun` first for commands C39 marks as needing the check, show the server's question, then resend with `confirmed: true`.
- **Nothing is ever tried on the real printer by an agent.** The owner decides what to try, one control at a time, printer idle first (C39 §13).
- Never send G-code or touch the printer except by the typed commands in the contract.
- The token and the camera frames are never logged; `FEED_ERROR` messages are generic.

## Tests
As C39 §11.2 and §12 list per package, translated: `wire`/`commands` literal-JSON tables, `session` (backoff, rev, foreground/background), `format`, `settle`, the view-model (each command's JSON, refusals inline, Controls countdown and auto-off), each card renders from a fixture, the tile fallback rule ("via Jarvis" only when direct is unreachable), `printer_control` cards and refusals, **no printer call without Controls** where the contract requires it.

## Acceptance criteria
- [ ] Each package: `scripts/ios-check.sh` green; the screens driven against the pretend feed on the iPhone (development build), with the fake's request log checked for the "not requested until opened" and "stops polling" rules.
- [ ] **Owner, real Jarvis and printer (stage 1, then stage 2 one control at a time):** the page shows the real state, temperatures, files (after Load files), history, the camera; away from home over Tailscale too; stopping Jarvis shows the banner not a spinner; Controls turn themselves off; Pause/Resume on a real print; Cancel on a throwaway print; emergency stop last.

## Risks and unverified assumptions
- Long-poll through the hand-written HTTP client (C66 §2): a socket that stays open for `wait` seconds; verify the module keeps a connection idle for 25 s on iOS.
- Binary responses for camera frames through the same client; frame rate on a phone's CPU (Base64 in JS): measure; lower the rate before optimising.
- This is a large amount of UI: keep each card small, share a `Card` component, and resist inventing features beyond C39.

## Not in this chunk
Stages 3–4 (upload, start, delete, add-to-queue, alerts, recordings, live camera, screen taps).

## As built
Work packages land as separate PRs; each adds its own lines here. Nothing below has met a real Jarvis or printer (the Jarvis side of the connection is `jarvis-tailscale-listener-spec.md`, not built yet).

### E1 — Wire, commands, fixtures, pretend feed (built)
- `printer/feed/wire.ts`: the printer feed's data shapes (`PrinterState`, `StateAnswer`, `Check`, `SharedPrinter`, files, history, console, temperatures, macros), ported from Android's `PrinterFeedWire.kt` including the later Jarvis-review fields (`epoch`, `consoleSeq`, `features`, `schema`, `hidden` macros). **Every parser is total**: a missing field takes its default, a wrong-typed value is ignored, a state string from a newer Jarvis is shown rather than thrown on.
- `printer/feed/commands.ts`: all 33 commands as a typed union with `serializeCommand`, which writes the literal JSON Jarvis's validator reads (the kind first, fields in the declared order, unset optionals and an empty macro `params` left out). A table test lists every kind against its literal JSON and fails if a kind is missing. JavaScript writes `220.0` as `220`: the same JSON value.
- `printer/feed/fixtures/`: the 15 Android fixtures, copied unchanged. `printer/feed/testkit.ts`: **the pretend feed** for tests (not the Node script: that one serves a self-signed certificate, which Expo Go cannot use). It answers every route from the fixtures, records requests, can hold a long poll, and implements the Controls and confirmation behaviour; `connectedFeed()` gives a paired `JarvisService` talking to it. Test code only.

### E2 — The client (built)
- `printer/feed/api.ts`: `PrinterFeedApi` (one function per route over C66a's authenticated client; the `PrinterRoutes` interface is what the session and page take), the `CommandAnswer` mapping (`sent`, `checked`, `needsConfirm`, `refused`, `controlsOff`, `failed`) and `printer/feed/words.ts` (C39 §7.5's sentences, verbatim). Wait is capped at 25 s with a transport timeout of wait + 15 s; every value in a query is encoded; a dry run sends no request id; a retried send keeps its request id; a long poll is cancelled at once by its signal.
- Changes in C66a's client: failures now carry Jarvis's `code`, the HTTP `status` and the parsed `body` (a refused command's `check` is in it); an unreadable answer has the code `UNREADABLE`; `bytes()` answers camera pictures (3 MB cap, cancellable); `JarvisService.bytes()` and `sharedNow()`; `parseShared` carries the printer's share (`printerShare`).
- Tests: `wire.test.ts` (the Android assertions on the Android fixtures, plus "total" cases), `commands.test.ts`, `api.test.ts` (every route and every error mapping against the pretend feed). **Mutation-checked:** nine deliberate breaks (wait not capped, a request id on a dry run, a path glued into the URL, a confirmation not mapped, Controls-off not told apart, the kind not first, an empty `params` sent, a wrong field name, a default dropped) were each caught.
