# C53 — Camera clips on iOS (the Clips chip, list and player)

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave A |
| Status | **Design** |
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
