# C26 — Meross garage door opener (v2)

| | |
|---|---|
| Phase | 8 — Smart home (v2) |
| Depends on | C25 |
| Size | Medium (plus an MQTT library) |
| Read first | C21 "Checked on 2026-11-12" (Meross), C20 §5, 06-decisions **D10** |

## Why
The owner's garage door opener is a Meross. Meross has no official API, but its cloud protocol is simple and well
understood (the `meross-iot` Python library, maintained in 2026). It's the most dependable service, so it's the first
real backend.

## Specification (verify each point against `meross-iot` before building; it's the reference, not a dependency)
- **Sign-in:** Settings → Home → Meross: email and password (typed by the owner), Meross's HTTP sign-in, keeping only
  the returned token and key, encrypted (`SecretStore`). The password isn't stored. 2FA, if Meross asks for it.
- **Devices:** list the account's devices; keep the garage door openers (`DeviceType.GarageDoor`, `OpenClose`).
- **State and commands:** over Meross's cloud MQTT (TLS), as the reference does; open/close with C20's rules
  (open: always a card, never voice alone; close: a card). Report opening/closing/open/closed and when it last moved.
- **Library check first:** an MQTT client (e.g. Eclipse Paho or HiveMQ) on Android 17 and under R8 in a release
  build, as C24 did for Jakarta Mail; ask the owner before adding it if it needs keep rules beyond the usual.
- Optional later: LAN control at home with the device key (the cloud is still needed when away, D10 "always").

## Tests
The sign-in and message signing against made-up captures that follow the reference; state parsing; open needing an
on-screen confirmation; a refused token → Sign in again.

## Acceptance
- [x] Owner: sign in, "Is the garage open?", open and close (card, tap only), the Home
      tab shows it; the command log has each action. (Done with the shed door from the Home tab; chat open/close and the Garage Door
      itself were not moved: see "Not tried live".)

## Traps
Unofficial: any Meross app update can change it; fail with a plain message, never retry in a loop. Never log the
token, key or MQTT payloads.

## As built (2026-11-14)
- **Code (`meross/`):** `MerossProtocol` (signing, topics, messages: pure), `MerossApi` (OkHttp: sign-in with one region redirect and 2FA
  codes, device list, sign-out), `PahoMerossBus` (Eclipse Paho 1.2.5: one short-lived TLS session per operation, `sign` checked on every
  answer), `MerossService` (a `HomeService` plus the `HomeBackend`). Generic additions from C26: `home/HomeService.kt` (`ServiceState`, sign-in form
  types), `Home.register(service)` / `services`, `HomeBackend.timeoutMs` (Meross 14 s), `DeviceState.changedAt` ("moved 17 h ago"), and a
  service row with Sign in / Sign in again / Disconnect plus one shared sign-in dialog in Settings → Home (C27–C31 reuse them).
- **Protocol:** read from the `meross-iot` source (branch `0.4.X.X`) on 2026-11-14: HTTP `v1/Auth/signIn`, `v1/Device/devList`, `v1/Profile/logout`
  with the signed envelope; MQTT on port 443 with user = user id, password = `md5(userId + key)`; `GET Appliance.System.All` →
  `all.digest.garageDoor[{channel, open, lmTime}]`; `SET Appliance.GarageDoor.State {"state":{"channel","open","uuid"}}`. Ids are `uuid#channel`.
  The signing salt in the code is the public one from that library, not a secret of the owner's.
- **Saved:** only the token, key, user id and region addresses, encrypted (`SecretStore` key `home/meross`); the password is used once and never
  kept; Disconnect returns the token to Meross (it limits how many an account may hold) and deletes everything.
- **R8:** Paho finds its TCP and TLS modules through `ServiceLoader`, which R8 removed; `app/proguard-rules.pro` now keeps the library
  (verified: the factories are in `mapping.txt` and the services file is in the release APK).
- **Tests:** `MerossProtocolTest` (6, vectors worked out with `md5sum`), `MerossApiTest` (8, a pretend Meross that checks every `sign`),
  `MerossServiceTest` (13), `PahoMerossBusTest` (6, the real Paho client against a scripted MQTT broker), `HomeActionsTest` (+1),
  `ui/settings/HomeSettingsTest` (2, device). `scripts/check.sh`: 498 unit tests OK (`build-logs/2026-11-14_12-40-46_check.log`); `--device`: 123 OK
  (`build-logs/2026-11-14_12-07-26_check.log`).
- **Live, with the owner's account, release build (R8) on `Edith_Release_Test`:** sign-in worked (no 2FA asked); the Home tab and chat
  ("Is the garage door open") read "Garage Door" (closed, "moved 17 h ago") and "Shed Door"; the first read once showed the shed door "not
  responding" (cold connection), so a silent door is now asked twice; the owner **opened and closed the shed door** from the Home tab
  (`Command open … ok`, `Command close … ok`), and the door moved; the saved sign-in survived a reinstall. No crash.
- **Not tried live:** opening or closing through a chat message (the Demo home covers the card path: C25), "Sign in again" after Meross revokes
  the token, an account with 2FA, and the Garage Door's open/close (only the shed door was moved).
