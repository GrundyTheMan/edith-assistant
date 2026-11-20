# C73 — Honeywell / Resideo thermostat on iOS

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **Design** — blocked on the owner's developer app approval at Resideo |
| Depends on | C48 (services), C58 (the `edith://` redirect), C46 |
| Unblocks | — |
| Runs in | the development build (the OAuth redirect) |
| Size | Medium |
| Ports | C28 |
| Read first | C28 (the whole short chunk), C21's "Checked on 2026-11-12" (Resideo), C59's PKCE and `openAuthSessionAsync` helpers, C25 §4 (the card rule for thermostats), D10, D19 |

## Why
The owner's thermostat is Honeywell/Resideo, the **one home service with an official API** (`developer.honeywellhome.com`, OAuth 2).
Alexa (C54) can also reach it (D19: "Alexa plus direct": a device both report shows **once**, through the direct service, which
is the reliable route when Alexa breaks).

## Before you start — owner setup (write `docs/resideo-setup.md`; verify wording on the live site)
Create a developer account and an **app** at developer.honeywellhome.com; approval is reportedly **slow** (apply early). When
approved: the **Consumer Key** (and **Consumer Secret** if the app type needs it; **a secret in a phone app is not secret**: if
the API requires one, keep it in the `SecretStore` entered by the owner in Settings, never in the repo or `app.json`). Register the redirect
URI **`edith://resideo`** (verify that the portal accepts a custom scheme; if it demands `https`, stop and ask the owner: a web redirect page would be needed). If approval is still pending, **stop and tell the owner**.

## Specification
- **Sign-in** (`resideo/auth.ts`): Authorization-code flow in `ASWebAuthenticationSession` (C59's helper), PKCE if the portal supports it (else state only), the token endpoint with the consumer key/secret **as the API's docs say (Basic header or form fields: read the docs)**. Cache the **access token until shortly before it expires** (never ask for a token on every call); refresh with the refresh token (check whether it rotates; if so apply C51's save-before-use rule); a refused refresh → `needs_sign_in`.
- **Devices:** `GET /v2/locations?apikey=…` → thermostats (`HomeDevice { type:'thermostat', capabilities:['thermostat'], unit, modes }`). The API's base URL moved in 2026: **keep it in one constant** (`resideo/config.ts`).
- **State:** indoor temperature, humidity, heat/cool setpoints, mode, fan → `DeviceState { temperature, setpoint, mode }` (humidity/fan shown in the tile text only).
- **Commands:** `set_temperature` and `set_thermostat_mode` through C46's `home_control`, with the **card rules already in `actions.ts`** (mode change always; setpoint when the change is over 5 °F / 3 °C). The API's rules on changing a setpoint (it requires the mode and both heat and cool setpoints, and optionally `thermostatSetpointStatus: 'TemporaryHold'`/`'PermanentHold'`): read the docs and choose **TemporaryHold** as the default (a documented decision in the PR). Re-check at 3, 10, 30 s (thermostats are slow).
- **Merging with Alexa (C54):** both report the thermostat; the direct one wins while it answers (`isHub` rule in C46; set `isHub = false` here, `true` for Alexa; names must match after normalisation: add an alias field in Settings → Home → Resideo "Show as" if they differ).
- **Settings → Home → Resideo:** the form (Sign in via the browser sheet), state text, Disconnect (revoke if the API offers it, else delete the token and say where to remove Edith's access).

## Files
`resideo/{auth,api,parse,service,config}.ts`, tests with a pretend Resideo, `docs/resideo-setup.md`; changed `core/services.tsx`.

## Tests
Auth URL and exchange against the pretend server; token caching (no refresh while valid; cleared after a 401); location/device parsing from made-up responses that follow the docs; setpoint command bodies (mode, both setpoints, hold type); the card threshold; the Alexa merge; refresh failure → sign in again.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner:** sign in; "What's the temperature inside?"; "Set the heat to 68" (a card if it is a big change); the Home tab shows one thermostat (not two) when Alexa is connected too.

## Risks and unverified assumptions
- The portal's redirect rules, the token endpoint's secret requirement, and the setpoint API's hold semantics are all from memory: **read the docs first** and write down what they say.

## Not in this chunk
Schedules, geofencing, multiple thermostat zones beyond listing them.
