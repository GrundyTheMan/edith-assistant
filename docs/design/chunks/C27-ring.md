# C27 — Ring doorbell and cameras (v2)

| | |
|---|---|
| Phase | 8 — Smart home (v2) |
| Depends on | C25 |
| Size | Medium–large |
| Read first | C21 (Ring, and "Checked on 2026-11-12"), C23 trap 1 (rotating refresh tokens), 06-decisions **D10** |

## Why
The owner has Ring. Ring's official API is for business partners only, so Edith uses the flow the maintained
`ring-client-api` (Node) uses, rewritten in Kotlin.

## Specification (verify against `ring-client-api` first)
- **Sign-in:** email, password and the 2FA code, once, typed by the owner; keep only the refresh token, encrypted.
- **Rotating refresh token:** Ring hands out a new one on refresh and the old one stops working soon after. **Save the
  new one before using the access token**, every time (as C23 does for Microsoft); losing it signs the owner out.
- **Devices:** doorbells and cameras (`Camera`, `Doorbell`; `Snapshot`, `Events`), with battery and online state.
- **Snapshots** (`camera_snapshot`) and **event history** (`recent_events`: rings and motion, last 24 h by default).
- **Clips (D19, as Jarvis's `ringClips.ts`):** a **Clips** chip on the Home tab lists recorded clips (newest first,
  older pages on scroll; what made it record: Ring, Motion, Person) and plays one in the app (Media3 ExoPlayer; ask
  the owner before adding the library). No subscription → Ring's own "no plan" message, not an error.
- No live view (C32) and no push notifications (C33) in this chunk.

## Tests
Sign-in with 2FA; the rotated token stored before use; snapshots (JPEG bytes); event parsing; a dead token → Sign in
again; one Ring failure not stopping other backends.

## Acceptance
- [ ] Owner: sign in, "Show me the front door camera" (a snapshot in the bubble), "Did anyone ring today?", the Home
      tab, a clip played from the Clips chip; a restart keeps it signed in.

## Traps
Unofficial; never log tokens or images; snapshots of a sleeping battery camera can be old or slow; say so.

## As built — part 1 of 2 (core; Clips follow in a second PR, agreed with the owner 2026-11-14)
- **Done in part 1:** sign-in with the 2FA code, the rotating refresh token, devices (doorbells and cameras, with battery and
  online state), snapshots (`camera_snapshot`, C25's tool), event history (`recent_events`, new), and Settings → Home (the generic
  service row and sign-in dialog from C26). **Not yet:** the Clips chip and playback (part 2; the owner approved adding Media3
  ExoPlayer for it).
- **Code:** `ring/RingApi` (OkHttp, stateless), `ring/RingService` (a `HomeService` plus the `HomeBackend`); generic additions in `home/`:
  `HomeBackend.events` and `EventKind`/`DeviceEvent`, `HomeBackend.snapshotTimeoutMs` (Ring: 35 s; a battery camera must wake),
  `Home.events`, and the `recent_events` tool (`RecentEventsTool`: `device?`, `hours?` 1–72, default 24).
- **Protocol:** read from `ring-client-api` **v14.3.0** (Jarvis's `node_modules`) on 2026-11-14. `POST oauth.ring.com/oauth/token`
  (`client_id=ring_official_android`, password grant, 2FA code in the `2fa-code` header, HTTP 412 = a code is wanted, 400
  "Verification Code…" = wrong code; then the `refresh_token` grant), `POST clients_api/session` (this phone's hardware id; Ring wants one
  before it answers the rest), `GET clients_api/ring_devices`, `GET app-snaps.ring.com/snapshots/next/{id}?after-ms=…&extras=force`
  (JPEG), `GET clients_api/locations/{loc}/devices/{id}/events`. Headers `Authorization: Bearer …`, `hardware_id`, `User-Agent: android:com.ringapp`.
- **Rotating token (C23 trap 1):** the new refresh token is saved (`SecretStore` `home/ring`: the token and the phone's hardware id, nothing
  else) *before* the access token it came with is used, and refreshes never overlap (a lock): several calls at once would otherwise
  spend the same old token and lose the sign-in. Tests: `RingServiceTest` (saved-before-use, six parallel calls → one refresh).
  A refresh Ring refuses (400/401) is "Sign in again"; an unreachable Ring is not (the owner stays signed in).
- **Found by a test:** a 2FA sign-in makes two or three requests before anything is saved; each must send the **same** hardware id
  (Ring ties the code to it), so one is held in memory until the sign-in completes.
- **Snapshots:** always a *forced new* picture (as Jarvis), so it is new or fails; Edith says why when it can't: camera offline,
  "Record Motion"/motion detection off (Ring then gives no pictures), or no picture within 35 s (battery cameras can't take one while
  streaming). Pictures and tokens are never logged.
- **Events:** the last 50 per camera, filtered to the asked hours, newest first; `ding` = Ring, `motion` (a person when
  `cv_properties` say so), `on_demand` = a live view; other kinds are left out of the answer.
- **Tests:** `RingApiTest` (8), `RingServiceTest` (13), `RingToolsTest` (5), against a pretend Ring that rotates refresh tokens like Ring
  does (`RingTestKit`). `scripts/check.sh`: OK (`build-logs/` newest `*_check.log`).
- **Not verified against the real Ring:** everything above is from the library's source and the pretend Ring. The owner's live check
  (acceptance, first three items): sign in with the 2FA code, "Show me the front door camera", "Did anyone ring today?", the Home tab,
  and a restart that stays signed in. Unconfirmed guesses: that the Ring app lists Edith under Control Center (used in the disconnect
  hint), and the `events` endpoint's page size (50).
