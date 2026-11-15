# C29 — Blink cameras (v2)

| | |
|---|---|
| Phase | 8 — Smart home (v2) |
| Depends on | C25 |
| Size | Medium |
| Read first | C21 (Blink, and "Checked on 2026-11-12"), 06-decisions **D10** |

## Why
The owner has Blink cameras. There's no official API; Edith uses the OAuth flow the maintained `blinkpy` (Python)
uses, rewritten in Kotlin. **Fragile:** sign-in broke for everyone in July 2026 (Blink began rejecting a `hardware_id`
that isn't a UUID) until the libraries caught up.

## Specification (verify against `blinkpy`'s current release first)
- **Sign-in:** Blink's OAuth sign-in with the owner's email, password and 2FA PIN; a random UUID as `hardware_id`, kept
  for the install; tokens encrypted.
- **Devices:** cameras (`Camera`; `Snapshot`, `Events`), armed state, battery.
- **Snapshots:** ask the camera for a new thumbnail, wait for it, fetch the JPEG; **clips:** list recent motion clips
  (`recent_events`). **Clips (D19, as Jarvis's `blinkClips.ts`):** in the Home tab's Clips chip (added by C27, or
  here if Blink comes first), played in the app. No live view (C32).

## Tests
The sign-in steps against made-up captures that follow `blinkpy`; the UUID kept; thumbnail polling with a time limit;
a sign-in Blink refuses → a plain message and Sign in again, no retry loop.

## Acceptance
- [ ] Owner: sign in, "Show me the backyard camera", "Any motion on the Blink cameras today?", the Home tab, a
      clip played.

## Traps
Expect outages: when Blink changes something, Edith says Blink can't be reached; other services keep working.

## As built
Read against `blinkpy`'s `dev` branch and Jarvis's `blink.ts` on 2026-11-15.
- **Done:** Settings → Home → **Blink** takes the Blink email and password, and a code when Blink asks for one (the same form as Ring). Cameras, minis and doorbells appear on the Home tab as
  cameras and doorbells with **Snapshot** and **Events**; the Clips chip now lists Blink's recordings with Ring's; "Show me the backyard camera" and "any motion on the Blink cameras today?" use the
  same tools as Ring. Nothing can be commanded (no arming, no live view: C32).
- **Sign-in is blinkpy's form flow, not Jarvis's window** (Edith has no equivalent of Jarvis's redirect catcher, and a form needs no web view, so it also works on Andarium): load the authorize page (PKCE, `client_id=ios`,
  the UUID `hardware_id`), load `/oauth/v2/signin` and read the CSRF token from its `<script id="oauth-args" type="application/json">`, `POST /oauth/v2/signin` (email, password, token; cookies carried), then
  **412** (or **202** listing code methods) means a code is wanted: `POST /oauth/v2/2fa/verify`, then `GET /oauth/v2/authorize` without redirects whose `Location` carries the `code`, traded at `/oauth/token`
  with the PKCE verifier. The cookies, token and verifier of a sign-in waiting for its code are kept **in memory only**; if the app was closed meanwhile a new sign-in starts and the typed code is tried on it.
- **What is kept:** only the refresh token, the hardware id (a UUID, made once: Blink rejects any other, July 2026), the tier and the account id, encrypted (`home/blink`). Like Ring's, the refresh token is
  treated as rotating: saved before the access token it came with is used, refreshes never overlap, and a token Blink refuses turns the service to **Sign in again**.
- **Snapshots:** `POST` the camera's `thumbnail` address (each kind has its own), poll the command until `complete` (up to 20 s), then re-read the homescreen until the camera's thumbnail changes (up to 10 s) and
  fetch it with the bearer token. A busy sync module (HTTP 409) is asked again three seconds later, up to four times; a 409 that says **offline** is not retried and tells the owner to check the sync module.
- **Clips and motion:** `media/changed?since=…&page=…` (pages until one is not full of clips inside the period). Events for a camera are its recordings. The video is on the account's server and needs the sign-in,
  so the player now takes a **`ClipSource`** (address + headers) instead of a bare address (`HomeBackend.clipSource`; Ring's default is its address with no headers); the Media3 player sends the header.
  Thumbnails of clips and clip lengths are not shown (Blink's list gives no length).
- **Not shown, on purpose:** the battery (Blink says only "ok" or "low", and `DeviceState.battery` is a percentage: nothing is invented) and whether the system is armed (nothing in Edith's model for it yet).
- **Logging:** status codes only, at each sign-in step, so a Blink that changes something shows *which* step in the log without a password, code, token or answer.
- **Code:** `blink/BlinkParse` (pure), `blink/BlinkApi` (HTTP; every call on the IO threads, L39), `blink/BlinkService`; generic: `ClipSource`, `HomeBackend.clipSource`, `Home.clipSource`.
- **Tests (30 in `blink/`, against a pretend Blink that checks cookies, CSRF and the PKCE verifier):** parsing (homescreen of three kinds, offline, snapshot addresses, media, both time spellings, the code and the CSRF
  token), the sign-in steps (no code, code asked, wrong code, wrong password, a changed page, a 202), refresh rotation, busy and offline 409, and the service (kept values, one UUID, no retry loops,
  snapshots in order, a snapshot that never arrives, dead token, clips, events, the clip header). `scripts/check.sh` OK. **Checked that the tests can fail:** 11 deliberate breaks (wrong PKCE verifier, no cookie jar, wrong CSRF key, clip header dropped, rotated token not saved, busy never retried, hardware id not a UUID, offline 409 read as busy, old picture returned, a header added to Ring's default, deleted clips shown) were each caught by a test, and the code restored.
- **Verified on the emulator against a pretend Blink on the PC** (a debug-only hook: the debug app reads its private `files/blink-test-server.txt` for an address, nothing else changes; release builds never look; the server was a Node copy of the test fake with a code step, 120-second access tokens and a "busy" answer to the first snapshot): the in-app sign-in with a wrong code then the right one (the same sign-in page was reused; the server checked cookies, CSRF, PKCE and the UUID); Blink shown as Connected; the three cameras on the Home tab; **Snapshot** (refresh and token rotation, a busy answer retried three seconds later, the command polled, the new picture fetched with the bearer token, shown); the **Clips** list (newest first, a deleted clip skipped); a **clip played** from a server that serves it only with the bearer header; chat: "Any motion on the Blink cameras today?" (answered from `recent_events`) and "Show me the backyard camera" (`camera_snapshot`, picture in the reply); disconnect. No crash, and debug StrictMode (which kills on main-thread network use) did not fire.
- **Found while trying it:** asked about "the Blink cameras", the model first passed `device: "Blink"` to `recent_events` and `home_devices`; both said so in plain words, and it then asked for all events and answered. The tools match devices by name, not by service; a Blink camera named "Blink" would be a coincidence. Left as is.
- **Not covered here:** Ring's clip playing on a device after the player change (no Ring account on the emulator; `ClipsTest` covers that Ring's clips come through with no headers, and the player's no-header path is the one it used before).
- **Not verified, and why it matters:** nothing has been tried against **real Blink** (only a pretend one that follows blinkpy). The flow follows blinkpy's code; the real service may differ (the final authorize step carries no PKCE parameters in
  blinkpy and relies on the cookies; the media list and thumbnail address shapes vary by account and camera). If the sign-in fails, the app log (tag Home) shows the step and status. Acceptance below is the owner's.
