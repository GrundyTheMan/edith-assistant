# C70 — Meross cloud control on iOS (MQTT over TLS), so the garage works away from home

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **Design** (decision D33: build it) |
| Depends on | C50 (Meross sign-in, devices, signed envelopes), C58 (a development build with `react-native-tcp-socket`) |
| Unblocks | the garage and shed doors from anywhere (D10 "it must work **always**") |
| Runs in | the development build only |
| Size | Large |
| Ports | C26's MQTT part (`PahoMerossBus`) |
| Read first | C26 spec **and** As built (the protocol paragraph, one short-lived TLS session per operation, `sign` checked on every answer, the traps), C50, `meross-iot` (`MerossManager`, the MQTT client: read it for topics, client id, and keep-alive), C65's `net/socket.ts` (the injected `Socket`/`Connect` and `net/fakeServer.ts`) |

## Why
C50 controls the doors **on the home Wi-Fi**. The owner wants them from anywhere. Meross's cloud exposes the same devices through
an **MQTT broker over TLS on port 443** (C26 As built: user = user id, password = `md5(userId + key)`; `GET Appliance.System.All`;
`SET Appliance.GarageDoor.State`; ids `uuid#channel`). React Native has no MQTT-over-TCP in Expo Go, and the popular `mqtt`
package on RN speaks **WebSocket only**. So: the socket module from C58 plus a **minimal MQTT 3.1.1 client** in TypeScript.

## Before you start (pre-checks; record results)
1. **Try WebSocket first** (cheaper if it works): does Meross's broker (`mqttDomain` from the sign-in reply) accept `wss://<mqttDomain>:443/mqtt` (or `/`)? Test with `mqtt` over RN's built-in `WebSocket` from a throwaway screen **with the owner present** (it uses their real token: the agent never sees or logs it; the test prints only "connected"/"refused"). If it works, use `mqtt` (pure JS) and skip §2's client.
2. Otherwise prove **TLS TCP to the broker on 443** with `react-native-tcp-socket` (certificate validation **on**; the broker has a public CA certificate: verify the module validates the hostname).

## Specification

### 1. Protocol facts to port (verify each against `meross-iot` and Android's `MerossProtocol.kt`)
- Connect: username = user id, password = `md5(userId + key)`, **client id** `app:<md5 of something the library defines>` (copy the library's rule), clean session, keep-alive 30 s.
- Topics: subscribe to the app's response topic `/app/<userId>-<appId>/subscribe`; publish to the device topic `/appliance/<uuid>/subscribe`. Message envelope = C50's signed envelope with `from` = the app topic; **verify `sign` on every answer** (a wrong `sign` is dropped and reported "Meross answered something Edith couldn't trust").
- Operations are the same as C50 §4: `GET Appliance.System.All` (state) and `SET Appliance.GarageDoor.State`.

### 2. Minimal MQTT 3.1.1 client (`meross/mqtt.ts`) — only if WebSocket fails
Over C65's `Connect`/`Socket`: packet encode/decode for **CONNECT, CONNACK, SUBSCRIBE, SUBACK, PUBLISH (QoS 0 and 1 incl. PUBACK), PINGREQ/PINGRESP, DISCONNECT**; remaining-length varint; keep-alive pings; a read buffer that reassembles packets split across TCP chunks; **one short-lived session per operation** (connect → subscribe → publish → wait for the answer with a 10 s timeout → disconnect), as Android's Paho usage does (a cold connection can fail once: **ask a silent door twice** before "not responding", C26). Pure and unit-tested against byte vectors (compute the expected bytes by hand from the MQTT spec, once, and write them in the test).

### 3. Using it from the backend (`meross/service.ts`)
`HomeBackend.command/state`: **try the LAN first** (C50) when the door's LAN address answers within 2 s, **else the cloud**. The same signing, the same `uuid#channel` ids. The tile no longer says "works on your home Wi-Fi"; it says "via Meross cloud" in the log line. The command log records which route was used. Re-checks (3/10/30 s) as before.
- **Trust rule (C26 review change; C37):** the MQTT host must be the `mqttDomain` from a **checked** sign-in (`https` host under `meross.com`) — never a host from an unvalidated answer.
- Never log the token, key, passwords, topics with user ids, or message payloads.

## Files
New: `meross/mqtt.ts` (or `meross/ws.ts`), `meross/cloudBus.ts`, tests. Changed: `meross/service.ts`, `package.json`/`NATIVE_CHANGES.md` (**needs a new build** if the socket module was not already in the batch).

## Tests
MQTT packet vectors (CONNECT with credentials, SUBSCRIBE, PUBLISH QoS 1, PINGREQ, a CONNACK refusal code, a PUBLISH split across two chunks, a zero-length remaining-length edge); the bus against a scripted broker: success, `sign` mismatch dropped, a silent door asked twice, 10 s timeout, a refused credentials → needs sign-in; route choice (LAN answers → no cloud call; LAN fails → cloud); the host-trust rule; the command card still precedes any publish (drive through the tool loop and assert **no PUBLISH before Confirm**).

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner, on mobile data (Wi-Fi off):** "Is the garage door open?" answers; "Close the shed door" → card → Confirm → the door moves; the command log shows the cloud route; back on Wi-Fi it uses the LAN.

## Risks and unverified assumptions
- Everything about the broker is from the library's source; the first live use is the owner's. A Meross app update can change it: fail with a plain message, **never retry in a loop**.
- The socket module's TLS validation (same pre-check as C65/C66).

## Not in this chunk
Meross plugs and lights, subscribing to push updates, LAN discovery changes.
