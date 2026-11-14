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

## As built — part 2 of 2 (Clips)
- **Done:** a **Clips** chip on the Home tab (shown only while a connected service has clips: `HomeBackend.hasClips`) opens a full-screen
  list, newest first: camera, what made it record (Ring, Motion, A person, Live view) and its length, with "Today 3:42 PM". Tapping a
  clip plays it in the app with **Media3 ExoPlayer 1.11.1** (`media3-exoplayer` and `media3-ui`; the owner approved adding it,
  2026-11-14). Older clips load as the list is scrolled.
- **Code:** `HomeBackend.clips(sinceMs, untilMs)` / `clipUrl(clipId)` / `hasClips`, `Home.clips` (all services' clips together, one
  failing doesn't hide the others) and `Home.clipUrl`, `ClipsUi` and the clip functions of `HomeViewModel`, `home/ui/ClipsScreen.kt`;
  Ring: `RingApi.clips` (`video_search/history`, per camera) and `RingApi.clipUrl` (`dings/{id}/recording?disable_redirect=true`).
- **Paging:** the list loads **3-day windows**, going back up to **60 days**; a quiet window loads the next older one (up to 4 per scroll)
  so the list isn't empty while older clips exist. No numbered pages: Ring's search takes a date range.
- **The video address is asked for at play time**, not kept from the list: Ring's addresses are signed and expire (Jarvis re-asks after a
  403). Only an `https` address is played. The player is released when the dialog closes, so a clip never plays on behind it.
- **No plan:** Ring keeps recordings only with Ring Protect. The search still lists events, with `had_subscription` false: if every
  recent event has it false, Edith says so in words ("Ring keeps recordings only with a Ring Protect plan, and this account doesn't have one")
  and stops looking further back; a plan that began recently shows only what it recorded. It is never shown as an error.
- **Left out on purpose:** thumbnails in the list (Ring's thumbnail addresses need their own signed fetch; Coil is in the app if the
  owner wants them later) and downloading or sharing a clip.
- **Tests:** `RingApiTest` (+2), `RingServiceTest` (+5), `ClipsTest` (5: aggregation, a failing or hanging service, no-plan, the clip
  address, the words). `scripts/check.sh` OK; `:app:minifyReleaseWithR8` builds with Media3 in the app.
- **Not verified:** playback itself. Nothing here has played a real Ring clip, and the Compose list and player were not run on the emulator
  or in a release build. **Per the project rule, a release build must be checked on `Edith_Release_Test` before publishing** (R8 broke ONNX
  Runtime once; it accepts Media3, but only playing a clip proves the keep rules). Andarium on the iPhone may lack a video decoder or
  behave differently: the player then shows "This phone couldn't play the clip (…)" and logs the code.
