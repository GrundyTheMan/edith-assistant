# C33 — Camera alerts: doorbell, person, motion (v2)

| | |
|---|---|
| Phase | 8 — Smart home (v2) |
| Depends on | C27 (Ring, clips), C29 (Blink, clips) |
| Size | Medium–large |
| Read first | 06-decisions **D19**; Jarvis's `ringEvents.ts`, `blinkEvents.ts`, `shared/blinkAlerts.ts` (references only) |

## Why
D19: Jarvis shows a notification when the doorbell rings or a camera sees a person or motion, with a snapshot, and
clicking it opens the clip. The owner wants this on the phone.

## Specification (verify each service's current flow first)
- **Ring:** Ring's push notifications (ding, person, motion), as `ring-client-api` receives them (it registers for
  Firebase push). Check whether that can run on Android without Google Play services (Andarium on the iPhone has none,
  C17b); if it can't, fall back to checking Ring's event history every so often while alerts are on, and say how late
  alerts then are.
- **Blink:** Blink pushes only to its own app, so, as Jarvis does, the clip list is checked every 45 s while alerts are
  on; each new clip a camera recorded by itself is one alert (at most 3 per check; a failed check waits longer).
- **Android:** a notification channel per kind (Doorbell, Person, Motion), with the snapshot or clip thumbnail;
  tapping opens the clip on the Home tab. Checking in the background needs a foreground service or WorkManager:
  measure the battery cost on the emulator and tell the owner before choosing.
- **Settings → Home → Alerts:** per service and per kind (Jarvis's `RingAlerts`: ding, person, motion), off by default
  until the owner turns them on.

## Tests
New-clip detection (no repeats, the 3-per-check limit, back-off); push parsing; channels; tapping opens the clip.

## Acceptance
- [ ] Owner: the doorbell rings → a notification with a picture within the expected delay; motion on a Blink camera →
      a notification; tapping each opens its clip; alerts off → nothing.

## Not in scope
Alerts for other devices (locks left open, temperature): a later chunk if wanted.
