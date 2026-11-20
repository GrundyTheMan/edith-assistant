# C71 — Live camera view on iOS (Blink stream, Ring WebRTC) and the camera wall

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **Design** — three PRs, each starts with a feasibility spike: (a) Ring over WebRTC, (b) Blink's stream, (c) the camera wall and the chat button |
| Depends on | C58 (development build with WebRTC and TCP sockets), C51 (Ring), C52 (Blink), C53 (the player component, clips) |
| Unblocks | C72's "open the live view from an alert" polish |
| Runs in | the development build only |
| Size | **XL** (the most fragile chunk in the roadmap) |
| Ports | C32 (`BlinkLive`, `BlinkLiveStream`, Ring's live call, `LiveFeed`, the Live dialog) |
| Read first | C32 spec **and** As built (Blink's protocol: address, 122-byte handshake, keep-alives, `FrameReader`; the software-decoder fallback; the 5-minute limit; what is still to do), D19, Jarvis's `ring.ts` (`startLive`), `blink.ts` (`startLive`) and `blinkLive.ts`, `ring-client-api` source (the WebRTC session), C53 |

## Why
The owner wants live video from Ring and Blink cameras on the phone, one at a time and as a wall, as Jarvis has. It is the
largest and most fragile camera piece. **Two very different mechanisms**, so two feasibility spikes:

## Part (a) — Ring live view over WebRTC
- **Library:** `react-native-webrtc` + the `@config-plugins/react-native-webrtc` config plugin (native; **needs a new build**; adds camera/microphone permission strings: set `NSCameraUsageDescription` and `NSMicrophoneUsageDescription` text; we only **receive** video, never open the phone's camera: pass `{audio:false, video:false}` and a recvonly transceiver; verify iOS doesn't prompt).
- **Signalling:** exactly as `ring-client-api`'s live call (read the library: it creates the session through Ring's REST client, exchanges an SDP offer for Ring's answer, sends ICE candidates, and keeps the call alive; copy the **endpoints and body shapes from the library's source**, not from memory). The access token path is C51's (rotating token rules apply: save-before-use, no overlapping refresh).
- **UI:** `RTCView` in a modal with Close, a "Live · 0:42" timer, and an error line; **closing or backgrounding the app ends the call** (`pc.close()`, stop tracks) and tells Ring the view is over. A battery camera's view is time-limited: say so when Ring ends it.
- **Feasibility spike (1 day):** a throwaway screen that connects to one camera and shows frames. If the call connects but there is no video on iOS, record the ICE/SDP logs (no tokens) and ask the owner before more time is spent.
- Add `Capability 'live'` and `HomeBackend.hasLive/startLive` for Ring devices; the Home tab shows a **Live** button on camera rows (C48 declared `startLive`).

## Part (b) — Blink's live stream
Blink uses its own protocol (C32 As built): ask Blink for a live view (`requestLive`, poll the command), connect over **TLS** to the "immis" server named in the answer, send a **122-byte handshake** carrying the serial and the session, then read an **MPEG-TS** stream; keep-alives; Blink ends it after **5 minutes** (the dialog says so); a battery camera limits it too; tell Blink the view is over when closing (the camera keeps streaming until told).
- **Network:** `react-native-tcp-socket` TLS (C65's `net/socket.ts`), the same `Connect`; port `BlinkLive.ts` from Jarvis's `blinkLive.ts` and Android's `BlinkLive.kt` (**byte-exact handshake with unit tests from Android's `BlinkLiveTest`: 9 tests incl. handshake bytes, packets, the frame reader**).
- **The hard part is playback.** AVPlayer cannot read a raw MPEG-TS byte stream from a custom socket; Android used a Media3 `DataSource`. Options, in the order to try:
  1. **Local HLS:** write the incoming MPEG-TS into rolling **2-second segment files** in the cache directory (split on the stream's keyframes: parse the TS packets' PAT/PMT and the PES start with the random-access indicator), maintain a live `index.m3u8` playlist file, and play it with `expo-video` (`file://` playlist); delete old segments. Verify first that AVPlayer plays a **local file HLS playlist** (it does for file URLs with local segments; test with a canned `.ts` file from the owner's earlier run).
  2. A **native module** (Swift) with `AVAssetResourceLoader`: can't be compiled without an EAS build; last resort.
  3. **Give up gracefully:** "Live view isn't available for Blink on iPhone yet; the Snapshot button works" and keep C52's snapshots. Ask the owner before spending more.
- **Feasibility spike (1–2 days):** the segmenter on a recorded stream file, played by `expo-video`.

## Part (c) — The camera wall and the chat button
- **Camera wall:** a screen showing every live-capable camera at once (a grid of live views with names; **closing the screen stops every stream**, `AppState` background stops them too). Limit the number of simultaneous streams to what the phone sustains (start with 4, then test). Battery cameras: only on request (a tap on the tile).
- **From chat:** "Show me the front door live" → a tool `show_camera_live(device)` returns a text "Opening the live view…" and an **action button in Edith's bubble** ("Watch live") that opens the modal (the model is never sent video). Add `MessageAction`-style support to the bubble (a typed `actions` field on the in-flight/stored message is **not persisted**: the button appears with the reply only; after a restart the message shows "Live view isn't kept").

## Files
`live/{feed,blinkLive,blinkSegmenter,ringLive}.ts`, `ui/LiveView.tsx`, `ui/CameraWall.tsx`, tests, `package.json` (`react-native-webrtc` + config plugin), `NATIVE_CHANGES.md` (**needs a new build**).

## Tests
Blink handshake bytes/packets/frame reader (port Android's 9 tests, recomputing bytes from the protocol source); the TS packet parser and keyframe segmenter on a canned stream (segment boundaries, PAT/PMT, partial packets, discontinuities); the HLS playlist writer (sequence numbers, target duration, old segments deleted); Ring signalling against a fake REST (offer → answer, ICE posting, call end); lifecycle (background/close stops everything: assert `close` called on all); the wall's concurrency limit; the chat button appears and opens the live modal.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green for each PR.
- [ ] **Owner, on the iPhone:** a Ring camera live; a Blink camera live (if the spike allowed it); the wall; leaving the app stops the streams (no camera light, no battery drain: check Ring/Blink app showing the camera idle); Blink's 5-minute end message.

## Risks and unverified assumptions
- Everything here is the owner's real devices' behaviour; nothing is verified. Any part may be dropped after its spike with the owner's agreement. Document the outcome per part in "As built".
- Battery cameras limit live sessions (Ring/Blink) and each view wakes the camera.

## Not in this chunk
Two-way talk, recording live video, picture-in-picture, AirPlay.
