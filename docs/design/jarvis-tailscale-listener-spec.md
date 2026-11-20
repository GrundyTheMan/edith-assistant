# Jarvis: a second feed listener with a Tailscale certificate (for the iPhone)

**For:** the Jarvis agent (a change in the Jarvis repository, on top of `jarvis-feed-spec.md` as built and `jarvis-printer-feed-spec.md`).
**Written by:** the Edith agent, 2026-11-20. **Decision:** D39 (`ios-roadmap.md`): the owner asked for the iPhone to reach Jarvis without a development build.
**Read first:** `jarvis-feed-spec.md` parts 5 (transport), 6 (pairing) and 14 (as built), and this file's §0.

## 0. Why this exists
Edith on **Android** pins Jarvis's **self-signed** certificate (the feed spec, part 5.2 and 6). Edith on **iOS** cannot do that from the app code Expo Go runs:
iOS's networking refuses a self-signed certificate, and an app can only change that with native code (which needs a development build, and the owner's
Apple Developer membership has lapsed). Everything else the iPhone needs (the endpoints, the device token, the printer feed, the long poll) is ordinary HTTPS
and JSON.

**The change:** Jarvis also listens on a **second port with a real, publicly trusted certificate for the PC's Tailscale name**, issued by Tailscale
(`tailscale cert`, a Let's Encrypt certificate for a name like `ben-pc.tail1234.ts.net`). The iPhone's normal HTTPS then just works, with no pinning.

**What does not change (important):** the existing self-signed listener, its port, its certificate, the Android app, the endpoints, the device tokens,
the pairing codes, the printer routes, the rate limit and the "private callers only" wall. **This is additive.** With the Tailscale listener switched off
Jarvis behaves exactly as today. One paired-phones list serves both listeners.

## 1. What the owner does (once; Jarvis shows these words, exactly)
1. In the Tailscale admin page (login.tailscale.com → DNS): make sure **MagicDNS** is on, then switch on **HTTPS Certificates**. (Tailscale warns that machine
   names become public in certificate logs; the owner accepts that by switching it on.)
2. In Jarvis → Settings → Edith → **Tailscale (for iPhone)**: press **Get certificate**. Jarvis does the rest (§3).
3. Press **Pair a phone** (as today). The iPhone needs the PC's Tailscale name (shown, with Copy) and the code.

If HTTPS Certificates is off, `tailscale cert` fails; Jarvis shows: "Tailscale's HTTPS certificates are switched off for your account. Turn them on at
login.tailscale.com → DNS → HTTPS Certificates, then press Get certificate again."

## 2. Settings (additive fields in the feed settings, part 4)
```
tailscale: {
  enabled: boolean          // default false
  port: number              // default 47822 (1024-65535, not equal to the self-signed port)
  name: string | null       // the PC's tailnet DNS name, lower case, no trailing dot; set by "Get certificate"
}
```
Stored next to the existing feed settings; **the certificate and its private key are files under `userData/feed/tailscale/`**
(`cert.pem`, `key.enc`): the key encrypted with Electron `safeStorage` exactly like the self-signed key (part 4). Neither is ever logged or shown.
`tailscale cert` writes plain files: write them to a temp directory with the owner-only permission, read them, encrypt the key, **delete the temp files**.

## 3. Getting and renewing the certificate (`src/main/feed/tailscaleCert.ts`)
- **Find the name:** run `tailscale status --json` (the executable is `tailscale` on the PATH, or `C:\Program Files\Tailscale\tailscale.exe`; **no shell**:
  `execFile` with a fixed argument array) and read `Self.DNSName`; strip the trailing dot and lower-case it. Not running or not signed in:
  "Tailscale isn't running on this PC."
- **Get the certificate:** `tailscale cert --cert-file <tmp>\cert.pem --key-file <tmp>\key.pem <name>` (timeout 60 s). Exit code ≠ 0 → map the output to the
  §1 sentence when it mentions HTTPS being disabled, otherwise "Tailscale couldn't make a certificate: <first line, 200 characters>".
- **Validity:** parse the certificate (`crypto.X509Certificate`): `validTo`, and check that `subjectAltName` contains the name. Store `validTo`.
- **Renewal:** at start-up and every 24 hours, if the listener is enabled and the certificate has **fewer than 30 days** left, run the same command again.
  Swap the new certificate into the running listener with `server.setSecureContext({cert, key})` (no restart, no dropped connection). A failed renewal is
  retried at the next check; the Settings status line says "Certificate valid until <date>" or, in the last 7 days, "Certificate runs out on <date>: renewal failed".
- Pure functions to unit-test: `parseTailscaleStatus(json) → name | null`, `daysLeft(validTo, now)`, `needsRenewal(daysLeft)`, `mapCertError(output)`.

## 4. The second listener (`src/main/feed/server.ts`)
- When `tailscale.enabled` and a certificate exists: a **second `https.createServer`** with the Tailscale certificate and key, TLS 1.2+, listening on
  `0.0.0.0:<tailscale.port>`, **using the very same request handler** as the first (routing, auth, rate limit, body and time limits, error shapes), the same
  **private-caller check** (part 5.3: Tailscale's `100.64.0.0/10` and the LAN ranges stay allowed) and the same device list.
- The rate limit's failure counters are shared between the two listeners (one count per remote address).
- Switching the feed off stops both. Switching the Tailscale listener off stops only the second. A busy port: "Port 47822 is in use" in the status line.
- **Windows firewall:** the same words as part 2.8 of the feed spec, for this port.
- `GET /v1/hello` on **this** listener returns `{"app":"jarvis-feed","version":1,"transport":"tailscale","host":"<name>"}` (adding fields is allowed by part 5.9;
  the self-signed listener's answer is unchanged).

## 5. Pairing over this listener (`POST /v1/pair`)
The request and response shapes are the same as part 6.1. **Only the proof's message differs**, because an app using normal HTTPS cannot read the TLS
fingerprint (and does not need to: the certificate chain already proves it is talking to that name):

```
proof = lowercase hex of HMAC-SHA256( key = the code without the dash, uppercase,
                                      message = UTF-8 bytes of  "tailscale:" + <name> )
```
where `<name>` is `tailscale.name` (lower case, no trailing dot). Jarvis computes it with its own name and the current code and compares with
`crypto.timingSafeEqual`. The `tailscale:` prefix keeps a proof made for one listener from being accepted by the other. Everything else is as in part 6:
one code, five minutes, one use, five wrong tries, `PAIRING_REFUSED` with the same text for every failure, the token returned once.

**On the self-signed listener the proof stays `HMAC(code, fingerprint)`** (Android is unchanged). A request to either listener with the other's proof fails like
any wrong proof.

**What the owner compares:** Jarvis's Settings shows the **name** next to the code (with Copy); the iPhone shows the name it connected to. There is no
fingerprint comparison on this listener.

## 6. Settings page (additions to part 2)
A group **Tailscale (for iPhone)**:
- a switch **Allow the iPhone over Tailscale** (default off; refuses to switch on until a certificate exists: "Press Get certificate first.");
- **Name** (read-only, with Copy) and **Port** (number box, default 47822);
- **Get certificate** and a status line: "No certificate yet", "Certificate valid until 18 Feb 2027", "Listening on ben-pc.tail1234.ts.net:47822", an error sentence from §3, or "Port 47822 is in use";
- a short note: "Your PC's Tailscale name will appear in public certificate logs. Nothing else about it does."
The **Pair a phone** panel shows the Tailscale name beside the address and fingerprint when the listener is on.

## 7. Privacy and security
- The token, the code, the proof, the certificate's private key and every response body are never logged. The cert command's output is logged only as an error
  class ("tailscale cert failed", the exit code), never the key path or contents.
- `execFile`, never a shell, fixed arguments, the name read from `tailscale status` (never from a request).
- A trusted certificate for a name is **not** authentication: the device token and the "private callers only" wall still protect every endpoint.
- If the Tailscale listener is switched on while the PC is not on a tailnet, it still starts (callers arrive from the LAN too), but the status line says
  "Tailscale isn't running on this PC."

## 8. Tests and checks (`npm run typecheck`, `npm test`, `npm run build` must pass)
- The four pure functions in §3 (including a `status --json` fixture with a trailing dot, a name with capitals, and "NeedsLogin").
- Pairing: the Tailscale proof is accepted on the Tailscale listener and rejected on the self-signed one, and the other way round; a wrong code counts as a try;
  the code burns on success on either listener; two listeners share the failure count.
- The same device token works on both listeners; removing the phone revokes it on both.
- With the Tailscale listener off, no second server is created and the self-signed behaviour is byte-for-byte unchanged (an existing test, unchanged, must pass).
- `setSecureContext` swap on renewal (inject a fake server).

## 9. What Edith will do with this (so you can see the other side)
The iPhone app takes the **name** (not an IP) and the code, connects with ordinary HTTPS to `https://<name>:<port>` (hosts under `.ts.net` only), calls
`GET /v1/hello` (and checks `host` equals the name it typed), computes the proof as in §5 with the code the owner typed, calls `POST /v1/pair`, and keeps
`{name, port, token}` in the iOS Keychain. Every later call carries `Authorization: Bearer <token>`. It never asks Jarvis for the fingerprint and never
pins anything. The Android app keeps using the self-signed listener.

## 10. Questions to ask the owner (do not guess)
1. Is **HTTPS Certificates** switched on in the tailnet's admin page, and is **MagicDNS** on? (Without both, this cannot work.)
2. Is `47822` free on the PC? (Change it in Settings if not.)
3. Do they accept that the PC's Tailscale name appears in public certificate logs?

## 11. Order of work and PR
One PR in the Jarvis repository: the pure functions and their tests, `tailscaleCert.ts`, the second listener, the pairing proof, the Settings group. Tell the
Edith agent (the owner passes it on) when it is merged and what the status line says on the owner's PC, so Edith's side can be tried for real.
