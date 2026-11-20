# C66a — The Jarvis connection on iOS (pairing and the authenticated client, in Expo Go)

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave A′ (it needs **no development build**: decision D39) |
| Status | **Design; building** — the Jarvis side is `jarvis-tailscale-listener-spec.md` (a change in the Jarvis repository, not yet built) |
| Depends on | C48 (services, `SecretStore`, `secureFetch`) |
| Unblocks | C67 (the printer through Jarvis), C66b (the work account through Jarvis), C72 option B (Jarvis sends the push) |
| Runs in | **Expo Go**: ordinary HTTPS to a `*.ts.net` name with a publicly trusted certificate |
| Size | Medium |
| Ports | C38's pairing and `JarvisApi` core (the transport differs: no pinning) |
| Read first | `jarvis-feed-spec.md` parts 5–7 and 14, **`jarvis-tailscale-listener-spec.md` (the contract this chunk uses; §5 the pairing proof)**, D25, D39, C48 (`HomeService`), C37 (`secureFetch`) |

## Why
Edith pairs once with Jarvis (the owner's always-on PC) and then asks it for things: the printer (C67), the work account (C66b), later alerts (C72 B).
On Android the phone pins Jarvis's self-signed certificate. iOS cannot do that without native code (a development build), so **Jarvis gets a second
listener with a real Tailscale certificate** (the Jarvis spec above) and the iPhone talks to it with plain HTTPS. This chunk is **only the connection**:
pairing, the stored link, the authenticated client, and honest states. It shows nothing from Jarvis yet.

## Before you start
- **Jarvis side:** `jarvis-tailscale-listener-spec.md` must be built and merged in Jarvis for a real pairing. **This chunk is built and tested against a pretend Jarvis
  without it**, and says in "As built" that it has not met the real one. Do not weaken any rule here to make a real pairing work: tell the owner and the Jarvis agent.
- **Owner (for the real check):** Tailscale on the PC and the iPhone, HTTPS Certificates on in the tailnet, Jarvis's feed and its Tailscale listener switched on,
  the PC's Tailscale name and a pairing code from Jarvis's screen.

## Specification

### 1. The service (`jarvis/service.ts`, `HomeService` id `jarvis`, name "Jarvis")
Shown in Settings → Home → Services like the others. It has **no devices yet** (`backend()` answers an empty home; C67 adds the printer fallback).
States as C48: signed out; connecting; connected (detail = the PC's name from the pair reply); **needs sign in** ("Jarvis removed this phone: pair it again"); error
("Edith can't reach Jarvis. Is the PC on and Jarvis running?"). Never a retry loop; an unreachable Jarvis is **not** a sign-out.
- Form: **PC name** (the Tailscale name, e.g. `ben-pc.tail1234.ts.net`; keyboard `url`), **Pairing code** (10 characters; accept lower case, spaces and a dash). Intro text
  tells the owner where to find both on the PC ("Jarvis → Settings → Edith → Pair a phone"), that **Tailscale must be on** on this iPhone, and that the code lasts five minutes.
- Name rules: lower-cased, trimmed, an optional `:port` (default **47822**, 1024–65535), a pasted `https://…/` is accepted and cut down, and the host **must end in `.ts.net`**
  (anything else: "Use the PC's Tailscale name, which ends in .ts.net. Jarvis shows it next to the pairing code."). IP addresses and plain `http://` are refused.
- `signIn` → `JarvisApi.pair(...)` (below). The code and the proof are used once and never kept or logged. Saved in the Keychain (`home.jarvis`, JSON `{host, port, token, pcName}`);
  **the token never appears in a log, an error, a `toString` or a notice** (a `Secret` wrapper whose `toJSON`/`toString` print `[hidden]`).
- `disconnect()` forgets the link and the Keychain entry. (There is no route that tells Jarvis; the owner removes the phone in Jarvis's list. Say so in the Settings hint.)

### 2. The client (`jarvis/api.ts`, over `secureFetch` with `allowHosts: ['ts.net']`)
- `hello(host, port)`: `GET /v1/hello` → `{"app":"jarvis-feed","version":1,"transport":"tailscale","host":"…"}`. Refused unless `app` is `jarvis-feed`, `version` is 1, `transport` is
  `tailscale` and **`host` equals the name that was typed** ("This isn't the Jarvis you meant."). This is the only unauthenticated call besides `pair`.
- `pair(host, port, code, phoneName)`: `hello`, then `POST /v1/pair` `{ name, proof }`, `proof = hex(HMAC-SHA256(key = code without dash, upper case, message = "tailscale:" + host))`
  (Jarvis spec §5). `PAIRING_REFUSED` (403, one text for every cause) → "Jarvis didn't accept that code. Make a new one on the PC (it lasts 5 minutes)." 429 → "Jarvis says to
  slow down; try again in a minute." Success → `{token, deviceId, pcName, version}`.
- `get(path, {signal, timeoutMs})` / `post(...)`: adds `Authorization: Bearer <token>`, `Accept: application/json`; parses the `{error:{code,message}}` shape into a typed result
  `FeedResult<T> = ok | unauthorized | notShared | busy | unavailable | timeout | unreachable | failed(message)`. **401 → the service turns to needs-sign-in once.** The default timeout is 15 s,
  callers may pass more (long polls: `wait + 10 s`), and every call is cancellable. The response body is read as text and parsed; **bodies and the token are never logged**; log only the route
  name and the status ("jarvis GET /v1/shared 200").
- HMAC-SHA256 is `core/hmac.ts` over the existing pure `sha256` (RFC 2104, block 64), tested with **RFC 4231's vectors** and against Node's `crypto`.
- Phone name sent at pairing: "iPhone" (the device name is not read: privacy).

### 3. What it does not do
No fingerprint, no pinning, no self-signed certificates, no IP addresses, no plain HTTP: those are Android's (and C66b's fallback, if the owner ever picks D29 A). It reads nothing from
Jarvis (`/v1/shared` and everything after are the next chunks) except a `GET /v1/shared` check used by the Settings detail line ("Shares: Outlook mail, calendar, tasks, printer") **once
after pairing and on pull-to-refresh**, failing quietly.

## Files
New: `core/hmac.ts`, `jarvis/{link,api,service,names}.ts` (the name rules in `names.ts`), tests `jarvis/jarvis.test.tsx` (a pretend Jarvis that checks the proof, the bearer token, the host and the
rate limit) and `core/hmac.test.ts`. Changed: `core/services.tsx` (registered), `docs/design/*`.

## Tests
HMAC vectors; the name rules (accepted forms, `.ts.net` only, port, IP and `http://` refused); the proof (against Node's `crypto`); a full pairing against the pretend Jarvis (hello checked, proof accepted,
token saved before the service says connected, nothing secret in the saved text of the notice or log spy); a refused code, a rate-limited pairing, a wrong `host` in hello, a non-Jarvis answer, an
unreachable Jarvis (not a sign-out); 401 → needs sign in once, no loop, and a re-pair works (the new token is used, not the old); disconnect forgets everything; the token never appears in `JSON.stringify(service)`.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner, with the Jarvis change built:** Settings → Home → Jarvis → Sign in with the PC's Tailscale name and the code → Connected "BEN-PC"; remove the phone in Jarvis → Edith says "Jarvis removed this phone: pair it again"; Tailscale off on the phone → "Edith can't reach Jarvis".

## Risks and unverified assumptions
- **Nothing here has met the real Jarvis**: the proof message (`tailscale:<name>`), the extra `hello` fields and port 47822 are the Jarvis spec's, not a running server's.
- iOS may block a `.ts.net` name until the Tailscale app is running and its VPN is on; a Local Network prompt does not apply (Tailscale addresses are not local network), but verify.
- The Jarvis agent might choose another default port; the form accepts `:port`.

## Not in this chunk
Anything shown from Jarvis (the printer: C67; mail, calendar, to-dos: C66b), pinning, alerts through Jarvis (C72 B).
