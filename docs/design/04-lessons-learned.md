# 04 — Lessons learned from Edith v1

v1 (tag `edith-v1`) taught these the hard way. Each lesson is a rule for the rebuild, with the chunk
that must honour it.

| # | What happened in v1 | Rule for the rebuild | Chunks |
|---|---|---|---|
| L1 | The original code was AI-generated without ever being compiled: ~155 compile errors, five files duplicated under a mistyped package folder, Room set up with `annotationProcessor` (database crashed at startup). | Every chunk must build, run and be exercised on the emulator before it's called done. | all |
| L2 | **Tool calls never worked.** The DTO declared `function` as a string; Ollama sends an object. Every real tool call threw a parse error and the reply failed. | Model Ollama's exact JSON (03 §3.1) with kotlinx.serialization; fixture tests from real responses. | C03, C08 |
| L3 | Tool results were saved to the chat history as `system` messages ("Tool Result: …"), and failed calls left text-form `<tool_call>` replies in history. The model then copied that pattern: **text tool calls 3/3 with history, real calls 3/3 without it.** | Never persist tool exchanges; only `user`/`assistant` rows; strip tool-call text from context; store errors, not fabricated apologies. | C05, C06, C08 |
| L4 | Follow-up tool rounds read tool calls from a field Ollama never sets → silent empty replies. No round limit. | One loop, one code path for every round; cap at 5 rounds; final answer request without tools. | C08 |
| L5 | A weather service parsed wttr.in's `current_condition` (a JSON **array**) as an object → every lookup failed, and Edith apologised. | Parse external APIs from captured real responses in tests; prefer Open-Meteo (structured, keyless). | C09 |
| L6 | Calendar and email returned hard-coded sample data; once tools worked, a model would read "Meeting with Team" as the owner's real schedule. | Never ship fake data behind a real-sounding tool. Unconnected = not offered, and the prompt says so. | C08, C17–C19 |
| L7 | v1 offered 17 tools, 11 of them desktop coding tools (`run_command`, `write_file` …) copied from another project — dangerous and broken on a phone. | Only offer tools that make sense on a phone and are implemented and tested. | C08 |
| L8 | gemma4 "thinking" was on by default: 4–6.5 s per reply. | `think:false` on every request. | C03 |
| L9 | Different `num_ctx` per request and gemma4's huge default context made Ollama swap models on the GPU (3–4 s each way, twice per message). | Same `num_ctx` (16384) on every request. | C03 |
| L10 | The ViewModel was created with `remember { }` → rotation and sign-in wiped live attachments and in-flight replies. Repositories were rebuilt on recomposition. | Real ViewModels via factories; singletons from `AppContainer` only. | C02, C07 |
| L11 | Silence in a voice memo produced a model apology that was sent as the user's message. | `[NO_SPEECH]` marker in the transcription prompt; empty transcript = nothing sent, with a message. | C12 |
| L12 | Ollama only accepts WAV/MP3 audio; Android's default recorders produce 3GP/AMR or M4A. | Record 16 kHz mono PCM WAV with `AudioRecord`; convert other formats in memory. | C12, C15 |
| L13 | Server-side voices: Ollama `phonon` needs Apple MLX (impossible on Windows); an Orpheus + SNAC voice server worked but crackled on the emulator, and the owner removed it. | Phone TextToSpeech only, no network for speech. | C13 |
| L14 | Speaking only after the whole reply arrived meant 8+ s of silence. | Stream replies and speak sentence by sentence as they complete. | C06, C13 |
| L15 | Long first sentences delayed speech. | Cut a long opening sentence at a natural pause (comma/semicolon/dash) after ≥25 chars when > ~100 chars. | C13 |
| L16 | `allowBackup="true"` could copy chat history to Google Drive. | Backups off; attachments app-private. | C01 |
| L17 | Settings' "Tap to retry" stopped retrying after an error (regression from another agent). | UI copy and behaviour must match; test the error path. | C04 |
| L18 | Multiple AI agents worked in the same checkout: uncommitted work appeared, a `reset --hard` discarded work, a commit was built on a stale commit. | Branch per chunk from fresh `master`; check `git status` first; never discard others' work without the owner's OK. | all |
| L19 | The emulator's display failed (black screen including the home screen and status bar) and its Bluetooth service crashed; it looked like an app ANR. | Prove where a failure is (e.g. check the home screen) before blaming either the app or the emulator; cold-boot the emulator (`-no-snapshot-load`) when its display misbehaves. | all |
| L20 | DuckDuckGo's instant-answer API returns nothing for most real questions ("Pistons game tonight"). | Use a real search API (06-decisions D3). | C10 |
| L21 | The owner's model names changed between sessions (`gemma4:12b` → `gemma4:12b-it-q8_0`), breaking a saved setting. | Validate the saved model on startup and when sending; offer to pick another if it's gone. | C04 |
| L22 | `adb shell … cat` corrupts binary output; Git Bash rewrites `/sdcard/...` paths; Windows `>` in PowerShell corrupts PNGs. | Use `adb exec-out`, `MSYS_NO_PATHCONV=1`, and Git Bash for binary redirects (05-environment). | testing |
| L23 | (Rebuild, C03 live check, 2026-10-31) On Android 17 (targetSdk 37) the app couldn't reach Ollama at `10.0.2.2:11434` or the PC's LAN address: every request timed out, while `adb shell` reached it instantly. Cause: Android's local network protection; LAN access needs the runtime permission `android.permission.ACCESS_LOCAL_NETWORK`. Proven A/B: revoked → all requests `Timeout`; granted → models, streaming and tool calls work. | Declare it (done in C03) **and request it at runtime before the first connection** (C04); when it's denied, say so plainly instead of "can't reach your server". | C04 |
