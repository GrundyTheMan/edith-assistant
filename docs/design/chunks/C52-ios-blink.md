# C52 — Blink on iOS (sign-in with a code, cameras, snapshots, events)

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave A |
| Status | **Design** |
| Depends on | C48 (services), C49 (pictures from tools) |
| Unblocks | C53 (clips), C71 (live view), C72 (alerts) |
| Runs in | Expo Go |
| Size | Large |
| Ports | C29 (`blink/`) |
| Read first | C29 spec **and** As built (the sign-in steps, the 406/VPN finding, snapshot polling, the 409 rules), `blinkpy`'s current release (the reference; Blink changed its rules in July 2026 and the libraries had to catch up), Jarvis's `blink.ts` (reference) |

## Why
The owner has Blink cameras. There is no official API; Edith follows `blinkpy`'s OAuth form flow. It is **fragile** (Blink
broke sign-in for everyone in July 2026 by rejecting a `hardware_id` that isn't a UUID) and **the owner's VPN breaks it**
(Blink's sign-in host refused the owner's NordVPN addresses: HTTP 406; with the VPN off it worked, C29 As built). Expect
outages: when Blink changes something, Edith says Blink can't be reached and every other service keeps working.

## Before you start
- **Owner:** Blink email, password, and the PIN Blink sends. **Tell the owner to turn the VPN off for the first sign-in** and say so in the sign-in form's intro text.
- **Agent pre-check:** re-read `blinkpy` and compare the steps below with it. If a step differs, follow `blinkpy` and write the difference in "As built".

## Specification

### 1. Sign-in (`blink/api.ts`, `secureFetch`, allowed hosts `blink.com`, `immedia-semi.com`, and whatever the reference names)
The form flow, **cookies carried between steps** (RN's `fetch` keeps a cookie jar on iOS (NSURLSession) when `credentials: 'include'` is set; verify, and if not, parse `Set-Cookie` yourself into a small in-memory jar):
1. Load the authorize page (PKCE: S256 challenge from a random verifier, `client_id=ios`, the install's `hardware_id` UUID).
2. Load `/oauth/v2/signin`; read the CSRF token from `<script id="oauth-args" type="application/json">` in the HTML (a pure parser over the page text: `html.ts` from C42 has a tokenizer you can reuse).
3. `POST /oauth/v2/signin` (email, password, csrf token). **HTTP 412, or 202 listing code methods, means a code is wanted** → return `needs_code`; keep the cookies, CSRF token and verifier **in memory only** for the second step. If the app restarts in between, start a new sign-in and try the typed code on it.
4. `POST /oauth/v2/2fa/verify` with the code.
5. `GET /oauth/v2/authorize` **without following redirects** (`redirect: 'manual'`; if the SDK's `expo/fetch` can't do that, use `XMLHttpRequest` with a custom handling, or read the `Location` from the final URL; verify and record which) → the `Location` carries `code`.
6. Trade the code at `/oauth/token` with the PKCE verifier → access + refresh token, plus the account id and tier from the follow-up calls the reference makes.
- **`hardware_id` must be a UUID, made once, kept for the install** (July 2026 rule). `expo-crypto` `randomUUID()`.
- **Kept** (`SecretStore` `home.blink`, JSON): refresh token, hardware id, tier, account id. Rotation rule exactly as C51 §2: save the new refresh token before using the access token; refreshes never overlap; a refused token → `needs_sign_in`.
- **Log status codes per step only** ("signin 200", "verify 412") so a change in Blink shows *which* step failed; never a body, cookie, password, code or token.
- **The 406 finding:** if the sign-in page answers 406, retry once forcing IPv4 only is not possible from JS; instead fail with the plain message "Blink refused the connection (HTTP 406). If you use a VPN, turn it off and try again." and the step name. Do not loop.

### 2. Devices and state
Homescreen call → cameras, minis and doorbells → `HomeDevice { type: 'camera'|'doorbell', capabilities: ['snapshot','events'] }`. State: online/offline; **no battery percentage** (Blink gives only "ok"/"low": do not invent one), **no arming** (nothing in Edith's model for it).

### 3. Snapshots (`snapshot`, `snapshotTimeoutMs = 45_000`)
`POST` the camera's `thumbnail` address (each kind has its own), poll the command until `complete` (up to 20 s), then re-read the homescreen until the camera's thumbnail URL changes (up to 10 s) and fetch it with the bearer token. **HTTP 409** from a busy sync module → ask again 3 s later, at most 4 times; a 409 that says **offline** is not retried and tells the owner to check the sync module.

### 4. Events and clips list
`media/changed?since=…&page=…` (page until one is not full of clips inside the period). `events(deviceId|null, sinceMs)` = the camera's recordings (kinds: `motion`). The video URL needs the bearer token (C53 uses `clipSource`: address + headers).

### 5. The service
`HomeService`: form `email`, `password`; second step `code` (number, `oneTimeCode`). Intro text mentions the VPN. `disconnect()` forgets secrets (no server call needed unless the reference logs out).

## Files
New: `blink/api.ts`, `blink/parse.ts` (pure: oauth-args page, homescreen, media), `blink/service.ts`, tests with **a pretend Blink that checks cookies, CSRF, the PKCE verifier and that `hardware_id` is a UUID** (`blink/testkit.ts`). Changed: `core/services.tsx`.

## Tests
Port C29's 30: parsing (homescreen of three camera kinds, offline, snapshot addresses, media with both time spellings, the code and CSRF token), each sign-in step (no code, code asked, wrong code, wrong password, a changed page, a 202), refresh rotation, busy/offline 409, one UUID, no retry loops, snapshot order and a snapshot that never arrives, dead token → sign in again, events/clips list. **Mutation-check your tests**: break the PKCE verifier, drop the cookie jar, use a wrong CSRF key, hand back the old picture, and show each is caught (C29 did eleven such breaks).

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner, on the iPhone, VPN off:** sign in with the PIN; cameras on the Home tab; "Show me the backyard camera" → picture; "Any motion on the Blink cameras today?"; a restart keeps the sign-in.
- [ ] With the VPN on the form says the 406 sentence (record what happens).

## Risks and unverified assumptions
- Blink may block iOS network stacks the same way it blocked the owner's phone path (IPv6/VPN). Cookie jar behaviour and `redirect: 'manual'` must be verified early (do these two probes first, in a throwaway script against the owner's account only with the owner present).
- `hardware_id` and client id values change when Blink changes; keep them in one module.

## Not in this chunk
Clips player (C53), live view (C71), arming, alerts (C72).
