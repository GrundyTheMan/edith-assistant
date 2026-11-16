# C34 — 3D printer card (Moonraker) (v2)

| | |
|---|---|
| Phase | 8 — Smart home (v2) |
| Depends on | C25 |
| Size | Medium |
| Read first | 06-decisions **D19**; Jarvis's `src/main/printer/` (`moonraker.ts`, `service.ts`, `camera.ts`) and `printerTools.ts` (references only) |

## Why
D19: Jarvis's Home page shows the owner's 3D printer (Klipper with Moonraker). The owner wants a printer card on
Edith's Home tab too, after the smart-home chunks.

## Specification (check Jarvis's current printer plan, `jarvis/docs/plans/printer.md`, first)
- **Connection:** Moonraker's HTTP API on the home network (its address and optional API key in Settings → Home,
  the key in `SecretStore`). It's only reachable at home (or over a VPN): away from home the card says so, without an
  error banner.
- **Card:** state (idle, printing, paused, error), the file, progress and time left, temperatures, and the camera
  picture if it has one (MJPEG snapshot).
- **Tools:** `printer_status`, and `printer_control` for pause, resume and cancel; cancel is always a card.
- Commands go into C25's command log.

## Tests
Parsing Moonraker's answers from made-up captures; unreachable → "not at home"; the cancel card.

## Acceptance
- [ ] Owner: the card on the Home tab at home; "How's the print going?"; pause and resume.

## Not in scope
Uploading or starting prints, macros, SSH (Jarvis has these on the PC).

## As built
- **Done:** a 3D printer (Klipper with Moonraker) on the home Wi-Fi. Settings → Home → **3D printer** takes its address (`192.168.4.86`, or `http://host:port`) and an optional API key
  (kept encrypted, `SecretStore` `home/printer`). The printer is one device on the Home tab with its own **card**: state, the file ("Printing Benchy"), a progress bar, about how long
  is left, the layer, nozzle and bed temperatures, and Pause / Resume / **Cancel print** / Camera buttons. A **Printer** filter chip joins the others.
- **Tools:** `printer_status` ("How's the print going?") and, instead of a separate `printer_control`, the existing `home_control` with `pause`, `resume` and `cancel` (so the
  printer shares C25's cards, command log and hands-free rules). **Cancel always shows a card** ("Cancel the print: 3D printer?", buttons **Cancel print** and **Keep printing**: two
  buttons that both said "Cancel" would be easy to mix up on something that can't be undone). Pause and Resume need none. Commands go into the command log like any other.
- **Away from home is not an error.** A printer that doesn't answer (off, or this phone is on another network) makes the card say "Not reachable. It works on your home Wi-Fi." with the last
  report kept, and **no error banner**. Wrong key or a printer that answers something else *is* an error, in words.
- **Code:** `printer/MoonrakerApi` (HTTP, `{"result": …}` unwrapped, 5 s calls, 3 s connect), `printer/MoonrakerParse` (Jarvis's `parseStatus`, `timeLeft`, `fileTitle`,
  webcams), `printer/PrinterService` (sign-in, reads, commands, camera), `home/PrinterTools` (`printer_status`). Generic additions in `home/`: `DeviceType.Printer`,
  `Capability.PrintControl`, `DeviceState.print` (`PrintJob`) and `heaters`, `DeviceCommand.Pause/Resume/CancelPrint`, their rules in `HomeActions`, `SignInField.optional`, the card
  and chip in `HomeScreen`, and a demo printer in the Demo home.
- **How it reads:** one `objects/query` (webhooks, print_stats, display_status, virtual_sdcard, pause_resume, idle_timeout, extruder, heater_bed), the slicer's estimate from
  `/server/files/metadata` once per file, and the webcams from `/server/webcams/list` once. Progress is the slicer's own (`display_status`, from M73) when it differs from the file position;
  time left is the slicer's estimate when there is one, else from the pace (not before 1% and a minute). A Bambu-style `3mf/model/…/plate_1.gcode` is shown as "Name (plate 1)". Klipper
  not ready (starting, shut down) shows as an error with Klipper's first line.
- **Commands are checked first:** pause only while printing, resume only while paused, cancel only with a print going; asking for what is already so is a success and sends nothing.
- **Network off the main thread:** `MoonrakerApi` runs every call on the IO threads whoever asks (lesson L39: Android kills the app for a network read on the main thread).
- **Tests (59 in `printer/`, run against a pretend Moonraker):** parsing (addresses, progress by M73, time left, states, Klipper errors, titles, webcams, odd answers), the HTTP layer (key sent
  only when set, 401, error messages, unreachable, pictures, **never read on the asking thread**), the service (connect, reads, away, commands and their guards, camera), and Home (the words for a
  print, the cancel card, the tools, no banner away from home). `scripts/check.sh` OK.
- **Verified on the emulator, against a pretend Moonraker on the PC** (debug builds fail on main-thread network use): connect (no key), the card, Pause, Resume, the Cancel card (backing out sends
  nothing; confirming sends `cancel`), the camera picture, "How's the print going?" by chat, and the printer going away (the card says so, no banner). No crash, no main-thread network use.
- **Verified on the owner's real printer (2026-11-15, dev.9, on Andarium):** the owner reports it behaves. Until then it had only been tried against the pretend Moonraker. The risks listed next were the open points; none has been reported as a problem.
- **Was not verified before that, and why it mattered:** nothing had been tried against the owner's real printer. The parsing is Jarvis's, tested on made-up captures in the shapes Moonraker sends, not on the
  printer's own answers. Things a real Klipper/ZMOD printer may do differently: the progress and time estimate (M73), 3mf file names, the heaters it reports, whether the camera's snapshot
  address works, and an API key (never tried against a printer that wants one). Acceptance below is the owner's.
