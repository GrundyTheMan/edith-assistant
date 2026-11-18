# Spec for Jarvis: the printer in the "Edith feed" (the 3D printer, from the phone, at home and away)

**Audience: the Jarvis agent** (the AI that works in `D:\Dev Projects\jarvis`). This extends the work-data feed you already built
(`jarvis-feed-spec.md`, parts 1-14; code in `src/main/feed/`). Read that document first: **every convention there applies here
unchanged** (HTTPS and pinning, private-address check, bearer token, error shape, millisecond times, rate limiting, how a phone is
paired). This document only adds the printer. Edith's side is design chunk C39 (`chunks/C39-printer-parity.md`); read its part 2 to see
the other side of this contract.

Write this as if you will never be asked a question: if something below is unclear, **do not guess**; part 13 says what to ask the owner.

## 1. Why this exists, and the one design rule

The owner wants Edith (the Android app) to have **the same 3D-printer features as Jarvis's Printer page**: status, temperatures with a
chart, jog and home, macros, files, history, console, objects, queue, camera, alerts and so on, and to have them **away from home**
too. Edith can already talk to the printer directly on the home Wi-Fi (Moonraker on port 7125; C34), but that only works at home and
would mean re-writing Jarvis's printer logic a second time in Kotlin.

**The rule: Jarvis stays the only implementation of the printer's logic.** Jarvis already parses Klipper's objects into a `PrinterState`,
already has the command layer (`checkCommand`, `buildGcode`, `describeCommand`, the safety model of `docs/plans/printer.md` parts 5-6),
already knows the AD5X's quirks (ZMOD macros, the file-list freeze, the two-phase cancel, `.3mf` files). The feed **serves that state and
runs those commands** for a paired phone, over the same HTTPS channel as the work mail. Edith is a thin client: she draws the state and sends
**typed commands**; she never builds G-code and never talks to Moonraker through this feed.

Consequences you must keep true:
- **Edith never sends G-code, a URL path or a Moonraker endpoint.** She sends a `PrinterCommand` (the type in
  `src/shared/printerCommands.ts`, as JSON). Jarvis runs it through the **same** `checkCommand` / `buildGcode` the Printer page uses.
- **Every safety rule that exists for the page exists for the phone**, enforced in the main process, never trusted from the phone
  (part 8). Where the page has "Controls on" (arming), the phone has its own, per phone.
- **No new way to reach the printer is opened.** The feed is the same server, the same token, the same private-address rule.
- The printer is the owner's, often **mid-print**. Part 0 says what you may do to the real one while building this (almost nothing).

## 0. Rules for you (read before writing any code)

1. **The real printer** (`192.168.4.86`, AD5X, ZMOD): `docs/plans/printer.md` section 0.2 applies word for word. You may send read-only `GET`s
   (part 3.1 of that plan). **Never** send a `POST`, a `DELETE`, any G-code, a macro, pause/resume/cancel/start, an upload, a delete or a
   screen tap to the real printer without the owner's explicit approval in chat **for that exact action**. Test everything against
   `e2e/fakeMoonraker.ts`. Never point a test at the real printer.
2. **Do not change what the Printer page or the existing feed does.** Add; don't rewrite. All current tests keep passing.
3. **Do not duplicate logic.** The routes below are thin wrappers over `src/main/printer/service.ts` and the shared functions
   (`checkCommand`, `buildGcode`, `describeCommand`, `commandSettled`, `printAlert`, `groupMacros`). If something you need is missing in
   `service.ts` (part 3 lists three additions), add it there and let the page use it too.
4. **Nothing from the phone reaches G-code unchecked.** The phone sends a `PrinterCommand` object; parse it with a strict validator
   (part 6.4) and pass it to `checkCommand`. Unknown `kind`, a wrong type, a non-finite number, an extra field that matters: refuse
   with `BAD_REQUEST`.
5. **Logging:** every accepted command is logged exactly as the page's is (`sent <kind>: ...`, `docs/plans/printer.md` 5.5) with
   `(phone "<device name>")` after it. Every refusal is logged with its reason. **Never log** the bearer token, a pairing code,
   or the printer's API key.
6. **Run the checks** `npm run typecheck`, `npm test`, `npm run build` and the printer e2e tests before you finish each step. Add the new
   tests listed in part 10.
7. **One PR per step** (part 11), branch `feed-5-…`, merge commit, as before. Update Jarvis's `README.md`, `AGENTS.md` and
   `docs/plans/printer.md` (progress log) in the same PR.
8. **If something here contradicts what the printer does, stop and tell the owner**; don't pick a side.

## 2. What the owner sees in Jarvis (Settings → "Share work data with Edith" section)

Add a sub-section **"3D printer"** to the existing Edith feed settings:

- A switch **"Let paired phones see the printer"** (`sharePrinter`, default **off**). Disabled, with the hint "Set up the printer first
  (Settings → 3D printer)", when no printer is configured.
- A switch **"Let paired phones control the printer"** (`printerControl`, default **off**), shown only when the first is on. Hint:
  "A phone still has to turn on Controls, and every risky command asks for a confirmation on the phone. You can turn this off at any time."
- A switch **"Send print alerts to paired phones"** (`printerAlerts`, default **on** when sharing is on; separate from the desktop alert
  setting `PrinterConfig.alerts`, which only controls Jarvis's own notifications; phone alerts do not need that to be on, but they do need
  the printer service to be watching, see part 9.7).
- Under **Recent requests** (the list you already built), printer requests appear with their route like all others.

All three are stored in `userData/feed/feed.json` next to the existing share ticks (part 4).

## 3. Files to add or change (in the Jarvis repo)

New:
- `src/main/feed/printerRoutes.ts`: the route table for part 6 (a function `printerRoutes(deps)` returning entries in the same shape as
  `routes.ts`; `routes.ts` spreads it into its table, only when `sharePrinter` is on).
- `src/main/feed/printerRoutes.test.ts`: unit tests (part 10).
- `src/main/feed/printerWire.ts`: the strict validator for an incoming `PrinterCommand` (`parseCommand(body): PrinterCommand | WireError`) and the
  helpers that turn state into the JSON in part 7. No Electron imports (so it is testable).
- `src/main/feed/printerArm.ts`: **per-phone arming** (part 8.1). Pure logic with an injected clock.
- `src/main/feed/printerEvents.ts`: the alert event log for phones (part 9.7).
- `e2e/feedPrinter.spec.ts` (Playwright, same style as the existing feed e2e): the feed against `fakeMoonraker`.

Changed:
- `src/main/printer/service.ts`: three additions only (part 3.2).
- `src/main/feed/routes.ts`: include `printerRoutes`; `/v1/shared` gains a `printer` entry (part 6.1).
- `src/main/feed/store.ts` and `src/shared/feed.ts`: the new settings and wire types.
- the Settings page component for the Edith section: the new switches.
- `e2e/fakeMoonraker.ts`: only if a route the feed needs isn't faked yet (it should already be: the Printer page uses the same ones).

### 3.2 The three additions to `src/main/printer/service.ts`

1. **`commandFor(deviceId, cmd, via, opts)`**: like `command(cmd, via)` but the arm check uses **that phone's** arm state
   (`printerArm`), not the page's `armedUntil`, and the audit line carries the phone's name. Implementation hint: refactor the body of
   `command()` into one internal function that takes `{ armed: boolean, who: string }`; `command()` calls it with the page's values and
   `commandFor()` with the phone's. **Do not duplicate the body.** The page's behaviour must not change.
2. **`uploadBytes(name, bytes)`**: the part of `upload()` after the file is chosen (size cap, extension check, `POST /server/files/upload`,
   logging), taking bytes and a name instead of opening a dialog. `upload()` calls it too.
3. **`snapshotFor(cameraId)`**: returns the JPEG **bytes** (not a data URL) with a 500 ms cache per camera so several phones don't hammer the
   printer. (`snapshot()` returns a data URL for the page; keep it, and have both use one fetch.)

## 4. Settings stored (`userData/feed/feed.json`, extending part 4 of the main spec)

```json
{
  "sharePrinter": false,
  "printerControl": false,
  "printerAlerts": true
}
```
Missing keys read as the defaults above. Switching `sharePrinter` off **at once** makes every printer route answer `PRINTER_NOT_SHARED`,
ends every long-poll with that error, and disarms every phone. Switching `printerControl` off makes every command except `estop`
(part 8.5) answer `CONTROL_OFF` and disarms every phone.

## 5. Conventions and the new error codes

Same as the main spec (part 5 there): `Authorization: Bearer`, private-address callers only, JSON bodies up to 64 KB (except the upload,
part 6.9), `{ "error": { "code", "message" } }` with a status, all times milliseconds since 1970, rate limit 120 requests a minute per
phone **for everything except the long-poll routes** (part 9.2), which are limited by the number of simultaneous polls instead.

New codes (the `message` is a sentence the phone may show to the owner; write them in plain words):

| Status | `code` | When |
|---|---|---|
| 403 | `PRINTER_NOT_SHARED` | `sharePrinter` is off, or no printer is configured |
| 403 | `CONTROL_OFF` | `printerControl` is off |
| 409 | `CONTROLS_OFF` | the phone hasn't turned Controls on (or they timed out) and the command needs them |
| 409 | `CONFIRM_REQUIRED` | the command's risk is `confirm` or `danger` and the request had `"confirmed": false` (body has the check, part 6.7) |
| 422 | `REFUSED` | `checkCommand` said no; `message` is its reason ("Not while printing", "Home the printer first", ...) |
| 400 | `BAD_REQUEST` | the body isn't a valid command or request |
| 502 | `PRINTER_UNREACHABLE` | the printer doesn't answer Jarvis (`Connection` is `unreachable` or `none`) for a route that needs it |
| 504 | `TIMEOUT` | the printer didn't answer in time |
| 413 | `TOO_LARGE` | upload over the cap |
| 404 | `NOT_FOUND` | a file, camera, recording or job that doesn't exist |
| 429 | `TOO_MANY` | rate limit, or more simultaneous long-polls than allowed |

## 6. Endpoints

All under `/v1/printer/`. All need the bearer token and `sharePrinter`. Examples are literal; field names are final.

### 6.1 `GET /v1/shared` (existing; one addition)

When `sharePrinter` is on, the answer gains:

```json
"printer": {
  "name": "AD5X",
  "control": false,
  "alerts": true,
  "armMinutes": 2,
  "presets": [ { "name": "PLA", "extruder": 220, "bed": 60 }, { "name": "PETG", "extruder": 240, "bed": 80 } ],
  "cameras": [ { "id": "video", "name": "Camera" }, { "id": "screen", "name": "Printer screen" } ]
}
```
`armMinutes` and `presets` are the printer config's own (`presetsOf(config)`, so the defaults appear when the owner set none); the phone only **reads** them (presets are edited in Jarvis). `name` is the printer's hostname (`PrinterState.hostname`, or "3D printer"). `control` is `printerControl`. The phone uses this to decide
whether to show the Printer page at all. Absent when not shared. Cameras come from the printer service's last known list (may be empty).

### 6.2 `GET /v1/printer/state` (the long-poll)

Query: `after` (optional integer revision), `wait` (optional seconds, 0-25, default 0).

- Jarvis keeps a **revision number** that goes up by one every time the `PrinterState` it holds changes (compare with a cheap structural
  check, not on every websocket tick that changes nothing). It also goes up when this phone's arm state changes.
- If `after` is absent, or `after` is lower than the current revision: answer **at once** with the current state.
- If `after` equals the current revision and `wait` > 0: hold the request open until the revision changes or `wait` seconds pass, then
  answer (with the unchanged revision after a timeout: that is the signal "nothing new"; it is **not** an error).
- Hold at most **3 simultaneous long-polls per phone**; more answer `429 TOO_MANY`.
- While any phone is polling `state`, the printer service must **watch** (keep its websocket open) the way it does when its own page is
  open; when none is (and no alert source needs it, part 9.7) it may let go. Use the existing `watch`/`keepForAlerts` mechanism; don't add a second connection.

Answer (`200`):

```json
{
  "rev": 1287,
  "state": { /* PrinterState exactly as in src/shared/printer.ts, part 7.1 */ },
  "arm": { "armed": false },
  "quick": { "pause": { "allowed": false, "reason": "Nothing is printing" }, "home:xyz": { "allowed": true } },
  "serverTime": 1794948226679
}
```
- `arm` is **this phone's** `ArmState` (`{ armed, until? }`), never the page's.
- `quick` (part 7.3) is the result of `checkCommand` for the commands that take no parameters, computed with this phone's arm state, so the
  phone can enable or disable its buttons without asking each time.
- `serverTime` lets the phone correct for a wrong clock when it shows "time left" or `arm.until`.
- Strip `PrinterState.cameras[].streamUrl` and `snapshotUrl` (absolute URLs on the printer's LAN, useless and revealing to a phone). Keep `id`, `name`, `aspect`, `rotation`, `flipH`, `flipV`.

### 6.3 Read-only routes (each needs only `sharePrinter`)

| Route | Answer |
|---|---|
| `GET /v1/printer/temperatures` | `{ "series": [ TempSeries … ] }`: from `service.temperatures()`. The phone calls it when the chart opens and appends from `state` after that. |
| `GET /v1/printer/macros` | `{ "macros": [ MacroInfo … ] }`: **already filtered** exactly as the Macros card (`visibleMacros`: deny list, hidden list, the "has its own button" list) with `risk` filled in, **and grouped**: add `"group": "filament"` (one of `filament`, `printing`, `bed`, `camera`, `system`, `other`) from `groupMacros()`. |
| `GET /v1/printer/console?count=200` | `{ "lines": [ ConsoleLine … ] }`, oldest first, from `service.consoleLines()`. `count` 1-500. Lines matching `/^\/\/ value:/` are **not** filtered here (the phone filters). |
| `GET /v1/printer/files?fresh=0\|1` | `{ "files": [ PrinterFile … ], "cachedAt": 1794948226679 }`. **`fresh=1` forces a re-list**, which freezes the AD5X for 6-11 s (`docs/plans/printer.md` 7.9): only do it when the phone asks. `fresh=0` returns the cache if there is one, else **does the listing** (the phone only calls this after the owner pressed "Load files"). Cached 60 s as the page does; cleared on `notify_filelist_changed`. |
| `GET /v1/printer/files/detail?path=<moonraker path>` | `PrinterFileDetail` (thumbnail as a `data:` URL, as the page gets it). `NOT_FOUND` when the path isn't in the cached list. A 404 from Moonraker (no metadata) is **not** an error: return `{ "path": "…" }` with nothing else. |
| `GET /v1/printer/history?start=0` | `{ "jobs": [ HistoryJob … ], "totals": HistoryTotals }`, 50 jobs per page, from `service.history(start)`. `start` ≥ 0 integer. |
| `GET /v1/printer/camera/{id}/snapshot` | `image/jpeg` bytes from `snapshotFor(id)` (500 ms cache). `id` must be in the camera list or `screen`; else `NOT_FOUND`. `Cache-Control: no-store`. A camera that is off or not answering: `502 PRINTER_UNREACHABLE` with the message "The camera isn't answering." |
| `GET /v1/printer/recordings` | stage 4 (part 6.11) |

### 6.4 `POST /v1/printer/controls` (this phone's "Controls" switch)

Body `{ "on": true }` or `{ "on": false }`. Needs `printerControl` on (else `CONTROL_OFF`). Answers `{ "arm": { "armed": true, "until": 1794948346679 } }`.
Rules are part 8.1. Turning off never fails. Turning on when the printer is `unreachable`/`none` or Klipper isn't `ready` answers
`PRINTER_UNREACHABLE` with the reason. **No page state is touched.**

### 6.5 `POST /v1/printer/command`

Body:
```json
{ "command": { "kind": "jog", "axis": "x", "distance": 10 }, "via": "phone", "confirmed": false, "dryRun": false }
```
- `command`: a `PrinterCommand` (parsed by `parseCommand`, part 6.8).
- `via`: `"phone"` (default; the owner pressed a button, Controls must be on) or `"chat"` (the owner approved an Edith chat card; `CHAT_KINDS`
  only, no arm needed, exactly as `docs/plans/printer.md` 5.4: refuse anything else with `REFUSED` "Edith's chat can't do that").
- `confirmed`: whether the owner saw and accepted the question the phone got from `check` (below). Default `false`.
- `dryRun`: when `true`, **nothing is sent**; the answer is the check (part 6.6).

Order of work (every step is mandatory, in this order):
1. `printerControl` off → `CONTROL_OFF` (except `estop`, part 8.5).
2. Parse and validate the body → `BAD_REQUEST`.
3. Get the printer's state; if older than 2 s refresh as `service.command` does (part 6.3 of the plan). Unreachable → `PRINTER_UNREACHABLE`.
4. Arm: `via: "phone"` and not `estop` and phone not armed → `CONTROLS_OFF` ("Turn on Controls first").
5. `checkCommand(cmd, ctx)` with `armed` = this phone's arm. Not allowed → `REFUSED` with its `reason`.
6. `dryRun` → answer the check now (200).
7. Risk `confirm` or `danger` and `confirmed !== true` → `409 CONFIRM_REQUIRED` with the check in the body (the phone shows `question` and `confirm` and, if the owner agrees, sends again with `confirmed: true`).
8. Log, extend this phone's arm timer, send (through `commandFor`), as the page does (never await a G-code script's completion; part 6.3 step 5 of the plan).
9. Answer `200` `{ "sent": "G28", "risk": "confirm" }`.

A command whose settle takes minutes (G28, M190) answers `200` as soon as it is sent, like the page. The phone watches the state (the long-poll) for the result; a G-code error the printer raises later shows up as a console line of kind `error` and as `PrinterState.alert`.

### 6.6 The check answer (dry run, and the body of `CONFIRM_REQUIRED`)

```json
{ "allowed": true, "risk": "confirm", "question": "Home all axes?", "confirm": "Home" }
```
`question` and `confirm` come from `describeCommand(cmd, state)`. When `allowed` is false: `{ "allowed": false, "risk": "none", "reason": "The nozzle is at 25 °C: heat it to at least 170 °C to extrude" }` (a dry run answers `200` with this, **not** a `REFUSED` error; only a real send answers `422 REFUSED`).

### 6.7 `POST /v1/printer/files/upload` (stage 3)

`POST /v1/printer/files/upload?name=<file name>` with `Content-Type: application/octet-stream` and the file as the body. Needs `printerControl` and the phone **armed**
(it is a command: `via: "phone"`; log `sent upload (phone "<name>"): POST /server/files/upload <name> (<n> bytes)`).
Cap **64 MB** (`TOO_LARGE`); the name must match `/^[A-Za-z0-9 _.()+\-]{1,100}\.(gcode|3mf)$/` (else `BAD_REQUEST`). Stream to `uploadBytes`; don't hold more
than the cap in memory. Answers `200 { "path": "name.gcode" }`. The existing 60 s request timeout does not apply: allow 5 minutes for this route only.
Deleting a file, adding to the queue and starting a print are **commands** (`deleteFile`, `queueAdd`, `start`) through 6.5, not separate routes.

### 6.8 The validator `parseCommand` (`printerWire.ts`)

Accept exactly the `PrinterCommand` union in `src/shared/printerCommands.ts` (all 33 kinds, including `screenTap` and the four queue kinds). For each kind:
required fields present with the right JS type, strings trimmed and at most 200 characters (the console `script` at most 200 and one line), numbers
`Number.isFinite`, enums exactly one of the allowed strings, `params` of `macro` a plain object of string values with at most 20 keys. Unknown
extra fields are ignored. A failure is `BAD_REQUEST` with a sentence ("jog needs an axis x, y or z"). **This is only syntax; the safety rules
are `checkCommand`'s.** Add a test that feeds every kind in `COMMAND_KINDS` a valid and an invalid body (the list is the loop; a new kind
without a validator must fail the test).

### 6.9 Camera frames as a stream (stage 4, **required for parity** since 2026-11-18)

`GET /v1/printer/camera/{id}/stream` as `multipart/x-mixed-replace` MJPEG relayed from the printer's stream through `camera.ts`, at most 2 simultaneous
streams in total, closed when the phone disconnects, and closed after 10 minutes (the phone reopens). Built in J4 (15.4); until then the phone
works with `snapshot` polling (about one frame a second).

### 6.10 `GET /v1/printer/events` (print alerts for the phone)

Query `after` (integer sequence number, default 0), `wait` (seconds 0-25).
```json
{ "seq": 42, "events": [ { "seq": 41, "time": 1794948226679, "kind": "finished", "title": "Print finished", "text": "Chimney and Platforms finished after 1h 14m." } ] }
```
`kind` is one of `finished`, `failed`, `paused`, `klipper-stopped`, `error` (the kinds `shared/printerAlerts.ts` produces; map its output, do not
re-derive the rules). `seq` is a counter that survives only until Jarvis quits (start from the current time in seconds so a restart never
repeats a number a phone already saw). Keep the last 50 events in memory. A phone that asks with `after` ≥ `seq` waits; with `after` older
than the oldest kept event gets everything kept. Needs `printerAlerts` on (else an empty list that waits normally). Part 9.7 says how it is fed.

### 6.11 Recordings (stage 4)

Built in J4 (required since 2026-11-18). `GET /v1/printer/recordings` → `{ "items": [ { "name", "size", "modified", "duration"? } ] }` from the existing recordings
service (SSH, `printerRecordings.ts`); `GET /v1/printer/recordings/{name}` streams the video with `Range` support (`206`), `Content-Type: video/mp4`.
`name` must come from the list (never a path from the phone; reject `/`, `\`, `..`). The SSH key and its set-up stay on the PC; the phone never sees them.

## 7. Wire types (the single source is Jarvis's own TypeScript)

### 7.1 State and everything it contains

The JSON is **`PrinterState` as defined in `src/shared/printer.ts` today**, unchanged, serialised with `JSON.stringify`: `connection`, `message?`,
`lastSeen?`, `hostname?`, `print{…}`, `heaters[]`, `sensors[]`, `fans[]`, `light`, `toolhead{…}`, `extruder{…}`, `retraction?`, `filamentSensors[]`,
`objects[]`, `slots[]`, `slotsLive`, `alert?`, `host?`, `versions?`, `cameras[]`, `mesh?`, `queue?`. The same for `PrinterFile`, `PrinterFileDetail`,
`HistoryJob`, `HistoryTotals`, `ConsoleLine`, `TempSeries`, `MacroInfo`, `MacroParam`, `ArmState`, `BedMesh`, `JobQueue`, `QueueJob`, `Heater`, `Sensor`, `Fan`,
`PrintObject`, `FilamentSlot`, `FilamentSensor`, `PrinterCamera` (minus the two URLs). **Do not rename or reshape any field.** Edith has a Kotlin copy of exactly these;
a rename here breaks her silently. If you must change `src/shared/printer.ts` for the page, **additions are fine** (the phone ignores unknown fields); renames,
removals and meaning changes need a note in part 14 and the owner's OK.

### 7.2 Optional fields

`undefined` fields are **omitted**, never sent as `null` (the page's JSON already does this). Where the TypeScript type says `T | null` (`timeLeft`, `light`), send `null`.

### 7.3 The `quick` map

Keys and the command each stands for (compute each with `checkCommand` and the phone's arm state; value `{ "allowed": boolean, "reason"?: string }`):

| Key | Command |
|---|---|
| `pause`, `resume`, `cancel`, `estop` | `{kind:…}` |
| `home:xyz`, `home:xy`, `home:z` | `{kind:'home', axes:…}` |
| `motorsOff`, `heatersOff`, `restart`, `firmwareRestart` | `{kind:…}` |
| `queueStart`, `queuePause` | `{kind:…}` |
| `camera:on`, `camera:off` | `{kind:'camera', on:…}` |
| `air:internal`, `air:external`, `air:stop` | `{kind:'airCirculation', mode:…}` |

For every other command (a jog, a temperature, a macro, a file) the phone asks with `dryRun` when it needs to know. `quick` is a convenience; the real
check always runs again on send.

## 8. Safety (the part that must not be "simplified" away)

### 8.1 Per-phone arming (`printerArm.ts`)

- State per phone (device id from the token): `armedUntil` (ms) or none. **Not** shared with the page's arming or with other phones.
- `arm(deviceId, now)` sets `armedUntil = now + minutes*60_000` where `minutes` is the printer config's `armMinutes` (default 2; allowed 1, 2, 5, 10).
- Each **accepted** command from that phone (and an accepted `controls: on` repeat) restarts the timer.
- Armed state ends when: the time passes; the phone sends `controls: false`; the phone is **unpaired** (the existing "remove a phone"); `printerControl` or
  `sharePrinter` is turned off; the printer's `connection` leaves `ready`; the printer address changes (`setConfig`). Jarvis quitting clears all of it (in memory only).
- The phone **also** disarms itself when the Printer screen is left or the app goes to the background, but **never rely on it**: the timer is the guarantee.
- Pure functions with an injected `now`, unit-tested (part 10).

### 8.2 Layer 2 is `checkCommand`, with `armed` = the phone's, `state` = fresh, `macros` = the visible list, `files` = the cached list (for `start`)

Exactly as the page; **do not pass `armed: true` from the feed**. The `ctx.config` is the printer's own config.

### 8.3 Layer 3 is the confirmation, and it is enforced by you

The page's confirm is a UI step; for the phone **you** enforce it: risk `confirm`/`danger` without `confirmed: true` is `409 CONFIRM_REQUIRED` (6.5 step 7).
`risk: 'none'` never asks. `estop` always needs `confirmed: true` (it is `danger`).

### 8.4 The cancel guard (ZMOD's two-phase cancel)

`CANCEL_GUARD_MS` (60 s) applies across **all** callers: a cancel sent from the page or any phone blocks another cancel for 60 s (`lastCancelAt` is the
service's, not the arm's). Never retry a cancel on the printer's behalf.

### 8.5 Emergency stop

`estop` needs no arm (as the page, decision D9) and needs **`sharePrinter`** (so a phone can only stop a printer it can see) but **not** `printerControl`.
(The owner decided this on 2026-11-17: part 13, question 2.) It still needs `confirmed: true`. Log it at `warn`.

### 8.6 Chat path

`via: "chat"` permits only `CHAT_KINDS` (`pause`, `resume`, `cancel`, `light`), needs `printerControl`, needs **no** arm, still runs `checkCommand` and still needs
`confirmed: true` (Edith shows her own approval card first). An Edith chat call must **never** be able to reach another kind: test it.

### 8.7 Rates and sizes

Commands: at most 10 a second per phone (a held jog button must not flood the printer; refuse extra with `TOO_MANY`). Body 64 KB. `console` input (`gcode` kind) follows
`docs/plans/printer.md` 7.8 exactly, through `checkCommand`.

## 9. Behaviour details that are easy to get wrong

1. **Never block the main process.** Every route is `async`; a long-poll is a held promise with a timer, not a loop.
2. **Long-polls and the rate limiter:** the 120/min limit does not count a held request while it waits, only when it is answered, and an answer to an unchanged poll
   (timeout) counts as **0**. Otherwise a phone polling once every 25 s is fine but one polling quickly after changes would be throttled.
3. **Close handling:** when the phone drops the connection (`req.on('close')`), cancel the timer and remove the waiter. Log the "phone closed the connection" line the
   timing log (part 15 of the main spec) already produces. A held request must never keep the service watching forever.
4. **Opening the page's state is not listing files.** `state` and `quick` must never trigger a file listing, history, or any request that freezes the printer.
5. **Debounce:** coalesce revisions: if the state changes 4 times a second, a phone that is waiting gets one answer per 250 ms at most.
6. **Size:** a `state` answer is about 10-20 KB. **Send the whole state every time** (simplicity; the phone replaces its copy). If the owner later finds it too heavy, that is a new decision.
7. **Alerts without a page:** the alert rules (`shared/printerAlerts.ts`, `printerAlerts.ts` in main) already watch the printer service. Add a second consumer of the same
   `onState` snapshots that appends to `printerEvents` when `printerAlerts` is on **and** at least one phone is paired; this keeps the printer service watching (`keepForAlerts`)
   even with no page and no poll. It must not send a desktop notification a second time and must not change the desktop alerts.
8. **Time zones:** none of these routes use local time. `serverTime` is UTC ms.
9. **Unpaired / removed phones:** their long-polls end with `401 UNAUTHORIZED` immediately.
10. **The printer being off** is normal: `state` answers `200` with `connection: "unreachable"` and the last values Jarvis has; **only commands and camera/file routes answer `PRINTER_UNREACHABLE`**.
11. **Do not expose** the printer's address, API key, SSH key, `/screen/*` URLs or any URL on the printer's LAN anywhere in an answer.
12. **Screen taps (stage 4):** the `screenTap` command needs Controls (the phone's arm) like the page and is **not** in `CHAT_KINDS`. The printer's screen is camera id `screen`.

## 10. Tests (vitest, plus one Playwright spec); all must pass

Unit (`printerRoutes.test.ts`, `printerWire.test.ts`, `printerArm.test.ts`, `printerEvents.test.ts`):
- `parseCommand`: every kind in `COMMAND_KINDS` valid and invalid; extra fields ignored; strings over 200 chars refused; `NaN`/`Infinity` refused; `params` rules.
- Arm: on, expiry by the clock, each accepted command extends, off, unpair, `printerControl` off, connection loss, two phones independent, the page's arming unaffected.
- Routes with a fake printer service: every route answers `PRINTER_NOT_SHARED` when off; `CONTROL_OFF`; `CONTROLS_OFF`; `CONFIRM_REQUIRED` with the question; `REFUSED` with the reason;
  `dryRun` sends nothing; `via: "chat"` accepts only the four kinds; `estop` works unarmed with `confirmed`, refused without it; two cancels in 60 s refused.
- `state`: immediate answer without `after`; held until a change; timeout answers the same `rev`; at most 3 per phone; closed connection removes the waiter; `quick` values;
  `arm` is the phone's; URLs stripped from cameras.
- **Fixtures:** Edith keeps sample answers in her repo (`app/src/test/resources/printer-feed/*.json`, written by her side from your TypeScript types). Add a test that builds each of the same situations with your real functions (`printerSample.ts` and friends) and checks that **every field the fixture has exists in your output with the same type**; extra fields in yours are fine. A mismatch means one side is wrong: tell the owner.
- No log line from any printer route contains the bearer token, the printer's API key, or a file path from the phone beyond the file's name.
- `files` never lists on `state`; `fresh=1` lists; the 60 s cache.
- Upload: cap, name rule, `printerControl` and arm needed.
- Events: a printing → complete transition produces `finished` once; a phone asking with an old `after` gets everything; the sequence never repeats after a simulated restart.

Playwright e2e (`feedPrinter.spec.ts`, against `fakeMoonraker` and a real feed server on a throwaway profile): pair a fake phone, read `shared`, `state`, a long-poll change, arm,
a `confirm` round trip (409 then 200) for `home`, a refused jog while not homed, `estop`, switching `sharePrinter` off mid-poll.

## 11. Order of work and PRs

1. **J1 (read-only):** settings and switches (`sharePrinter` only), `printerWire.ts`, `printerEvents` skeleton not yet, `/v1/shared` entry, `state` (long-poll, `quick` without arm = all "Turn on Controls first" or per
   check), `temperatures`, `macros`, `console`, `files`, `files/detail`, `history`, `camera/{id}/snapshot`, the service addition `snapshotFor`. Tests, e2e for the read routes.
2. **J2 (controls):** `printerControl` switch, `printerArm.ts`, `controls`, `command` with the full order of 6.5 and `dryRun`, the `commandFor` refactor, rate limit, chat path. Tests, e2e.
3. **J3 (alerts and uploads):** `printerAlerts` switch, `printerEvents.ts`, `events` route, `uploadBytes`, `files/upload`. Tests.
4. **J4 (required since 2026-11-18, parity, after J3):** recordings, camera stream, screen taps. Part 15.4 says what else each step now carries.

Edith builds against J1 first; she does not need J2 to ship her read-only stage. Tell the owner when each step is merged so Edith's side can start its matching stage.

## 12. What not to build

- No Moonraker pass-through, no generic "send G-code" route (the console is the `gcode` command with its rules).
- No second printer, no printer discovery, no edits to the printer's address, API key, SSH key or arm time from the phone (they stay in Jarvis's Settings). Presets and the hidden-macro list **are** editable from the phone (15.5, `POST /v1/printer/settings`).
- No push to the phone (the phone asks; part 6.10 is a long-poll).
- No change to the desktop alerts, the page's arming, the work-data routes or the pairing.

## 13. Questions for the owner (1-4 answered 2026-11-17, 5-8 answered 2026-11-18: these are the owner's decisions; do not ask again)

1. **Answered: yes.** 64 MB is the cap for uploads from the phone (the AD5X's `.3mf` files are a few MB).
2. **Answered: yes.** The **emergency stop** works from a phone that is only allowed to see the printer, with a confirm.
3. **Answered: yes.** A **phone alert** fires even when Jarvis's own desktop alerts are off (they are separate switches).
4. **Answered: yes.** **Starting a print from the phone** has the same restrictions as the page (`.3mf` refused until U1 is settled, printer must be in "Local Only" mode, `docs/plans/printer.md` 2.7 and part 14).
5. **Answered 2026-11-18: parity.** "If it can do it in Jarvis, Edith needs to be able to do it, if at all possible." Nothing the Printer page can do is withheld from the phone by default (including starting and heating from away from home); part 15.5 lists how the phone gets each thing and the only deliberate exceptions (address, API key, SSH key, arm time). The away-from-home restriction of an earlier draft is an **opt-in** switch, default off (15.3 O1).
6. **Answered 2026-11-18:** alerts when the app is asleep: catch-up on opening always, a foreground poll as an option, **no** outbound push service (15.3 O2).
7. **Answered 2026-11-18:** the file-listing rule of 15.3 O3 (refused while printing or paused; at most once a minute across phones).
8. **Answered 2026-11-18 ("use your best judgement"):** adopt all of 15.2 (1-5 required, 6 recommended).

## 14. As built on the Jarvis side

_(The Jarvis agent fills this in: branches, what differs from the text above, the numbers it measured, anything Edith's side must know. Keep the same style as part 14 of the main spec.)_

## 15. Review from the Jarvis side (2026-11-18): what the feed server cannot do yet, and gaps in this design

**Status of this part.** Written after reading this spec against the Jarvis code on `main` (the feed in `src/main/feed/`, the printer in
`src/main/printer/service.ts`). Nothing here has been built. Part 15.1 is a list of **corrections to what parts 3 and 9 assume** about the
feed server; they are mechanical and belong in step J1 (or J2 where marked). Part 15.2 is **contract additions**; they change wire shapes,
and the owner has decided to adopt them (15.3 O4); Edith's side carries the new fields (the contract says field names are final unless both sides change this document). Part
15.3 records the **owner's decisions** (answered 2026-11-18, also in part 13), and 15.5 is the **parity check** against the Printer page. Sizes in 15.2 are estimates; nothing was measured.

### 15.1 What the feed server does not do today (add to J1's file list)

Each item is something parts 3, 5, 6 or 9 assume. Files are in `src/main/feed/`.

1. **A route does not know which phone is asking.** `AuthedRoutes` (`handler.ts`) receives `{ method, path, query, body }` and the settings; the device the token
   matched is found in the handler and dropped. Per-phone arming (8.1), per-phone `state.arm` and per-phone limits are impossible until the device (`id`, `name`)
   is passed to the routes. Change `AuthedRoutes` to take it; the work-data routes ignore it.
2. **Held requests do not fit the server's limits.** The handler allows **4 requests at once, across all phones** (`MAX_BUSY`) and answers `503 BUSY` beyond that, and it
   gives every request **60 s** (`504 TIMEOUT`). Three held `state` polls from one phone plus an `events` poll fill the cap and make mail answer `BUSY`. A held request
   must **not count** toward `MAX_BUSY` while it waits (count it only for the instant it is answered), has its own cap (3 per phone, part 6.2) and its own deadline
   (`wait` + 5 s). The same held requests would also (a) write a "still running after 10 s" line to the log every time (part 15 of the main spec) and (b) fill the
   20-entry **Recent requests** list in Settings. Held polls must be left out of the "still running" lines, and a poll that times out with nothing new must not be
   listed or logged as a request at all (log only an answered change, as one line).
3. **Replies are JSON only and request bodies are read whole.** `listen.ts` always writes `JSON.stringify(body)` with a JSON content type, and reads the request into memory
   with a **64 KB** cap, flagging anything bigger `tooLarge`. The camera snapshot (`image/jpeg`, 6.3), the optional MJPEG stream (6.9), recording ranges (6.11) and the
   **64 MB upload** (6.7) need: a reply that carries bytes and a content type (and `206` for ranges); and, for the upload route only, a request body that is
   **streamed** to `uploadBytes` and counted against the cap as it arrives (stop reading and answer `413` the moment it passes 64 MB), with the 5-minute allowance. Every other route keeps the 64 KB rule.
4. **The "120 requests a minute per phone" limit does not exist.** The feed's only limiter (`FailureLimiter`) counts **failed** sign-ins and pairings. Add a per-phone request
   limiter as part 5 describes (held polls excluded as 9.2 says), and a separate one for commands (10 a second, 8.7). Without it 8.7 is unenforced.
5. **`service.watch()` is keyed on a window.** In `printer/service.ts`, `watch(sender: WebContents, on)` keeps a `Map<WebContents, number>` and releases a watcher when its
   window is destroyed. A phone has no `WebContents`. Part 3.2's "three additions" and 6.2's "use the existing `watch`/`keepForAlerts` mechanism" therefore miss a **fourth
   addition: a feed-side watch** (for example `watchFeed(deviceId, on)`) that holds the connection open the same way (so the idle close of `IDLE_CLOSE_MS` waits for it), is
   released by a poll ending or a phone being removed, and **cannot leak** (a poll that is abandoned releases it; test this). Also add a state listener that is not an
   Electron window: the service has `stateListeners`; the revision counter (6.2) should hang off that.
6. **Unpairing must reach the printer part.** `removeDevice` (`server.ts`) removes the phone from the settings only. 8.1 says unpairing disarms; add the call, and end that
   phone's held polls with `401` at once (9.9). The same for `sharePrinter` / `printerControl` going off.
7. **`/v1/shared` already lists work data only.** The `printer` entry (6.1) goes in `createRoutes` `GET /v1/shared`; a phone paired before the printer was shared sees it on its next read.
8. **Timing log (part 15 of the main spec) applies to every printer route**, with the same privacy rule: never log a file name from the phone beyond its name (spec 10 already says it), a camera frame, or the printer's address. The route template for `files/detail?path=` and `camera/{id}/…` must be a template (`{id}`), never the real value.

### 15.2 Contract additions (adopted by the owner, 2026-11-18: see 15.3 O4)

1. **Do not flood the phone with state.** 6.2 sends the whole state, 10-20 KB, with a 250 ms debounce. While printing, the nozzle and bed temperatures, the position, the speed
   and fan values change almost every second, so a phone left open would receive a new answer about **4 times a second**, roughly 40-80 KB/s (an estimate: step J1 must
   **measure** a real printing session with the fake printer replaying one, and record the numbers in part 14). Proposed rules:
   - the revision goes up at once for a **meaningful** change: `connection`, `print.state`, the filename, `alert`, `message`, a heater **target**, queue, slots, camera list, arming;
   - changes that are only **live readings** (current temperatures, position, speeds, fan values, progress within the same percent) are folded together and answer **at most once every 2 s**;
   - answers are **gzip**-compressed when the phone sends `Accept-Encoding: gzip` (the JSON compresses well);
   - the phone may send `quiet=1` (screen off or Printer screen not in front) to get live-reading changes at most every 10 s.
2. **A boot id, and what "after" means after a restart.** The revision restarts when Jarvis restarts. A phone holding `after=1287` then asks a Jarvis whose revision is 3; the spec
   does not say what happens when `after` is **greater** than the current revision. Proposed: `state` answers carry `"epoch": <ms Jarvis's printer service started>`;
   a request whose `after` is greater than the current revision answers **at once** (treated as stale), and the phone compares `epoch` to know it must replace everything. The
   events `seq` already starts from the current time (6.10); keep that, and add the same `epoch`.
3. **Commands must be safe to retry.** Away from home a request can time out after Jarvis has already sent the command; the phone retries; a jog is done twice, a **start** or a
   **queue add** twice. `POST /v1/printer/command` gets `"requestId": "<uuid>"` (required unless `dryRun`). Jarvis remembers the last 200 ids per phone for 5 minutes and answers a
   repeat with the **stored answer** (same status and body) without sending again; the log line says "repeat of an earlier request". A missing id on a real send: `BAD_REQUEST`.
4. **Say which stages this Jarvis has.** `/v1/shared`'s `printer` entry gains `"features": ["state","temperatures","macros","console","files","history","camera","controls","events","upload"]`
   (only what is built and switched on), and `"schema": 1` for the `PrinterState` shape. Edith then hides what an older Jarvis lacks (J1 vs J2 vs J3 are merged at different times) and can
   tell when Jarvis's `src/shared/printer.ts` has moved past the copy she was built from.
5. **Per-phone permission to control.** The three switches in part 2 are global. Add to each paired phone in the Settings list **"May control the printer"** (default off); `printerControl` stays the
   master switch. A paired phone that is only to look (a family member's, or one that may be lost) then cannot be armed. Stored on the phone's entry in `feed.json`; `GET /v1/shared` reports `control` per phone
   as `true` only when both are on.
6. **Look at the printer before touching it** (a suggestion, not a requirement): `controls` on answers `PRINTER_UNREACHABLE` today only when the printer is unreachable. Consider also saying, in the
   answer, whether the printer's camera answered in the last minute (`"cameraSeen": true|false`), so Edith can warn "you cannot see the printer" before a heating or starting command is sent from away.

### 15.3 The owner's decisions (answered 2026-11-18; these replace the OPEN items of the first version of this part)

**The governing rule: parity.** The owner: "If it can do it in Jarvis, Edith needs to be able to do it, if at all possible." Where this part and the rule disagree, the rule wins; part 15.5
lists everything the Printer page can do and how the phone gets it, and the few deliberate exceptions.

**O1. Away from home: decided, parity.** A phone is **not** restricted when it reaches Jarvis over Tailscale: starting, heating, moving, macros, files and the queue all work away from home, as at home.
The restriction table proposed earlier is **dropped as a default**. What stays, because it adds friction without removing a capability:
- **a phone does nothing unless the owner ticked it**: the per-phone "May control the printer" (15.2.5, default off), on top of the global `printerControl`;
- the confirmations of 8.3 and the arm of 8.1 as written;
- the arm lasts **1 minute** (not `armMinutes`) when the request came over Tailscale; every accepted command restarts it, so it costs a working session nothing;
- a **notification on the PC and a `warn` log line** whenever a phone sends `start`, a heating command (`setTemp`, `preset`), a macro, `gcode`, `deleteFile` or an upload, naming the phone; the Printer page shows "Controls on for <phone name>" while a phone is armed;
- **optional, default off:** a switch "When a phone is away from home, let it only look, pause, cancel, stop, change the light and use the camera". An owner who wants the old restriction turns it on; nobody gets it by default.
The owner chose this knowing that a phone can then start a print or heat the bed while nobody is at the printer.

**O2. Alerts when the app is asleep: decided.** (a) **Catch-up on opening** is always on: Edith asks `events` with the last `seq` she saw and shows what she missed. (b) A **foreground-service poll**
("Watch my print") is an option the owner turns on in Edith; on Andarium it is unverified and Edith's side says so in the screen. (c) **No outbound push service** for now; it is a new decision if (a) and (b) prove not enough, and its text would be generic ("A print finished").

**O3. File listing from a phone: decided, the default.** `files?fresh=1`, and a `fresh=0` that has to list because there is no cache, is refused while `print.state` is `printing` or `paused` (`REFUSED`,
"Not while printing: the printer would stop answering for several seconds") and is allowed at most once a minute across **all** phones; a cached list is always served. (Why: listing freezes the AD5X for 6-11 s, the emergency stop included, `docs/plans/printer.md` 7.9.) The page's own Load files is not changed.

**O4. Which contract additions to adopt: decided, all of 15.2** on the owner's "use your best judgement": 15.2.1 to 15.2.5 are **required**; 15.2.6 (`cameraSeen`) is **recommended** and cheap, build it with the camera route. The Jarvis agent builds them with the step in 15.4; Edith's side
carries `epoch`, `features`, `schema` and `requestId` in her Kotlin client and fixtures.

### 15.4 Effect on the order of work (part 11), as decided

- **J1** also does **15.1 (all eight items)**, the measuring in 15.2.1, 15.2.2 (`epoch`), 15.2.4 (`features`, `schema`) and the O3 rule. J1 ends with the **parity check** of 15.5 (a list, in part 14, of any data a Printer-page card reads that is not in the state JSON).
- **J2** adds 15.2.3 (`requestId`), 15.2.5 (per-phone control), the O1 safeguards (arm of 1 minute over Tailscale, the PC notification and log line, the "Controls on for <phone>" badge, the optional restriction switch) and the settings route of 15.5.
- **J3** adds alerts and uploads as written, plus the O2 behaviour on Jarvis's side (the `events` route is the catch-up).
- **J4 is no longer optional.** Parity needs it: recordings (6.11), the camera stream (6.9) and screen taps (the `screenTap` command with the `screen` camera, 9.12). Build it after J3.
- Edith's side: `epoch`, `features`, `schema`, `requestId`, `cameraSeen` and the per-phone `control` are new fields her Kotlin client and fixtures must carry.

### 15.5 Parity check: what the Printer page can do, and how the phone gets it

Source: the cards in `src/renderer/src/components/printer/` and `PrinterConfig` in `src/shared/printer.ts` on Jarvis `main`, 2026-11-18. "Covered" means the spec already has it; "Added" means this part adds it.

| Printer page | Phone | |
|---|---|---|
| Status: progress, time left, pause, resume, cancel, emergency stop, alert | `state`, `quick`, the commands | Covered |
| Temperatures: targets, presets, chart, heaters off | `state`, `temperatures`, `setTemp`, `preset`, `heatersOff`; presets come from `/v1/shared` | Covered |
| Toolhead: home, jog, Z offset, extrude, motors off, speed and flow | commands | Covered |
| Fans, light, air circulation, camera on/off, filament sensors | `state` and commands | Covered |
| Sensors, slots (filament), objects and Exclude object, bed mesh, retraction, limits | `state` (objects, slots, mesh, retraction, toolhead) and commands | Covered; **J1 verifies that every value these cards read is in the state JSON** and lists any that is not (the spec's 7.1 field list was written from memory of the type; `limits` is an example to check) |
| Macros: list, run with parameters | `macros`, the `macro` command | Covered |
| Hide a macro from the list | `POST /v1/printer/settings` | **Added** |
| Console: lines, typing a line | `console`, the `gcode` command | Covered |
| Files: list, details, upload, delete, start, add to queue | `files`, `files/detail`, `files/upload`, `deleteFile`, `start`, `queueAdd` | Covered |
| Queue: add, remove, start, pause | `state.queue` and four commands | Covered |
| History and totals | `history` | Covered |
| Camera card: snapshot | `camera/{id}/snapshot` | Covered |
| Camera card: live view, and choosing which camera is shown | stream (6.9); the choice is the phone's own, no setting is written | **J4, now required** |
| Camera card: tap the printer's screen | `screenTap` with camera `screen` | **J4, now required** |
| Recordings (videos kept on the printer) | 6.11 | **J4, now required** |
| System: versions, restart Klipper, firmware restart | `state.versions`, `restart`, `firmwareRestart` | Covered |
| Settings card: temperature presets | `POST /v1/printer/settings` | **Added** |
| Settings card: print alerts on or off | the phone's own switch (`printerAlerts`, part 2) | Covered |
| Settings card: printer address, API key, SSH key for recordings, how long Controls stay on | stay on the PC | **Deliberate exception**: credentials and the safety timer are not reachable from a phone. If the owner wants any of these on the phone, they say so and it is a new decision |
| Chat: `printer_status`, and `printer_control` for pause, resume, cancel and light behind an approval card | Edith's chat uses `state` and `via: "chat"` (8.6) | Covered |
| Home and Today printer tiles, print alerts | Edith's own screens, from `state` and `events` | Covered (Edith's side) |

**`POST /v1/printer/settings` (added).** Body `{ "presets": [ {"name","extruder","bed"} … ] }` and/or `{ "hiddenMacros": [ "NAME" … ] }`; at least one. Needs `printerControl` and the phone **armed** (it changes what a later `preset` command does), and runs through
the **same validation the Settings card uses** (preset names, numbers inside the printer's limits, upper-cased macro names; a refusal is `BAD_REQUEST` or `REFUSED` with its reason). Logged like a command: `sent settings (phone "<name>"): presets` (the names of
the settings changed, never the values of anything secret; there is none here). It answers the new `presets` and `hiddenMacros`. It **cannot** touch the address, the API key, `armMinutes`, `camera`, `alerts` or the SSH key; extra fields are ignored. `GET /v1/printer/macros` already returns the visible list; add
`"hidden": [ … ]` to its answer so the phone can show what is hidden and offer to show it again.
