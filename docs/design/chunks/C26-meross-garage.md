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
- [ ] Owner: sign in, "Is the garage open?", "Close the garage" (card), "Open the garage" (card, tap only), the Home
      tab shows it; the command log has each action.

## Traps
Unofficial: any Meross app update can change it; fail with a plain message, never retry in a loop. Never log the
token, key or MQTT payloads.
