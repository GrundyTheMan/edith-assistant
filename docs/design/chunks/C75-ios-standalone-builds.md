# C75 — Stage 3: standalone builds, TestFlight and updates on iOS

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B (the last build chunk) |
| Status | **Design — needs the membership (C58) and the owner's decisions below** |
| Depends on | C58 (a working development build), enough features that the owner wants to leave Expo's dev server behind |
| Unblocks | Edith running on the iPhone **without the PC's dev server** (and replacing Andarium) |
| Runs in | a standalone (production-profile) build |
| Size | Medium |
| Ports | C40 §4 ("A standalone build") and Android's release runbook (`05-environment.md` §6, `scripts/release.sh`, `scripts/publish.sh`, the L30–L35 release-check lessons) |
| Read first | C40 §4, `05-environment.md` §6, `04-lessons-learned.md` L30–L35, Expo's current docs for EAS Build, EAS Submit, EAS Update (re-verify every command) |

## Why
A development build needs the PC's Metro server (same Wi-Fi). To use Edith like a normal app (away from home, no PC), the owner
needs a **standalone build**: the JavaScript bundled inside. It also finally replaces the owner's Android-on-iPhone setup
(Andarium).

## Decisions to ask the owner (record in `06-decisions.md`)
- **D36 Distribution:** (1) **internal distribution** (ad hoc, up to the registered devices; reinstall required when the provisioning profile or certificate changes; no review) — **recommended to start**; or (2) **TestFlight** (App Store Connect; builds last 90 days; automatic updates; needs an App Store Connect app record and an API key for `eas submit`; no public listing). The App Store itself is **not planned**.
- **D37 Over-the-air updates:** **EAS Update** (`expo-updates`) lets JavaScript-only changes reach the standalone app without a new build (channels `preview`/`production`; rollbacks). Recommended **yes** (it also reduces the 15-builds-a-month pressure), with the rule below.
- The bundle id (D34) stays the same as the development build so the **app data and Keychain items carry over** on update.

## Specification

### 1. Build profiles and config
- `eas.json`: `production` (store/TestFlight-style, `autoIncrement` of the build number) and `preview` (internal distribution, release configuration, no dev client), each with a **channel** if D37 = yes (`"channel": "preview"` / `"production"`). `runtimeVersion`: `{ "policy": "appVersion" }` (or `fingerprint`: **check the current recommendation**) so an update is only applied to a compatible native build.
- `app.config.ts`: `updates.url` (from EAS), `ios.infoPlist` unchanged; **no `NSAllowsArbitraryLoads`**; `ios.config.usesNonExemptEncryption: false`; icon and splash final (check `assets/` are 1024×1024 and without transparency for the icon).
- Version: `expo.version` is the human version (start `2.0.0` as in app.json; bump per release); the **build number is remote-managed** (`appVersionSource: remote`).
- Remove dev-only things from release: the Demo home switch is `__DEV__`-only (already); confirm no debug screens (HelloOllama) are reachable in release.

### 2. The release runbook (`docs/05-environment.md` §7, written in this PR)
1. `bash scripts/ios-check.sh` green on `master`.
2. Ask the owner; then `npx eas-cli build --profile preview --platform ios` (or `production`); record queue time and build time.
3. Install from the link/QR **on the iPhone** (or via TestFlight).
4. **The release check (L30's lesson: R8 broke ONNX once; here the equivalent is "the standalone build behaves differently from the dev build")**: on the standalone build verify, in this order: chat streams over plain HTTP to Ollama (Local Network prompt appears); a photo, a voice memo, a tool call (weather), a card (Demo home unlock), Settings persists across a force-quit, a Google/Microsoft sign-in (if built) returns to the app through the custom scheme, the Keychain items survive an update install over the previous build.
5. Publish to the owner: nothing public. The "release" is the build link or the TestFlight build.
6. Tag the repo `ios-vX.Y.Z` **only when the owner asks** (the AGENTS.md rule).
- If D37 = yes: `npx eas-cli update --channel production --message "…"` for JS-only fixes; the runbook states **which changes need a build** (any native module, `app.config` plugin or Info.plist change: see `NATIVE_CHANGES.md`) and which don't.

### 3. Crash and diagnostics
No third-party crash reporting (privacy; D27 spirit). A **Settings → About → Diagnostics** screen shows the app version, build number, update id (if any), Ollama version and the last 50 **non-sensitive** log lines kept in memory (`core/log.ts` ring buffer: tags and messages without conversation text, which already is the rule), with a **Copy** button the owner can send to the agent. Test that no `sensitive` line enters the buffer in release.

### 4. CI
`ios-app.yml` stays tests-only. A manual `workflow_dispatch` job **may** run `eas build` only if the owner provides an `EXPO_TOKEN` secret and asks: **not built here** (quota).

## Files
`eas.json`, `app.config.ts`, `core/log.ts` (ring buffer), `ui/Diagnostics.tsx`, `docs/05-environment.md` §7, `NATIVE_CHANGES.md` (reset after the build), tests (config test from C58 extended: channels present, `usesNonExemptEncryption`, no arbitrary loads; the log buffer excludes sensitive lines).

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green with the extended config test.
- [ ] **Owner:** the standalone build runs **without the PC** (Metro off, away from home on mobile data, reaching Ollama over Tailscale is out of scope: say so; at home it works over Wi-Fi); an update installed over a previous build keeps chats, settings and Keychain items; a JS-only fix arrives by EAS Update (if D37) and applies after a restart; Diagnostics shows sensible text.

## Risks and unverified assumptions
- TestFlight/EAS Submit steps need the owner's App Store Connect access (an API key): the agent never handles it.
- OTA updates must not change native behaviour: the `runtimeVersion` policy is the guard; test an update on a build with a different runtime version is ignored.
- Ollama over Tailscale from the iPhone (reaching the PC away from home) is a **separate** subject: the server address is a setting, so the owner can type the Tailscale address; the app has no special handling (C66 documents Tailscale for Jarvis).

## Not in this chunk
The App Store, public beta, push notifications (C72 B), Apple Watch, widgets.
