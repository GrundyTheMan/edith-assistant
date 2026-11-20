# C55 — The 3D printer on iOS, direct on the home Wi-Fi (Moonraker)

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave A |
| Status | **Built** in Expo Go; see "As built" at the end. The owner's iPhone check, against the real printer, is open |
| Depends on | C48 (services, optional sign-in fields), C46 |
| Unblocks | C67 (the full printer features through Jarvis build on the same card) |
| Runs in | Expo Go |
| Size | Medium |
| Ports | C34 (`printer/`: `MoonrakerApi`, `MoonrakerParse`, `PrinterService`, `PrinterTools`) |
| Read first | C34 spec **and** As built (what the card shows, how progress and time left are computed, the command guards, verified live 2026-11-15), Jarvis's `src/main/printer/` (`moonraker.ts`, `service.ts`, `camera.ts`) and `printerTools.ts` (references), D19, D26 |

## Why
Jarvis's Home page shows the owner's 3D printer (Klipper with Moonraker, ZMOD firmware). Android has a printer card and
`printer_status`; the owner confirmed it works. This is the same on the iPhone: a card on the Home tab, "How's the print
going?", Pause / Resume / Cancel. It works **at home only** (Moonraker's address is on the LAN): away from home the card
says so without an error banner. The richer features (Controls, macros, console, upload) are Jarvis's (C67).

## Specification

### 1. Connection
- Settings → Home → **3D printer**: the address (`192.168.4.86`, or `http://host:port`; plain HTTP is intended: the printer is on the LAN, so this service uses the **plain** client, `httpFetch`, and says why in its PR) and an **optional API key** (kept in the SecretStore `home.printer`, sent as `X-Api-Key`). `SignInField.optional` (C48).
- Moonraker is `GET /printer/objects/query?…`, `GET /server/files/metadata?filename=…`, `GET /server/webcams/list`, `POST /printer/print/pause|resume|cancel`. Every response is `{"result": …}`; unwrap it. Timeouts: 5 s per call, 3 s connect (use `AbortController` timers).
- **Local-network permission:** the first call triggers iOS's prompt; mention it in the form's intro ("iOS will ask to find devices on your network").

### 2. Reading (one query, parsed by pure code in `printer/parse.ts`, ported from Jarvis's `parseStatus`, `timeLeft`, `fileTitle`)
One `objects/query` for `webhooks, print_stats, display_status, virtual_sdcard, pause_resume, idle_timeout, extruder, heater_bed`; the slicer's estimate from `/server/files/metadata` once per file; webcams from `/server/webcams/list` once.
- **Progress** is the slicer's own (`display_status.progress`, from M73) when it differs from the file position, else `virtual_sdcard.progress`. **Time left** is the slicer's estimate when there is one, else from the pace (not before 1 % and one minute).
- **Title:** a Bambu-style `3mf/model/…/plate_1.gcode` is shown as "Name (plate 1)".
- **States:** idle, printing, paused, complete, cancelled, error (Klipper not ready: starting or shut down shows as an error with Klipper's first line).
- **Away from home / printer off:** a connect failure or timeout → card text "Not reachable. It works on your home Wi-Fi." with the **last report kept**, **no error banner**. A wrong key (401/403) or a server that answers something else **is** an error, in words.

### 3. The card on the Home tab
`DeviceType 'printer'`, `Capability 'print_control'`, `DeviceState.print { state, file, progress, timeLeftSec, layer, totalLayers }` and `heaters { nozzle, bed }` (add to `home/model.ts`). The printer is one device with its own card (`ui/PrinterCard.tsx`): state, the file ("Printing Benchy"), a progress bar, "about 1 h 12 min left", the layer, nozzle and bed temperatures (actual / target), and buttons **Pause**, **Resume**, **Cancel print**, **Camera**. A **Printer** filter chip joins the others. The camera button shows a snapshot: `GET` the webcam's snapshot URL (resolve relative addresses against the printer's base; a webcam on another port is allowed), shown in a viewer (`ImageViewer`); refresh button.

### 4. Commands and tools
- `home_control` gets actions `pause`, `resume`, `cancel` for devices with `print_control` (add to `ACTIONS`, `NEEDS`, `buildCommand`, `confirmationFor` in `home/actions.ts`), so the printer shares C46's cards, command log and (later) hands-free rules. **Cancel always shows a card** with the buttons **Cancel print** and **Keep printing** (two buttons that both said "Cancel" would be easy to confuse on something that can't be undone). Pause and Resume: no card.
- **Commands are checked first:** pause only while printing, resume only while paused, cancel only with a print going; asking for what is already so is a success and **sends nothing**.
- New tool `printer_status` (no parameters, read-only): "Printing Benchy, 42 %, about 1 h 12 min left, nozzle 215 °C, bed 60 °C." or "The printer is idle."; "not reachable" wording as above. Enabled while the printer service is connected.
- Commands go into C46's command log.

### 5. Service
`printer/service.ts` (`HomeService`), `printer/api.ts` (Moonraker over `httpFetch`), `printer/parse.ts`. `restore()` reads `home.printer` (address + key). `disconnect()` deletes it. A polling loop is **not** added: the card refreshes on pull-to-refresh and when the Home tab is focused and every 5 s while a print is in progress **and the tab is visible** (stop when it isn't; use `useFocusEffect`).

## Tests
Port C34's 59 against a pretend Moonraker: parsing (addresses, progress by M73, time left, states, Klipper errors, titles, webcams, odd answers), the HTTP layer (key sent only when set, 401, error messages, unreachable), the service (connect, reads, away, commands and their guards, camera), and the Home tab words, the **cancel card** (backing out sends nothing; confirming sends `cancel`), `printer_status`, **no banner away from home**.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner, on the iPhone at home:** add the printer; the card shows the real print; "How's the print going?"; Pause then Resume; the Cancel card (back out); the camera picture. Away from home the card says it works on home Wi-Fi with no red banner.

## Risks and unverified assumptions
- Plain HTTP to the printer's IP in Expo Go (ATS exempts IPs; Ollama proves it for the owner's phone). A hostname will not work: say "use the IP address" in the form hint.
- The snapshot URL: relative addresses and a webcam on port 8080 (ZMOD) — as Android found them.

## Not in this chunk
Uploading or starting prints, macros, G-code, the console, recordings, alerts (C67).

## As built (2026-11-20)
Built by an AI agent; Android is untouched. Where the code differs from the specification above, the code is right.
- **Files:** `printer/parse.ts` (ported from Jarvis's `shared/printer.ts`: `parseStatus`, `timeLeft`, `fileTitle`, `formatDuration`, plus webcam and address helpers),
  `printer/api.ts` (`MoonrakerApi`, `PrinterError`), `printer/service.ts` (`PrinterService`), `printer/tools.ts` (`printer_status`), `ui/PrinterCard.tsx`,
  `core/bytes.ts` (`bytesToBase64`, `readBytes`); changes in `home/model.ts` (`PrintJob`, `HeaterReading`, `DeviceState.print/heaters`, `pause`/`resume`/`cancel_print`),
  `home/actions.ts` (the actions `pause`, `resume`, `cancel`; the cancel card; `printerWords`), `ui/HomeScreen.tsx` (the Printer chip, the card, the camera viewer, a 5 s refresh
  while a print is going and the tab is focused), `core/services.tsx` (the service and the tool are registered).
- **One device, one card:** the printer is a `HomeDevice` (`type: 'printer'`, room "Workshop") with capabilities `print_control` and, when Moonraker lists a webcam, `snapshot`. So
  "show me the printer camera" also works through C49's `camera_snapshot`.
- **Cancel** shows "Cancel the print: 3D printer?" with the buttons **Cancel print** and **Keep printing**, and the file's name as a detail. Pause and Resume ask nothing. Commands are
  checked first against a fresh reading and send nothing when already so; "isn't printing / isn't paused / no print to cancel" are plain failures.
- **Away from home** is a device that is not online with the last report kept ("Not reachable. It works on your home Wi-Fi."), **no problem line, no banner**. A wrong key
  (401/403) is a problem line and turns the service to "Sign in again"; an address that isn't Moonraker is refused at sign-in.
- **Deviation:** the slicer estimate comes from Moonraker's `/server/files/metadata` (`estimated_time`) only, not from reading the G-code header as Jarvis also does (ZMOD files
  without metadata then use the pace). Asked once per file.
- **Plain HTTP:** the service uses `httpFetch` (not `secureFetch`) on purpose: Moonraker has no TLS and lives at a LAN IP (AGENTS.md exception). The form says to use the numbered
  address, because iOS only allows plain HTTP to raw IPs.
- **Not verified:** everything on the real printer and the iPhone: whether iOS shows its Local Network prompt and allows the calls in Expo Go (Ollama on the same network already
  works), the ZMOD progress/estimate and webcam address, a printer that wants an API key, and how the card looks.
- **Found on the owner's iPhone (2026-11-20), both fixed:**
  - **The camera answered HTTP 404.** A relative camera address ("/webcam/?action=snapshot") was asked of Moonraker's API port (:7125), which has no `/webcam/`. It belongs to the printer's **web server**: the host without the port when the owner typed a bare address or :7125 (as Android does), or the typed port when it is some other one (a proxy serving both). Test: a pretend printer whose API port answers 404 for the camera, as the real one did. The card and the live reading of the printer worked, and the card showed the real print (Diorama, 22%, nozzle 260 °C, bed 100 °C).
  - **The "Cancel print" dialog on the Home tab was confusing:** two buttons that read "Cancel" and "Cancel print", and a title ("Cancel the print: 3D printer?") that did not say which print. The Home tab's dialog ignored the confirmation's own wording; the card in chat already had "Keep printing". Now both use it: the title is "Cancel the print on 3D printer?", the message names the print ("Printing: Diorama" and the device), and the buttons are **Keep printing** and **Cancel print** (red). Every other confirmation keeps "Cancel" as its way out.
