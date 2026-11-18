# C32 — Live camera view (v2)

| | |
|---|---|
| Phase | 8 — Smart home (v2) |
| Depends on | C27 (Ring), C29 (Blink) |
| Size | Large |
| Read first | 06-decisions **D19**; Jarvis's `ring.ts` (`startLive`), `blink.ts` (`startLive`), `blinkLive.ts`, `CameraWall.tsx` (references only) |

## Why
D19: Jarvis shows live video from Ring and Blink cameras, one at a time or every camera at once. The owner wants the
same on the phone. This is the largest and most fragile camera piece, so it has its own chunk.

## Specification (verify each service's current flow first)
- **Ring:** live view over WebRTC, as Jarvis (`ring-client-api`'s live call: an SDP offer, Ring's answer). Needs an
  Android WebRTC library: check its size, Android 17 support and R8 rules, and ask the owner before adding it.
- **Blink:** Blink's own stream (Jarvis's `blinkLive.ts`: the "immis" server, a handshake, then an MPEG-TS stream over
  TLS), played with Media3. Blink cameras on battery limit how long a view lasts; say so.
- **On the Home tab:** a Live button on a camera's picture; a **camera wall** with every camera live at once (closing
  it stops them all). A view stops when the app leaves the screen.
- **From chat:** "Show me the front door live" opens the live view (a button in Edith's bubble); the model is never
  sent video.

## Tests
Blink's handshake bytes and stream parsing against made-up captures (as Jarvis's `blinkLive.test.ts`); Ring's
signalling messages; stopping every stream when the screen closes.

## Acceptance
- [ ] Owner: a Ring and a Blink camera live; the camera wall; leaving the app stops the streams (battery).

## Not in scope
Two-way talk, recording live video, picture-in-picture.

## As built — part 1 (Blink live view; Ring, the camera wall and the chat button are still to do)
- **Done:** Blink live view end to end, and a **Live** button on a camera's row on the Home tab (any device with `Capability.Live`; Blink cameras
  and, in debug builds, the Demo doorbell). It opens a dialog with the picture; **Close**, or the app leaving the screen (`ON_STOP`), stops the
  stream and tells Blink the view is over (the camera keeps streaming until it is told). Blink's own limit is 5 minutes (`liveLimitMs`); the
  dialog then says so. Battery cameras limit a view too, which the dialog says.
- **Code:** `blink/BlinkLive` (the protocol, as Jarvis's `blinkLive.ts`: address, 122-byte handshake, keep-alives, `FrameReader`),
  `blink/BlinkLiveStream` (the TLS connection, run as a coroutine), `BlinkService` (`startLive`: the liveview request, then a `BlinkLiveFeed`
  that polls Blink's command and ends the view when Blink does), `BlinkApi.requestLive/liveRunning/endLive`, `BlinkParse.liveviewPath/liveAnswer/commandRunning`.
  Generic: `LiveFeed` (a channel of MPEG-TS bytes and an `ended` reason), `HomeBackend.hasLive/startLive`, `Capability.Live`, `Home.startLive`.
  UI: `home/ui/LiveView.kt` (`FeedDataSource`, a Media3 data source reading the feed; `LiveDialog`; the software-decoder fallback shared with Clips).
- **Tests:** `BlinkLiveTest` (9: handshake bytes, packets, frame reader, a stream against a pretend server) and four live tests in `BlinkServiceTest`
  (video passes on and the handshake carries the serial, Blink ending the command, the time limit, the reasons it can't start). `scripts/check.sh` OK.
- **Seen on the emulator (2026-11-17):** the Demo doorbell's Live button played a made-up MPEG-TS clip (looped, `app/src/debug/assets/demo-live.ts`,
  `home/DemoLive`) in the Media3 player; leaving the app closed the dialog and released the player.
- **Decided (owner, 2026-11-17): the stream's TLS certificate is not checked, as in Jarvis** (`BlinkLiveStream.tlsSocket`): Blink's stream servers are reached by address with
  certificates that don't name them, and Jarvis/blinkpy skip the check too. Only the camera's video and serial number cross that connection, never a token or
  password, and the exception is that one socket's. It is outside C37's "secure client" rule, with the owner's OK for this one socket only.
- **Not verified:** everything against a real Blink (the handshake, the liveview answer's shape per camera kind, how long a real view lasts, whether the
  iPhone's Andarium decodes the stream). Unknown until the owner tries it.
- **Still to do:** Ring live view (WebRTC: **needs the owner's OK for an Android WebRTC library** first, as the spec says), the camera wall, and
  "Show me the front door live" from chat.

## As built — part 2 (Ring live view)
- **Done:** Ring live view, with a Live button on Ring cameras and doorbells. It is WebRTC, receive only (video, and an audio section Ring wants, muted).
  The flow is simpler than the spec feared: no websocket, only `POST api.ring.com/integrations/v1/liveview/start` with `{session_id, device_id, sdp, protocol: "webrtc"}`
  answered with `{sdp}`, and `…/end` with the session id (read from `ring-client-api` v14.3.0's `SimpleWebRtcSession`). Candidates are gathered for up to 3 s before the offer,
  as Jarvis's `RingLive.tsx` does (Ring takes the whole offer at once). Ring's STUN servers are the two Kinesis ones Jarvis uses.
- **The library (owner approved 2026-11-17):** `io.getstream:stream-webrtc-android` 1.3.10 (Stream's build of Google's WebRTC, `org.webrtc` package, Apache 2.0). Checked before adding:
  its arm64-v8a and x86_64 libraries are 16 KB aligned (the two 32-bit ones are not, and Edith ships 64-bit only); about 5.4 MB each compressed. It is a community build, last released
  2025-09-11, so watch for it going stale; the alternatives found were LiveKit's `io.github.webrtc-sdk:android` and `dev.bargen.webrtc-android` (not checked).
- **Code:** `ring/RingRtc.kt` (`RingRtc` and `RtcCall`, and `WebRtcClient`, the real implementation), `RingApi.liveStart/liveEnd`, `RingService` (`startLive` and `RingLiveFeed`, which
  tells Ring when a view is stopped, lost or past its 5-minute limit). `LiveFeed` is now a base with two kinds: `StreamLiveFeed` (Blink's MPEG-TS bytes) and `TrackLiveFeed`
  (Ring's WebRTC track, drawn on a `SurfaceViewRenderer` in `LiveView.kt`'s `RtcPlayer`).
- **Tests:** four-plus live tests in `RingServiceTest` with a fake `RingRtc` (the JVM can't run the native library): the offer sent for the camera, Ring's answer handed over, the session ended at Ring on stop, a lost connection, the time limit,
  Ring refusing, an offline camera. `scripts/check.sh` OK, and `:app:minifyReleaseWithR8` builds with the library (the library ships its own keep rule `-keep class org.webrtc.** { *; }`).
- **Not verified, and cannot be without a real Ring:** that `WebRtcClient` (offer, candidate gathering, the answer, decoding the video) works at all. Nothing has loaded the native library or
  run a call; the emulator has no Ring. A release build must also be checked on `Edith_Release_Test` before publishing (R8 builds, but only a live call proves the keep rules).

## Found on the owner's phone (2026-11-17, v2.0.0-dev.18 in Andarium)
- **Ring: "Ring answered HTTP 400 for the live view."** Ring refused the start request. Likely cause (not yet confirmed): `device_id` was sent as a string; `ring-client-api` sends
  it as a number (Ring's device ids are numbers). **Change:** it is sent as a number now, and a refused live view shows Ring's own error text (`error`/`message`, 120 characters at most),
  so a repeat says why. Tests: `RingServiceTest` (the id is a number; the error text is shown). Still unverified against a real Ring.
- **Blink: "Blink ended the live view."** This one message covered two different causes (the stream server closing the connection, and Blink's live view command reporting it had stopped), so
  it could not say which happened. **Change:** the reasons now differ. A closed stream says whether any video arrived and how much ("closed the connection before any video arrived", "after N KB of video");
  a stopped command gives Blink's codes ("its command stopped: status_code …, state_condition …", as Jarvis logs them); both are also logged. The command poll now uses the camera's own network id,
  as Jarvis does. This is diagnostics, not a known fix: the cause is still open until the owner tries it again and reads the new text.

## Found on the owner's phone (2026-11-17, v2.0.0-dev.19 in Andarium)
- **Blink live view works** (a night picture from Front / Driveway). Blink's handshake and stream are verified against a real camera.
- **Ring: "The live view couldn't connect to Ring."** The HTTP 400 is gone: Ring now accepts the request and answers, and the failure is the WebRTC connection itself (the peer connection reports failed). Cause still open;
  the likely suspect is the network path from Andarium to Ring's servers (the video travels over UDP), which is a guess, not checked.
- **Blink crash when the screen rotated (a real crash, not a coincidence).** Stopping a view closes its video channel; the stream could be mid-read and its next `channel.send` threw "Channel was closed" in a
  coroutine with no handler, which killed the app. It could happen on any stop, not only a rotation. **Fix:** the stream hands video over with `trySend`, which does nothing on a closed channel.
  Test: `BlinkServiceTest › stopping a view while video is still arriving does not crash` (failed with the same exception before the fix).
- **Change:** a rotation no longer ends the live view. Edith stops a view when the app leaves the screen, and a rotation counts as that; the Home screen now ignores a stop that is only a rotation (as the chat screen already did), because the view model and the stream live on. Checked on the emulator: the Demo live view kept playing through rotations, and Close still worked.
