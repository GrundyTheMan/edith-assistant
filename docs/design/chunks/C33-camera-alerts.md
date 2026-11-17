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

## As built (2026-11-16)
- **Decision (D24, the owner's, 2026-11-16):** a foreground service checks every 45 s while any alert is on. Ring's push (Firebase)
  needs Play services, which Andarium has none of (C17b), so Ring is polled like Blink and an alert can be up to one interval
  (plus the service's own time) late. WorkManager's 15-minute floor was ruled out. Android cuts an app's network a few seconds after
  it leaves the screen unless a foreground service runs (L24), so the service and its small "Edith is watching your cameras"
  notification are the price of alerts.
- **Code (`alerts/`):**
  - `AlertWatcher`: the pure rule. First look only learns what exists; each look reaches back 10 min and remembers what it has seen
    (a late-listed event is caught, nothing is announced twice); at most 3 alerts per service per look (the newest); a failed look
    waits 90 s, 180 s, then 300 s at most, and a good one resets it; switching a service off forgets it, so switching on learns afresh.
  - `Home.activity(backendId, since)` is its source: a service's **clips** when it has them (Blink; Ring with a plan), else each
    camera's **events** (Ring without Ring Protect keeps events but no recordings). The camera list is kept, not re-read every look.
    `DeviceClip.backend` is empty until `Home.clips` fills it, so the key uses the service id (a test caught this).
  - `AlertService` (foreground, `specialUse`; Android 14 wants a type, and `dataSync` is cut after 6 h on Android 15) runs the loop,
    stops itself when the last alert goes off, and survives a service's surprise (logged by type only). `AlertKeepAlive` starts it
    when Edith opens with alerts on or the first switch goes on; `AlertBootReceiver` starts it after a restart.
  - `AlertNotifier`: a channel per kind (Doorbell high; Person, Motion default; plus the low "watching" channel), so each can be
    silenced in Android's settings. **Every alert** (doorbell, person, motion) gets its camera's picture a few seconds after the alert shows (the alert
    isn't held up by a battery camera waking). The picture is a fresh snapshot taken after the alert, not a frame of the event itself, and it
    wakes the camera on each event (the owner asked for this; it costs some camera battery).
  - **Tap:** the notification starts `MainActivity` with `ACTION_OPEN_CLIPS`; `AlertTaps` (a flag) brings the Home tab forward, which
    opens the **Clips list** (events carry no clip id, so it opens the list, not one clip: the newest is at the top).
  - **Settings → Home → Alerts:** per signed-in service (Ring: doorbell, person, motion; Blink: motion), all off by default; turning
    one on asks for the notification permission (Android 13+).
- **Debug builds only:** the Demo home's doorbell can alert (`FakeHomeBackend.ring()`, `DebugAlertReceiver`: `--es mode on|ring|off`).
- **Tests:** 27 new (756 in all, `scripts/check.sh` OK, lint: no issues): `AlertWatcherTest` (12: learn first, once only, 3 per look,
  kinds off, live view never, back-off and reset, due times, a failing service not stopping the other, switching off and on),
  `AlertActivityTest` (6: clips, events fallback, kept camera list, failure, not signed in, camera by name),
  `AlertPartsTest` (9: switches, catalog, mapping, keep-alive rule, tap flag, titles).
- **Emulator (Pixel_9_API_37, debug build, Demo doorbell):** alerts on → the service ran in the foreground (`specialUse`) with its
  notification; the doorbell rang → the alert, with the doorbell's picture, appeared ~35 s later (one interval); tapping it opened the
  Home tab's Clips list; alerts off → the service and its notification went away; the crash buffer stayed empty.
- **Cost, honestly:** on the emulator the service used ~0.0007 mAh and 0% CPU over 5 minutes, but the Demo home makes no network calls,
  so that measures only the loop. The real cost is the **requests**: one per service per look for Blink (its clip list), and one
  per camera per look for Ring (its history search; events without a plan are one per camera too), every 45 s while alerts are on.
  The owner's phone shows the real battery cost (Android's battery screen, after a day).
- **Not verified:** the owner's live check (acceptance below): a real Ring doorbell, a real Blink motion, and the delay; whether Ring's
  per-camera search every 45 s is welcome to Ring's servers (a failure backs off, but nothing was seen over a long run); the
  boot start (the receiver was not exercised on the emulator); Andarium on the iPhone (foreground-service rules there are unknown).
- **Known limits:** only about one interval of delay is promised, not instant; if Android or Andarium kills the service, alerts stop
  until Edith is opened; a service signed out (needs signing in again) just backs off and says nothing; the 3-per-look limit drops
  the rest.

## Not in scope
Alerts for other devices (locks left open, temperature): a later chunk if wanted.
