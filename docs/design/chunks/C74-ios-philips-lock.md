# C74 — Philips smart lock on iOS

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **Not needed — do not build.** The owner confirmed on 2026-11-20 that the lock, linked to Alexa, locks and unlocks from the Home tab (C54). Decision D38. |
| Depends on | C48 (services), C46 (the lock model and card rules), C45 |
| Unblocks | — |
| Runs in | Expo Go if the cloud API is plain HTTPS (likely); verify |
| Size | Medium |
| Ports | C31 |
| Read first | C31 (short), C21's "Checked on 2026-11-12" (Philips), C20 §5 (locks), D10, C46's `actions.ts` (`confirmationFor`: unlock always a card) |

## Why
The owner has a Philips lock (Philips Home Access; the model must be confirmed). There is **no official API**. Community
integrations call the Philips Home Access cloud with a signing key embedded in the Philips app and unlock through the lock's Wi-Fi
gateway. **Fragile and security-sensitive:** the key can change with any app update, and an unlock path built on it must be handled
with the most care in the whole app. The same decision C31 asks, again on iOS.

## Before you start — ask the owner (record the answer in `06-decisions.md`)
1. Confirm the **model** and that it has the **Wi-Fi gateway**.
2. **Do they accept an unofficial unlock path at all?** The safer alternative is **lock and state only** (no unlock): recommended unless the owner says otherwise. If the answer is "state and lock only", build only those and say in the tool text "Edith can't unlock this door".
3. The owner can't be signed in to the Philips app **and** Edith with the same account at once: they **share the lock to a second account** made for Edith. Walk them through it; the agent never sees either password.
4. If the owner has already linked the lock to Alexa (C54), **Alexa may be able to lock/unlock it** (the August path D19 describes): then this chunk may be unnecessary. Check C54's live result first.

## Specification
- **Sign-in:** the second account's email and password, typed by the owner into Edith's form (C48 `SignInForm`); keep **only the session token**, encrypted (`SecretStore` `home.philips`). The password is used once and never kept.
- **Protocol:** read the community reverse-engineering the Android chunk would have used (verify what exists today; **write what you found and its licence in the PR**; do not guess endpoints). Requests are signed with the app's embedded key: keep the key and signing in **one module** (`philips/sign.ts`) with test vectors worked out by hand from the source; a signing change must be a one-file fix.
- **State:** locked / unlocked / jammed, battery, last operation → `DeviceState { lock, battery, changedAt }`; tile text "Locked · battery 80% · moved 3 h ago".
- **Lock:** no card (C46's rule). **Unlock (only if accepted):** **always a card, never by voice alone** (D10): on iOS the conversation mode (C57/C69) must **not** approve unlock; this chunk adds a test that a hands-free-sourced command for this device returns the "tap Confirm on the screen" path. The command log records it (source and route).
- **Errors:** a refused token → "Sign in again"; a vendor change (signature rejected) → a plain message ("Philips changed something; Edith can't reach this lock right now") **and no retry loop**; other services keep working.
- **Never log** the token, the signing key or payloads; a leaked unlock path would be the worst failure in the app.

## Files
`philips/{sign,api,parse,service}.ts`, tests with a pretend Philips cloud, `docs/philips-setup.md` (the sharing steps); changed `core/services.tsx`.

## Tests
Signing vectors; state parsing from made-up responses; lock goes through without a card; **unlock needs a card (and is absent entirely in lock-only mode)**; a voice-sourced unlock is refused; refused token → sign in again once; logs contain no secrets (a test that runs a full flow with a logger spy).

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner:** sign in with the shared account; "Is the front door locked?"; "Lock the front door"; if accepted, "Unlock the front door" → card → tap; check the Philips app shows the same state.

## Not in this chunk
August locks (no partner key: C21; consider Matter/HomeKit in a separate chunk if the owner's model supports it), the Philips video features.

## Outcome (2026-11-20)
The owner answered the questions above: the lock has the Philips Wi-Fi gateway and they would share it to a second account; they would accept an unlock path behind a card; **and the lock is linked to Alexa, where it works** (lock and unlock from the Home tab). Question 4 above decided it: Alexa covers the need, so this chunk's unofficial signing key and second account are not needed. The unlock rules stay as C46 and C54 built them (a card every time; conversation mode never approves). If Alexa stops covering the lock, reopen this chunk and start from the answers above.
