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
- **Devices (D19, Jarvis's full set):** every smart-home device linked to the account, with its state: lights,
  plugs, switches, fans, thermostats, temperature sensors, locks (August included), garage doors and cameras (listed
  only; Jarvis can't control those two through Alexa either). `isHub = true`: a device a direct backend also reports
  is shown through that one (C25).
- **Commands:** Jarvis's `ALEXA_ACTIONS`: on/off, brightness, percentage (fans), colour, white tone, setpoint, mode,
  lock, unlock, under C25's card rules (unlock: always a card, never by voice alone). Each command is checked against
  Alexa's answer (Jarvis's `controlProblem`). The temperature scale follows the Amazon site (`.com` → °F).
- **State:** Alexa's state query in batches (Jarvis's `stateBatches`); a device that doesn't answer keeps its last
  known state with its age. Routines and speakers are out of scope.

## Tests
Parsing the device list and state from made-up answers; commands; an expired session → Sign in again.

## Acceptance
- [ ] Owner: sign in, "Turn on the living room lamp", "Which lights are on?", "Set the thermostat to 70",
      "Lock the front door" (the August lock through Alexa), the Home tab.

## As built — part 1 of 2 (sign-in, devices and states; commands follow in part 2)
- **Pre-check (as the chunk asked), 2026-11-14:** `alexa-remote2` 8.1.1 and `alexa-cookie2` 5.0.6 (what Jarvis uses) are the latest releases, both
  updated 2026-08-15, so the approach isn't known to be blocked. The owner confirmed Jarvis's Alexa works for their account today. Nothing has
  been tried against Amazon from Edith: that first sign-in is the owner's.
- **Done in part 1:** sign-in on Amazon's own page, the device list, and each device's state, as Home devices (Settings → Home, the Home tab
  and `home_devices`, "Which lights are on?"). `isHub = true`, so D19's rule shows a device both Alexa and a direct service report once.
  **Read-only:** devices have no capabilities yet, so no controls are offered; commands, the unlock/open card rules and the thermostat steppers are part 2.
- **Code:** `alexa/AlexaParse` (Jarvis's `shared/alexa.ts` parsing, into Edith's `DeviceState`), `alexa/AlexaApi` (Amazon's HTTP), `alexa/AlexaService`
  (a `HomeService` plus the `HomeBackend`). Generic additions in `home/`: `PageSignIn` / `PageSignInStart` on `HomeService` (for a service that
  signs in on a web page, not a form), and in `ui/settings/`: `PageSignInDialog` (the choice, the in-app web view, the browser and paste).
- **Sign-in, as the owner chose: both.** "Sign in here" shows Amazon's page in an in-app WebView (JavaScript on, a browser user agent without the
  `wv` marker, fresh cookies, cleared again when it closes); the sign-in ends when the page redirects to `…/ap/maplanding?openid.oa2.authorization_code=…`,
  which Edith catches. "Use my browser" opens the same page in the phone's browser, and the owner pastes the address it ends on (as C17b's paste
  fallback; it works wherever Chrome does, Andarium included). The password goes to Amazon's page, never through Edith's code.
- **Protocol (from `alexa-cookie2` and `alexa-remote2`):** Amazon's `/ap/signin` page for the Alexa iPhone app (`pageId=amzn_dp_project_dee_ios`, PKCE
  S256, a device id ending `…23413249564c5635564d32573831`); then `POST api.<site>/auth/register` with the code and verifier, as an Alexa
  "device" (`A2IVLV5VM2W81`, app name "Edith"), which returns the **refresh token**; then `POST www.<site>/ap/exchangetoken/cookies` with it for the
  cookies, and a page on `alexa.<site>` for the `csrf` cookie. Devices: `POST alexa.<site>/nexus/v1/graphql` (the query is `alexa-remote2`'s,
  verbatim); states: `POST /api/phoenix/state` in batches of 5 by maker, 20 s each (Jarvis's tuning).
- **What is kept:** only the refresh token, the Amazon site and the device serial, encrypted (`SecretStore` `home/alexa`). Cookies and the csrf
  token are fetched when needed (`exchangetoken` needs nothing but the refresh token), held in memory, and fetched again once if Amazon refuses
  them (they last days). A refresh token Amazon refuses (400/401) is "Sign in again"; an unreachable Amazon is not. The refresh token does not
  rotate (unlike Ring's): no new token comes back from the exchange.
- **Last-known states:** a batch that gets no answer shows each device's last reading with when it was reported (in memory, not saved across
  restarts: Jarvis saves them; a possible later change). A device missing from an answered batch shows no state, as in Jarvis.
- **Differences from Jarvis, on purpose:** Echo speakers and Fire TVs (`ALEXA_VOICE_ENABLED`) aren't listed (no smart-home state; they'd clutter a
  phone); no rooms (Alexa's room groups aren't asked for), so the Home tab files these under "Other" and the type chips are the way in; the Amazon
  site is fixed to **amazon.com** (the list of other sites is in the code, `AmazonSite.ALL`, but there is no picker yet); no "disable a device"
  (Edith's "hide" on the Home tab does that).
- **Tests:** `AlexaParseTest` (10), `AlexaApiTest` (11), `AlexaServiceTest` (15) and `AlexaToolsTest` (3), against a pretend Amazon that checks the PKCE
  verifier, trades refresh tokens for cookies and expires them (`AlexaTestKit`). `scripts/check.sh` OK. The Compose UI tests (`--device`) were not
  run and the sign-in dialog was not run on the emulator.
- **Not verified, and why it matters:**
  - Nothing has run against the real Amazon. Everything is from the libraries' source and the pretend Amazon.
  - **The browser-and-paste way may fail at registration.** `alexa-cookie2` makes up the `frc` and `map-md` cookies and plants them in the page it
    shows; Edith sends the same made-up values at registration, but a real browser doesn't have them, and whether Amazon minds is unknown.
  - **The in-app way may be treated differently by Amazon** (a WebView, not a browser), and whether Andarium has a WebView is unknown.
  - The disconnect hint's path to Edith in the Alexa app is from memory. Amazon may also ask for a code by text or email: the library notes only
    app-based two-step verification works through its relay; whether it matters in a WebView is unknown.
