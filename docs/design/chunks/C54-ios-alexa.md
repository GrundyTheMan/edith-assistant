# C54 — Alexa devices on iOS (Amazon's page in a WebView, devices, state, commands)

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave A |
| Status | **Built** in Expo Go, both parts in one PR; see "As built" at the end. The owner's first sign-in and live commands are open |
| Depends on | C48 (services, `PageSignIn`, the WebView modal), C46 |
| Unblocks | the lights, plugs, fans, thermostats and locks the owner linked to Alexa, from the Home tab and chat |
| Runs in | Expo Go (`react-native-webview` is included) |
| Size | Large |
| Ports | C30 (`alexa/`) |
| Read first | C30 spec **and** As built (both parts), D22 and D19 in `06-decisions.md`, Jarvis's `alexa.ts` and `shared/alexa.ts` (references), `alexa-cookie2` 5.x and `alexa-remote2` 8.x (the libraries Jarvis uses; read their source for the exact calls) |

## Why
Many of the owner's lights and plugs are linked to Alexa, and so are the August locks. Amazon has no API for an app to control
them, so Edith uses `alexa-cookie`/`alexa-remote2`'s approach: Amazon's own sign-in page, then the session it creates. It is
**fragile** (the libraries are fixed every few months and are reported to be on their way out). The iOS version shows Amazon's
page in an in-app WebView and catches the redirect; Edith never sees the password (the page does).

## Before you start
1. **Owner:** their Amazon account (the one the Alexa app uses). Two-step verification by an **authenticator app** works; SMS/email 2FA reportedly fails through the relay.
2. **Agent pre-check:** confirm `alexa-cookie2` and `alexa-remote2` are still current and that the owner's Jarvis Alexa still works today (ask the owner). If Amazon has blocked the flow, **stop and tell the owner**; consider controlling those devices through their own brand instead (C30 "Before you start").

## Specification

### Part 1 — sign-in, devices, states (read-only)
- **Sign-in:** `PageSignIn` (C48) with the WebView modal: `startUrl()` = Amazon's `/ap/signin` page for the Alexa iPhone app (`pageId=amzn_dp_project_dee_ios`, PKCE S256, a device id of the form the library builds — copy C30's As built "Protocol" paragraph exactly and re-verify against the libraries). The WebView needs: JavaScript on; a **Safari-like user agent** (no `wv` marker: set `userAgent` on the WebView); **fresh cookies each time** (`incognito` prop, or clear with `CookieManager`-equivalent via `sharedCookiesEnabled={false}` + `incognito`); `onNavigationStateChange`/`onShouldStartLoadWithRequest` watch for the redirect to `…/ap/maplanding?openid.oa2.authorization_code=…`; when seen, **stop the load** (return false) and pass the code to `complete(code)`.
- **Then:** `POST api.<site>/auth/register` with the code and PKCE verifier as an Alexa "device" (device type `A2IVLV5VM2W81`, app name "Edith") → the **refresh token**. Cookies and the CSRF token come from `POST www.<site>/ap/exchangetoken/cookies` with the refresh token and a page on `alexa.<site>` for the `csrf` cookie; hold them in memory; **fetch again once if Amazon refuses them** (they last days). The refresh token does **not** rotate (unlike Ring's).
- **Kept** (`SecretStore` `home.alexa`, JSON): refresh token, Amazon site, device serial. Nothing else. A refresh token Amazon refuses (400/401) → `needs_sign_in`; unreachable ≠ sign-out.
- **Amazon site** fixed to `amazon.com` (the other sites' list exists in Android's `AmazonSite.ALL`; a picker is a later change); temperature scale follows the site (`.com` → °F).
- **Devices:** `POST alexa.<site>/nexus/v1/graphql` with `alexa-remote2`'s query **verbatim** (copy from the library, with its licence noted if required). Map: lights, plugs, switches, fans, thermostats, temperature sensors, locks (August included), garage doors and cameras (listed only; cannot be controlled through Alexa). `isHub = true` (a device a direct backend also reports shows once, through the direct one: C46 merging). Echo speakers and Fire TVs are **not** listed. No rooms (Alexa's room groups aren't requested): everything is filed under "Other" and the type chips are the way in.
- **State:** `POST /api/phoenix/state` in batches of **5 by maker**, 20 s each (Jarvis's tuning). A batch that gets no answer keeps each device's last reading with the **age** of that reading (kept in memory; saving them across restarts is a later change, as in C30). A device missing from an answered batch shows no state.
- Parsing is pure: `alexa/parse.ts`, ported from Jarvis's `shared/alexa.ts` into Edith's `DeviceState`. Devices have **no capabilities in part 1** (read-only).

### Part 2 — commands
- Jarvis's `ALEXA_ACTIONS`: on/off, brightness, percentage (fans), colour, white tone, setpoint, mode, lock, unlock. `capabilities` are added per device type. Card rules are C46's (`confirmationFor`): **unlock always a card** (on iOS there is no hands-free voice approval to forbid yet: C57/C69 must keep "unlock/open needs an on-screen tap" when they exist), thermostat mode always, setpoint when the change is large.
- Each command is **checked against Alexa's answer** (Jarvis's `controlProblem`): a refusal or no-effect is reported in words, not as success. After a command the C46 re-check runs (3 s; locks and thermostats also 10 s and 30 s).
- Thermostat setpoint limits (50–90 °F) already live in `actions.ts`.

## Files
New: `alexa/parse.ts`, `alexa/api.ts`, `alexa/service.ts`, `alexa/commands.ts` (part 2), tests with **a pretend Amazon that checks the PKCE verifier, trades refresh tokens for cookies and expires them** (`alexa/testkit.ts`). Changed: `core/services.tsx`, `ui/PageSignInModal.tsx` (from C48; this chunk exercises it for real).

## Tests
Parsing the device list and states from made-up answers (every device kind); batches of 5 by maker; a missing/late batch keeps the last reading with its age; cookies expire → one refetch; expired refresh token → needs_sign_in; commands (each action's payload, the unlock card, `controlProblem` cases); the redirect catcher recognises the finish URL and ignores look-alikes (query on another host); the WebView modal calls `complete` exactly once.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green (each PR).
- [ ] **Owner, on the iPhone, part 1:** "Sign in here" shows Amazon's page; after the 2FA the modal closes and Alexa shows Connected; "Which lights are on?"; the Home tab lists the linked devices under "Other".
- [ ] **Part 2:** "Turn on the living room lamp", "Set the thermostat to 70", "Lock the front door" (the August lock through Alexa), "Unlock the front door" (card, tap).

## Risks and unverified assumptions
- Nothing has run against the real Amazon from Edith. **A WebView may be treated differently from a browser** (Android's in-app way was unproven on the owner's Andarium; on iOS `WKWebView` is a different case). If Amazon refuses the WebView, fall back to a "paste the address" flow through `expo-web-browser` (open the page in Safari, the owner copies the final URL) exactly as C17b/C30 did; build only if needed and record it.
- The made-up `frc`/`map-md` cookies the library plants in its page: a real browser doesn't have them; whether Amazon minds is unknown (C30).
- Amazon may ask for a code by text or email, which the relay reportedly can't handle.

## Not in this chunk
Routines, speakers, Echo devices, scheduled tasks, other Amazon sites.

## As built (2026-11-20)
Built by an AI agent from the Android code (`alexa/AlexaApi.kt`, `AlexaParse.kt`, `AlexaService.kt`), which is itself from `alexa-cookie2` 5.0.6 and `alexa-remote2` 8.1.1; Android is untouched.
Both parts are in one PR. Where the code differs from the specification above, the code is right.
- **Files:** `alexa/parse.ts` (sites, the device list, states, capabilities, commands, `controlProblem`), `alexa/api.ts` (sign-in page address with PKCE, registration, cookies and csrf
  token, the GraphQL device query, states, control, a cookie jar), `alexa/service.ts` (`AlexaService`), `core/sha256.ts` (SHA-256 in plain TypeScript, synchronous; tested against Node's);
  changes in `ui/PageSignInModal.tsx` (asks for the start address once: every call starts a new sign-in), `ui/HomeScreen.tsx` (Lock/Unlock and Open/Close buttons only for devices that can),
  `core/services.tsx` (registered).
- **Sign-in** is the WebView page flow of C48: the owner types the password on Amazon's page; Edith catches the redirect to `…/ap/maplanding?openid.oa2.authorization_code=…`, registers as an Alexa
  device with the PKCE verifier, and keeps only the refresh token, the site and a serial (Keychain `home.alexa`). Cookies and the csrf token are got from the refresh token when needed, held in
  memory, one request at a time, and fetched again once when Amazon refuses them. A refused refresh token is "Sign in again"; an unreachable Amazon is not.
- **Devices** are filed without a room (the Home tab's "No room"), Echo speakers, Fire TVs and the Alexa app are left out, the garage door and cameras are listed but have no capabilities (so no
  buttons). Thermostat units follow the Amazon site (`.com` → °F). States are asked in batches of five by maker, 20 s each; a batch with no answer, or one Amazon refuses as a whole,
  leaves each device with its last reading and when it was reported (in memory only). **Change from Android:** a whole-request refusal counts as no answer (Android showed no state).
- **Commands** are Android's: on/off, brightness and speed as fractions, colour and white by name, thermostat setpoint (account scale) and mode, lock and unlock; the unlock card is C46's.
  Amazon answers 200 to a refusal, so `controlProblem` reads the answer and a refusal is a failure in words.
- **The API checks every address** (HTTPS, and under an Amazon site) and follows redirects itself to keep the cookies set along the way; a redirect to Amazon's sign-in page means the cookies are no good.
- **Found on the owner's iPhone (2026-11-20): the first load worked, every later one said "Alexa gave no csrf token".** iOS keeps Amazon's cookies itself after the first sign-in, so Amazon no longer
  sends the `csrf` cookie, and Edith never saw it. **Fix:** the csrf token is remembered (in the Keychain value, with the refresh token) and reused when Amazon doesn't send it, and every Amazon request is
  made with `credentials: 'omit'` so the platform's shared cookie store neither hides `Set-Cookie` nor sends cookies of its own. This also shows that `Set-Cookie` is visible to the app on iOS.
  Tests: a pretend Amazon that sends the cookie only the first time. Whether Alexa accepts a reused csrf token for new cookies is still unconfirmed.
- **Not verified, and it matters a lot:**
  - **Nothing has run against the real Amazon.** Everything is from the libraries' source, Android's port and a pretend Amazon that checks the PKCE proof, the cookies and the csrf token.
  - **Cookies on iOS.** The csrf token arrives as a `Set-Cookie` header. Whether `expo/fetch` shows that header to the app on iOS, and whether `redirect: 'manual'` is honoured, is unknown. If the
    header is hidden, signing in works but every later call fails with "Alexa gave no csrf token"; the fix would be reading the cookie jar through a native module (a development build) or XMLHttpRequest.
  - **A WebView's cookies are not passed to registration** (Expo Go can't read them): registration goes with the two made-up cookies only, as Android's "Use my browser" path did, and whether Amazon
    minds is unknown. If Amazon refuses the WebView, the fallback in this document (open the page in Safari and paste the address) is not built.
  - Amazon may ask for a code by text or email, which the relay reportedly can't handle; the disconnect hint's path to Edith in the Alexa app is from memory.
