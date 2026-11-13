# C20 — Smart home: architecture (v2 — design now, build later)

| | |
|---|---|
| Phase | 6 — **Deferred to v2** (decision A8). Do not implement in v1. |
| Depends on | C08 (tools + confirmations), C17 pattern (Integration) |
| Size | Design: small. Implementation: large, per backend |
| Read first | 01-architecture §12; C21 (service notes); 06-decisions **D10** |

## Goal

Define how Edith will see and control the owner's home — Alexa-linked devices, Ring and Blink cameras,
thermostats, garage door openers, locks and similar — so that v1's tool framework and integration interface
already fit, and v2 can be built in independent chunks.

## 1. Device model (backend-independent, `home/`)
```kotlin
data class HomeDevice(val id: String, val name: String, val room: String?, val type: DeviceType,
                      val capabilities: Set<Capability>, val backend: String, val online: Boolean)
enum class DeviceType { Light, Switch, Plug, Thermostat, Lock, GarageDoor, Camera, Doorbell, Sensor, Speaker, Other }
sealed interface Capability {
    data object OnOff : Capability
    data class Brightness(val min: Int = 0, val max: Int = 100) : Capability
    data class Thermostat(val modes: Set<ThermostatMode>, val unit: TempUnit) : Capability
    data object LockUnlock : Capability
    data object OpenClose : Capability            // garage doors, covers
    data object Snapshot : Capability             // cameras
    data object LiveView : Capability
    data object Events : Capability               // motion/ring history
}
sealed interface DeviceState { /* on/off, level, temperature + setpoint + mode, locked/unlocked/jammed,
                                  open/closed/opening/closing, battery, last event time, … */ }
interface HomeBackend : Integration {           // one per backend (Jarvis bridge, Home Assistant, direct service)
    suspend fun devices(): Outcome<List<HomeDevice>>
    suspend fun state(deviceId: String): Outcome<DeviceState>
    suspend fun command(deviceId: String, command: DeviceCommand): Outcome<DeviceState>
    suspend fun snapshot(deviceId: String): Outcome<ByteArray>   // JPEG
}
```
Name resolution ("the porch light", "downstairs thermostat") happens in the tool layer with fuzzy matching on
name + room; ambiguous → the tool returns the candidates and Edith asks.

## 2. Tools (v2)
| Tool | Confirmation |
|---|---|
| `list_devices(room?, type?)` | no |
| `get_device_state(device)` | no |
| `set_power(device, on)`, `set_brightness(device, percent)` | no (low risk) — owner may opt in to confirm |
| `set_thermostat(device, temperature?, mode?)` | **yes** if the change is > 5°F/3°C or changes mode |
| `lock(device)` | no (making things safer) |
| `unlock(device)` | **always yes**, and **never from hands-free without an on-screen tap** (security: voice can be spoofed) |
| `open_garage(device)` / `close_garage(device)` | **open: always yes, on-screen only**; close: yes |
| `camera_snapshot(device)` | no — the image is shown in Edith's bubble and passed to the model only if it can see (vision) |
| `recent_events(device?, hours=24)` | no |
Status labels: "Checking the house…", "Turning on the porch light…", "Getting a snapshot from the doorbell…".

## 3. Backend options (decision D10 at v2 start — present these to the owner)
| Option | How | Pros | Cons |
|---|---|---|---|
| **A. Through Jarvis on the PC** | Add a small authenticated local HTTP API to Jarvis exposing its existing Alexa/Ring/Blink integrations; Edith calls it on the LAN | Jarvis already signs in to Alexa, Ring, Blink (C21); least new reverse-engineered code | Works only while the PC and Jarvis run; Jarvis needs new code (separate project); no thermostat/garage/lock support today |
| **B. Through Home Assistant** | Run Home Assistant (e.g. on the PC, a Pi or a NAS); Edith uses HA's REST/WebSocket API with a long-lived token | One API for hundreds of device types (thermostats, locks, garage via local hardware, Matter, Ring, Blink, Alexa-media); community-maintained | Another system to run and maintain; HA setup is the owner's job |
| **C. Direct from the phone** | Re-implement each vendor's (often unofficial) protocol in Kotlin | No other system needed | Largest, most fragile code; several vendors actively block third parties (C21) |
Recommendation for the v2 discussion: **B for breadth (especially thermostat/garage/locks), optionally A for Alexa if HA can't cover it**. Not decided.

## 4. UI (v2)
A **Home** destination in the bottom bar: rooms → device tiles (state + one-tap safe actions), camera snapshots,
a lock/garage section with explicit buttons. Edith's chat answers can include a device card with the same controls.

## 5. Security rules (binding for v2)
- Backend tokens via `SecretStore`; LAN APIs require a token even on the home network.
- Unlock and garage-open require an on-screen confirmation tap every time (C08 confirmation), never auto-approved,
  never triggered by hands-free voice alone.
- Log commands (device + action + result) locally for the owner's audit view; never log camera images.

## Acceptance for this chunk (design only)
- [ ] Owner has reviewed sections 1–5 and decided D10 (record in 06-decisions) before any v2 chunk is written.

## Decided (2026-11-12)
- **D10 = C, directly from the phone** (06-decisions): no Jarvis bridge, no Home Assistant (the owner won't run it),
  and the home must work whether or not the PC is on. Each service gets its own `HomeBackend` in Kotlin.
- **Never by voice alone:** unlocking a door and opening the garage (§5, as recommended). Thermostat changes and
  closing the garage keep §2's cards and can be confirmed by voice in hands-free.
- **The owner's devices:** Honeywell/Resideo thermostat, Meross garage door opener, August and Philips locks, and
  Alexa devices, Ring and Blink (C21).
- **What option C means:** no Node libraries can be reused. Jarvis's approaches (alexa-remote2, ring-client-api,
  blinkpy's flow) are references only; each service's sign-in, token refresh (Ring's rotating refresh token, C21)
  and API is written and maintained in Kotlin. Services without an official API can break at any time, so each
  backend is its own chunk and fails on its own (one broken service never stops the others).

## Jarvis parity (D19, 2026-11-13)
Before C25 started, Jarvis's home code (`jarvis/src/main/alexa.ts`, `ring*.ts`, `blink*.ts`,
`src/renderer/src/lib/alexaTools.ts`, `homeTiles.ts`, `HomeView.tsx`) was compared with this design. The owner chose:
- **Alexa: Jarvis's full control** (C30): on/off, brightness, fan speed (percentage), colour, white tone, thermostat
  setpoint and mode, lock and unlock. Jarvis asks before any lock, garage, security-panel or camera command; Edith
  keeps §2's rules instead (lock needs no card; unlock always does, and never by voice alone).
- **Alexa plus direct:** devices linked to Alexa (August locks included) work through Alexa; C28 (Resideo) and C31
  (Philips) stay as a second route. A device both report shows once, through the direct service while it answers.
- **Tools in Jarvis's shape** (replaces §2's list of one tool per action): `home_devices(filter?)` lists devices with
  their state; `home_control(device, action, value?)` acts on one; cameras add `camera_snapshot` and `recent_events`.
  §2's confirmation rules apply per action. Fewer tools suit a 12B local model better than eleven.
- **Home tab as Jarvis's Home page:** chips (Favorites, Lights, Plugs and switches, Fans, Climate, Locks and doors,
  Cameras, and Clips once a camera chunk adds it, All), starred favourites, hidden devices (also hidden from Edith),
  a state re-check after a command (clouds lag: 3 s, 10 s, 30 s for thermostats and locks), the last-known state
  with its age, thermostat steps sent after a short pause, fan speed presets.
- **Camera clips** (browse and play): in C27 (Ring) and C29 (Blink). **Live view** and a wall of every camera: C32.
  **Alerts** (doorbell, person, motion notifications with a snapshot; tapping opens the clip): C33. **3D printer
  card** (Moonraker): C34, after the home chunks.
- **Not matched:** Jarvis's scheduled tasks (Edith has no scheduler), Windows notifications (C33 uses Android's).

## Before any v2 chunk is written
1. Verify each service's current access against official sources (C21's *(verify)* items), plus Meross and the
   Philips lock, which C21 doesn't cover yet. Record per service: official API or not, sign-in, what's possible
   (state, control, snapshots, events), terms that forbid it.
2. From that, split v2 into chunks (likely: the `home/` core with the device model, tools and Home tab; then one
   chunk per service, most reliable first) and add them to the design README's table.
