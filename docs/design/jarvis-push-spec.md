# Spec for Jarvis: push alerts to the owner's iPhone (Apple Push, APNs)

**Status: proposed, 2026-11-26. Nothing in this document is built.** Written on Edith's side for the Jarvis agent, as `jarvis-feed-spec.md` and
`jarvis-printer-feed-spec.md` were. Edith's half is in [`ios-alerts-while-closed.md`](ios-alerts-while-closed.md); read its "Why" first.
Do not guess: the questions in §9 are the owner's to answer, and anything here that disagrees with what Jarvis really does is wrong here: say so and fix the spec.

## 1. Why this exists
An iPhone app cannot watch Ring and Blink in the background: iOS suspends it seconds after it leaves the screen, so alerts today arrive only while Edith is open
(plus a "While you were away" summary when she is opened again). Jarvis is always on and (this is from Edith's earlier notes, C72; verify) already finds
doorbell rings, people and motion for its own desktop alerts. If Jarvis also sends an **Apple push** to the phone, the owner gets the alert on the lock screen with
Edith closed, at home or not. It works only while the PC and Jarvis are on and online; the phone does not need Tailscale for the push itself (Jarvis to Apple to the phone).

## 2. What the owner sees in Jarvis (Settings, the existing "Edith" section)
A new block **Push alerts**:
- **Apple push key**: a field to paste the `.p8` key text, **Key ID** and **Team ID** (the owner types these; they never go to Edith or to an agent), and the app's **bundle id** (the push topic;
  the iPhone app's current one is the Phase 0 spike's `com.edith.app.spike`, the final one waits for decision D43, so it is a field, not a constant).
- A list of **phones registered for push** (name as paired, registered time, last result: "sent 14:02", "Apple says this phone is gone: removed"), each with Remove.
- **Send test push** (one button per phone): sends "Jarvis test: push works" so the owner can see the whole chain without waiting for a doorbell.
- What is sent: a checkbox **"Include the camera name in the push"** (default on). The text passes through Apple's servers; with it off the push says only "Doorbell" / "Person" / "Motion".
- Status line: "No key yet" / "Ready" / "Apple refused the last push: <reason>".

The key is stored the way Jarvis stores its other secrets (safeStorage), never logged, never returned by any route.

## 3. The feed routes (same transport, auth and rules as `jarvis-feed-spec.md` §5; every path starts `/v1/`)
- `POST /v1/push/register`, body `{ "token": "<hex device token, 64 to 200 hex characters>", "env": "production" | "sandbox", "bundleId": "<topic>" }` → `204`. Needs the device token
  (`Authorization: Bearer`). Stores `{ phoneId, apnsToken, env, bundleId, registeredAt }`, replacing any earlier token of that phone. `400` for a token that is not hex or a body that does not parse.
  Idempotent: Edith calls it at every launch and when the token changes.
- `POST /v1/push/unregister` → `204`: forgets the phone's token (the owner switched the alerts off in Edith).
- `GET /v1/push/status` → `{ "configured": true|false, "registered": true|false, "lastResult": "ok"|"unregistered"|"error", "lastAt": <ms or null> }`, so Edith can show "Registered with Jarvis".
  `configured` is whether a key exists; it never says anything about the key.
Remove a phone (Jarvis Settings) removes its push token too.

## 4. When Jarvis sends
For each new event its existing Ring and Blink watchers raise (doorbell ring, person, motion), once per event (the watchers' own de-duplication applies; do not add a second poller):
send one push to every registered phone. The owner's per-kind choices live in Edith, not here, **so Edith tells Jarvis which kinds to send** in the registration: add to the register body
`"kinds": ["ring:doorbell","ring:person","ring:motion","blink:motion"]` (the ones switched on in Edith's Settings; empty list: send none). Edith re-registers whenever the choice changes.

## 5. The push (APNs HTTP/2, token-based)
- Host `api.push.apple.com` for `env: production` (TestFlight and App Store builds), `api.sandbox.push.apple.com` for `sandbox` (only builds run from Xcode). Path `/3/device/<token>`.
- Authorization: `bearer <JWT>`; the JWT is ES256 over `{ "iss": "<team id>", "iat": <seconds> }` with header `{ "alg":"ES256", "kid":"<key id>" }`, signed with the `.p8` key. Build it once and **reuse it for
  about 40 minutes** (Apple refuses tokens older than an hour and rate-limits new ones: `TooManyProviderTokenUpdates`).
- Headers: `apns-topic: <bundle id>`, `apns-push-type: alert`, `apns-priority: 10` for a doorbell and `5` for a person or motion, `apns-expiration: <now + 10 minutes, seconds>` (an old doorbell ring is not news),
  `apns-collapse-id: <the alert's key>` so the same event never shows twice.
- Body:
  `{ "aps": { "alert": { "title": "Front door", "body": "Someone rang the doorbell" }, "sound": "default", "interruption-level": "time-sensitive" | "active", "thread-id": "camera-alerts" },
     "edith": { "v": 1, "kind": "ring:doorbell", "camera": "Front door", "at": <event ms>, "key": "<the alert's key>" } }`.
  `interruption-level` is `time-sensitive` for a doorbell and `active` for the rest; never `critical`. Titles and bodies in English plain words ("Someone rang the doorbell", "A person was seen", "Motion was seen").
  No picture and no clip link in the payload (the payload crosses Apple's servers). Edith opens the clip list on a tap.
- One HTTP/2 connection kept open and reused (Node: `http2.connect`); reconnect after errors or `GOAWAY`; at most a few pushes a minute per phone (collapse-id covers repeats).

## 6. Answers from Apple and what Jarvis does
`200`: ok. `410 Unregistered`, or `400 BadDeviceToken` / `DeviceTokenNotForTopic`: forget that phone's token and show "Apple says this phone is gone: removed" (Edith registers again on its next launch).
`403 ExpiredProviderToken` / `InvalidProviderToken`: rebuild the JWT once and retry once; if it still fails show "Apple refused the key: check the Key ID, Team ID and bundle id in Settings". `429`/`5xx`: drop the push (do not queue: late alerts are worse than none) and note it.
`BadEnvironmentKeyInToken`/`TopicDisallowed`: say so in the status line. Never log the key, the JWT or a full device token (the last 6 characters are enough).

## 7. Tests and checks
`npm run typecheck`, `npm test`, `npm run build` all pass. With a **fake APNs HTTP/2 server** on localhost (the host is an injected setting): the JWT's claims and `kid` and that it is reused for 40 minutes; each header and the body for
a doorbell and for a motion event; collapse; the camera name left out when the box is off; 410 removes the token; a refused JWT retries once; the register/unregister/status routes with and without the device token; a malformed token; the kinds
filter; removing a phone forgets its token; the key never appears in any route's answer or in the log. A manual check with the real key: **Send test push** reaches the owner's phone.

## 8. Order of work and PR
One PR on the Jarvis side: Settings block and storage, the three routes, the sender and its tests. Edith's side (`ios-alerts-while-closed.md`) can be built against a pretend Jarvis in parallel.

## 9. Questions for the owner (do not guess)
1. Create the APNs key (developer.apple.com → Certificates, Identifiers & Profiles → Keys → "Apple Push Notifications service (APNs)") and type it into Jarvis's Settings yourself.
2. Is "Include the camera name" on by default acceptable? (It tells Apple the camera's name; nothing else.)
3. Does the owner want the push for events Jarvis sees while the phone is at home and Edith is open? Edith hides a push whose alert she has already shown herself (same key), so the default is yes.
