# C39 — The 3D printer, as in Jarvis, through Jarvis (D26)

| | |
|---|---|
| Phase | 8 Smart home (v2) |
| Depends on | C34 (the direct printer card), C38 (pairing with Jarvis, `jarvis/`), C25 (Home, confirmation cards), C33 (the alerts service, for stage 3) |
| Unblocks | The whole Jarvis Printer page on the phone, at home and away, plus print alerts |
| Size | Large: fourteen work packages (E1-E14) in four stages, delivered stage by stage. **Each stage is shippable on its own.** |
| Read first | [`../jarvis-printer-feed-spec.md`](../jarvis-printer-feed-spec.md) (the contract; **you will not understand this chunk without it**), C38 "As built", C34 "As built", Jarvis's `docs/plans/printer.md` (parts 1, 2, 5, 6, 7) |

**Audience: the AI agents who will build this.** It is written to be followed literally. Where it says "exactly", it means exactly. Where it
says "ask", stop and ask the owner; don't pick. Every file you create or change is named. Part 12 is the order of work; part 13 is what to check
at the end of each stage.

## 0. Rules for you (read first, every time)

1. **You may never talk to the owner's real printer** (`192.168.4.86`) or send anything to it. Everything is built and tested against the pretend feed (`scripts/fake-jarvis.mjs`,
   part 11) and unit tests. The real-printer checks are the **owner's** (part 13). Same for the real Jarvis: its printer feed is built by the Jarvis agent; you build against the contract.
2. **Edith never builds G-code, never sends a Moonraker path, never parses Klipper objects through the feed.** She sends typed `PrinterCommand` objects and draws the `PrinterState` Jarvis
   sends. The only Moonraker code in the app is C34's (`printer/MoonrakerApi`, `MoonrakerParse`, `PrinterService`), **which you do not change** except where part 9 says.
3. **Safety is the server's.** Edith's buttons are conveniences. Never skip the confirm flow (part 7.2), never send `confirmed: true` without the owner having pressed the confirm button on a sheet
   that showed the server's `question`, never keep Controls on when the screen is left (part 7.1).
4. **Network code:** the feed uses the **existing pinned Jarvis client** (`jarvis/JarvisApi`, built on `httpClients.secure`, C37/C38). **Do not create an `OkHttpClient`.** `HttpClientsWiringTest`
   must keep passing. The printer's own (plain HTTP) client stays only in C34's `PrinterService`.
5. **Main thread:** no network or disk on the main thread (lesson L39; debug builds kill the app for network on main).
6. **Secrets and privacy:** nothing from the feed (state, file names, console lines, history) is written to the log in release builds. Debug logs may name routes and counts, never bodies.
7. **Tests first for logic** (formatting, settle rules, parsing, session state machine); UI gets Compose tests only for the confirm sheet and the Controls bar. Run `scripts/check.sh` before every commit.
8. **One PR per work package** (E-number), titled `C39 E<n>: …`, branch `c39-e<n>-short-name`, merge commit. Update this chunk's "As built" and the design README in the same PR. Ask the owner before
   committing, pushing, merging or publishing.
9. If the feed contract (the Jarvis spec) and reality differ, **stop and tell the owner**; don't paper over it in the client.
10. Words shown to the owner are plain sentences; no jargon ("Moonraker", "Klipper") except inside the System card and where Jarvis's own words quote it.

## 1. Why, and what the owner gets

The owner wants "an identical feature set for the 3D printer to what is in Jarvis". Jarvis's Printer page is large (status, temperatures and chart, toolhead, fans and light,
sensors, macros, limits, console, files, objects, history, slots, system, queue, bed mesh, camera, recordings, alerts, emergency stop, a safety model). C34 gave Edith a status
card with pause/resume/cancel over the **home Wi-Fi only**. The owner chose (D26): **Jarvis proxies the printer through the feed**, so the phone has the full feature set, **away from home
too** (Tailscale, C38), and the printer's logic and safety live in one place, Jarvis.

### Stages (each can ship alone)

| Stage | The owner gets | Work packages | Needs on the Jarvis side |
|---|---|---|---|
| **1. See it** (read-only) | A **Printer page** (open it from the printer's tile on Home): status with progress and time left, temperatures with a 20-minute chart, sensors, fans and light (values), filament slots, limits (values), system, bed mesh, history, files list and details, console, objects, the queue, the camera picture (about 1 a second). Works at home **and away**. The Home tile and `printer_status` fall back to Jarvis when the printer doesn't answer directly. | E1-E6 | J1 |
| **2. Control it** | **Controls** (a switch that turns itself off), then everything the page can do: pause/resume/cancel, set temperatures and presets, home, jog, Z offset, extrude/retract, motors off, fans, light, air circulation, speed/flow, limits, retraction, macros, filament sensor, exclude an object, the queue, the console, restart, **emergency stop**. Chat: "pause the print", "turn the printer light on" with approval cards. | E7-E10 | J2 |
| **3. Extras** | Upload a file from the phone, start a print, delete a file, add to the queue; **print alerts** on the phone (finished, failed, paused, Klipper stopped), also away from home. | E11-E12 | J3 |
| **4. Only if asked** | Timelapse recordings, a live (moving) camera, taps on the printer's own screen. | E13-E14 | J4 |

Away from home needs the phone to reach the PC (Tailscale, C38) **and** Jarvis running; otherwise the Printer page says so (part 6.4).

## 2. The contract in one page (the Jarvis spec has the detail; this is what Edith relies on)

- Base: the paired Jarvis account's address and pinned certificate (C38). Routes under `/v1/printer/…`; bearer token; JSON; times in ms; errors `{ "error": { "code", "message" } }`.
- `GET /v1/shared` → a `printer` entry (name, `control`, `alerts`, `armMinutes`, `presets`, cameras) when the owner shares it. **No entry = not shared.**
- `GET /v1/printer/state?after=<rev>&wait=<s>` → `{ rev, state, arm, quick, serverTime }` (a long-poll).
- Read routes: `temperatures`, `macros`, `console`, `files`, `files/detail`, `history`, `camera/{id}/snapshot`, and (stages 3-4) `events`, `recordings`.
- `POST /v1/printer/controls { on }` → `{ arm }`.
- `POST /v1/printer/command { command, via, confirmed, dryRun }` → `{ sent, risk }`, or the errors `CONTROLS_OFF`, `CONFIRM_REQUIRED` (with the check), `REFUSED` (with Jarvis's reason), `CONTROL_OFF`,
  `PRINTER_UNREACHABLE`, `PRINTER_NOT_SHARED`, `TOO_MANY`, `BAD_REQUEST`, `TIMEOUT`, `TOO_LARGE`, `NOT_FOUND`.
- All JSON shapes are **Jarvis's TypeScript types** (`src/shared/printer.ts`, `printerCommands.ts`) unchanged. Unknown fields ignored; optional fields omitted when empty; `timeLeft` and `light` may be `null`.

## 3. Design

### 3.1 The one picture

```
 Settings → Accounts → Work (via Jarvis)  = the pairing (C38; reused, nothing new to pair)
              │
   JarvisConnection.shared.printer ──────────────► "is the printer shared? may phones control it? presets, cameras"
              │
   printer/feed/PrinterFeed  (resolves the paired Jarvis; owns one PrinterSession)
              │
   PrinterSession  (long-polls /state while the Printer page is on screen; holds PrinterScreenState; sends commands)
              │
   ui: PrinterViewModel → PrinterScreen (cards)          home: PrinterService (C34) tries direct, falls back to the feed
                                                          chat: PrinterStatusTool / PrinterControlTool
```

### 3.2 Decisions made for you (the owner can change them; D26)

| # | Decision | Why |
|---|---|---|
| 1 | The **Printer page always uses the feed**, even at home. C34's direct path stays for the Home tile, `printer_status` and pause/resume/cancel **when Jarvis is not available** (PC off). | One implementation of the printer's rules (Jarvis's), one safety model. Direct keeps the basics working when the PC is off. |
| 2 | Edith sends **typed commands**; the server builds G-code. | The phone can't be tricked, or buggy, into sending G-code. |
| 3 | The phone has its **own Controls** (per-phone arming on the server). It turns off by itself on a timer (server), when the screen is left, when the app goes to the background. | Same three layers as Jarvis's page. |
| 4 | Risky commands need a **confirmation sheet** with the server's own words, and the server refuses to run them without `confirmed: true`. | Two independent guards. |
| 5 | Live data by **long-poll** (no websocket from the phone), the camera by **snapshot polling**. | Simple, works through Tailscale, no new libraries. |
| 6 | The chat tools: `printer_status` (extended) and a new `printer_control` (pause, resume, cancel, light). Cancel is **screen-only** approval (a tap, never a voice). | Jarvis's D7 plus Edith's D10 rule. |
| 7 | Print **alerts** use the foreground service of C33 and the feed's `events` long-poll. | No Play services (Andarium): the phone asks, Jarvis doesn't push. |
| 8 | Presets, hidden macros, arm minutes, the printer's address and API key are **edited in Jarvis only**; Edith reads them. | Single source of settings. |

### 3.3 Packages and files (all under `app/src/main/java/com/edith/app/`)

New:
```
printer/feed/PrinterFeedWire.kt        DTOs for everything in part 4.1
printer/feed/PrinterCommand.kt         the typed commands and their JSON (part 4.2)
printer/feed/PrinterFeedApi.kt         one function per route (part 5)
printer/feed/PrinterSession.kt         the state holder and the long-poll loop (part 6)
printer/feed/PrinterFeed.kt            finds the paired Jarvis and owns the session; the single entry point for UI, Home and tools
printer/feed/PrinterFormat.kt          durations, temperatures, number formatting (part 8.2)
printer/feed/PrinterSettle.kt          "has this command taken effect" (part 7.3), a Kotlin port of Jarvis's commandSettled
printer/feed/PrinterWords.kt           plain-English words for every feed error (part 7.5)
printer/ui/PrinterViewModel.kt         UI state, actions, the confirm flow, Controls
printer/ui/PrinterScreen.kt            the page: header, Controls bar, cards, emergency stop
printer/ui/cards/*.kt                  one file per card (part 8.4)
printer/ui/ConfirmSheet.kt             the confirmation bottom sheet
printer/ui/ControlsBar.kt              the Controls switch with its countdown
printer/ui/TempChart.kt                the chart (Compose Canvas)
home/PrinterControlTool.kt             the chat tool (E10)
alerts/PrinterAlertWatcher.kt          stage 3 (E12)
```
Changed (small, named in the work packages): `jarvis/JarvisApi.kt` (raw and byte routes, per-call timeouts), `jarvis/JarvisWire.kt` (`Shared.printer`), `jarvis/JarvisAccounts.kt`
(expose the paired connection and its shared printer), `accounts/Accounts.kt` (a lookup for the Jarvis connection), `di/AppContainer.kt`, `ui/navigation/EdithNavHost.kt` (a `printer` route),
`home/ui/HomeScreen.kt` (the tile's **Details** button), `printer/PrinterService.kt` (feed fallback), `home/PrinterTools.kt` (richer `printer_status`), `ui/settings/HomeSettings.kt` (help text),
`settings` DataStore (small prefs, part 9), `alerts/AlertPrefs.kt` and `alerts/AlertService.kt` (stage 3).

Test fixtures (new): `app/src/test/resources/printer-feed/*.json` (part 11.1).

## 4. The model

### 4.1 DTOs (`PrinterFeedWire.kt`)

Every class is `@Serializable internal data class`, uses the **same field names as Jarvis's TypeScript** (so no `@SerialName` is needed except where Kotlin forbids the name), and gives **every field with a
sensible default** so a missing field never crashes the parse. Use `feedJson` from `jarvis/JarvisWire.kt` (`ignoreUnknownKeys = true`, `explicitNulls = false`, `encodeDefaults = false`).

| Kotlin | From TypeScript (`src/shared/printer.ts`) | Notes |
|---|---|---|
| `PrinterState` | `PrinterState` | fields exactly as there: `connection: String`, `message: String?`, `lastSeen: Long?`, `hostname: String?`, `print: PrintInfo`, `heaters: List<Heater>`, `sensors: List<Sensor>`, `fans: List<Fan>`, `light: Double?`, `toolhead: Toolhead`, `extruder: ExtruderInfo`, `retraction: Retraction?`, `filamentSensors: List<FilamentSensor>`, `objects: List<PrintObject>`, `slots: List<FilamentSlot>`, `slotsLive: Boolean`, `alert: PrinterAlert?`, `host: HostInfo?`, `versions: Versions?`, `cameras: List<PrinterCamera>`, `mesh: BedMesh?`, `queue: JobQueue?` |
| `PrintInfo` | `print{…}` | `state: String` (`standby/printing/paused/complete/cancelled/error`), `filename: String?`, `title: String?`, `progress: Double`, `printDuration: Double`, `totalDuration: Double`, `timeLeft: Double?`, `timeLeftFrom: String?`, `filamentUsed: Double`, `layer: Layer?` (`current: Int, total: Int`), `message: String?`, `paused: Boolean`, `busy: Boolean` |
| `Toolhead` | `toolhead{…}` | `position: List<Double>` (3), `homed: String`, `min: List<Double>`, `max: List<Double>`, `liveVelocity`, `maxVelocity`, `maxAccel`, `squareCornerVelocity`, `minimumCruiseRatio`, `speedFactor`, `extrudeFactor`, `zOffset`, `motorsOn` |
| `ExtruderInfo` | `extruder{…}` | `canExtrude`, `pressureAdvance?`, `minExtrudeTemp`, `nozzleDiameter?` |
| `Heater`, `Sensor`, `Fan`, `FilamentSensor`, `FilamentSlot`, `PrintObject`, `PrinterCamera`, `BedMesh`, `JobQueue`, `QueueJob`, `Retraction`, `HostInfo`, `Versions`, `PrinterAlert` | same-named types | `PrintObject.center/polygon` are `List<Double>` / `List<List<Double>>` (JSON arrays of numbers); `PrinterCamera` has no URLs (`id, name, aspect, rotation, flipH, flipV`) |
| `PrinterFile`, `PrinterFileDetail`, `HistoryJob`, `HistoryTotals`, `ConsoleLine`, `TempSeries`, `MacroInfo` (+`group: String = "other"`), `MacroParam`, `ArmState` | same-named | |
| `StateAnswer` | the `state` route answer | `rev: Long`, `state: PrinterState`, `arm: ArmState`, `quick: Map<String, QuickCheck>`, `serverTime: Long` |
| `QuickCheck` | | `allowed: Boolean`, `reason: String?` |
| `Check` | the check (dry run / `CONFIRM_REQUIRED`) | `allowed: Boolean`, `risk: String`, `reason: String?`, `question: String?`, `confirm: String?` |
| `SharedPrinter` | `shared.printer` | `name: String`, `control: Boolean`, `alerts: Boolean`, `armMinutes: Int = 2`, `presets: List<Preset>`, `cameras: List<CameraRef>` |
| `Preset` | | `name: String`, `extruder: Int`, `bed: Int` |
| `EventsAnswer`, `FeedEvent` | `events` route | `seq: Long`, `events: List<FeedEvent>`; `FeedEvent(seq: Long, time: Long, kind: String, title: String, text: String)` |

Rules: strings that are enums on the Jarvis side stay **`String` in Kotlin** and are compared with constants in an `object PrinterWords`/`object States` (so a new value from a newer Jarvis never throws).
Helper properties go on the DTOs (`val PrinterState.isReady get() = connection == "ready"`, `val PrintInfo.isActive get() = state == "printing" || state == "paused"`).

### 4.2 Commands (`PrinterCommand.kt`)

One `sealed interface PrinterCommand`. Each subtype is `@Serializable` with `@SerialName("<kind>")`; JSON is made with a dedicated `commandJson = Json { classDiscriminator = "kind"; encodeDefaults = false; explicitNulls = false }`
so the output is e.g. `{"kind":"jog","axis":"x","distance":10}`. **All 33 kinds, exactly these names and fields** (they are Jarvis's `PrinterCommand`):

```
pause  resume  cancel
start(path)  estop  restart  firmwareRestart
setTemp(heater, target: Double)   preset(name)   heatersOff
home(axes: "xyz"|"xy"|"z")   jog(axis: "x"|"y"|"z", distance: Double)   zOffset(delta: Double)
extrude(length: Double, speed: Double)   motorsOff
speedFactor(percent: Double)   flowFactor(percent: Double)
limits(velocity?: Double, accel?: Double, scv?: Double, cruiseRatio?: Double)
retraction(length?: Double, speed?: Double, extraLength?: Double, unretractSpeed?: Double)
fan(fan, speed: Double)   light(level: Double)   airCirculation(mode: "internal"|"external"|"stop")
filamentSensor(sensor, enabled: Boolean)   camera(on: Boolean)
macro(name, params: Map<String,String>)   excludeObject(name)   gcode(script)   deleteFile(path)
screenTap(x: Int, y: Int)   queueAdd(path)   queueRemove(jobId)   queueStart   queuePause
```
Kotlin: `data object Pause : PrinterCommand` for the parameterless ones; `data class Jog(val axis: String, val distance: Double) : PrinterCommand`, and so on. Axes and modes are `String` constants in a companion (`Axis.X = "x"`), not enums (so a typo can't compile
into a wrong kind). Add `val PrinterCommand.kind: String` (from the `@SerialName`) for logging and the quick-map keys. A test (part 11.2) lists all 33 and checks the JSON of each against a literal.

### 4.3 The screen state (`PrinterSession.kt`)

```kotlin
sealed interface PrinterScreenState {
    data object NoJarvis : PrinterScreenState                       // no paired Jarvis account
    data object NotShared : PrinterScreenState                      // paired, but the printer isn't shared (or Jarvis is too old to know)
    data object Connecting : PrinterScreenState                     // first answer not in yet
    data class Live(val data: StateAnswer, val receivedAt: Long, val offsetMs: Long) : PrinterScreenState
    data class JarvisAway(val last: StateAnswer?, val why: String) : PrinterScreenState   // can't reach the PC
}
```
`offsetMs` = `serverTime - clock.now()` at the last answer, used to show the Controls countdown correctly on a phone with a wrong clock. `Live.data.state.connection` may be `unreachable`
(Jarvis is fine, the printer is off): that is **Live**, shown with the last values and the words from part 8.3.

## 5. The client (`PrinterFeedApi.kt` and changes to `JarvisApi.kt`)

### 5.1 Changes to `jarvis/JarvisApi.kt` (small; do not change existing behaviour)

Add, next to `get`/`post`:
```kotlin
internal class Raw(val status: Int, val contentType: String?, val body: ByteArray)
internal sealed interface FeedResult<out T> { data class Ok<T>(val value: T): FeedResult<T>; data class Err(val status: Int, val code: String?, val message: String, val body: String): FeedResult<Nothing>; data object Unreachable: FeedResult<Nothing>; data object Unauthorized: FeedResult<Nothing> }
internal suspend fun <T> getJson(path: String, query: Map<String,String> = emptyMap(), serializer: KSerializer<T>, last: String? = null, readTimeoutS: Long? = null): FeedResult<T>
internal suspend fun <T> postJson(path: String, body: String, serializer: KSerializer<T>): FeedResult<T>
internal suspend fun getBytes(path: String, query: Map<String,String> = emptyMap()): FeedResult<Raw>
internal suspend fun postBytes(path: String, query: Map<String,String>, body: RequestBody, callTimeoutS: Long): FeedResult<String>   // upload (E11)
```
They share the request building and the **cancellable call** (`Call.await()`, L43), the pinning, `onUnauthorized`, and map a non-2xx answer to `Err` with the server's `code`/`message`
(**parse the error body**; the existing `failureFor` stays for the old routes). A cancelled coroutine cancels the HTTP call. The long-poll asks for `wait=20`, so the existing 30 s read and 40 s call timeouts are enough; only the **upload** needs longer, so `postBytes` builds `client.newBuilder().callTimeout(callTimeoutS, SECONDS)` for **that call only** (never change the shared client). Tests in `JarvisFeedTest` style (MockWebServer over TLS): a 409 with a body is `Err(409, "CONFIRM_REQUIRED", …)`; cancelled coroutine cancels the call (copy the existing test).

### 5.2 `PrinterFeedApi` (one class, one function per route; returns `FeedResult`)

| Function | Route | Returns |
|---|---|---|
| `state(after: Long?, waitS: Int)` | `GET state?after&wait` | `StateAnswer` |
| `temperatures()` | `GET temperatures` | `List<TempSeries>` |
| `macros()` | `GET macros` | `List<MacroInfo>` |
| `console(count: Int = 200)` | `GET console?count` | `List<ConsoleLine>` |
| `files(fresh: Boolean)` | `GET files?fresh=0/1` | `FilesAnswer(files, cachedAt)` |
| `fileDetail(path)` | `GET files/detail?path` | `PrinterFileDetail` |
| `history(start: Int)` | `GET history?start` | `HistoryAnswer(jobs, totals)` |
| `snapshot(cameraId)` | `GET camera/{id}/snapshot` | `ByteArray` (JPEG) |
| `controls(on: Boolean)` | `POST controls` | `ArmState` |
| `command(cmd, via, confirmed, dryRun)` | `POST command` | `CommandAnswer` (`Sent(sent, risk)`, `Checked(check)`, `NeedsConfirm(check)`, `Refused(reason)`, `ControlsOff`, `Failed(words)`) — the **mapping from error codes to these** is part 7.5 |
| `events(after, waitS)` | `GET events` | `EventsAnswer` (E12) |
| `upload(name, source: InputStream, size)` | `POST files/upload` | path (E11) |

Path segments with odd characters (a camera id, a file path as a **query value**) are encoded by OkHttp's `HttpUrl` builder; **never concatenate strings into a URL**.

## 6. The session (`PrinterSession.kt`)

One instance per app (created by `PrinterFeed`, lives in `AppContainer`), used by the Printer page. It is **only active while the page is on screen** (the ViewModel calls `attach()` in `onStart` and `detach()` in `onStop`).

### 6.1 The loop

```
attach():  cancel any old loop; state = Connecting (keep the last Live if there is one, marked stale); launch loop
loop:
   1. access = feed.access()  -> NoJarvis | NotShared | Ready(api, shared)        (reads the paired Jarvis account and its last `shared`; refreshes `shared` once per attach)
   2. rev = null
   3. repeat while active:
        r = api.state(after = rev, waitS = if (rev == null) 0 else 20)
        Ok(a)            -> rev = a.rev; publish Live(a, now, a.serverTime - now); failures = 0
                            if a.rev == previous rev (a timeout answer): do nothing else
        Err 403 PRINTER_NOT_SHARED -> publish NotShared; stop the loop
        Err 429          -> wait 3 s, continue
        Unauthorized     -> publish JarvisAway(last, "Jarvis removed this phone: pair it again"); stop
        Unreachable/Err  -> failures++; publish JarvisAway(last, words); wait backoff(failures) = min(2s * 2^(n-1), 30s); continue
detach(): cancel the loop; best effort controls(false) if armed (E7); keep the last Live for the next attach, marked stale
```
- The loop is a coroutine on `dispatchers.io`; every wait is cancellable. **No busy loop**: a state answer with the same `rev` arrives only after the wait; a fast stream of changes is at most one answer per ~250 ms (server side).
- `PrinterSession.state: StateFlow<PrinterScreenState>`. `PrinterSession.lastLive` keeps the last good answer (the page shows it greyed while `JarvisAway`).
- `refreshNow()`: re-issues `state` without `after` (used after a command, so the card updates at once).
- A `Clock` is injected; tests use `FakeClock` and a fake `PrinterFeedApi`.

### 6.2 What the page shows for each state (words are final; copy them)

| State | The page shows |
|---|---|
| `NoJarvis` | "Pair Edith with Jarvis to use the printer away from home." and a button **Pair with Jarvis** (opens Settings → Accounts). If C34's direct printer is connected: below it "At home, the printer tile on Home still works." |
| `NotShared` | "Jarvis isn't sharing the printer with this phone. In Jarvis: Settings → Edith → 3D printer, switch on **Let paired phones see the printer**." (also shown when Jarvis is a version that doesn't know the printer: `shared.printer` absent) |
| `Connecting` | a progress indicator and "Connecting to Jarvis…" |
| `Live`, `connection == "ready"` | the page |
| `Live`, `connection == "unreachable"` | the page, greyed, with the banner "Jarvis can't reach the printer (is it on?). Showing what it last saw {age}." |
| `Live`, `connection` is `klippy-startup` / `klippy-shutdown` / `klippy-error` | the page, with the banner `state.message` (Jarvis's words) and, for shutdown/error, the **Firmware restart** button on the System card (E8) |
| `Live`, `connection == "none"` | "No printer is set up in Jarvis." |
| `JarvisAway` | the last page greyed (or "Edith can't reach Jarvis…" with the reason when there is none), banner "Edith can't reach Jarvis ({why}). Is the PC on, Jarvis running, and Tailscale connected?" and **Try again** |

## 7. Controls, confirmation and errors

### 7.1 The Controls bar (stage 2; `ControlsBar.kt`, state in `PrinterViewModel`)

- Shown only when `shared.printer.control == true`. Otherwise a line: "Jarvis lets this phone look, not control." (and every control in the page is not drawn at all, not just disabled.)
- A switch **Controls**, off when the page opens. Turning it on: `api.controls(true)` → `arm`. While on, the bar shows "Controls on · 1:43" (the time left, computed from `arm.until - (now + offsetMs)`, ticking each second) and the page gets an accent border.
- It goes off (and the call `controls(false)` is sent, best effort, never awaited by the UI) when: the countdown ends; the app goes to the background (`Lifecycle.Event.ON_STOP`); the page is left; `JarvisAway`; the printer is not `ready`; the switch is turned off.
  **Never** rely on the phone alone: the server's timer is the guarantee.
- Every accepted command extends the timer (the server returns the new `arm` with the next `state` answer; the bar follows it).
- **While Controls are off,** every control that sends a command is drawn **disabled with the reason** "Turn on Controls to use this" (long-press shows it as a tooltip; text under the control on a phone). Look-only cards stay readable.
- **Emergency stop** is a red button in the page header, enabled only when `connection == "ready"` and the printer is shared, even with Controls off. It needs the confirm sheet (7.2) whose button reads **Stop now**. Jarvis decides whether `estop` is allowed without `control` (spec part 8.5).

### 7.2 The confirm flow (all commands; `PrinterViewModel.send(cmd)` and `ConfirmSheet.kt`)

```
send(cmd):
   1. call api.command(cmd, via = "phone", confirmed = false, dryRun = false)
   2. NeedsConfirm(check)   -> show ConfirmSheet(check.question, check.confirm, danger = check.risk == "danger")
                                 owner taps the confirm button -> api.command(cmd, "phone", confirmed = true) -> step 3
                                 owner taps Cancel / outside / back / 10 s pass -> do nothing, close the sheet
      Sent(sent, risk)      -> mark the command pending (7.3); refreshNow()
      ControlsOff           -> switch Controls off in the UI, show "Controls turned off. Turn them on to continue."
      Refused(reason)       -> show the reason (snackbar, or inline under the control for a jog/temperature) and nothing else
      Failed(words)         -> show the words
   3. Sent / Refused / ... as above (a second NeedsConfirm should never happen; if it does, show "Jarvis asked again. Try again.")
```
- The sheet shows **exactly the server's `question`** (e.g. "Cancel the print of Chimney and Platforms? The print can’t be resumed.") and a confirm button labelled with the server's `confirm` ("Cancel print"), plus a plain **Keep going**/**Cancel** button.
  For `risk == "danger"` the confirm button is red. **Never label a confirm button "OK" or "Yes".** The two buttons are never the same colour and never both say "Cancel" (see C34: Cancel print vs Keep printing: when `confirm` text contains "Cancel", the other button reads **Keep printing**).
- A sheet closes itself after **10 seconds** with no answer (as Jarvis's ConfirmBar).
- A command with a dry-run check is **not needed** to draw buttons (the `quick` map does that for the parameterless ones); use `dryRun` only for: **jog** (to show why a jog is refused before sending: optional), **console** lines (to show the exact line and risk in the sheet; the normal flow already does).
- Only one sheet at a time. A new command while a sheet is open replaces it.

### 7.3 Pending buttons (`PrinterSettle.kt`, a port of Jarvis's `commandSettled` / `settleTimeout`)

After a command is `Sent`, its button shows a small spinner and is disabled until the **state shows the change** or the timeout passes. The table is Jarvis's (copy the logic, constants and tests from `src/shared/printerCommands.ts` lines 620-716):

| Command | Done when | Gives up after |
|---|---|---|
| `home` | every requested axis is in `homed`, the toolhead is still (`liveVelocity < 1`) and (motion was seen, or 5 s have passed) | 120 s |
| `pause` | `print.state == "paused"` | 120 s |
| `resume` | `print.state == "printing"` and not `paused` | 120 s |
| `cancel` | `print.state` is neither `printing` nor `paused` | 120 s |
| `start` | `print.state` is `printing` or `paused` | 60 s |
| `jog` | still, and `position[axis]` within 0.05 mm of before + distance | 30 s |
| `setTemp` | that heater's `target` equals the rounded target | 15 s |
| `preset` | `extruder` and `heater_bed` targets equal the preset's | 15 s |
| `heatersOff` | every heater target is 0 | 15 s |
| `light` | `light` within 0.011 of the level | 10 s |
| `fan` | that fan's `speed` within 0.011 (or the fan is gone) | 10 s |
| `speedFactor` / `flowFactor` | factor × 100 within 0.5 of the percent | 10 s |
| `zOffset` | `zOffset` within 0.0005 of before + delta | 10 s |
| `queueAdd` | more jobs, or a different queue state | 10 s |
| `queueRemove` | the job id is gone | 10 s |
| `queueStart` / `queuePause` | `queue.state` is not `paused` / is `paused` | 10 s |
| everything else | done at once (no spinner) | 0 |

`PrinterSettle.settled(cmd, before: PrinterState, now: PrinterState, info: SettleInfo(elapsedMs, sawMotion)): Boolean` and `PrinterSettle.timeoutMs(cmd): Long`. Port **every** test case from Jarvis's `printerCommands.test.ts`
that covers `commandSettled`/`settleTimeout` (read it; the expected values are there). `sawMotion` is true once a state shows `liveVelocity >= 1` after the send.

### 7.4 Which controls need what (summary for the UI; the server is the truth)

Use the `quick` map from the last `StateAnswer` for the parameterless buttons (`pause`, `resume`, `cancel`, `home:xyz`, `home:xy`, `home:z`, `motorsOff`, `heatersOff`, `restart`, `firmwareRestart`, `queueStart`, `queuePause`, `camera:on`, `camera:off`, `air:*`, `estop`): disabled when `allowed == false`, with `reason` shown.
Everything with a parameter is enabled when Controls are on, and the server's `REFUSED` reason is shown inline if the server says no.

### 7.5 Words for every error (`PrinterWords.kt`; **these are the strings**)

| Situation | Words |
|---|---|
| `Unreachable` (can't reach Jarvis) | "Edith can't reach Jarvis. Is the PC on, Jarvis running, and Tailscale connected (away from home)?" |
| `Unauthorized` | "Jarvis removed this phone: pair it again." |
| 403 `PRINTER_NOT_SHARED` | "Jarvis isn't sharing the printer with this phone." |
| 403 `CONTROL_OFF` | "Jarvis lets this phone look at the printer, not control it." |
| 409 `CONTROLS_OFF` | "Controls turned off. Turn them on to continue." |
| 422 `REFUSED` | the server's `message` exactly |
| 502 `PRINTER_UNREACHABLE` | "Jarvis can't reach the printer right now." |
| 504 `TIMEOUT` | "The printer took too long to answer." |
| 413 `TOO_LARGE` | "That file is too big to send from the phone." |
| 404 `NOT_FOUND` | "Jarvis doesn't have that any more. Refresh the list." |
| 429 `TOO_MANY` | "Too many requests at once. Wait a moment and try again." |
| 400 `BAD_REQUEST` | "Jarvis didn't understand that. (It may be an older version.)" |
| anything else | the server's `message` if it is a sentence, else "Jarvis answered something Edith didn't expect." |

## 8. The page

### 8.1 Where it lives

- A new navigation route `printer` in `ui/navigation/EdithNavHost.kt` (**not** a bottom-bar tab; `Destination.kt` is unchanged). Back returns to Home. The ViewModel is created with the container (like the others).
- Entry: the printer's tile on Home (`home/ui/HomeScreen.kt` `PrinterTile`) gets a **Details** button, shown when a Jarvis account is paired (even if the printer isn't shared: the page then explains).
  When no Jarvis account exists the button is **Printer page** and opens the `NoJarvis` message.
- The chat tools mention it ("Open the Printer page for details").

### 8.2 Formatting (`PrinterFormat.kt`; unit-tested; copy Jarvis's rules)

- `duration(seconds)`: ≥ 1 h → "1h 14m"; ≥ 1 min → "52m"; else "45s"; null/under 0 → "—". Round **down** minutes, never show "0m" for ≥ 1 h (drop the minutes: "2h").
- `timeLeft`: "about 1h 14m" (always "about"); `null` → "—" with the note "Not enough printed yet to tell."
- `eta(now, timeLeft)`: a clock time in the **phone's** zone, "about 14:32" (use 24 h if the phone's locale uses it).
- Temperatures: current to **1 decimal**, targets whole numbers; positions **2 decimals**; filament `mm / 1000` to 1 decimal "metres"; percent whole.
- Dates/times in lists: relative ("2 h ago", "yesterday") for under a week, else a short date.
- File and job titles: **use the `title` Jarvis sends**. Do not re-derive titles from file names.

### 8.3 Layout

A single scrolling column (`LazyColumn`) of **collapsible cards**; a pinned header; the collapsed/expanded state of each card is remembered (DataStore, `printer.collapsed`, a set of card ids; default: Status, Temperatures and Camera expanded, others collapsed).

**Header (pinned):** the printer's name and a connection chip ("Connected", "Printer not answering (last seen 2 min ago)", "Klipper: shutdown – <message>", "Jarvis not reachable"), the **Controls** bar (stage 2), the red **Emergency stop** (stage 2).
When the page is greyed (`JarvisAway`/`unreachable`) every command control is disabled and the chip says why.

**Card order (phone):** Status · Camera · Temperatures · Toolhead · Fans and light · Limits and retraction · Sensors · Filament slots · Macros · Files · Objects · Queue · History · Console · Bed mesh · System. (Recordings, stage 4, after Queue.)

### 8.4 The cards (one file each in `printer/ui/cards/`; every string below is final)

**Status (`StatusCard.kt`).** Title = `print.title` (or "Ready" when standby and no file). A coloured chip for `print.state`: Printing (accent), Paused (amber), Complete (green), Cancelled and Error (red), Standby (muted).
A progress bar with the whole percent. A grid: **Time left** (`timeLeft`, "about 1h 14m", tooltip "Estimated from the slicer"/"Estimated from the time printed and the progress" by `timeLeftFrom`), **Elapsed** (`printDuration`), **Total** (`totalDuration`),
**ETA**, **Filament** ("12.4 m"), **Layer** ("12 / 240", only when `layer` is present), **Speed** ("85 mm/s · 100 %"), **Flow** ("100 %"). `print.message` as a small note. `alert` (the printer's latest error line): a coloured box "The printer said: {text}" with "{age}" and **Dismiss** (hide for this
`alert.time`, remembered until the page is closed). Buttons (stage 2): **Pause** / **Resume** (whichever the `quick` map allows), **Cancel print** (danger, through the confirm sheet). When standby and history has a last job (read lazily): "Last print: {title}, {status} {age}".

**Camera (`CameraCard.kt`).** A picker of `cameras` (names as given; "Printer screen" is id `screen`). Polls `snapshot(id)` **every 1000 ms while the card is expanded and the page is on screen**; draws the last good frame with the camera's `rotation` and flips and `aspect`.
No frame yet: "Connecting…". Two failures in a row: "Camera offline" and (stage 2, `control`) a button **Turn camera on** (command `camera(on=true)`; the confirm sheet shows the server's question). Stops on collapse, page leave, background. The chosen camera is remembered (`printer.camera`).
Decode on `dispatchers.io`, never on main; keep one bitmap, recycle the old one. **Do not poll when `connection` isn't `ready`.**

**Temperatures (`TemperatureCard.kt`, `TempChart.kt`).** One row per heater: label, current (big), "→ {target}", a thin power bar, and (stage 2) a number box for the target with **Set** (command `setTemp`) and **Off** (`setTemp` 0). **Presets** are chips from `shared.printer.presets` ("PLA"…): tap sends `preset(name)`; the sheet shows the server's question
("Heat the nozzle to 220 °C and the bed to 60 °C?"). **All off** (`heatersOff`). The chart: loads `temperatures()` when the card opens, then appends one point per second from `state` answers (a ring buffer of 1200 points per series, kept in the ViewModel);
a line per heater and sensor, a dashed line per heater target; y from 0 to max seen + 10 %, gridlines every 50 °C, x labelled "minutes ago"; tapping a legend entry hides/shows it. Draw with Compose `Canvas` and **theme colours** (readable in light and dark; at least 3:1 contrast; no colour-only meaning: dashed vs solid).
`weightValue` is not a temperature: not drawn (the sensor has an empty unit). Presets can't be edited here.

**Toolhead (`ToolheadCard.kt`, stage 2 controls).** Position X Y Z (2 decimals) and the homed axes as three letters (greyed when not homed). Home: **All**, **XY**, **Z** (commands `home`). Jog: a cross of buttons X−, X+, Y−, Y+ and Z−, Z+ with step chips 0.1 / 1 / 10 / 50 / 100 (100 only for X/Y); no confirm; each tap sends `jog`.
Labels "Z+"/"Z−" (not "bed up/down"). **Z offset**: the value and ±0.01, ±0.05, ±0.1 buttons (`zOffset`; usable during a print). **Extrude**: length (default 10 mm), speed (default 5 mm/s), **Extrude** and **Retract** (`extrude` with a negative length); **Motors off** (`motorsOff`).
Disabled-with-reason texts come from the server's `REFUSED`: "Heat the nozzle to at least 170 °C", "Home the printer first", "Not while printing". Holding a button does **not** repeat. Stage 1: position and homed letters only.

**Fans and light (`FansCard.kt`).** Settable fans (`fan.settable`) as sliders 0-100 % that send **on release** (`fan`); automatic fans as read-only percentages with "automatic". Light: a slider 0-100 % (`light`, level 0-1) and an On/Off button (on = 100 %). Air circulation: **Internal**, **External**, **Stop** (`airCirculation`). Labels come from `Fan.label`.

**Limits and retraction (`LimitsCard.kt`).** Speed factor slider 10-300 % and flow 50-150 %, send on release (`speedFactor`, `flowFactor`). Number boxes for Velocity, Accel, Square corner velocity, Minimum cruise ratio with **Apply** (`limits`, the confirm sheet). Note: "Back to the printer's settings after a restart." Retraction (when `retraction` is present): length, speed, extra length, unretract speed with **Apply** (`retraction`).

**Sensors (`SensorsCard.kt`).** Temperature sensors (unit `°C`) as rows; filament sensors: "Filament detected"/"No filament" and an **Enabled** switch (`filamentSensor`; the server asks for confirmation, and says danger when turning it off during a print).

**Filament slots (`SlotsCard.kt`).** `slots` as four swatches (hex), material, colour name, loaded or not. When `slotsLive` is false: "The printer isn't reporting which slots have filament right now. Its screen shows them." and **no** "empty" label.

**Macros (`MacrosCard.kt`, stage 2).** `macros()` loaded when the card opens. A filter box; groups in this order with counts and a fold toggle: Filament and nozzle, Printing, Bed and calibration, Camera screen and sound, System, Other (`MacroInfo.group` = `filament`, `printing`, `bed`, `camera`, `system`, `other`). Each macro a button with its description underneath; `risk == "danger"` has a red outline. A macro with `params` opens a small form (a text box per param, the default as the hint) with **Run**. Every run goes through the confirm sheet (`macro(name, params)`).
While the filter has text, every group is open. **Edith never decides what is hidden or denied: she shows exactly the list Jarvis sends.**

**Console (`ConsoleCard.kt`).** `console(200)` when the card opens, then updated from `state` answers (poll `console` once every 5 s while the card is open and expanded). Lines coloured by kind (error in red); lines matching `^// value:` hidden by default (a switch **Hide "// value" lines**, on). **Clear** clears the view only. Stage 2: a one-line input
(max 200 chars, up/down history of the last 50 in DataStore `printer.history`); Enter sends `gcode(script)`; the confirm sheet shows **the exact line** and the risk; a refused line shows the server's reason ("Use the System card or the printer's screen for this").

**Files (`FilesCard.kt`).** **Nothing loads when the page opens.** The card shows "Listing the files keeps the printer busy for several seconds, and its buttons wait." and a button **Load files**. After loading: a search box, a switch **Printed before** (on) / **All**, sorted newest first; each row title, size, "{age}", a "printed before" badge. Tapping a row opens a detail sheet
(`fileDetail(path)`: thumbnail, estimated time, filament, layers; "No details on the printer for this file" when empty) with (stage 3) **Print** (`start`), **Add to queue** (`queueAdd`), **Delete** (`deleteFile`, danger). A **Refresh** button re-lists (`files(fresh=true)`) after the same note. Cached in the ViewModel for 60 s. Stage 3: **Upload** (E11).
Print note for `.3mf`: the server refuses (Jarvis's reason is shown: "Start .3mf files from the printer's screen"); Edith shows that reason, nothing more.

**Objects (`ObjectsCard.kt`).** Only when printing or paused and `objects` is not empty. A `Canvas` of the bed (0..`toolhead.max[0]` × 0..`max[1]`, Y flipped) with each object's polygon (current = accent, excluded = hatched), and a list with **Exclude** (stage 2: `excludeObject(name)`, danger sheet "Stop printing {name}? This can't be undone for this print.").

**Queue (`QueueCard.kt`).** `queue.state` as a chip with what it means ("The next job starts as soon as the printer is free" for `ready`), jobs with "{age}" and **Remove** (stage 2: `queueRemove`, no confirm), **Start queue** (confirm) and **Pause queue** (stage 2). Note: "Starting prints from here needs the printer in Local Only mode."

**History (`HistoryCard.kt`).** Totals (jobs, print time, filament in metres, longest print) and the last 50 jobs (title, status, when, duration, filament) with **More** (`history(start += 50)`). Read-only. Loaded when the card first opens (never on page open).

**Bed mesh (`MeshCard.kt`).** `mesh`: a grid of heights, back of the bed at the top, blue below the middle and red above (use a colour scale that is readable in both themes), the spread and the lowest and highest points; "Saved profile (not in use now)" when `active` is false. View only.

**System (`SystemCard.kt`).** The connection chip's message, `versions` (Moonraker, MCU), `hostname`, `host` load and memory; stage 2: **Restart Klipper** and **Firmware restart** (both danger sheets; firmware restart also appears in the header banner when `connection` is `klippy-shutdown`/`klippy-error`).

## 9. Settings, preferences and the Home integration

### 9.1 Settings → Home → 3D printer (`ui/settings/HomeSettings.kt`)

Keep C34's direct-printer fields. Add under them a short paragraph (final words): "To see and control the printer away from home, pair Edith with Jarvis (Settings → Accounts → Work) and, in Jarvis, switch on **Let paired phones see the printer**." and a status line that reads the paired account's `shared.printer`:
"Jarvis: sharing the printer ({name})", "Jarvis: not sharing the printer", "Jarvis: not paired". No new secrets.

### 9.2 Preferences (DataStore keys, in `settings/` like the others; each has a default and a test)

`printer.collapsed: Set<String>`, `printer.camera: String`, `printer.history: List<String>` (the console's last 50, newest first), `printer.consoleHideValues: Boolean = true`, `printer.alerts: Boolean = true` (stage 3).

### 9.3 Home tile and `printer_status` fall back to Jarvis (E6)

`printer/PrinterService.kt` (C34) today reads the printer directly. Change `read()` so that **when the direct read is `Away` (unreachable)** and `PrinterFeed.access()` is `Ready`, it fetches **one** `state(after=null, waitS=0)` from the feed and builds the same `PrinterStatus`/`DeviceState`
from the `PrinterState` (map: state, title, progress, timeLeft, temperatures, layer, error text). The tile then says "via Jarvis" in small text. It does **not** use the feed when direct works (the printer answers faster than a PC round trip and works with the PC off).
`DeviceCommand.Pause/Resume/CancelPrint` on the printer: when direct is unreachable and the feed is available, send the command through the feed (`via: "chat"` if the user asked in chat, else `"phone"` with the usual Controls rules: the Home tile's buttons are **not** allowed to arm Controls; they stay direct-only and show "Not reachable. Open the Printer page.").
`printer_status` (home/PrinterTools.kt) additionally mentions, when a state is available, the layer, nozzle/bed temperatures and the printer's latest alert text, and says "(via Jarvis)" when it came from the feed.

## 10. Chat tools (stage 2, E10)

- `printer_status` stays and gets richer (9.3).
- **`printer_control(action, level?)`** in `home/PrinterControlTool.kt`: `action` ∈ `pause`, `resume`, `cancel`, `light` (`level` 0-100 for `light`; `light` without a level means 100). Offered only when the paired Jarvis shares the printer **with control**. Its description (final): "Pause, resume or cancel the owner's 3D print, or set the printer's light, through Jarvis (works away from home). Every call shows the owner a card first."
  `confirmation(args)` returns a `ConfirmationRequest` whose **title and confirm label come from the server's dry run** (`api.command(cmd, "chat", confirmed=false, dryRun=true)` → `Check.question`, `Check.confirm`); a refused dry run returns no card and `execute` explains the reason. **`cancel` sets `screenOnly = true`.** `execute` calls `api.command(cmd, "chat", confirmed=true)`. Result text: "Done: {sent}." or the words of part 7.5.
  `via: "chat"` never arms Controls and the server only accepts the four kinds.
- Both printer tools are registered with the existing home tools (`Home.ID` integration) and the tool list test is updated. `home_control` on the printer is **unchanged** (direct, at home); `printer_control` is for "anywhere". Their descriptions say which to use.
- Hands-free: a `screenOnly` card makes hands-free say "Tap the screen to approve" (existing behaviour).

## 11. Testing

### 11.1 Fixtures

`app/src/test/resources/printer-feed/` (create in E1; each file is real-shaped JSON written by hand from the TypeScript types, with a comment-free valid JSON body):
`state-ready-idle.json`, `state-printing.json` (progress 0.4, layer 12/240, heaters on, objects, cameras), `state-paused.json`, `state-unreachable.json`, `state-klippy-shutdown.json`, `state-alert.json`, `shared-with-printer.json`, `files.json`, `history.json`, `temperatures.json`, `macros.json`, `console.json`, `check-confirm.json`, `error-controls-off.json`, `error-refused.json`.
Both sides use them: Edith's DTO tests parse each; **the Jarvis agent is asked (spec part 10) to validate its output shape against the same files**. Keep them in step with the TypeScript.

### 11.2 Unit tests (JUnit, `src/test/java/com/edith/app/printer/feed/`)

- `PrinterFeedWireTest`: every fixture parses; unknown fields ignored; missing optional fields default; a future `state` string value doesn't throw.
- `PrinterCommandTest`: all 33 kinds serialise to the literal JSON in part 4.2 (a table-driven test; the table lists every kind, and a test asserts the table covers the `sealed` subclasses via reflection on `PrinterCommand::class.sealedSubclasses`).
- `PrinterFormatTest`: durations, "about", eta in a zone, decimals, titles passthrough.
- `PrinterSettleTest`: every row of 7.3, including the "home reports homed before it moves" case and the timeouts.
- `PrinterFeedApiTest` (MockWebServer over TLS, as `JarvisFeedTest`): each route's URL, query, headers; error bodies map to the results of 7.5; cancellation cancels the call; the bearer token never appears in a log or an error message.
- `PrinterSessionTest` (fake api, `FakeClock`, test dispatcher): first answer, long-poll with `after`, an unchanged `rev` is a no-op, backoff (2 s, 4 s … 30 s) and recovery, `NotShared` stops, `Unauthorized` stops, `detach` cancels and (stage 2) disarms, `attach` after `detach` keeps the last state marked stale, `offsetMs`.
- `PrinterViewModelTest`: the confirm flow (`NeedsConfirm` → confirm → `confirmed=true`; cancel does nothing; 10 s timeout closes; a second command replaces the sheet), `CONTROLS_OFF` switches the switch off, Controls switch off on `ON_STOP`, the countdown from `arm.until` and `offsetMs`, pending until settled, quick-map gating.
- `PrinterControlToolTest`: the card text equals the server's `question`; `cancel` is `screenOnly`; a refused dry run gives no card; `light` clamps; not offered without `control`.
- `PrinterServiceFallbackTest` (E6): direct away + feed ready → the feed's state; direct ok → no feed call; feed not shared → plain "Not reachable".
- `HttpClientsWiringTest` stays green (no new `OkHttpClient`).

### 11.3 The pretend feed (E1; `scripts/fake-jarvis.mjs`)

Extend the existing pretend Jarvis (do not make a new file) with the printer routes of the contract, backed by a small **simulated printer** in memory:
- `shared.printer = { name: "FAKE-AD5X", control: true, alerts: true, armMinutes: 1, presets: [PLA 220/60, PETG 240/80], cameras: [{id:"video",name:"Camera"},{id:"screen",name:"Printer screen"}] }`; env `FAKE_PRINTER=off` omits it; `FAKE_PRINTER_CONTROL=off` sets `control:false`.
- A state machine: `standby` → after `start` becomes `printing` with progress +1 % per 3 s, layers moving, heaters ramping toward targets at 2 °C/s, `pause`/`resume`/`cancel` change state (cancel returns to `standby` after 3 s), `home` sets `homed` after 3 s, `jog` moves the position within limits, `setTemp`, `light`, `fan`, `speedFactor`... the commands of 33 kinds each do something visible.
- `state` is a **real long-poll** (holds up to `wait`, answers when `rev` changes), `quick` computed by small rules (pause allowed only when printing, etc.).
- Per-phone arming: `controls {on}` sets `armedUntil = now + armMinutes`; commands without it → `409 CONTROLS_OFF`; risk table (home confirm, cancel danger, jog none, setTemp confirm, estop danger, macro confirm…) → `409 CONFIRM_REQUIRED` with `{allowed, risk, question, confirm}` when `confirmed` is false; refusals (jog before home, extrude when cold, nothing printing) → `422 REFUSED` with Jarvis's wording from part 5.2 of its plan.
- `files` (3 sample files, one `.3mf`), `files/detail`, `history` (5 jobs, totals), `temperatures` (60 points), `macros` (6 macros in 3 groups, one with params), `console` (20 lines incl. an error), `camera/{id}/snapshot` (a generated JPEG with the time drawn, so changes are visible), `events` (long-poll; emits `finished` 20 s after a print completes), `files/upload`.
- Honour `Authorization`; a `DELETE`-like "remove phone" switch the existing fake has should also make printer routes answer 401.
- It must be small, dependency-free and documented at the top (how to run, ports, the env switches). **It is a pretend; it must follow the contract and nothing else.**

## 12. Work packages (build in this order; each is a PR)

Every package ends with `scripts/check.sh` green, the pretend feed driven on the emulator (debug build, `scripts/run.sh`) where the UI changed, and the "As built" lines updated.

### Stage 1 (needs Jarvis J1; build E1-E6 against the pretend feed first)

**E1 — Wire model, fixtures, pretend feed.** Files: `PrinterFeedWire.kt`, `PrinterCommand.kt`, fixtures (11.1), `scripts/fake-jarvis.mjs` printer routes (11.3), tests `PrinterFeedWireTest`, `PrinterCommandTest`.
Done when: every fixture parses; all 33 commands serialise to the literal JSON; the pretend feed answers every route of the contract with the fixtures' shapes (check with `curl -k` and the pairing code).

**E2 — The client.** Files: changes to `jarvis/JarvisApi.kt` (5.1), `jarvis/JarvisWire.kt` (`Shared.printer: SharedPrinter?`), `jarvis/JarvisAccounts.kt` (`JarvisConnection` exposes `printerInfo: SharedPrinter?` refreshed in `start()`, and `val feedApi: JarvisApi`), `accounts/Accounts.kt` (`fun jarvisConnection(): JarvisConnection?`), `printer/feed/PrinterFeedApi.kt`, `PrinterWords.kt`. Tests: `PrinterFeedApiTest`, the extended `JarvisFeedTest` cases (409 body, cancellation).
Done when: all routes callable against MockWebServer; errors map to the words of 7.5; the old Jarvis routes (mail, calendar, tasks) still pass their tests.

**E3 — The session.** Files: `PrinterSession.kt`, `PrinterFeed.kt`, `di/AppContainer.kt` (one `PrinterFeed`, `Lazy` like the others; the wiring test's "service takes `httpClients.secure`" rule: `PrinterFeed` takes the `JarvisAccounts`/`Accounts`, which already use the secure client). Test: `PrinterSessionTest`.
Done when: against the pretend feed the session publishes `Live` and follows changes within a second of the fake changing; backoff and recovery verified by the unit test.

**E4 — The page skeleton and the Status card.** Files: `PrinterViewModel.kt`, `PrinterScreen.kt`, `cards/StatusCard.kt`, `PrinterFormat.kt`, `PrinterNavigation` in `EdithNavHost.kt`, the tile's **Details** button, prefs (9.2 collapsed set), the six states of 6.2. Tests: `PrinterFormatTest`, `PrinterViewModelTest` (state mapping), one Compose test for the six states' text.
Done when (emulator, pretend feed): Home → printer tile → Details → the page with a live Status card; stopping the pretend feed shows the Jarvis-away banner; `FAKE_PRINTER=off` shows `NotShared`; with no Jarvis account shows `NoJarvis`.

**E5 — The read-only cards.** Files: `TemperatureCard.kt`, `TempChart.kt`, `SensorsCard.kt`, `FansCard.kt` (values), `SlotsCard.kt`, `LimitsCard.kt` (values), `SystemCard.kt`, `MeshCard.kt`, `HistoryCard.kt`, `FilesCard.kt` (Load files, list, search, detail), `ConsoleCard.kt` (read), `ObjectsCard.kt` (view), `QueueCard.kt` (view), `CameraCard.kt`. Tests: chart ring buffer and axis maths (pure functions), file filtering/sorting, console filter, mesh colour scale mapping, camera polling cadence (fake clock).
Done when (emulator): every card draws from the pretend feed; the chart moves; the camera picture updates about once a second and stops when the card is collapsed or the page left (check the fake's request log); **Files does not request a list until Load files is pressed** (check the fake's log).

**E6 — Home and chat fall back to Jarvis; settings text.** Files: `printer/PrinterService.kt`, `home/PrinterTools.kt`, `ui/settings/HomeSettings.kt`. Test: `PrinterServiceFallbackTest`.
Done when: with the direct printer unreachable and the pretend feed up, the Home tile shows the state "via Jarvis" and "How's the print going?" answers from it; with direct reachable, no feed call is made.
**Stage 1 ships here** (the owner's checks: part 13, stage 1).

### Stage 2 (needs Jarvis J2)

**E7 — Controls and the confirm flow.** Files: `ControlsBar.kt`, `ConfirmSheet.kt`, `PrinterViewModel.send`, `PrinterSettle.kt`, lifecycle hooks (disarm on `ON_STOP`/leave), pending spinners, the `quick` gating, **Emergency stop**, status buttons (Pause, Resume, Cancel print). Tests: `PrinterSettleTest`, `PrinterViewModelTest` (7.2), a Compose test of the sheet (two different buttons, the server's words, closes at 10 s).
Done when (pretend feed): Controls on/off with countdown; Pause→spinner→Paused; Cancel shows the danger sheet and **Keep printing**; backgrounding the app turns Controls off (the fake's arm state shows it); `CONTROLS_OFF` is handled; e-stop works with Controls off and asks first.

**E8 — Temperatures, toolhead, fans, limits, sensors, objects, queue, system controls.** Files: the controls in the cards of 8.4. Tests: input validation helpers (numbers parse, empty = disabled), each control sends the literal command JSON (ViewModel test with a fake api).
Done when: every control of 8.4 stage 2 sends the right command and shows the server's refusal inline (pretend feed: jog before home → "Home the printer first").

**E9 — Macros and the console input.** Files: `MacrosCard.kt`, console input and history in `ConsoleCard.kt`. Tests: grouping/fold/filter, param form → `macro(name, params)`, history (50, newest first), 200-char limit.
Done when: a parameterised macro runs through the sheet with the right params; the console shows the exact line in the sheet.

**E10 — Chat: `printer_control` and the richer `printer_status`.** Files: `home/PrinterControlTool.kt`, registration, `home/PrinterTools.kt`. Test: `PrinterControlToolTest`.
Done when (emulator, real chat model, pretend feed): "pause the print" shows a card with the server's wording and pauses it; "cancel the print" shows a screen-only card; "turn the printer light on" works; "How's the print going?" mentions temperatures.
**Stage 2 ships here.**

### Stage 3 (needs Jarvis J3)

**E11 — Upload, start, delete, queue add.** (Each of these needs Controls on, like any command; Upload is refused by the server without them.) Files: `FilesCard.kt` (detail sheet buttons, **Upload** via `ActivityResultContracts.OpenDocument` limited to names ending `.gcode`/`.3mf`, size ≤ 64 MB checked before sending, a progress bar, cancellable), `PrinterFeedApi.upload` (streams from the `InputStream` through a `RequestBody` with progress; 300 s call timeout; **never read the whole file into memory**). Tests: the name/size gates, the request body streams (MockWebServer receives exact bytes), cancel aborts.
Done when: the pretend feed receives the exact bytes; Print/Delete/Add to queue show their sheets; a `.3mf` Print shows Jarvis's refusal words.

**E12 — Print alerts.** Files: `alerts/PrinterAlertWatcher.kt`, `AlertPrefs.kt` (a `printer` choice), `AlertService.kt` (run the watcher when the printer alert is on and Jarvis shares it), the Settings row **Print alerts** (under Settings → Home → 3D printer, default on when the printer is shared; says "Needs Jarvis and a connection to the PC"), `AlertNotifier.printer(event)` (channel "Printer", tap opens the Printer page), `lastPrinterSeq` in DataStore.
Behaviour: while the service runs, loop `events(after = lastSeq, wait = 20)`; for each new event show **one** notification (id by seq, so a repeat can't double), update `lastSeq`; failures back off 5 s → 5 min and are quiet (no notification, one log line without bodies); stop when the choice is switched off or the account is gone. **Away without Tailscale: no alerts** (the Settings row says so).
Tests: dedupe by seq, restart doesn't repeat, backoff, one notification per event, stop on switch off (fake api + fake notifier).
Done when: the pretend feed's `finished` event raises a notification that opens the page. **Stage 3 ships here.**

### Stage 4 (only when the owner asks; each needs Jarvis J4)

**E13 — Recordings.** `RecordingsCard.kt`: list from `recordings`, play with the platform `VideoView`/Media3 if already a dependency (check `libs.versions.toml`; **do not add a library without asking**), save to Downloads through the system picker. **E14 — Live camera and screen taps.** The MJPEG stream (`camera/{id}/stream`) replacing snapshot polling when available; the printer's screen view with taps (`screenTap`, the letterboxed point mapping of Jarvis's `screenPoint`, Controls on, no confirm per tap, a ring marks the tap, one tap in flight).

## 13. Acceptance

The agent checks the **emulator + pretend feed** items; the **owner** checks the real ones.

**Stage 1 (agent, emulator):**
- [ ] Printer page opens from Home; all six states of 6.2 appear for the right situations.
- [ ] Status, Camera, Temperatures (chart moves), Sensors, Fans, Slots, Limits, System, Mesh, History, Files (Load files), Console, Objects, Queue draw from the pretend feed.
- [ ] No request for files/history/console until their card is opened or Load files is pressed (the fake's log).
- [ ] The camera stops polling when collapsed, when the page is left and in the background (the fake's log).
- [ ] The Home tile and `printer_status` show "via Jarvis" only when direct is unreachable.
- [ ] `scripts/check.sh` green; `HttpClientsWiringTest` green; no network on the main thread (debug StrictMode).
**Stage 1 (owner):**
- [ ] Against the real Jarvis once J1 is merged: the page shows the real printer's state, temperatures, files list (after Load files), history, the camera picture. **At home and on mobile data over Tailscale.**
- [ ] Stopping Jarvis shows the "Edith can't reach Jarvis" banner, not a crash or a spinner forever.

**Stage 2 (agent, emulator):** every control sends the right command; every risky command shows the server's question; Controls turn off by themselves (timer, leave, background); `CONTROLS_OFF` recovers; cancel is screen-only in chat; no sheet button is labelled OK/Yes.
**Stage 2 (owner, real printer, **one control at a time, printer idle first**):** the owner decides what to try (light, fan, home, jog, a temperature, pause/resume on a real print, cancel on a throwaway print, e-stop last). Nothing is tried on the real printer by an agent.

**Stage 3 (agent):** an upload sends exact bytes and respects the cap; alerts dedupe and survive a restart. **(owner):** a real print's finish raises the phone notification at home and away.

## 14. Not verified, and known limits (fill in "As built" as you go)

- **Not verified until the owner tries:** everything against the **real Jarvis** and the **real AD5X**; Tailscale on mobile data; Windows firewall for the feed port (spec part 9 of the main spec); the camera's frame rate over Tailscale; the phone's battery with the page open for a long time.
- **Known limits by design:** the Printer page needs the PC (Jarvis) on; the printer's address, key, presets and hidden macros are edited in Jarvis only; no printer discovery; no second printer; no push (alerts are a long-poll, so they stop when Android kills the service or the phone can't reach the PC); `.3mf` prints can't be started from the phone (the printer's own rule, shown in words); recordings, live video and screen taps are stage 4.
- **Questions for the owner: all answered 2026-11-17 (the defaults were confirmed; do not ask again):** (1) 64 MB upload cap: yes; (2) emergency stop from a look-only phone: yes, with a confirm; (3) phone alerts when Jarvis's desktop alerts are off: yes; (4) a Quick Settings tile or notification for a running print: not now; (5) stage 4: only if the owner asks.

## 15. As built

_(Each work package adds its lines here: date, what was built, what was verified where, what differs from the text above.)_
