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

## Checked on 2026-11-12 for D10 (directly from the phone)
Web research only (vendor pages, library repositories and issue trackers, community reports); nothing tried against
the owner's accounts yet. Every service below except the thermostat's official API is **unofficial**: it uses the
vendor's own app endpoints, which may be against its terms, and can stop working without notice. Ranked from the
most to the least dependable route.

| Service | Official API for us? | Best route from the phone | Main risks | Verdict |
|---|---|---|---|---|
| **Meross** garage (MSG100-type) | No | Meross cloud (HTTP sign-in + MQTT over TLS), as `meross-iot` (Python, release Jan 2026) does; LAN control needs a key from the cloud and works only at home | Unofficial; MQTT client library needed in Kotlin | **Good**: maintained references, simple device |
| **Ring** | Partner-only (business agreement) | `ring-client-api`'s flow: email + password + 2FA once, then a **rotating refresh token saved on every refresh**; snapshots and event history over REST | Unofficial; token rotation must never lose a token (as C23's Microsoft); live view (WebRTC) out of scope | **Workable** |
| **Honeywell/Resideo** thermostat | **Yes**, `developer.honeywellhome.com` (OAuth, consumer key) | The official API | New developer apps reportedly sit in "pending review" for a long time; the API's address moved in 2026 | **Workable if the key is granted**: the owner should apply now |
| **Blink** | No | blinkpy's OAuth flow | Broke for everyone in July 2026 (406 at sign-in; Blink began rejecting a non-UUID `hardware_id`); fixed in Home Assistant 2026.8 | **Fragile**: expect outages |
| **Alexa** devices | No (the Smart Home Skill API is the other direction) | `alexa-remote2`/`alexa-cookie`: Amazon's own sign-in page, then a cookie/token | Needs app-based 2FA, not SMS/email; maintainers fix login breaks every few months; reported to be on its way out | **Fragile** |
| **Philips** lock (Home Access) | No | Community integrations call the Philips Home Access cloud with a signing key embedded in the app; unlock goes through the Wi-Fi gateway | The key can rotate with any app update; the owner can't be signed in to the Philips app and Edith at once (share the lock to a second account); an unlock path built this way is a security concern | **Fragile, and risky for unlocking** |
| **August** lock | Partner-only | None found: `yalexs` says the public key in community code is **no longer accepted** and a vendor-issued partner key is required; Yale switched off password sign-in (July 2026) | Blocked for an app like Edith | **Not possible now** (check whether the owner's model supports Matter or HomeKit) |

Sources: Resideo/Honeywell — <https://developer.honeywellhome.com>, <https://gbu.im/2026/03/12/resideo-thermostat-reverse-engineering.html>,
<https://community.openhab.org/t/resideo-api-binding-for-honeywell-thermostats-and-sensors/159903>. Meross —
<https://pypi.org/project/meross-iot/>, <https://github.com/albertogeniola/MerossIot>, <https://github.com/krahabb/meross_lan/discussions/63>.
August/Yale — <https://github.com/Yale-Libs/yalexs>, <https://github.com/homebridge-plugins/august-yale>,
<https://help.getseam.com/articles/6502053647-understanding-yale-s-smart-lock-services>. Philips —
<https://github.com/dinhhuy258/ha-philips-lock>, <https://github.com/swils23/philips_home_access>,
<https://community.home-assistant.io/t/support-for-philips-smart-locks/342198>. Alexa — <https://github.com/Apollon77/alexa-remote/>,
<https://github.com/Apollon77/alexa-cookie>, <https://discourse.nodered.org/t/node-red-contrib-alexa-remote2-applestrudel-oauth-enabling/100548>.
Ring — <https://github.com/dgreif/ring/wiki/Refresh-Tokens>, <https://github.com/dgreif/ring/issues/1715>,
<https://developer.amazon.com/docs/ring/api-documentation.html>. Blink — <https://github.com/fronzbot/blinkpy/issues/1266>,
<https://github.com/home-assistant/core/issues/176836>.

**Still to confirm with the owner:** the exact models (Meross garage, Philips lock, August lock, Honeywell thermostat);
whether the August lock has Matter or HomeKit; whether they'll apply for a Resideo developer key; and whether
relying on unofficial vendor endpoints is acceptable for each service.
