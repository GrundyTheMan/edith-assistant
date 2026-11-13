# C28 — Honeywell/Resideo thermostat (v2)

| | |
|---|---|
| Phase | 8 — Smart home (v2) |
| Depends on | C25, C23 (`BrowserSignIn`, `OAuthProvider`); **a Resideo developer key** (owner setup, can take weeks) |
| Size | Medium |
| Read first | C21 "Checked on 2026-11-12" (Resideo), C17b (browser sign-in), 06-decisions **D10** |

## Why
The owner's thermostat is Honeywell/Resideo, the one service with an **official** API (`developer.honeywellhome.com`,
OAuth). Approval of a developer app is reportedly slow, so the owner applies before this chunk starts.

## Before you start — owner setup (write it as `docs/resideo-setup.md`)
Create a developer account and an app at developer.honeywellhome.com; once approved, put the Consumer Key (and Secret,
if the app needs it) in `local.properties`. If it's still pending, stop and ask the owner.

## Specification
- **Sign-in:** Resideo's OAuth in the browser, reusing C23's `BrowserSignIn` and `OAuthProvider` (a new provider
  config). Cache the access token until shortly before it expires; never ask for a token on every call.
- **Devices:** locations → thermostats (`Thermostat` capability with its modes and unit).
- **State:** indoor temperature, humidity, setpoints, mode, fan. **Commands:** `set_thermostat` with C20's card rule
  (> 5 °F / 3 °C or a mode change).
- The API's address moved in 2026: keep the base URL in one place.

## Tests
The OAuth provider config; token caching (no refresh while it's valid; cleared after a 401); state parsing; setpoint
commands; the card threshold.

## Acceptance
- [ ] Owner: sign in, "What's the temperature inside?", "Set the heat to 68" (a card if it's a big change), the Home tab.
