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
