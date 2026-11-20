# C53 — Camera clips on iOS (the Clips chip, list and player)

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave A |
| Status | **Built** in Expo Go; see "As built" at the end. The owner's check with real clips (and whether Blink's header reaches the player) is open |
| Depends on | C51 (Ring) **or** C52 (Blink): either gives it clips |
| Unblocks | C72 (alerts open the Clips list) |
| Runs in | Expo Go |
| Size | Medium |
| Ports | C27 part 2 and C29 (Clips), C25 §5 (the Home tab) |
| Read first | C27 "As built — part 2" and "Found on the owner's phone: playing a clip" (the 404 and the endpoint order), C29 As built (`clipSource`, headers), C32 As built (software-decoder fallback) |

## Why
Both services record clips (Ring with a Ring Protect plan, Blink always). The owner wants to browse and play them on the Home
tab as Jarvis does. On iOS the player is `expo-video` (AVPlayer), which handles H.264 natively; it needs a way to send a header
(Blink's video requires the bearer token).

## Specification

### 1. Backend methods (declared in C48; implemented by Ring and Blink here)
`hasClips: boolean`, `clips(sinceMs, untilMs): Promise<DeviceClip[]>`, `clipSource(clipId): Promise<{ uri: string; headers?: Record<string,string> }>` (replaces C48's `clipUrl` placeholder: rename it in this chunk).
- Ring: `clips` = `video_search/history` per camera (Android C27 part 2); `clipSource` asks, in order, `dings/{id}/share/play` (the video as recorded), then `…/recording` (transcoded), then the signed `lq_url` the search listed (Jarvis plays from it). **Ask for the address at play time, not at list time** (Ring's addresses are signed and expire). Only `https` addresses are played.
- Blink: `clips` = `media/changed` windows (C52); `clipSource` = the media URL with `Authorization: Bearer <token>` (and `Accept` as the reference sends).
- No Ring plan: `had_subscription` false on every recent event → the message "Ring keeps recordings only with a Ring Protect plan, and this account doesn't have one" and stop looking further back. **Never an error.**

### 2. `Home.clips` and the Home tab
- `Home.clips(sinceMs, untilMs)`: ask every connected service that `hasClips`, in parallel, each with the 10 s limit; one failing doesn't hide the others (a problem line instead); merge, sort newest first. `Home.hasClips` is a `Store<boolean>` true while any connected service has clips (so a signed-out service doesn't show the chip: the C27 bug).
- A **Clips** chip on the Home tab (shown only while `hasClips`) opens a full-screen list (`ui/ClipsScreen.tsx`, a modal route or a `Modal`): newest first; row = camera name, what made it record (Ring, Motion, A person, Live view), the length (Blink gives none: omit), "Today 3:42 PM".
- **Paging:** 3-day windows going back up to 60 days; a quiet window loads the next older one (up to 4 per scroll) so the list isn't empty while older clips exist; no numbered pages.
- Tap a clip → `ui/ClipPlayer.tsx`: `expo-video`'s `useVideoPlayer({ uri, headers })` and `VideoView` (native controls; install `npx expo install expo-video`, it is in Expo Go). The player is **released when the modal closes** (a clip never plays on behind it; call `player.release()` in the cleanup). A failure shows "This phone couldn't play the clip" plus the player's error text.
- Pull to refresh; the list keeps what it has while loading more.

### 3. Chat
No new tool. `recent_events` (C51/C52) mentions the Clips chip in its text when clips exist ("Open the Clips list on the Home tab to watch it").

## Files
New: `ui/ClipsScreen.tsx`, `ui/ClipPlayer.tsx`, `home/clips.ts` (windowing, merge), tests `home/clips.test.tsx`. Changed: `home/home.ts` (`clips`, `hasClips`), `home/model.ts` (rename `clipUrl` → `clipSource`), `ring/service.ts` and `blink/service.ts` (implement), `ui/HomeScreen.tsx` (chip), `package.json` (`expo-video`).

## Tests
Aggregation across two fake services; a failing or hanging service becomes a problem line; the no-plan message; windows and paging (a quiet window loads the next, stop at 60 days); `clipSource` for Ring tries the endpoints in order including the 404-then-fallback case and refuses `http`; Blink adds the header; the chip appears only while a connected service has clips; the player component is created with the headers (mock `expo-video`) and released on close; the row words.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner, on the iPhone:** the Clips chip lists real clips; a Blink clip plays (with its header); a Ring clip plays or says why not; closing the player stops the sound; a service signed out does not show a chip or an error.

## Risks and unverified assumptions
- `expo-video` with custom headers on iOS (AVPlayer headers via `AVURLAsset` options): verify in the first hour; if unsupported in Expo Go, download the clip to a temp file with `expo-file-system` (with the header) and play the local file (delete afterwards).
- The 404 on one Ring endpoint is account-specific; the fallback order is the mitigation.

## Not in this chunk
Thumbnails in the list (Ring needs its own signed fetch), downloading or sharing a clip, live view (C71).

## As built
Built in one PR. Where the code differs from the specification above, the code is right.
- **Files:** new `home/clips.ts` (3-day windows, merge, words), `ui/ClipsScreen.tsx`, `ui/ClipPlayer.tsx`, tests `home/clips.test.tsx` (13) plus 5 clip tests in `ring/ring.test.tsx` and 1 in `blink/blink.test.tsx`; changed `home/model.ts` (`ClipsPage`, `ClipSource`; `clips` now answers a page, not a bare list), `home/home.ts` (`clips`, `clipSource`, `hasClips`), `home/homeTools.ts` (`recent_events` adds "Open the Clips list on the Home tab to watch one." when it lists events and clips exist), `ring/{api,parse,service}.ts`, `blink/service.ts`, `ui/HomeScreen.tsx` (the Clips chip and modal), `package.json` and the lock file (`expo-video`, `npx expo install`, ~58.0.14).
- **`clips` answers `{ clips, noPlan? }`**, not a bare list, so "no Ring Protect plan" is a state and never an error; `hasClips` is a getter on `Home` (it reads the connected services, and `version` already redraws screens) rather than a `Store<boolean>`. The `clipUrl` placeholder did not exist in the iOS model: `clipSource` is the only method.
- **Ring:** `video_search/history` per camera; a camera that fails is skipped and only all failing is an error; every recent event with `had_subscription` false means "no plan" (nothing listed; the list says "Ring keeps recordings only with a Ring Protect plan, and this account doesn't have one."). `clipSource` asks at play time: `dings/{id}/share/play`, then `…/recording`, then the address the search listed (`lq_url`, `hq_url`, `untranscoded_url`); only `https` is ever played, in the service and again in `Home.clipSource`.
- **Blink:** clips are `media/changed` from the window's start (at most 5 pages, as for events), filtered to the window; `clipSource` is the account server's address with `Authorization: Bearer <token>`. A long window far back can be cut off by the 5-page limit on a busy account; the newest clips of that window then show first.
- **Clips chip and list:** shown only while `home.hasClips` (a signed-out or disconnected service shows nothing). Rows say the camera, what made it record, "Today 3:42 PM" and the length when the service gives one (Blink does not). Windows of 3 days back to 60 days; a quiet window loads the next older one (up to 4 per load, again when the list is scrolled to its end); pull to refresh starts over; what is listed stays while more loads.
- **Player:** `expo-video` `useVideoPlayer({ uri, headers })` inside a full-screen modal that is mounted only while a clip is open, keyed by the clip; closing unmounts it, which releases the player, and the cleanup pauses it first. A failure says "This phone couldn't play the clip (…)" with the player's error text; a clip whose address can't be had says why.
- **Mutation-checked:** nine deliberate breaks (Blink header dropped; Ring tries `recording` first; Ring http addresses allowed; `Home` plays http; the chip ignores connection; windows never reach 60 days; a quiet window not continued; the player not paused on close; "no plan" shown as an empty list) were each caught, and the code restored.
- **Not verified, and it matters:** nothing has run on the iPhone. **Whether `expo-video` in Expo Go sends the `headers` for Blink's video** is unknown: if it does not, Blink's clips will fail with the player's error text, and the fallback in "Risks" (download to a temp file with the header, play the local file) is **not built**. Ring's 404 order is from the Android build's finding, on one account. Clip lengths for Ring come from `duration` in the search, unchecked on iOS.
