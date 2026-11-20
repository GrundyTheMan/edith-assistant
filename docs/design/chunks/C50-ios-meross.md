# C50 — Meross garage door openers on iOS (sign-in, devices, control on the home Wi-Fi)

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave A |
| Status | **Design** |
| Depends on | C48 (services framework), C46 |
| Unblocks | C70 (cloud control away from home) |
| Runs in | Expo Go |
| Size | Medium |
| Ports | C26 (`meross/`) — **everything except the cloud MQTT**, which needs raw TLS sockets (C70) |
| Read first | C26 spec **and** As built (protocol facts, traps, the review change), C20 §5, C37, `meross-iot` (the reference library, branch `0.4.X.X`; read its source, do not add it), the Kotlin code in `app/src/main/java/com/edith/app/meross/` **as a protocol reference only** (its tests hold md5 vectors worth reusing; do not copy code) |

## Why
The owner's garage door opener (and a shed door) are Meross. On Android Edith signs in to Meross's cloud over HTTPS, then
talks to the cloud's MQTT broker over TLS on port 443, so it works from anywhere (D10 "always"). **iOS in Expo Go has no raw
TCP/TLS sockets**, so cloud MQTT is not possible until the development build (C70). But Meross devices also answer **signed
HTTP requests on the home network**, which `fetch` can do. This chunk delivers everything the owner can use at home on
iPhone: sign in, see the doors and their state, open and close them with the confirmation card, in chat and on the Home tab.
Away from home the doors show "Not reachable: works on your home Wi-Fi" until C70.

## Before you start
1. **Owner:** their Meross account email and password (typed into Edith's form; no 2FA on the owner's account as of 2026-11-14, but support the code step).
2. **Agent pre-checks** (do these first and write what you found in the PR; stop and ask if either fails):
   - Read `meross-iot`'s source for the **local HTTP** path: the endpoint (`POST http://<device-ip>/config`), the envelope (`header` with `messageId`, `method`, `namespace`, `payloadVersion`, `from`, `timestamp`, `timestampMs`, `sign`, and `payload`), and the signing rule (`sign = md5(messageId + key + timestamp)` where `key` is the **user key** returned by sign-in). Confirm with the library, not memory.
   - Confirm that the cloud sign-in works from the owner's phone without a VPN (Blink's sign-in failed behind the owner's NordVPN; Meross has not shown this, but note the symptom).

## Specification

### 1. Protocol module (`meross/protocol.ts`, pure)
Port from the reference, with the **public signing salt** that `meross-iot` contains (it is not a secret of the owner's; keep it in one constant with a comment naming the source file):
- `signedEnvelope(params, payload, nonce, timestampMs)` for the cloud API (`sign = md5(salt + timestamp + nonce + base64(JSON(params)))`) and `localEnvelope(namespace, method, payload, key)` for the device.
- MD5: use `expo-crypto`? It supports `digestStringAsync(CryptoDigestAlgorithm.MD5, …)` (check the SDK). If MD5 is missing, add the small pure-JS `md5` implementation in `core/md5.ts` with tests against RFC 1321 vectors. Do **not** add a native module.
- Pure functions, 100% unit-tested with the vectors from Android's `MerossProtocolTest` (recompute each with `md5sum` first; do not trust the Kotlin test).

### 2. Cloud API (`meross/api.ts`) over `secureFetch` (allowed host suffix `meross.com` only, C37/C48)
- `signIn(email, password, mfaCode?)` → `v1/Auth/signIn`. The **password is sent as its md5** (the API's rule); never store it. Follow **at most one region redirect** and only to an `https://…meross.com` host (`isTrustedDomain`), as C26's review change requires; otherwise stop and send nothing further.
- `devList(token)` → `v1/Device/devList`; `logout(token)` → `v1/Profile/logout` (return the token on Disconnect and on "Sign in again", Meross limits tokens per account).
- Errors: wrong password, MFA required (a specific code in the reply: read it from the library) → `needs_code` form; "token expired" → `needs_sign_in`; anything unreachable → `error`. Plain words, no retry loops.
- Saved (`SecretStore` key `home.meross`, JSON): `token`, `key`, `userId`, `apiDomain`, `mqttDomain`. **Not** the password.

### 3. Devices
- Keep only garage door openers (`deviceType` starting `msg`, as Android does). `HomeDevice { id: 'uuid#channel', name, room: 'Garage' or the device's room name if the list gives one, type: 'garage_door', capabilities: ['open_close'], unit: undefined }`.
- **LAN address per device.** Meross's cloud list has no LAN IP. Find it by **scanning** the phone's /24: `expo-network`'s `getIpAddressAsync()` gives the phone's IP; probe `http://<a.b.c.1..254>/config` with a signed `GET Appliance.System.All`, 20 at a time, 1.5 s timeout each, and match the reply's `all.system.hardware.uuid` to a listed device. Store `{uuid: ip}` in the key-value store (`meross_lan_ips`; not a secret). Rescan when a stored address stops answering. Provide **Settings → Home → Meross → "Find on my Wi-Fi"** and a manual "Address" field per device as a fallback. iOS will show its **Local Network** permission prompt on the first probe (Expo Go already holds it for Ollama? Verify: it is per app, so the prompt may appear again; say so to the owner).
- `HomeBackend.timeoutMs = 14_000`; a door that doesn't answer is asked **twice** before "not responding" (the cold-connection lesson in C26).

### 4. State and commands (over the LAN)
- State: `GET Appliance.System.All` → `all.digest.garageDoor[{channel, open, lmTime}]`: `open: 1` = open, `0` = closed; `lmTime` (seconds since epoch of the last move) → `DeviceState.changedAt` ("moved 17 h ago"); a door that reports no `lmTime` shows no "moved" (the shed door).
- Command: `SET Appliance.GarageDoor.State` with `{"state":{"channel":N,"open":0|1,"uuid":"<uuid>"}}`. Open and close use C25's rules already in `actions.ts` (`confirmationFor`: open and close always a card). A door takes 15–30 s to travel: report `opening`/`closing` and re-check at 3, 10 and 30 s (C46's `RECHECK_SLOW_MS` covers `garage_door`).
- Check the reply's `sign` on every answer (as Android does); a wrong `sign` is "Meross answered something Edith couldn't trust" and no state change.
- **Away from home** (the phone isn't on the door's network, no LAN address answers): the tile says "Not reachable. It works on your home Wi-Fi." with the last known state and its age, **no error banner** (like C34's printer); commands answer the same sentence. C70 removes this limit.

### 5. The service (`meross/service.ts`)
Implements `HomeService` (C48): form fields `email` (keyboard email), `password` (secret), then the `code` step on `needs_code`. `restore()` loads `home.meross`; `disconnect()` calls `logout`, then deletes the secret and the LAN map. `backend()` returns the `HomeBackend` above. Register in `createServices`.

### 6. Tools
No new tools: `home_devices` and `home_control` already cover it (C46). The `home_control` description already names open/close. Verify "Is the garage door open?" and "Open the garage door" (card) end to end with the Demo-style fake.

## Files
New: `meross/protocol.ts`, `meross/api.ts`, `meross/lan.ts` (scan + signed local calls), `meross/service.ts`, `core/md5.ts` if needed, tests next to each (`protocol.test.ts`, `api.test.ts` with a pretend Meross cloud that **checks every sign**, `lan.test.ts` with a pretend device, `service.test.ts`). Changed: `core/services.tsx`, `ui/HomeSettings.tsx` ("Find on my Wi-Fi", per-device address), `package.json` (`expo-network`, maybe `expo-crypto`), `app.json` (`NSLocalNetworkUsageDescription` is a development-build setting; in Expo Go nothing to do).

## Tests
Signing vectors; sign-in with and without MFA; a wrong password; a region redirect to an allowed host, to `http`, and to a foreign `https` host (must stop before sending credentials); device-list filtering; the LAN scan against a fake network (matching uuid, timeouts, concurrency limit); state parsing (open/closed/opening, `lmTime`); the open command sends the exact payload only **after** the card is confirmed (drive it through `runToolTurn` with a fake model call); a bad `sign` is rejected; away-from-home wording; disconnect returns the token.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner, on the iPhone at home:** Settings → Home → Meross → sign in; "Find on my Wi-Fi" finds both doors; the Home tab shows them with "moved … ago"; "Is the garage door open?"; "Close the shed door" → the card → Confirm → the door moves; the command log has it. Away from home the tile says it works on home Wi-Fi.
- [ ] Disconnect then sign in again works (the token is returned and a fresh one issued).

## Risks and unverified assumptions
- The local HTTP protocol and whether the owner's firmware answers it. If the doors don't respond over HTTP, the chunk ships **sign-in and listing only** and says so; control waits for C70.
- A /24 scan on iOS: the Local Network permission prompt, and whether `fetch` to many addresses in parallel is throttled. Keep the limit at 20.
- The cloud region/host names change; keep them in one module.

## Not in this chunk
Cloud MQTT (C70), Meross plugs and lights (Android lists garage openers only), 2FA live test.
