# C72 — Camera alerts on iOS, under iOS's rules

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **Design** — decision D31 first |
| Depends on | C51 (Ring), C52 (Blink), C53 (clips; alerts open the list), C58 (development build for notifications entitlements and, for option B, push) |
| Unblocks | — |
| Runs in | option A: the development build; option B: the development build **and** a change in Jarvis |
| Size | Large (A) / XL (B, with the Jarvis part) |
| Ports | C33 (`AlertWatcher`, notification kinds, the tap target) and D24 |
| Read first | C33 spec **and** As built (the pure `AlertWatcher` rules: first look only learns; 10-minute reach-back; no repeats; ≤ 3 per service per look; back-off 90/180/300 s; `Home.activity`), D24, `ios-roadmap.md` §4 (iOS has **no foreground service**), `jarvis-feed-spec.md`, Jarvis's `ringEvents.ts`, `blinkEvents.ts`, `shared/blinkAlerts.ts` (references) |

## Why
The owner wants a notification when the doorbell rings or a camera sees a person or motion, with a picture, and a tap opens the
clip (D19, C33). Android does it with a **foreground service polling every 45 s** (D24): Ring's push (Firebase) needs Play
services, which the owner's setup lacked. **iOS has no foreground services**: an app is suspended soon after it leaves the screen,
so **an app on its own cannot poll in the background**. The honest choices (decision **D31**, ask the owner; recommended: **A now,
B only if they want away-from-home alerts**):

| | What it gives | What it needs |
|---|---|---|
| **A. While the app is open, plus a catch-up** | Alerts every 45 s while Edith is on screen or recently used; **when the owner opens the app, "While you were away: 2 doorbell rings, 1 person" with pictures**; a best-effort iOS background refresh (about every 15+ minutes, **not reliable, not promised**) | Only the app. Notifications permission. |
| **B. Jarvis sends the push** | Real alerts, away from home, with the app closed: Jarvis (always on, on the PC) already watches Ring/Blink for its own desktop alerts; it sends an **Apple push (APNs)** to the phone | The **paid Apple membership** (already needed for C58), an **APNs auth key** (.p8, created by the owner), **a change in Jarvis** (a push sender and a registration route), the push entitlement in the build, Edith registering for remote notifications. Works only while the PC and Jarvis are on. |
| C. Both | A when Jarvis is off; B when on | both |

## Option A — specification
1. **`AlertWatcher`** (`alerts/watcher.ts`, **pure**): port Android's rule exactly (C33 As built): the first look only learns what exists; each look reaches back 10 minutes and remembers what it has seen (a late-listed event is caught; nothing announced twice); at most **3 alerts per service per look** (the newest); a failed look backs off **90 s, 180 s, then 300 s at most**, and a good one resets it; switching a service off forgets it (switching on learns afresh); `Home.activity(backendId, since)` is its source: a service's **clips** when it has them (Blink; Ring with a plan), else each camera's **events** (Ring without Ring Protect). Test with the same cases as Android's `AlertWatcherTest` (12) and `AlertActivityTest` (6).
2. **Foreground loop** (`alerts/loop.ts`): while the app is **active** and any alert is on, look every **45 s** (`AppState` `active`; pause in the background; **resume with an immediate look**). 
3. **Catch-up on return:** when the app becomes active after more than 2 minutes in the background, run one look with a longer reach-back (up to 24 h, capped at 20 items) and show **one** summary notification-style banner in the Home tab ("Since 3:10 PM: Front door rang (2), Backyard saw a person") with pictures (snapshots would be *new*, not of the event: C33 says each alert's picture is **a fresh snapshot after the event**; for the catch-up use clip thumbnails if available, else none: say so).
4. **Best-effort background refresh:** `expo-background-task` (the successor of background fetch; verify the SDK 58 name and that it works in a development build): register a task that runs the same look and posts a **local notification** for new items. iOS schedules it at its discretion (often hours): document that alerts are **not** timely this way.
5. **Notifications** (`expo-notifications`, local only): permission asked **when the owner switches the first alert on**; channels don't exist on iOS, so categories: Doorbell (time-sensitive interruption level if the entitlement allows: do not request Critical), Person, Motion; each with the camera's name, kind and (when available) a picture attachment (a notification attachment needs a file URL: write the JPEG to the cache and pass it); tapping opens the app on the Home tab's **Clips list** (events carry no clip id; the newest is at the top), exactly as Android.
6. **Settings → Home → Alerts:** per signed-in service and per kind (Ring: doorbell, person, motion; Blink: motion), **all off by default**; a plain explanation: "Alerts arrive while Edith is open, and when you return. iPhone doesn't let apps watch in the background." and a link to option B if enabled.
7. **Demo:** the Demo home's doorbell can ring (a debug button or a debug-only tool `demo_ring`) so the whole path is testable without a real service.

## Option B — specification (only if the owner chooses it; **a Jarvis chunk and an Edith chunk, in this order**)
- **Jarvis side (write `docs/design/jarvis-push-spec.md` for the Jarvis agent; this chunk's first PR is that document):** a new feed route `POST /v1/push/register {token, platform:'ios', env:'development'|'production'}` (authenticated by the device token, stores `{deviceId, apnsToken, env}`), Jarvis's existing Ring/Blink watchers send an **APNs HTTP/2** request (`api.push.apple.com` or `api.sandbox.push.apple.com`) signed with a **provider token (JWT ES256 from the .p8 key)** with `apns-topic` = the bundle id and a payload `{aps:{alert:{title, body}, sound, "mutable-content":1, "interruption-level":"time-sensitive"}, kind, camera, at}` (and a picture fetched by a **notification service extension**: **Expo's `expo-notifications` has no service extension in managed apps: skip pictures in B v1** or tell the owner it needs a native extension). The `.p8` key lives only in Jarvis's `safeStorage`; **never** in the Edith repo or the phone. Contents of events are not logged.
- **Edith side:** `expo-notifications` `getDevicePushTokenAsync()` (a **development build** has the `aps-environment` entitlement via EAS credentials; the owner's Apple account), register the token with Jarvis (C66's account), handle the notification tap → Clips list; Settings text "Alerts via Jarvis: on" and the state (PC off → "no alerts while Jarvis is off").
- The owner's steps: create an APNs key in the Apple Developer account; give it to Jarvis (a field in Jarvis's Settings → Edith, **not** to the agent).

## Tests
Watcher (all Android cases), the loop with a fake clock and `AppState` (active → looks every 45 s; background → none; resume → immediate look), the catch-up summary builder, notification content builder (titles, bodies, categories, the tap payload), settings defaults off; the Demo ring path; (B) the registration call and the notification handler, with a fake `expo-notifications`.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner (A):** with alerts on and Edith open, the doorbell rings → a banner within ~45 s; leave the app for 20 minutes, ring the doorbell, return → the "While you were away" summary lists it; a tap opens the Clips list; alerts off → nothing. Record how often the background task actually ran (iOS decides).
- [ ] **Owner (B, if chosen):** with Edith closed and the phone locked, a ring produces an Apple push within seconds; away from home over mobile data too; PC off → no alerts.

## Risks and unverified assumptions
- iOS background refresh is unreliable by design; A's honest promise is "while open, and a catch-up".
- B depends on Jarvis staying up and the APNs key/entitlement setup (a classic source of "no push arrives": the sandbox vs production environment must match the build profile).
- Ring/Blink request volume every 45 s while open (C33 noted Ring's per-camera history search): back off on errors.

## Not in this chunk
Critical alerts (an Apple special entitlement), alerts for other devices (locks left open, temperature), Android changes.
