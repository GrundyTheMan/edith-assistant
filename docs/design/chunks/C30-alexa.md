# C30 — Alexa devices (v2)

| | |
|---|---|
| Phase | 8 — Smart home (v2) |
| Depends on | C25 |
| Size | Large |
| Read first | C21 (Alexa, and "Checked on 2026-11-12"), Jarvis's `alexa.ts` (reference), 06-decisions **D10** |

## Why
Many of the owner's lights and plugs are linked to Alexa. Amazon has no API for an app to control them (the Smart
Home Skill API works the other way), so Edith uses the approach of `alexa-remote2`/`alexa-cookie` (Node): Amazon's
own sign-in page, then the session it creates. **Fragile:** its maintainers fix sign-in breaks every few months, and
it's reported to be on its way out.

## Before you start
Check the current state of `alexa-cookie` and Jarvis's Alexa sign-in. If Amazon has blocked it, stop and ask the
owner. Consider controlling some devices through their own brand instead (many Alexa devices are also Wi-Fi brands).

## Specification (verify everything against the current `alexa-cookie`)
- **Sign-in:** Amazon's sign-in page in a WebView inside Edith (so its cookies can be kept), with app-based 2FA (SMS
  or email 2FA reportedly fails); keep only the resulting tokens/cookies, encrypted. The password never passes
  through Edith's code.
- **Devices:** the smart-home devices linked to the account: lights, plugs, switches (`OnOff`, `Brightness`), with
  their state.
- **Commands:** on/off and brightness (no card, C20 §2). Routines and speakers are out of scope.

## Tests
Parsing the device list and state from made-up answers; commands; an expired session → Sign in again.

## Acceptance
- [ ] Owner: sign in, "Turn on the living room lamp", "Which lights are on?", the Home tab.
