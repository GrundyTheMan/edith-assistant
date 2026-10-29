# C21 — Smart-home service notes (v2 research backlog)

| | |
|---|---|
| Phase | 6 — **Deferred to v2** |
| Purpose | What's known about each service the owner named, to seed v2 chunk planning |
| Status | **Research notes, not verified for v2.** Every claim marked *(verify)* must be re-checked against current official sources before planning, because vendors change access rules often |

## Services the owner named
Alexa, Ring, Blink, thermostat, garage door opener, locks, "etc."

## Alexa (Amazon)
- No official API lets a third-party app list and control the devices linked to a person's Alexa account
  *(verify)*. The Alexa Smart Home Skill API works in the opposite direction (Alexa controlling *your* devices).
- Jarvis uses **alexa-remote2** (unofficial, Node): a local relay shows Amazon's own sign-in page; Jarvis keeps
  the resulting session encrypted and never the password (`jarvis/src/main/alexa.ts`). There's no Kotlin equivalent.
- v2 paths: through Jarvis (C20 option A), or Home Assistant's community Alexa integrations *(verify current state)*,
  or controlling the underlying devices directly (many Alexa devices are also Matter/Zigbee/Wi-Fi brand devices).

## Ring (Amazon)
- No official public API *(verify)*. Jarvis uses **ring-client-api** (unofficial, Node; the library behind
  homebridge-ring): email + password + 2FA code once; keeps only the refresh token, encrypted, and **must save each
  new refresh token Ring hands out (~hourly) — reusing an old one breaks Ring's alerts for that sign-in**
  (`jarvis/src/main/ring.ts`).
- Capabilities: camera list, snapshots, live view (WebRTC/SIP — complex), ding/motion events.
- Home Assistant has a Ring integration *(verify)*.

## Blink (Amazon)
- Unofficial service. Jarvis opens Blink's own sign-in page in a window and catches the redirect to the phone
  app's address with a one-time code, then trades it for tokens — the flow **blinkpy** (Home Assistant's library)
  uses (`jarvis/src/main/blink.ts`, endpoints `api.oauth.blink.com`, `rest-prod.immedia-semi.com`).
- Capabilities: camera list, snapshots, clips; live view is limited.

## Thermostats (brand not yet specified — ask the owner)
- **Google Nest:** official **Smart Device Management (SDM) API** via Google's Device Access program (one-time
  registration fee for developers; OAuth) *(verify fee and terms)*.
- **ecobee:** had a public developer API; new developer registrations were restricted at some point *(verify current status)*.
- **Honeywell/Resideo:** has a developer program *(verify)*.
- Most are also available through Home Assistant.

## Garage door openers (brand not yet specified — ask the owner)
- **Chamberlain/LiftMaster myQ** blocked third-party integrations (including Home Assistant's) in late 2023
  *(verify)*. Common workaround: a local controller such as **ratgdo** (ESPHome/MQTT) wired to the opener,
  integrated through Home Assistant — no cloud dependency.
- Others (Genie Aladdin Connect, Tailwind, Meross) vary *(verify)*.

## Locks (brand not yet specified — ask the owner)
- **August/Yale:** official API historically partner-only; Home Assistant integration exists *(verify)*.
- **Matter/Thread locks:** controllable through a Matter controller (Home Assistant, Google Home, SmartThings).
- **SmartThings:** official REST API; personal access tokens have limited lifetimes now *(verify)*.
- Security: unlocks always need an on-screen confirmation (C20 §5).

## Questions to ask the owner at v2 start
1. Which thermostat, garage door opener and lock brands/models do you have?
2. Do you already run, or are you willing to run, Home Assistant? Where (PC, Raspberry Pi, NAS)?
3. Should Edith control the home only while your PC (Jarvis) is on, or always?
4. Which actions should never be possible by voice alone? (Recommended: unlock, garage open.)
