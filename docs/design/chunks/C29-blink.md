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
