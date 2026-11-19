# C39 stage 3 — handoff notes (living document)

Written for the next agent. Started 2026-11-19 by Claude Sonnet 5.5 when the owner's weekly allowance was running down. **Keep this file current as you work; delete it when stage 3 ships** (its lasting content then belongs in the "As built" sections of `chunks/C39-printer-parity.md`).

Read first, in this order: `AGENTS.md`, `docs/design/README.md`, `chunks/C39-printer-parity.md` (E11/E12 at "### Stage 3", and the "As built" sections for E1–E10b), `jarvis-printer-feed-spec.md` (6.7 upload, 14.6 events, 14.7 upload).

## Where things stand

| Item | State |
|---|---|
| Stages 1 and 2 (E1–E10b) | Merged to master and **released as `v2.0.0-dev.27`** (versionCode 27). |
| E11 (upload, Print, Add to queue, Delete) | **Built, unit-tested, device-tested (`PrinterScreenTest`, 63 tests) and checked on the emulator against the pretend feed**, in worktree `D:\Dev Projects\edith\.worktrees\c39s3`, branch `c39-e11-upload` (from master 6bb7938). **Not committed yet** (the repo rule: ask the owner before committing). Left: `scripts/check.sh`, then commit and PR (checklist below). The chunk file has an "E11 as built" section. |
| E12 (print alerts) | **Not started.** Plan in the chunk file; notes below. |
| Stage 4 (E13 recordings, E14 live camera and screen taps) | Not started. Needs Jarvis J4 (merged on the Jarvis side per the spec's part 14). |

## How E11 is built (so you can continue without re-reading everything)

- `printer/feed/PrinterUpload.kt` — pure rules and the streaming body.
  - `problem(name, size)` returns plain words or null. It copies Jarvis's own rules: `UPLOAD_NAME = /^[A-Za-z0-9 _.()+-]{1,100}\.(gcode|3mf)$/` (**no case flag: `.GCODE` is refused by Jarvis, so here too**; the 100 is the part *before* the ending) and `UPLOAD_MAX_BYTES = 64 * 1024 * 1024` (64 MiB exactly). Source: `D:\Dev Projects\jarvis\src\main\feed\printerRoutes.ts` lines 73 and 77.
  - `body(source, size, type, onProgress)` streams in 64 KB chunks, stops at the promised size, reports bytes sent, is one-shot.
- `PrinterRoutes.upload(name, source, size, onProgress)` / `PrinterFeedApi.upload` — the route existed since E2; E11 added the progress callback and moved the body into `PrinterUpload`. 300 s call timeout (`UPLOAD_SECONDS`). Cancelling the coroutine cancels the OkHttp call (`JarvisApi.await`).
- `printer/ui/PrinterCardData.kt`:
  - `FilesUi.upload: UploadUi` (name, active, sent, total, done, error).
  - `chooseUpload(name, size, open)` then `sendUpload()` — choose checks the rules and waits; send opens the stream on `io` (a constructor parameter, default `Dispatchers.IO`; tests give their own), streams, shows Jarvis's words on a refusal. One at a time. `cancelUpload()` clears all upload state. `clearUpload()` clears a finished result. `detached()` stops a running upload and leaves "The upload stopped because the page was closed."
  - `commandSent(command)` — called by `PrinterControl.onSent` when Jarvis **accepted** a command: `DeleteFile` removes the file from the cached list (the list is **not** re-requested: listing freezes the printer for seconds), `Start`/`QueueAdd`/`DeleteFile` close the file sheet. A refusal or a "No" on the confirmation leaves the sheet open.
- `PrinterControl.onSent` (new hook, set in `PrinterViewModel`'s `init`).
- UI (`cards/DataCards.kt`): `FilesCard` takes `ctx` and the upload callbacks; `UploadRow` (button, progress bar with "x of y", Cancel, "Sent X. Refresh the list to see it.", errors); the file detail dialog has **Print / Add to queue / Delete** (`CmdButton`s, so they use the existing confirm sheet, spinner and `Refusal` line) shown only when `ctx.mayControl`. Test tags: `upload-row`, `upload-button`, `upload-progress`, `upload-progress-text`, `upload-cancel`, `upload-done`, `upload-error`, `file-print`, `file-queue`, `file-delete`.
- `PrinterScreen.kt` — the `OpenDocument` picker (`*/*`: `.gcode` has no media type, so the name is checked after picking), `UploadPick.describe` (`ui/UploadPick.kt`) reads the display name and size from the `ContentResolver` without opening the file.
- Tests added: `PrinterUploadTest` (12: the gates, the body), `PrinterFeedApiTest` (+3: 2.5 MB arrives byte for byte with progress, refusals in words, Jarvis away), `PrinterUploadDataTest` (11: exact bytes, gates, unreadable file, refusal, progress and cancel, one at a time, leaving the page, clearing, delete/print/queue effects on the list and sheet). `FakePrinterRoutes` got `uploadCalls`, `uploadAnswer` and `uploadGate`.

## Findings from the emulator check (worth remembering)

- **The picker ends Controls.** Opening the system file picker takes the Printer page off screen (`ON_STOP` -> `detach` -> Controls off, on purpose). A one-step "pick and send" was therefore always refused with `CONTROLS_OFF`. Fixed with two steps: **choose** (no Controls needed), then **Send** (Controls on). Unit tests with fakes could not show this; only the real picker did. Remember it for **any** feature that leaves the page (stage 4's save-to-Downloads picker for recordings has the same shape).
- **Three buttons in a dialog `Row` overflow a phone.** Delete was squeezed into a vertical strip; now a `FlowRow`, with a device test on the button width.
- **The default "Printed before" filter hid a new upload**; a successful upload now switches the list to All.
- The pretend feed now keeps the exact bytes of an upload and prints `upload <name>: <n> bytes, sha256 <hash>` (compare with `sha256sum` of the file).
- **Port 47821 is the REAL Jarvis app on this PC** (an `electron.exe` from `D:\Dev Projects\jarvis`). Run the pretend with `PORT=47822 node scripts/fake-jarvis.mjs` and pair the debug app to `10.0.2.2:47822` (code `ABCDE-FGHJK`). Before opening the Printer page on the emulator, confirm Settings -> Home -> 3D printer says "Jarvis: not paired" or is paired to the pretend, never to the real Jarvis. Never kill a process you did not start; check its command line first.

## State left on the emulator

The standard emulator (`emulator-5554`) is running with the **debug** app (`com.edith.app.debug`) built from this branch, and it is **still paired to the pretend feed at `10.0.2.2:47822`**, which has been stopped (the Work account and the Printer page will say Jarvis is away). Unpair it in Settings -> Accounts -> Work if you want it clean, or start the pretend again on that port. The test files (`tiny.gcode`, `big.gcode`, `bin.3mf`, `model.stl`) are in the emulator's Download folder.

## An open question about the device tests

`PrinterScreenTest` passed all 63 in two runs in a row, but one earlier full run (right after an install, with the Printer page of the debug app still open and polling the pretend feed) reported **1 failure of 63 whose name I did not capture**; the test the name most likely belonged to (the file-sheet buttons) passed on its own. **It is not explained and not proven flaky.** If you see a device-test failure, save the whole `am instrument` output to a file first (`... | tr -d '\r' > out.txt`) and read the failure block before rerunning (lesson L45: read the report before calling a failure flaky). A full `PrinterScreenTest` run takes 2 to 10 minutes.

## Decisions made on the owner's behalf in E11 (tell the owner)

1. **Upload is drawn only for a phone that may control** (like every control). **Choosing** a file works with Controls off; **Send** is disabled with Jarvis's reason until Controls are on (Jarvis refuses an upload without Controls, as the spec says).
2. **The picker shows every file** and the name is checked afterwards, because the design said "limited to names ending .gcode/.3mf" but Android cannot filter by extension for a type with no media type.
3. **Leaving the page cancels an upload** (the Printer page's `ON_STOP` calls `detach`; Android 17 blocks background networking anyway). The owner sees why when they return.
4. **After a successful upload the list is not refreshed automatically** (it freezes the printer); the card says "Refresh the list to see it" (not while printing).

## Not verified (say so in the PR)

- Nothing has been sent to the real Jarvis, and **never** to the real printer (spec part 1: no `POST`, upload or delete to the real printer without the owner's explicit approval for that exact action).
- A real picked file through `ContentResolver` (the unit tests use byte arrays; the emulator check below uses a real file).
- Upload progress feel on a slow link; cancel mid-flight against a real Jarvis (Jarvis "throws away" a half-received file per spec 14.7: untested here).

## Checklist for finishing E11 (in order)

1. [x] (done, 63 pass) Device UI tests in `app/src/androidTest/java/com/edith/app/printer/ui/PrinterScreenTest.kt` (53 tests today; add: Upload button only with Controls on a control phone, progress and cancel drawn from a `UploadUi`, result lines, the three file buttons, the refusal line under the buttons). Run with `scripts/check.sh --device` **only on an emulator that is not the owner's** (see AGENTS.md: never `connectedDebugAndroidTest`, never uninstall).
2. [x] (done) Emulator check against the pretend feed: `PORT=47822 node scripts/fake-jarvis.mjs` (see the findings above and the top of that file), pair the debug app, put a small `.gcode` in the emulator's Downloads (`adb push`), Files card → Upload → pick it → bytes arrive (the pretend logs them). A `.3mf` **Print** shows "Start .3mf files from the printer’s screen." (the pretend's refusal).
3. [x] (done 2026-11-19, `build-logs/2026-11-19_14-24-19_check.log`) `scripts/check.sh`: build successful, all unit tests pass, **"Lint: 0 errors, 1 warning"** (the warning is `CustomX509TrustManager` in `blink/BlinkLiveStream.kt:99`, not from this work). Rerun it after any further change and report the line exactly.
4. [ ] Update `chunks/C39-printer-parity.md` with an "E11 as built" section (copy the style of E10b), and `docs/design/README.md` / `AGENTS.md` if the status line changes.
5. [ ] Ask the owner, then commit, push and open the PR titled `C39 E11: upload, print, delete and queue add from the Files card`. Branch name already `c39-e11-upload`.

## E12 — print alerts (plan, not started)

From the chunk: `alerts/PrinterAlertWatcher.kt`, `AlertPrefs.kt` (a `printer` choice), `AlertService.kt` (run the watcher when the printer alert is on and Jarvis shares it), a Settings row **Print alerts** (default on when the printer is shared; says "Needs Jarvis and a connection to the PC"), `AlertNotifier.printer(event)` (channel "Printer", tap opens the Printer page), `lastPrinterSeq` in DataStore.
Behaviour: loop `events(after = lastSeq, wait = 20)`; one notification per new event with the id derived from `seq` (a repeat cannot double); update `lastSeq`; failures back off 5 s → 5 min and are quiet (one log line, no bodies); stop when the switch goes off or the account is gone. **Away without Tailscale: no alerts.** `PrinterFeedApi.events` and `EventsAnswer` already exist (E2).
Things to check before you start: how `AlertService` (C33, `alerts/`) runs its Ring/Blink watchers and how `AlertPrefs`/`AlertNotifier` are structured, so the printer watcher is a sibling, not a rewrite. Jarvis's `seq` restarts when Jarvis restarts (spec 14.6): **take `seq` from the answer, always**, and treat a smaller `seq` than the stored one as a restart (do not drop events because of it). Tests: dedupe by seq, a restart does not repeat, backoff, one notification per event, stop on switch-off (fake routes + fake notifier). Done when the pretend feed's `finished` event raises a notification that opens the page. Stage 3 ships with E12 (then a release is the owner's call).

## Environment gotchas that cost time

- The bash tool breaks on apostrophes/backslashes in inline scripts: write scripts with the Write tool, not heredocs.
- Files in this repo may be CRLF: use the Edit tool; whole-file rewrites from Node must keep the original line endings.
- A new worktree needs `scripts/fetch-sherpa.sh` once (the library is not in Git).
- A hung unit test (a view-model loop on a shared test scheduler) means the test forgot `detach()`/`detached()`; kill the Gradle test worker by exact PID.
- Lessons that bite here: L45 (declare what an `init`-started coroutine uses **above** the `init`), L40 (the lint line you report must match the log), and never call a failure "flaky" before reading CI's saved report.
- Run the focused unit tests with `./gradlew :app:testDebugUnitTest --tests '*PrinterUpload*'` (about 10–40 s once warm); the full `scripts/check.sh` only before a commit.
- Don't touch the real printer or the real Jarvis without the owner's say-so; `scripts/fake-jarvis.mjs` is the pretend feed.
