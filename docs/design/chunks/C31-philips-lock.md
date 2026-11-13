# C31 — Philips smart lock (v2)

| | |
|---|---|
| Phase | 8 — Smart home (v2) |
| Depends on | C25 |
| Size | Medium |
| Read first | C21 (Philips, "Checked on 2026-11-12"), C20 §5 (locks), 06-decisions **D10** |

## Why
The owner has a Philips lock (Philips Home Access, model to confirm). There's no official API. Community integrations
call the Philips Home Access cloud with a signing key embedded in the Philips app, and unlock through the lock's Wi-Fi
gateway. **Fragile and security-sensitive:** the key can change with any app update, and an unlock path built on
it must be handled with the most care in the whole app.

## Before you start
- Confirm the model, and that it has the Wi-Fi gateway. Ask the owner whether they accept an unofficial unlock path at
  all; offering **lock and state only** is the safer alternative.
- The owner can't be signed in to the Philips app and Edith with the same account at once: they share the lock to a
  second account for Edith.

## Specification
- **Sign-in:** the second account's email and password, typed by the owner; keep only the session token, encrypted.
- **State:** locked/unlocked/jammed, battery, last operation. **Lock:** no card (C20 §2). **Unlock (if the owner
  accepts it):** always a card, **never by voice alone** (D10), and the command log records it.

## Tests
Request signing against made-up captures; state parsing; unlock refusing a voice-only confirmation; a refused token →
Sign in again.

## Acceptance
- [ ] Owner: sign in with the shared account, "Is the front door locked?", "Lock the front door", "Unlock the front
      door" (card, tap only, if accepted).

## Not in scope
**August** locks: Yale/August now require a partner key (C21), so there's no August chunk unless the owner's model
supports Matter or HomeKit, which would need a Matter controller chunk of its own.
