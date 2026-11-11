# 05 — Environment and emulator runbook

The owner's reference environment (Windows 11 PC). If you're implementing elsewhere, mirror the
essentials: JDK 17+, Android SDK, an emulator image with Google Play, and network access to an Ollama
server with at least one model that has `tools` (and ideally `vision`+`audio`).

---

## 1. Owner's machine (as of 2026-10-29)

| Item | Value |
|---|---|
| Repo | `D:\Dev Projects\edith` (path has a space — always quote it) · GitHub `GrundyTheMan/edith` (private) |
| JDK | Temurin 21 — `JAVA_HOME=C:\Users\USER\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.12.1+1` |
| Android SDK | `ANDROID_HOME=C:\Users\USER\AppData\Local\Android\Sdk`; Android Studio installed |
| Emulator | AVD `Pixel_9_API_37` (Google Play image, x86_64, 1080×2424), WHPX acceleration |
| Ollama | 0.40.13 on the same PC, `OLLAMA_HOST=0.0.0.0:11434` (listens on the LAN, no auth), log `%LOCALAPPDATA%\Ollama\server.log` |
| PC LAN address used by the phone | `192.168.4.82` (check with `ipconfig`; may change) |
| Other tools | Node.js 24 (great for probing Ollama), ffmpeg 8 (test media), `gh` (logged in as `GrundyTheMan`), Python 3.14 via `py` only (`python` is the Store stub) |
| Hardware | 32 threads, 64 GB RAM, RTX 5080 16 GB |
| Neighbour project | `D:\Dev Projects\jarvis` — the owner's Electron/TypeScript desktop assistant whose Ollama tool loop works (`src/renderer/src/lib/chatEngine.ts`) and which integrates Google, Alexa, Ring, Blink. Useful reference; do not modify it. |

## 2. Build and run

Use the scripts in `scripts/` (bash: Git Bash on Windows, bash on Linux/macOS). Each was proven end to end
in C01. Run them from the repo root: `scripts/run.sh` in Git Bash, or in PowerShell
`& "C:\Program Files\Git\bin\bash.exe" scripts/run.sh` (`bash` alone isn't on the PowerShell PATH).

| Script | What it does |
|---|---|
| `scripts/check.sh` | `clean assembleDebug testDebugUnitTest lintDebug` — **exactly what CI runs**. Run before every commit |
| `scripts/check.sh --device` | the above, then the instrumented (Compose UI) tests on the connected emulator |
| `scripts/emulator.sh [--cold]` | starts `Pixel_9_API_37` if nothing is connected and waits for boot (`--cold` ignores the snapshot) |
| `scripts/run.sh` | emulator.sh, then build + install the debug app, launch it, fail if it crashed |
| `scripts/release.sh` | signed release APK → `dist/Edith-<versionName>.<versionCode>.apk` (owner's PC only, §6) |
| `scripts/publish.sh` | uploads that APK as a GitHub Release (**owner's OK required**, §6) |
| `scripts/create-signing-key.sh` | one-time creation of the release key (already done — don't run again) |

**Build logs — every build is recorded** in `build-logs/` (git-ignored); `build-logs/latest.log` is always a
copy of the newest. The owner reviews these, so when you report a build result, it must match the log; quote
the relevant lines rather than retyping them. Two sources, never both for the same run:
- **Scripts** (all above except `create-signing-key.sh`): `<date>_<time>_<script>.log` holds the complete terminal
  output, written live while it runs, from `# scripts/x.sh … | branch@commit` to `# Finished OK` / `# FAILED (exit N)`.
  A script run by another (`run.sh` → `emulator.sh`) writes into the caller's log.
- **Any other Gradle build** — `./gradlew.bat …` typed directly, or Android Studio's Build/Run — is logged by
  `gradle/build-log.settings.gradle.kts` to `<date>_<time>_gradle_<tasks>.log`: a header (tasks, time, command line
  or Android Studio, branch@commit, uncommitted changes), `> Task :x OUTCOME` for every task, everything tasks
  print (compiler errors/warnings, test failures, lint), and `BUILD SUCCESSFUL`/`BUILD FAILED` with Gradle's
  "What went wrong" message. Gradle's own hints ("Run with --scan…") aren't included. Android Studio's project
  sync isn't a build and isn't logged. A problem in the logger never fails a build.
The newest 30 logs are kept (`EDITH_LOG_KEEP=N` to change). Don't edit or delete logs to hide a failure.

Plain Gradle commands: `./gradlew.bat assembleDebug`, `installDebug`, `testDebugUnitTest`, `lintDebug`.
**Don't use `connectedDebugAndroidTest`** on the owner's emulator: it uninstalls the app afterwards, wiping the
owner's data. `check.sh --device` installs and runs the tests with `am instrument` instead.

With two devices connected (e.g. the release-test emulator below), set `ANDROID_SERIAL=emulator-5554` so adb
and the scripts know which one to use.

Toolchain as of C01: Gradle 9.8.2 (wrapper, checksum-pinned), AGP 9.5.0 (compiles Kotlin itself — there is no
`org.jetbrains.kotlin.android` plugin), Kotlin 2.4.21, compileSdk/targetSdk 37. All versions in
`gradle/libs.versions.toml`; the app version in `gradle.properties` (`edith.versionName`, `edith.versionCode`).

**Adding a library:** add it to `gradle/libs.versions.toml` at the latest *stable* version (01-architecture §1)
and reference it as `libs.…`. To find that version, read the Maven metadata, e.g.
`curl -s https://dl.google.com/android/maven2/androidx/room/room-runtime/maven-metadata.xml` (Google's
AndroidX libraries) or `https://repo1.maven.org/maven2/<group path>/<artifact>/maven-metadata.xml` (everything
else), ignoring alpha/beta/rc versions. Lint's "newer version available" checks are switched off on purpose.

**Expected noise (not problems):** `Unable to strip … libandroidx.graphics.path.so` (no NDK installed; harmless),
`Consider enabling configuration cache` (not enabled yet; fine to try in a later chunk if every script still
passes), and Git's `LF will be replaced by CRLF` warnings on Windows (`.gitattributes` keeps the repo consistent).

**Working somewhere other than the owner's PC** (cloud agent, another machine): you have no emulator, no
Ollama and no signing key. `scripts/check.sh` still works (JDK 17+ and an Android SDK are all it needs) and CI
checks every PR. Say clearly in the PR what you couldn't verify on a device, so the owner or a later agent can.

## 3. Emulator

```powershell
# start (normal)
Start-Process "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList "-avd Pixel_9_API_37"
# start cold (use when the display misbehaves or after a crash of the emulator)
Start-Process "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList "-avd Pixel_9_API_37 -no-snapshot-load"
adb wait-for-device
# then poll until booted:
adb shell getprop sys.boot_completed     # "1" when ready
# stop
adb emu kill
```

- The emulator is often closed between sessions; check `adb devices` first.
- From the emulator, the PC is `10.0.2.2`. From a phone, use the PC's LAN IP.
- **Mic:** records near-silence (~-77 dB) unless *Extended controls → Microphone → "Virtual microphone
  uses host audio input"* is on. For transcription tests, push WAV/MP3 files instead.
- **Audio out** can crackle when the PC is busy (emulator host-audio path; Android reports no underruns).
- **Display failures** (all black, even the home screen) are the emulator — cold-boot it.
- The owner may be using the emulator at the same time; unexpected messages in the chat are probably theirs.
- **State left by C01 (2026-10-29):** the new app was installed *over* v1, so the app's storage still holds
  v1's leftovers (database `edith_database`, prefs `edith_prefs`). The rebuild uses different names (`edith.db`,
  DataStore), so they're ignored and harmless; clearing them needs `pm clear`, which needs the owner's OK.
  Auto-rotate is **off** (C01 rotated with `settings put system user_rotation 1|0`); rotate the same way.
  A second AVD, `Edith_Release_Test`, exists for release APKs (§6) — it holds no data worth keeping.

## 4. Driving the app from `adb` (proven recipes, Git Bash)

```bash
dump() { adb shell "uiautomator dump /sdcard/u.xml >/dev/null 2>&1; cat /sdcard/u.xml"; }
tap()  { b=$(dump | grep -o "$1[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
         read x1 y1 x2 y2 <<<$(echo "$b" | tr '[],' '   '); adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); }
tap 'text="Message Edith"'; adb shell input text "What%sis%sthe%sweather?"; adb shell input keyevent 4
tap 'content-desc="Send"'
```
- Find elements by `text=` or `content-desc=` (so give every icon button a content description).
- Poll the dump until the "thinking" label disappears to wait for a reply.
- Screenshots: `adb exec-out screencap -p > shot.png` (Git Bash; PowerShell `>` corrupts binary).
- App logs: `adb logcat -s Edith/Chat:* Edith/Tools:*`; crashes: `adb logcat -b crash`; ANRs: `adb logcat -d *:E | grep ANR`.
- App-private files (debug builds are `com.edith.app.debug` since C17): `adb shell run-as com.edith.app.debug find . -type f`; copy out with
  `adb exec-out run-as com.edith.app.debug cat <path> > out` (**not** `adb shell … cat`).
- Database: copy `databases/edith.db*` out and open with Node 24's `node:sqlite` (`DatabaseSync`) or sqlite3.
- Pushing test media: `MSYS_NO_PATHCONV=1 adb push C:/path/file.wav /sdcard/Music/` then
  `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/file.wav`.
- Photo picker on API 37: select the photo, then tap **Done**. DocumentsUI search: type short words
  (`adb shell input text` can be garbled by the keyboard).
- Test media: speech via PowerShell `System.Speech.Synthesis.SpeechSynthesizer` → `SetOutputToWaveFile`
  (16 kHz mono); images via `ffmpeg -f lavfi -i color=...` + `drawtext` (font `C\:/Windows/Fonts/arial.ttf`).
  Delete test media from the emulator afterwards.

## 5. Windows shell gotchas

- Git Bash rewrites `/sdcard/...` → `C:/Program Files/Git/sdcard/...`: prefix `MSYS_NO_PATHCONV=1`.
- `sdkmanager` package names with `;` get split by cmd: use `--package_file=<file>`.
- Deep build folders can exceed Windows' path limit when deleting: `cmd /c 'rd /s /q "\\?\C:\full\path"'`.
- `rm -rf "$VAR"/...` may be blocked by safety tooling when the variable could be empty; use literal paths.

## 6. Release builds, signing and deploying

Deploy targets (owner decision, 2026-10-29): **the emulator** (debug builds, `scripts/run.sh`) and a
**downloadable signed APK** on the private repo's GitHub Releases. No Play Store.

### Signing key
- Lives **outside the repo** in `C:\Users\USER\.edith\`: `edith-release.jks` (RSA 4096, alias `edith`, valid
  to ~2054) and `keystore.properties` (its path, generated password, and the certificate's SHA-256 that
  `release.sh` checks every APK against). `app/build.gradle.kts` reads that file
  (`EDITH_KEYSTORE_PROPERTIES` overrides the location). Never copy either into the repo, a chat or a log.
- The owner backs up that folder. If it's lost, installed copies can't be updated: phones must uninstall
  Edith (losing her data) before a build signed with a new key installs.
- Release certificate fingerprints (public, not secret): SHA-1
  `D1:F6:25:27:35:0F:35:5D:25:42:A1:88:F5:C4:ED:3C:D6:9A:CE:DE` (Google OAuth Android client, C17), SHA-256
  `A5:59:FD:3A:CD:73:9F:17:E5:4C:77:01:46:0B:F6:4D:15:96:19:BF:94:4B:47:88:79:F2:51:93:59:20:AF:C0`.
- CI and other machines have no key: release builds there come out unsigned and `release.sh` refuses.

### Making and publishing a release (only when the owner asks)
1. On `master`, up to date with GitHub, clean tree. Bump `edith.versionCode` in `gradle.properties` (and
   `edith.versionName` when the owner wants a new version name) via a normal PR — Android only installs an
   update whose versionCode is higher than the installed one.
2. `scripts/release.sh` → runs unit tests and release lint, builds the R8-minified, signed APK, verifies the
   signature and key, writes `dist/Edith-<name>.<code>.apk` + `.sha256` (`dist/` is git-ignored).
3. Install it on the **release-test emulator** and check it launches (below). Then check the paths R8 can break
   and that only a release build has:
   - **hands-free on** for ~20 s (ONNX Runtime through JNI; it crashed the first release build, L30)
   - a **chat with a tool call**
   - **Sign in with Google** reaching Google's screen

   `adb -s emulator-5560 logcat -b crash -d` must stay empty. Check the APK's `.sha256` from inside `dist/` (it
   names the file without a path).
4. Ask the owner, then `scripts/publish.sh` → tags the commit `v<name>.<code>`, pushes the tag, creates the
   GitHub Release with the APK attached (marked pre-release while the name contains `-`, e.g. `2.0.0-dev`).
5. On the phone: open the release page in the browser **signed in to GitHub** (the repo is private), download
   the APK, allow the browser to install unknown apps. Later releases install over it, keeping data.

### Debug vs release on the same device
Since C17, debug builds are **`com.edith.app.debug`** (`applicationIdSuffix`) and release builds are
**`com.edith.app`**: Google's OAuth needs a package + SHA-1 pair of its own for each, and the debug pair with
`com.edith.app` was already taken by an Android client nobody could find. So the two install side by side, each with
its own data. Launch the debug build with `adb shell am start -n com.edith.app.debug/com.edith.app.MainActivity`.
- Debug builds from before C17 were installed as `com.edith.app`, signed with the debug key. A release APK won't
  install over one of those (different key); `adb uninstall com.edith.app` first, which **wipes that app's data**.
  Never do that on the owner's `Pixel_9_API_37` without asking.
- Release APKs can still be tested on the separate AVD **`Edith_Release_Test`** (same image, created in C01):
```bash
"$ANDROID_HOME/emulator/emulator" -avd Edith_Release_Test -port 5560 -no-snapshot -no-window -no-audio &
adb -s emulator-5560 wait-for-device      # then poll sys.boot_completed as in §3
adb -s emulator-5560 install -r dist/Edith-2.0.0-dev.1.apk
adb -s emulator-5560 shell am start -W -n com.edith.app/.MainActivity
adb -s emulator-5560 logcat -b crash -d   # must be empty
adb -s emulator-5560 emu kill
```

### CI
`.github/workflows/ci.yml` runs `scripts/check.sh` (JDK 17, Ubuntu) on every pull request and every push to
`master`; on failure the lint/test reports are uploaded as a build artifact. A PR is mergeable only with CI green.
