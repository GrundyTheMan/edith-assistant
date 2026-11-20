# C45 — Confirmations on iOS

| | |
|---|---|
| Phase | 9 — iOS (Expo) |
| Status | **Built** in Expo Go; see "As built" at the end. Nothing in the app asks yet, so there is nothing for the owner to try |
| Depends on | C41 (the tool loop) |
| Ports | C08 §6 (confirmations), D11 |
| Unblocks | Everything on iOS that changes something outside the app: calendar and mail writes, to-dos, home commands, the printer |

## Why
D11: Edith asks before she changes data outside the app. The Android tool loop does this with a card in her bubble; the
iOS tool loop (C41) had no way to ask. Every later iOS tool that writes needs it, so it is built once, first.

## Specification (as on Android)
- A tool may define `confirmation(args)`: a title ("Add to your calendar?"), label/value detail rows, and optional button
  labels. `null` means this call needs no approval.
- `runToolTurn` asks (`confirm` in its input) **before** `execute`, with an id and the tool name filled in. Confirm →
  the tool runs. Cancel → it does not, and the model gets "The user declined: <title> Don't retry unless they ask." as an
  ordinary result (not an error). No `confirm` available → the action is not run and the model is told why. A missing
  argument is reported before anything is asked.
- `ChatEngine` publishes the request in its `working` state (`confirmation`) and takes the answer with
  `resolveConfirmation(id, approved)`; an answer for another id is ignored. Stopping the reply while the card is up
  cancels the turn and runs nothing; a late answer changes nothing.
- The card sits in Edith's in-flight bubble: title, rows, **Cancel** ("Cancel action") and **Confirm** ("Confirm action").
- Not here: "screen-only" cards for hands-free (no hands-free on iOS yet), and waiting across an app restart (a killed
  app cancels the turn, as on Android).

## Tests
The loop with a fake tool: approve runs it, decline does not and says so, a tool with nothing to confirm never asks, no
way to ask means not run, missing argument first, stop while waiting; the engine on real SQLite (the request appears in
`working`, Confirm runs the tool and stores the reply, Cancel still gets an answer, Stop leaves the engine idle); the card.

## Acceptance criteria
- [x] `scripts/ios-check.sh` is green.
- [ ] The first tool that uses it (calendar, mail, home) shows the card on the iPhone and Confirm / Cancel behave.

## As built (2026-11-20)
Built by an AI agent; Android is untouched.
- Files: `tools/framework/tool.ts` (`ConfirmationSpec`, `ConfirmationRequest`, `Tool.confirmation`),
  `tools/framework/toolRunner.ts` (`approve`, `confirm`), `chat/engine.ts` (`resolveConfirmation`), `ui/ChatComponents.tsx`
  (`ConfirmationCard`), `ui/ChatScreen.tsx`; tests in `tools/confirmations.test.tsx`.
- **Not verified:** the card on a real iPhone (layout, button sizes), because no shipped tool asks yet.
