# C25 — Smart home core: devices, tools, Home tab (v2)

| | |
|---|---|
| Phase | 8 — Smart home (v2) |
| Depends on | C08 (tools, confirmations), C14 (hands-free: to block voice-only actions), C22 (the integration pattern) |
| Unblocks | C26–C31 (one backend each), C32–C34 |
| Size | Large |
| Read first | C20 (device model, security; its "Jarvis parity" section overrides §2's tool list), C21, 06-decisions **D10**, **D11**, **D19** |

## Why
D10: Edith controls the owner's home directly from the phone, one Kotlin backend per service. D19: as close to
Jarvis's home features as a phone allows. This chunk builds everything the backends share, against a **fake
backend**, so each service chunk only adds its `HomeBackend`.

## Specification
1. **`home/` model** (C20 §1, widened for D19):
   - `DeviceType`: Light, Switch, Plug, Fan, Thermostat, TemperatureSensor, Lock, GarageDoor, Camera, Doorbell,
     SecurityPanel, Sensor, Speaker, Other.
   - `Capability`: OnOff, Brightness, Percentage (fans), Color, WhiteTone, Thermostat(modes, unit, min, max),
     LockUnlock, OpenClose, Snapshot, Events. (Clips and LiveView come with C27/C29 and C32.)
   - `DeviceState` (one data class, every field optional, as Jarvis's `StateValues`): on, brightness, percentage,
     color, white tone, temperature, setpoint, mode, lock (locked/unlocked/jammed), door (open/closed/opening/
     closing), battery.
   - `HomeDevice(id, name, room, type, capabilities, backend, online, state, stateAt)`; `devices()` returns each
     device with its state, so one call fills the list (as Jarvis's `alexa_devices`).
   - `DeviceCommand`: TurnOn, TurnOff, SetBrightness, SetPercentage, SetColor, SetWhite, SetTemperature, SetMode,
     Lock, Unlock, Open, Close.
   - `HomeBackend : Integration` with `devices()`, `state(id)`, `command(id, command)`, `snapshot(id)`, and
     `isHub` (true for Alexa: a device another backend also reports is shown through the other one).
2. **`Home` registry** (like C22's `Accounts`): every connected backend; reads them in parallel with a 10 s limit
   each (C22's `Reads`); one backend's failure is a problem line, never an empty home. **Merging (D19):** a hub's
   device with the same name and type as a direct backend's is dropped while the direct one answered. Hidden devices
   are left out of everything except Settings → Home.
3. **Name resolution** (Jarvis's `matchDevice`, plus rooms): exact name (any case); then "room name" or "name in
   room"; then the only name containing the words; a trailing "(Light)" a model copied from the list is ignored.
   Ambiguous → the tool lists up to 10 candidates and Edith asks. No fuzzier matching.
4. **Tools** (D19, Jarvis's shape):
   - `home_devices(filter?)`: devices with their state, grouped by room; filter by name, room or type. At most 80
     listed; a backend that didn't answer is named.
   - `home_control(device, action, value?)`: `action` is one of turn_on, turn_off, set_brightness, set_percentage,
     set_color, set_white, set_temperature, set_thermostat_mode, lock, unlock, open, close. An action the device
     can't do is refused with what it can do. Setpoints are limited to 50–90 °F / 10–32 °C.
   - `camera_snapshot(device)`: the JPEG is shown in Edith's bubble and passed to the model only if it can see
     (C15's vision check).
   - Cards (C20 §2): turn_on/off, brightness, percentage, colour, white: none. set_temperature: a card when it moves
     the setpoint more than 5 °F / 3 °C (or the current setpoint is unknown); set_thermostat_mode: always. lock: none.
     close: a card. **unlock and open: always a card, marked screen-only**: hands-free has no way to confirm it by
     voice, and while hands-free is on Edith says to tap it on the screen.
   - After a command, the result says what the device now reports, and the state is checked again later (item 6).
5. **Home tab** (C20 §4, Jarvis's Home page): a **Home** destination in the bottom bar (Chat, Calendar, Home,
   Settings).
   - Chips: Favorites, Lights, Plugs and switches, Fans, Climate, Locks and doors, Cameras, All; the last one used is
     remembered.
   - Tiles grouped by room: name, state with its age ("On · 3 min ago"), "Not responding" when offline. One tap on
     a light, plug, switch or fan toggles it. Tapping a tile's name opens its controls: brightness slider, fan speed
     presets (Low 33, Medium 66, High 100), colour and white presets, thermostat − / + (sent 1.2 s after the last
     tap) and mode chips.
   - Locks and garage doors in their own section with explicit Lock/Unlock and Open/Close buttons; Unlock and Open
     ask on a dialog first (the same rule as the tools).
   - Cameras: a Snapshot button; the picture opens over the page.
   - A star marks a favourite; "Hide" hides a device (from the tab and from Edith).
   - A backend that can't be reached shows a banner, as the Calendar tab does. Pull to refresh.
6. **State re-check after a command** (Jarvis): the device is asked again 3 s later; thermostats and locks also at
   10 s and 30 s (their clouds lag). The tile shows the new state as soon as it's reported.
7. **Settings → Home**: the connected services, each with Sign in / Sign in again / Disconnect (C26–C31 fill them
   in); the hidden devices (Show again); the command log. In debug builds only, a **Demo home** switch connects the
   fake backend.
8. **Command log** (C20 §5): time, device, action and value, result, and where it came from (chat or Home tab);
   stored in Room (schema v3, a new table, migration test); the newest 500 kept; viewable in Settings → Home; never
   camera images.
9. **Fake backend** (debug only, `BuildConfig.DEBUG`, so R8 drops it from release): a porch light (dimmable), a
   living-room lamp (colour and white), a kitchen plug, a bedroom fan, a hallway thermostat (°F; heat, cool, auto,
   off), a front-door lock, a back-door lock, the garage door and a doorbell camera (a generated JPEG). Locks and the
   thermostat report their change a few seconds late, as real clouds do.

## Tests
- Name resolution (exact, room, contains, the copied "(Light)", ambiguous); every action against the fake backend;
  the card rules, including the thermostat threshold and screen-only unlock/open; one backend timing out alone;
  hub/direct merging; hidden devices left out of the tools; the command log and its migration (v2 → v3); the Home
  tab (Compose UI test: chips, a toggle, the unlock dialog).

## Acceptance
- [x] With the fake backend (debug build, Demo home on): "Turn on the porch light", "Set the fan to low", "Unlock
      the front door" (card; in hands-free she says to tap it), "Show me the doorbell camera", the Home tab
      (chips, toggle, thermostat steps, unlock dialog, favourite, hide), the command log.
- [x] `scripts/check.sh --device`; release APK on `Edith_Release_Test` (no Demo home there).

## Not in scope
Any real service (C26–C31); clips (C27/C29); live video (C32); alerts (C33); the printer (C34); automations or
schedules.

## As built (2026-11-13)
- **Code:** `home/` — `HomeModel.kt` (types, `HomeBackend`), `HomeActions.kt` (actions → commands, card rules,
  state words), `DeviceNames.kt` (§3), `Home.kt` (registry, merging, last-known states, commands, re-checks),
  `HomePrefs.kt` (favourites, hidden, Demo home, chip; in the `settings` DataStore), `CommandLog.kt` (Room table
  `home_commands`, schema v3, `MIGRATION_2_3`), `HomeTools.kt`, `FakeHomeBackend.kt` + `DemoPicture.kt` (debug only:
  `AppContainer` passes the fake only when `BuildConfig.DEBUG`), `ui/HomeViewModel.kt`, `ui/HomeScreen.kt`;
  `ui/settings/HomeSettings.kt`.
- **Framework changes:** `ToolResult.Ok` carries `images` (`ToolImage`); `ToolTurnRunner` adds them to the tool message
  only for a model that can see, and passes them to `TurnEvent.Finished`; `ChatEngine` keeps them as Edith's image
  attachments (`AttachmentStore.writeBytes`), drawn as pictures in her bubble (`EdithAttachments`).
  `ConfirmationRequest.screenOnly`. `SpeechOutput.announce()` says a line without muting the reply;
  `HandsFree` says "Tap … on the screen" once per waiting card (hands-free has no voice approval at all, so every
  card waits for a tap; screen-only cards say so).
- **Merging:** a hub's copy is dropped while the direct backend answers; when the direct one fails, the hub's copy
  replaces its last-known copy, so a device never shows twice.
- **Command log:** a command whose device reports the old state at first (lagging clouds) is logged as "Sent; it still
  reported Locked at first", not as if nothing happened.
- **Tests:** `HomeTest` (9), `HomeActionsTest` (5), `DeviceNamesTest` (6), `HomeToolsTest` (7), `HandsFreeTest` (+1),
  `DestinationsTest` (4 tabs); device: `HomeScreenTest` (2), `MigrationTest` (+1 v2 → v3), `EdithNavHostTest` visits
  Home. `scripts/check.sh`: 372 unit tests OK (`build-logs/2026-11-13_17-04-11_check.log`).
- **Live (debug, `Pixel_9_API_37`, Demo home on, gemma4 12B):** the Home tab (rooms, locks and doors section, ages);
  Unlock → dialog → "Locked" then "Unlocked" about 10 s later (re-check); "Turn on the porch light" →
  `home_control(turn_on)`; "Show me the doorbell camera" → the picture in Edith's bubble; "Unlock the back door" →
  the screen-only card, Cancel → nothing sent; "Set the bedroom fan to low" → 30 % (the tool's description now names
  the presets: low 33, medium 66, high 100); star → kept after a restart; Hide → gone from the tab, listed in
  Settings → Home with Show again; the command log. No crash.
- `scripts/check.sh --device`: OK (115 tests, `build-logs/2026-11-13_17-06-53_check.log`). **Release APK (R8**,
  `build-logs/2026-11-13_17-10-55_gradle_app-assembleRelease.log`, not published) on
  `Edith_Release_Test`, installed over v2.0.0-dev.3: the chats survived the v2 → v3 migration; the Home tab says to
  connect a service; no Demo home switch (R8 dropped the fake: none of its strings are in the dex); no crash.
- **Not verified yet:** the hands-free prompt
  live (covered by a unit test only: on the emulator the debug wake-word hook scored every sample 0.001, including
  "Hey Jarvis", so hands-free couldn't be woken; that looks like a separate problem, not caused by this chunk).
