# C58 — Stage 2: the EAS development build on the owner's iPhone

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B (the gate) |
| Status | **Design — blocked on the owner**: the paid Apple Developer Program membership expired in May 2026 |
| Depends on | C40 (the project) |
| Unblocks | **every Wave B chunk** (C59–C75): custom URL schemes, sockets, native modules, background modes |
| Runs in | an EAS development build (this chunk creates it) |
| Size | Medium (mostly owner steps and verification) |
| Ports | C40 "Stage 2" and "Builds and releases" §4 |
| Read first | C40 (the "Facts checked 2026-11-19", "Before you start — owner setup", "Stage 2", §4), `ios-roadmap.md` §4 and §7, D27, D34 |

## Why
Expo Go can't carry custom native code, custom URL schemes or Info.plist keys. Google/Microsoft sign-in, iCloud mail,
Jarvis's pinned TLS, the wake word, Meross cloud MQTT, WebRTC and background audio all need them. A **development build** is
the app itself with Expo's dev client inside it: it runs JavaScript from the PC's Metro server exactly like Expo Go (day-to-day
JS changes reload with **no build**), but with Edith's own native configuration. Building it needs the paid Apple Developer
Program (a distribution certificate and an ad hoc provisioning profile that include the iPhone). **The owner decides and pays;
the agent never handles Apple or Expo credentials.**

## Owner steps (the agent walks the owner through each; write them as `docs/ios-build-setup.md`)
1. **Renew the Apple Developer Program** (developer.apple.com → Account). Apple's rules for a membership that has lapsed for months may differ: read the page with the owner and record what Apple said.
2. **Create an Expo account** (free) and run `npx eas-cli login` on the PC (**the owner types the password and any code**).
3. **Choose the bundle identifier (D34)** — unique across Apple, not `com.edith.app` unless free. Record it in `06-decisions.md` and `app.json`.
4. **Register the iPhone:** `npx eas-cli device:create` prints a link/QR; **the owner opens it on the iPhone** to install a profile (Settings → Profile Downloaded → Install).
5. **Developer Mode on the iPhone:** Settings → Privacy & Security → Developer Mode → On → restart (iOS 16+; the switch appears only after a development-signed app has been installed once; follow the EAS prompt order).
6. **Agree an EAS budget:** the free plan has about 15 iOS builds a month, in a low-priority queue that can wait 90+ minutes. **The agent asks before every build**, and batches native changes (below).

## Agent preparation (no owner credentials needed; can be done before the membership is renewed and reviewed in a PR)
1. **`app.json` (`expo.ios` and top level)** — add, with comments in the PR explaining each:
   - `ios.bundleIdentifier` (D34), `ios.buildNumber` managed by EAS (`appVersionSource: remote`), `scheme: "edith"` (already present).
   - `ios.infoPlist`:
     - `NSLocalNetworkUsageDescription`: "Edith talks to your PC running Ollama, your 3D printer, and your smart-home devices on your home network."
     - `NSAppTransportSecurity`: `{ NSAllowsLocalNetworking: true }` — plain HTTP to **hostnames** on the local network; raw IP addresses are already exempt. **Do not set `NSAllowsArbitraryLoads`.** (The exact structure is unverified: C40 says so. Verify on the device and record the final working keys.)
     - `UIBackgroundModes`: **leave empty now**; C69 adds `audio` only if the owner accepts a persistent microphone indicator.
   - Permission strings already come from the plugins: `expo-audio` (microphone), `expo-image-picker` (photos), `expo-location`. Review every string for plain, honest wording.
   - `ios.config.usesNonExemptEncryption: false` (HTTPS only; avoids the export-compliance prompt on every upload; confirm with the owner).
2. **`eas.json`** (create with `npx eas-cli build:configure`; **needs the owner's login, so the agent writes it by hand and the owner runs configure**):
   ```json
   {
     "cli": { "version": ">= 16.0.0", "appVersionSource": "remote" },
     "build": {
       "development": { "developmentClient": true, "distribution": "internal", "ios": { "simulator": false } },
       "preview": { "distribution": "internal" },
       "production": { "autoIncrement": true }
     },
     "submit": { "production": {} }
   }
   ```
   (Check the current CLI version and schema in Expo's docs.)
3. **`expo-dev-client`:** `npx expo install expo-dev-client` (adds the dev menu and launcher). This is itself a native change: do it in the **same first build** as the other native packages below.
4. **Batch the first build's native additions**, so one build covers the near-term chunks (each only *adds* a library; none is used until its chunk lands): `expo-dev-client`, `react-native-tcp-socket` (C65, C66, C70), `react-native-webrtc` + its config plugin (C71), `onnxruntime-react-native` (C69; check Expo compatibility first, else drop it from the batch), `expo-speech-recognition` is **not** needed (we transcribe with Ollama), `expo-notifications` (C72). **Ask the owner** which to include: every library adds size and risk; the default is to include only `expo-dev-client` and `react-native-tcp-socket`, then batch later ones.
5. **CI:** `.github/workflows/ios-app.yml` keeps running tests only; **never** runs EAS builds (quota).
6. **`docs/05-environment.md`**: a new section "iOS builds" with the commands below.

## The first build and verification (with the owner at the keyboard for the Apple sign-in)
1. `npx eas-cli build --profile development --platform ios` (interactive; the owner signs in to Apple when asked). Record the queue wait and build time.
2. The owner installs it from the build page's link/QR **on the iPhone**.
3. `cd ios-app && npx expo start --dev-client`; the iPhone connects on the same Wi-Fi.
4. **Verify, in this order, and record each result in "As built":**
   - Edith opens, the SQLite data from Expo Go is **not** there (a different app: expected) — the owner re-enters the server address once.
   - Chat streams from Ollama; iOS shows the **Local Network** prompt on first use; plain HTTP to the PC's IP works with the settings above (record the exact working `infoPlist` keys).
   - Keychain items, photo picker, microphone, location prompts each show Edith's own strings.
   - A JavaScript edit reloads without a build.
   - `Linking.createURL('auth')` gives `edith://auth` and the app opens when that URL is visited in Safari (proves the custom scheme; C59 needs it).
5. Memory/battery sanity: none expected; note anything odd.

## Rules for later chunks about native changes
- A chunk that adds a native module or changes `infoPlist`/plugins **says so in its PR title ("needs a new build")** and lists the change. The agent asks the owner before triggering the build, and batches with other pending native changes.
- JS-only changes never need a build.
- Keep an `ios-app/NATIVE_CHANGES.md` list of native changes made since the last build.

## Tests
No new unit tests beyond a Jest test that parses `app.json` and `eas.json` and fails if: `NSAllowsArbitraryLoads` is true, the bundle id is missing/equals `com.edith.app` without the owner's note, `scheme` ≠ `edith`, or `eas.json` lacks the three profiles.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green with the config test.
- [ ] **Owner:** the development build is installed and chat streams over plain HTTP with the Local Network permission accepted; `edith://auth` opens the app; one JS edit reloads live.
- [ ] The exact working Info.plist settings and the build queue/time are recorded in this file's "As built".

## Risks and unverified assumptions
- Everything about Apple/EAS behaviour is from C40's 2026-11-19 research and must be re-verified. Apple may require extra steps for a lapsed membership. EAS free-plan limits and queue times change.
- A first-time `eas device:create` flow needs the iPhone and PC to cooperate; budget an hour.
- Keychain contents saved by Expo Go are **not** carried over.

## Not in this chunk
TestFlight and standalone builds (C75), any feature that needs the new capabilities.
