# C25 — Smart home core: devices, tools, Home tab (v2)

| | |
|---|---|
| Phase | 8 — Smart home (v2) |
| Depends on | C08 (tools, confirmations), C14 (hands-free: to block voice-only actions), C22 (the integration pattern) |
| Unblocks | C26–C31 (one backend each) |
| Size | Large |
| Read first | C20 (device model, tools, UI, security — binding), C21 (service notes), 06-decisions **D10**, D11 |

## Why
D10: Edith controls the owner's home directly from the phone, one Kotlin backend per service. This chunk builds
everything the backends share, against a **fake backend**, so each service chunk only adds its `HomeBackend`.

## Specification
1. **`home/` model** exactly as C20 §1 (`HomeDevice`, `DeviceType`, `Capability`, `DeviceState`, `DeviceCommand`,
   `HomeBackend : Integration`). A `Home` registry (like C22's `Accounts`) holds every connected backend, reads them
   in parallel with a timeout per backend (as C22's `Reads`), and labels devices by backend only when names clash.
2. **Name resolution** in the tool layer: exact name, then name + room, then a prefix; ambiguous → the tool lists the
   candidates and Edith asks (no fuzzier matching than that; same idea as C22's `byName`).
3. **Tools** as C20 §2, with its confirmation rules. **Unlock and garage-open (D10):** always a card, and in
   hands-free (C14) the card can't be confirmed by voice; Edith says to tap it on the screen.
4. **Home tab** (C20 §4): rooms → device tiles with state and safe one-tap actions; locks and garage in their own
   section with explicit buttons (still confirmed); camera snapshots on demand. A backend that can't be reached shows
   a banner, as C22's Calendar tab does.
5. **Settings → Home**: the connected services, each with Sign in / Sign in again / Disconnect (filled in by C26–C31).
6. **Command log** (C20 §5): device, action, result, time; kept on the phone (Room), viewable in Settings → Home;
   never camera images.
7. **Snapshots**: shown in Edith's bubble; passed to the model only when it can see (C15's vision check).

## Tests
- Name resolution (exact, room, prefix, ambiguous); every tool against the fake backend; the confirmation rules,
  including the thermostat threshold (5 °F / 3 °C, or a mode change) and unlock/garage-open refusing a voice-only
  confirmation; one backend timing out alone; the command log; the Home tab (Compose UI test).

## Acceptance
- [ ] With the fake backend (debug build): "Turn on the porch light", "Unlock the front door" (card; refused by
      voice in hands-free), the Home tab, the command log.
- [ ] `scripts/check.sh --device`; release APK on `Edith_Release_Test`.

## Not in scope
Any real service (C26–C31); live video; automations or schedules.
