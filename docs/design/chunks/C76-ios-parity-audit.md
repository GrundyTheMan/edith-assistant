# C76 — Parity audit and polish: close the gaps against the Android app

| | |
|---|---|
| Phase | 9 — iOS (Expo), the last chunk |
| Status | **Design** — run it after the chunks the owner actually wants are built |
| Depends on | everything the owner wants from C48–C75 |
| Unblocks | the owner retiring Andarium |
| Runs in | the best build available (standalone if C75 is done, else development) |
| Size | Medium |
| Ports | the whole Android app, as a checklist |
| Read first | `00-product.md`, `ios-roadmap.md`, every chunk's "As built" and "Not verified" |

## Why
Chunk by chunk it is easy to ship something that works in tests and feels wrong in the hand. This chunk is a **deliberate
walk-through** of the iOS app against the Android app and the owner's real day, producing a short list of fixes and doing them.

## Method (do these in order; write the results into the PR as tables)

### 1. Feature parity table
Create `docs/design/ios-parity.md`: one row per user-visible Android feature (from `00-product.md` §3–4, the chunk table, and a **fresh read of the Android UI**: run the Android app on the emulator and click through every screen), with columns **Android / iOS status (built, partial, not planned, blocked) / notes / the chunk**. Start from this list and add what you find:
chat (stream, stop, regenerate, copy, Play), conversations (list, rename, delete, new, Clear), titles, tools (time, weather, location, web search/read), attachments (photos, audio, Edith's files, resends, retention), voice (memo, dictation→keyboard, speech, own voice→n/a, hands-free/conversation, wake word), accounts (Google, Microsoft, iCloud, Work via Jarvis), calendar (tools, tab), mail (tools), to-dos (tools, tab), home (tab, chips, tools, each service, clips, live, alerts, printer card, printer page), settings (every Android setting), notifications.
**Rule:** a "not planned" row needs a reason the owner accepted; a "partial" row names the missing part and a chunk or "not worth it".

### 2. The owner's checklist walk
For each chunk with an unchecked owner acceptance box (C40–C47 and onwards), **sit with the owner** (or send a checklist) and run it on the phone. Record each result in that chunk's "As built" and tick the box. Anything broken becomes an item below (a "fix" if it repairs broken code, a "change" otherwise: AGENTS.md wording).

### 3. Quality pass (each item a small commit; none a big refactor)
- **VoiceOver:** every control has an `accessibilityLabel` and a role; the order is sensible; images have labels; the confirmation cards read their title and details; Reduce Motion respected.
- **Dynamic Type:** text scales (RN `allowFontScaling` default is on): check the chat, the cards, the Home tiles at the largest sizes; nothing is cut off.
- **Dark mode:** every screen in both modes; contrast of muted text; the PrinterCard charts.
- **Keyboard and safe areas:** input row above the keyboard on every screen with a text field; the notch/home indicator.
- **Performance:** a conversation with 500 messages (generate with a debug seeder in a test, not the owner's data): scrolling, memory; the Home tab with 60 devices; startup time (record cold-start to first chat paint on the owner's phone).
- **Errors:** every `AppError` kind has plain words; no raw exception text reaches the screen; the "Used: …" tool line; offline behaviours (PC off: chat says so; a service down: the banner).
- **Strings:** one pass for typos and tone (Edith's voice is warm, brief).
- **App icon, splash, name:** final assets present; the display name is "Edith".
- **Privacy review:** grep the diff of the whole app for `console.`, `log.` of anything sensitive; run the app with a log capture and read it for secrets (tokens, email text, conversation text) in a release build.
- **Data:** the SQLite file contains no attachment bytes (scan, as C16's test did); the Keychain holds secrets, the key-value store holds none.

### 4. Optional small features, **only if the owner asks** (each its own tiny PR)
- A **camera button** in the attach menu (`expo-image-picker.launchCameraAsync`, permission string) for a new photo.
- A **Share Extension / "Ask Edith" from other apps** (needs a native extension target: EAS build; non-trivial).
- A **Siri/Shortcuts App Intent** "Ask Edith" that opens the app in conversation mode (native).
- Haptics on send/confirm (`expo-haptics` is already installed).

### 5. Retire the old setup
When the owner agrees the iOS app replaces Android-in-Andarium: update `README`, `AGENTS.md` and `00-product.md` to say so; the Android app remains for Android phones (D27).

## Deliverables
`docs/design/ios-parity.md` (the table, kept current), a list of fixes/changes done with commit links, the updated "As built" of every checked chunk, and the owner's sign-off line in the PR ("Owner accepts: …, date").

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] Every row of the parity table has a status and, if not "built", a reason the owner accepted.
- [ ] The owner has used the app for a week of real days and reported no crash (**crashes are never acceptable: investigate every one**, AGENTS.md).

## Not in this chunk
New features beyond §4, Android changes.
