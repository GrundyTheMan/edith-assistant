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
