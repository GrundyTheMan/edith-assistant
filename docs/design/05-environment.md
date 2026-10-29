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

```bash
./gradlew.bat assembleDebug          # build
./gradlew.bat installDebug           # build + install on the running emulator/phone
./gradlew.bat testDebugUnitTest      # unit tests
./gradlew.bat lintDebug
adb shell am start -n com.edith.app/.MainActivity
```

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
- App-private files: `adb shell run-as com.edith.app find . -type f`; copy out with
  `adb exec-out run-as com.edith.app cat <path> > out` (**not** `adb shell … cat`).
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
