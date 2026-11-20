# C51 — Ring on iOS (sign-in with 2FA, doorbells and cameras, snapshots, events)

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave A |
| Status | **Design** |
| Depends on | C48 (services), C49 (pictures from tools; `camera_snapshot`) |
| Unblocks | C53 (clips), C71 (live view), C72 (alerts) |
| Runs in | Expo Go |
| Size | Large |
| Ports | C27 part 1 (`ring/`) |
| Read first | C27 spec **and** As built (both parts: the protocol, the rotating-token rule, the found-live bugs), C23 trap 1 (rotating refresh tokens), C25 §4, `ring-client-api` source (the reference; v14.3.0 was used for Android; the owner's Jarvis has it in `node_modules`) |

## Why
The owner has Ring. Ring's official API is partner-only; Edith uses the same flow `ring-client-api` uses. It is all HTTPS
JSON, so it works in Expo Go. This chunk is the sign-in, the device list, **snapshots** (a picture in Edith's bubble) and
**event history** ("Did anyone ring today?"). Clips are C53, live view C71, alerts C72.

## Before you start
- **Owner:** Ring email, password and the 2FA code Ring sends (typed into Edith's form). Ring may list Edith as a new "device" under Control Center.
- **Agent pre-check:** read `ring-client-api`'s `rest-client.ts` and `ring-types` for the current OAuth endpoint, client id and headers; confirm against the Android constants in `ring/RingApi.kt` (reference only). If the endpoints changed, stop and ask the owner.

## Specification

### 1. API (`ring/api.ts`, over `secureFetch` with allowed hosts `ring.com`)
Endpoints and headers as in C27's As built: `POST oauth.ring.com/oauth/token` (`client_id` as the library, password grant; the 2FA code goes in the **`2fa-code` header**; **HTTP 412 = a code is wanted**; **400 "Verification Code…" = wrong code**; then the `refresh_token` grant), `POST clients_api/session` (the phone's `hardware_id`; Ring wants it before it answers anything else), `GET clients_api/ring_devices`, `GET app-snaps.ring.com/snapshots/next/{id}?after-ms=…&extras=force` (returns the JPEG), `GET clients_api/locations/{loc}/devices/{id}/events`. Headers: `Authorization: Bearer …`, `hardware_id`, and a `User-Agent` copied from the library's mobile value (the Android build sends `android:com.ringapp`; use the library's iOS string if it has one, else keep the same).
- **`hardware_id`**: a random UUID made **once per install** and stored with the token. A 2FA sign-in makes 2–3 requests before anything is saved; **every one must carry the same `hardware_id`** (Ring ties the code to it): hold it in memory until sign-in completes.
- Use `expo-crypto`'s `randomUUID()` (or `Crypto.randomUUID`).

### 2. Rotating refresh token (the most important rule)
Ring returns a **new refresh token on every refresh and the old one stops working soon after.**
- **Save the new token to the SecretStore (`home.ring`, JSON `{refreshToken, hardwareId}`) BEFORE using the access token it came with**, every time.
- **Refreshes never overlap**: one in-flight refresh promise; callers await it (several tool calls at once would otherwise spend the same old token and lose the sign-in). Test: six parallel calls → one refresh.
- A refresh Ring refuses (400/401) → `needs_sign_in`. An unreachable Ring is **not** a sign-out (`error`, stay signed in).
- Never log a token, the hardware id's pairing with a token, a picture or an event title.

### 3. Devices
`ring_devices` → doorbells (`doorbots`, `authorized_doorbots`) and cameras (`stickup_cams`). Each → `HomeDevice { id: 'ring:<id>:<kind>', name, room: location name, type: 'doorbell' | 'camera', capabilities: ['snapshot','events'], online, state: { battery } }`. Battery percentage from `battery_life` when present. Ignore chimes, base stations, sensors.

### 4. Snapshots (`HomeBackend.snapshot`, `snapshotTimeoutMs = 35_000`)
Always a **forced new picture** (as Jarvis): `snapshots/next/{id}?extras=force`, so the picture is new or the call fails. Return `{jpegBase64, takenAt: now}`. When it fails, say **why** in words: camera offline; "Record Motion"/motion detection off (Ring then gives no pictures); no picture within 35 s (battery cameras can't take one while streaming). C49's `camera_snapshot` shows it.

### 5. Events (`HomeBackend.events`) and the `recent_events` tool
- `events(deviceId | null, sinceMs)`: the last 50 per camera, filtered to the asked hours, newest first. Kinds: `ding` → `ring`; `motion` → `motion` (`person` when `cv_properties` say a person); `on_demand` → `live` (a live view started); others dropped.
- New tool **`recent_events`** (added to `home/homeTools.ts` here, enabled while any visible device has `'events'`): `device?` (name), `hours?` (1–72, default 24). Output: `Thu 3:42 PM — Front door — Ring` lines, newest first, at most 20, "No events in the last 24 hours." Read-only, no card.

### 6. The service (`ring/service.ts`)
`HomeService`: form fields `email`, `password` (secret); second step `code` (keyboard number, `textContentType: oneTimeCode`). Messages: wrong password, wrong code ("That code didn't work"), Ring unreachable. `restore()` reads `home.ring`; a missing token = `signed_out`. `disconnect()` forgets the token (Ring has no logout call that matters; do what the library does) and deletes the secret. Disconnect hint: "You can also remove Edith in the Ring app under Control Center" — **say "may be listed"**, it's unconfirmed.

## Files
New: `ring/api.ts`, `ring/parse.ts` (pure: devices, events), `ring/service.ts`, tests `ring/api.test.ts` (a **pretend Ring that rotates refresh tokens exactly like the real one** and checks the hardware id on every request), `ring/service.test.ts`, `ring/tools.test.ts`. Changed: `home/homeTools.ts` (`recent_events`), `core/services.tsx`.

## Tests
2FA sign-in (412 → code → success), wrong code, wrong password; the **same hardware id on every request of a sign-in**; the rotated token is saved before use; six parallel refreshes → one; a refused refresh → needs_sign_in once, no retry loop; unreachable ≠ signed out; snapshot success / camera offline / motion-detection-off / timeout (35 s fake timer); device parsing (doorbell, cameras, battery, ignoring others); events (kinds, hours filter, person detection, limit); `recent_events` output; one Ring failure does not empty the home (C46 registry test pattern).

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner, on the iPhone:** Settings → Home → Ring → sign in with the 2FA code; the doorbell/cameras appear on the Home tab with battery; "Show me the front door camera" → a picture in Edith's bubble; "Did anyone ring today?" answers from the history; after closing Expo Go completely and reopening, Ring is still signed in (secrets survive; **note**: in Expo Go the Keychain items belong to Expo Go, so deleting Expo Go signs Ring out).

## Risks and unverified assumptions
- Everything is from the library's source and a pretend Ring; nothing has run against the real Ring on iOS. The Android build's first live use found a 404 on one clip endpoint (C53 inherits that).
- Ring may treat an unknown `hardware_id`/User-Agent from a new "device" with an extra verification; document what the owner saw.

## Not in this chunk
Clips (C53), live view (C71), alerts (C72), chimes and sensors.
