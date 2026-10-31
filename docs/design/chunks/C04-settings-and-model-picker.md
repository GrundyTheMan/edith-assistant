# C04 — Settings store, server setup and model picker

| | |
|---|---|
| Phase | 1 |
| Depends on | C02, C03 |
| Unblocks | C06, C07 and every chunk that adds a setting |
| Size | Medium |
| Read first | 01-architecture §5.3, §10; 03-ollama-reference §1, §6; 04-lessons L17, L21 |

## Goal

Persistent settings with a single typed repository, and a Settings screen where the owner sets the
Ollama server address, tests the connection, and picks a model from those installed — with honest,
working error and retry states.

## Specification

### 1. `SettingsRepository` (`settings/`)
```kotlin
data class Settings(
    val serverUrl: String = "http://10.0.2.2:11434",
    val model: String? = null,
    val speakReplies: Boolean = true,
    val speechRate: Float = 1.0f,
    val units: Units = Units.Auto,
    val useDeviceLocation: Boolean = false,
    val searchProvider: String = "",            // C10 sets the real default from decision D3
    val keepRecentAttachments: Int = 10,
    val handsFreeEnabled: Boolean = false,
    val wakeWordSensitivity: Float = 0.5f,
    val followUpSeconds: Int = 8,
    val googleAccountEmail: String? = null,
)
enum class Units { Auto, Metric, Imperial }
interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun current(): Settings
    suspend fun update(transform: (Settings) -> Settings)
}
```
`DataStoreSettingsRepository` backed by Preferences DataStore `settings` with the keys from
01-architecture §5.3 (key names exactly as listed). Defaults come from the data class. All keys are
defined now (even ones later chunks use) so no chunk has to touch the storage format again.

`ServerUrl.normalize(input)`: trim; add `http://` if no scheme; strip trailing `/`; add `:11434` if no
port; reject spaces/invalid hosts → validation message.

### 2. Model selection rules (`settings/ModelSelection.kt`)
- If `settings.model` is null or **not installed** (lessons L21): choose a default — the first installed
  chat model whose name starts with `gemma4:12b`, else `gemma4`, else the first model with `tools`,
  else the first chat model. Save it and show a one-time notice in chat ("Your model X is no longer
  installed; switched to Y."). If no chat models exist → error state "No chat models installed on the server".
- Expose `suspend fun ensureModel(): Outcome<ModelInfo>` used by the chat engine before every send.

### 3. Settings screen (Server & model section only; other sections are added by their chunks)
- **Local network permission first (04-lessons L23).** Android 17 blocks connections to the PC (including
  the emulator's `10.0.2.2`) until the user grants `android.permission.ACCESS_LOCAL_NETWORK` (runtime,
  "access local network devices"; already declared in the manifest by C03). Without it every request just
  times out. Before **Test connection**, loading models, or the first chat, check the permission; if it's not
  granted, explain in one line why Edith needs it ("to talk to Ollama on your PC") and request it. If denied,
  show `AppError.PermissionDenied("local network")` with a button that opens the app's Android settings,
  not a "can't reach your server" error. Instrumented tests can grant it with
  `uiAutomation.grantRuntimePermission(...)` (see `OllamaLiveCheckTest`).
- **Server address** text field + **Test connection** button → `version()`:
  "Connected — Ollama 0.40.13" or the `AppError.userMessage`. Save on **Save** (explicit) — the
  field shows unsaved state. Hint text: "Emulator: http://10.0.2.2:11434 · Phone: your PC's address, e.g. http://192.168.4.82:11434".
- **Model** dropdown (`ExposedDropdownMenuBox`) listing `ModelCatalog.chatModels()`; each item shows
  the name and a capability line: "Sees images · Hears audio · Uses tools" (only those present).
  Selecting a model saves immediately (consistent with toggles; no separate save).
  - Loading: "Loading models…". Error: the error message + **Retry** button (a real button — v1's
    "tap to retry" broke; lessons L17). Empty: "No chat models installed on this server".
  - Warn if the saved model isn't installed.
  - Reload automatically 600 ms after the server address changes and on screen entry.
- **About** section (bottom): app version, Ollama version (if reachable), "Last crash report" (opens
  the C02 dialog when one exists).

### 4. ViewModel
`SettingsViewModel(settingsRepo, modelCatalog, ollamaClient, dispatchers)` with `StateFlow<SettingsUiState>`
(`serverField`, `serverSaved`, `connection: Idle|Testing|Ok(version)|Failed(msg)`, `models: Loading|Loaded(list)|Failed(msg)|Empty`,
`selectedModel`, `savedModelMissing`). Debounce server-field-triggered reloads with `delay(600)` in a
`collectLatest`.

## Tests
- `ServerUrlTest`: `192.168.4.82` → `http://192.168.4.82:11434`; `http://x:1234/` → `http://x:1234`; invalid input rejected.
- `ModelSelectionTest`: saved model missing → picks `gemma4:12b-it-q8_0` when present; prefers tools-capable; none → error.
- `SettingsRepositoryTest` (DataStore with temp file): defaults; update persists; every key round-trips.
- `SettingsViewModelTest` (fakes): error then Retry reloads (regression test for L17); debounce collapses rapid edits into one load.
- Compose test: picking a model updates the selection and persists.

## Acceptance criteria
- [ ] On the emulator, with Ollama running: Test connection shows the version; the model list shows the
      owner's chat models with correct capability lines; `nomic-embed-text` is hidden.
- [ ] With a wrong address (e.g. port 11400): a clear error and a working Retry.
- [ ] First run: Edith asks for the local network permission before connecting; denying it shows the
      permission message (not a timeout), and granting it from there connects.
- [ ] Rotation keeps unsaved field text; process death keeps saved settings.
- [ ] Removing the saved model (simulate by saving a bogus name) → automatic fallback + notice.

## Pitfalls
- Don't hard-code model names except as preferences. Don't block the UI on network calls.
