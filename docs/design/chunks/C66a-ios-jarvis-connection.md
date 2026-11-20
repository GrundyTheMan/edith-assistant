# C66a — The Jarvis connection on iOS (pairing and the authenticated client, in Expo Go)

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave A′ (it needs **no development build**: decision D39) |
| Status | **Built** against a pretend Jarvis; see "As built". The real check needs the Jarvis change (`jarvis-tailscale-listener-spec.md`), Tailscale HTTPS on, and the owner |
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

## As built
Built in one PR, against a **pretend Jarvis** (the Jarvis side, `jarvis-tailscale-listener-spec.md`, is not built yet). Where the code differs from the specification above, the code is right.
- **Files:** `core/hmac.ts` (HMAC-SHA256 over the pure SHA-256, tested on RFC 4231 cases 1, 2 and 6 and against Node's `crypto`), `jarvis/names.ts` (the name and code rules), `jarvis/link.ts` (`JarvisLink`: the token is private, hidden from `JSON.stringify` and `toString`; only `bearer()` and `serialize()` read it), `jarvis/api.ts` (`JarvisApi`: `hello`, `pair`, authenticated `call` with the typed `FeedResult`), `jarvis/service.ts` (`JarvisService`, a `HomeService` registered as "Jarvis" in Settings → Home → Services); tests `jarvis/jarvis.test.tsx` (20) and `core/hmac.test.ts` (2). Changed: `core/services.tsx` (registered with `secureFetch(httpFetch, { allowHosts: ['ts.net'] })`).
- **Pairing:** name (a `.ts.net` name with at least four labels, `:port` optional, default 47822; IPs, `http://`, other domains and the bare tailnet name are refused before any request) and the code (any case, dash and spaces tolerated). `hello` must say `jarvis-feed`, version 1, `transport: "tailscale"` and **the same `host` that was typed** (so a different PC, or Jarvis's other listener, is refused **before any proof is sent**). The proof is `HMAC-SHA256(code, "tailscale:" + name)`: the code itself is never sent. The token is saved to the Keychain (`home.jarvis`) **before** the service says connected. A refused or used code, five wrong tries and a rate limit each have plain words; an unreachable Jarvis names Tailscale and the listener.
- **Afterwards:** `JarvisService.call(method, path, parse, opts)` is the client later chunks use (`JarvisClient`). A 401 turns the service to "Jarvis removed this phone: pair it again" **once** and drops the refused token (no loop); pairing again uses the new token. An unreachable or slow Jarvis is **not** a sign-out and not an error state (the PC is often off): the row's detail says "can't reach it right now" and recovers by itself. `GET /v1/shared` fills the detail ("BEN-PC · shares mail, calendar, printer") after pairing and, from the Home tab's refresh, at most once a minute; it never raises a Home banner.
- **Secrets:** a test runs a whole pairing and a call with the console captured and checks neither the token nor the code appears in any log, state or `JSON.stringify(service)`.
- **Mutation-checked:** thirteen deliberate breaks (proof without the `tailscale:` prefix; the code sent instead of a proof; the `hello` host or transport not checked; the token saved after, or never; a 401 that keeps the token; an unreachable Jarvis becoming a sign-out; any host allowed; the token shown in JSON) were each caught, and the code restored.
- **Not verified, and it matters:** **nothing here has met a real Jarvis.** The proof's message (`tailscale:<name>`), the extra `hello` fields (`transport`, `host`) and port 47822 come from the Jarvis spec I wrote, not from a running server; the Jarvis agent may differ and the Edith side then changes (one file: `jarvis/api.ts`). Also unconfirmed: that iOS lets Expo Go reach a `.ts.net` name while Tailscale's VPN is on (no Local Network prompt should apply), and the Tailscale certificate being accepted by iOS's trust store (it is a public Let's Encrypt certificate, so it should).
