# C27 — Ring doorbell and cameras (v2)

| | |
|---|---|
| Phase | 8 — Smart home (v2) |
| Depends on | C25 |
| Size | Medium–large |
| Read first | C21 (Ring, and "Checked on 2026-11-12"), C23 trap 1 (rotating refresh tokens), 06-decisions **D10** |

## Why
The owner has Ring. Ring's official API is for business partners only, so Edith uses the flow the maintained
`ring-client-api` (Node) uses, rewritten in Kotlin.

## Specification (verify against `ring-client-api` first)
- **Sign-in:** email, password and the 2FA code, once, typed by the owner; keep only the refresh token, encrypted.
- **Rotating refresh token:** Ring hands out a new one on refresh and the old one stops working soon after. **Save the
  new one before using the access token**, every time (as C23 does for Microsoft); losing it signs the owner out.
- **Devices:** doorbells and cameras (`Camera`, `Doorbell`; `Snapshot`, `Events`), with battery and online state.
- **Snapshots** (`camera_snapshot`) and **event history** (`recent_events`: rings and motion, last 24 h by default).
- No live view (WebRTC/SIP) and no push notifications in this chunk.

## Tests
Sign-in with 2FA; the rotated token stored before use; snapshots (JPEG bytes); event parsing; a dead token → Sign in
again; one Ring failure not stopping other backends.

## Acceptance
- [ ] Owner: sign in, "Show me the front door camera" (a snapshot in the bubble), "Did anyone ring today?", the Home
      tab; a restart keeps it signed in.

## Traps
Unofficial; never log tokens or images; snapshots of a sleeping battery camera can be old or slow; say so.
