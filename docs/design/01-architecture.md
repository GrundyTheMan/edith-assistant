# 01 — Architecture

The shared technical design. Every chunk implements part of this; none may contradict it without
updating this file in the same pull request (and saying so in the PR description).

---

## 1. Tech stack and version policy

Use the **latest stable** versions available when the chunk is implemented, at or above these minimums.
Pin exact versions in `gradle/libs.versions.toml` (a Gradle version catalog). Never use `+` or dynamic versions.

| Area | Choice | Minimum | Why |
|---|---|---|---|
| Language | Kotlin (K2) | 2.0 | Compose compiler plugin ships with Kotlin 2 |
| Build | Gradle wrapper + Android Gradle Plugin, Kotlin DSL (`*.gradle.kts`) | AGP 9.5 (C01) | AGP 9 compiles Kotlin itself: apply the Compose/serialization/KSP plugins, never `org.jetbrains.kotlin.android` |
| JDK toolchain | Java 17 bytecode via `compileOptions`; JDK 17 or 21 to run Gradle | 17 | Don't add `jvmToolchain(17)`: it demands a JDK 17 install the owner's PC doesn't have |
| SDK levels | `compileSdk` = `targetSdk` = latest stable; `minSdk` 26 | 26 | Adaptive icons, `MediaDataSource`, java.time |
| UI | Jetpack Compose, Material 3, Navigation Compose | latest BOM | |
| Async | Kotlin Coroutines + Flow | 1.8 | |
| JSON | kotlinx.serialization (`JsonElement` for free-form tool arguments) | 1.7 | v1's Gson + `Map<String, Any>` caused type bugs |
| HTTP | OkHttp (direct, no Retrofit) | 4.12 | Full control of NDJSON streaming and timeouts |
| Database | Room with KSP, schema export on | 2.6 | |
| Settings | Jetpack DataStore (Preferences) | 1.1 | |
| Secrets | Android Keystore (AES/GCM) wrapper — `SecretStore` (§9) | — | |
| Images | Coil 3 for display; Android `ImageDecoder` for downscaling | — | |
| HTML → text | Jsoup | 1.17 | Web page reading tool (C10) |
| Google | Credential Manager + Google Identity `AuthorizationClient`; plain REST for Calendar/Gmail via OkHttp | — | No heavy Google API client libraries |
| Testing | JUnit 4/5, kotlinx-coroutines-test, Turbine, OkHttp MockWebServer, Robolectric (where useful), Compose UI test | — | |
| DI | **Manual** — one `AppContainer` (§3) | — | Transparent for any AI implementer; no annotation processing |

No Hilt, no Retrofit, no Gson, no RxJava. Don't add a library a chunk doesn't need; justify new ones in the PR.

## 2. Modules and package layout

Single Gradle module `:app` (keeps builds simple for agents). Packages under `com.edith.app`:

```
com.edith.app
├── EdithApp.kt                 Application: builds AppContainer, installs crash handler
├── MainActivity.kt             Single activity, edge-to-edge, hosts EdithNavHost
├── di/AppContainer.kt          Manual DI: constructs and holds singletons
├── core/
│   ├── error/                  AppError (sealed), Outcome<T>, user-facing messages
│   ├── log/                    Log wrapper (tags, redaction), CrashReporter
│   ├── dispatchers/            AppDispatchers (injectable for tests)
│   ├── time/                   Clock interface (injectable), formatting helpers
│   └── secrets/                SecretStore (Keystore AES/GCM)
├── settings/                   SettingsRepository (DataStore), Settings data class, SettingsScreen + ViewModel
├── ollama/                     OllamaClient, DTOs, streaming parser, ModelCatalog (capabilities cache)
├── data/
│   ├── db/                     EdithDatabase, entities, DAOs, converters
│   └── chat/                   ChatRepository (messages, conversations)
├── chat/
│   ├── engine/                 ChatEngine, PromptBuilder, ContextBuilder, ReplyFormat
│   └── ui/                     ChatScreen, components (bubbles, input row, action row), ChatViewModel
├── tools/
│   ├── framework/              Tool, ToolRegistry, ToolSpec DSL, ToolLoop, ArgumentReader, Confirmation
│   ├── weather/                WeatherTool, OpenMeteoClient
│   ├── web/                    WebSearchTool, SearchProvider(s), ReadWebPageTool
│   └── context/                Date/time + device location tools
├── voice/
│   ├── capture/                AudioRecorder (WAV), Transcriber(s), DictationController
│   ├── speech/                 SpeechOutput (TextToSpeech), SentenceChunker
│   └── handsfree/              HandsFreeService (foreground), WakeWordEngine, ConversationLoop
├── attachments/                AttachmentIntake, AttachmentStore, Retention, views
├── google/                     GoogleAuth, CalendarClient/Tools/Screen, GmailClient/Tools
└── ui/
    ├── theme/                  EdithTheme (light/dark), colors, typography
    └── navigation/             EdithNavHost, Destinations, bottom bar
```

Rule: `ui` depends on `engine`/repositories; repositories depend on `ollama`/`db`; nothing below `ui`
imports Compose. Tools never import UI.

## 3. Dependency injection: `AppContainer`

`EdithApp.onCreate()` builds one `AppContainer` holding long-lived objects (OkHttpClient, database,
SettingsRepository, OllamaClient, ModelCatalog, ChatRepository, ToolRegistry, ChatEngine,
SpeechOutput, AttachmentStore, GoogleAuth, …). Each is constructed once, in dependency order, with
constructor injection. ViewModels are created with `viewModelFactory { initializer { … } }` reading
from `(application as EdithApp).container`. Tests construct the pieces they need directly with fakes.

**Never** construct singletons inside composables (v1 rebuilt its repositories on every recomposition)
and never create ViewModels with `remember { … }` (v1 lost state on rotation).

## 4. Data flow: one chat turn

```
ChatScreen ──send(text, attachments)──► ChatViewModel ──► ChatEngine.send()
                                                            │
  1. ChatRepository.insertUserMessage()  (text + attachment rows)   ◄── Room
  2. PromptBuilder.system()  persona + capability sections + date/time/location
  3. ContextBuilder.build()  history → Ollama messages (hygiene + token budget + media window)
  4. ToolLoop.run()  ──► OllamaClient.chatStream(request with tools)  ──► Ollama /api/chat (NDJSON)
        │  text deltas ──► EngineState.Streaming(partial) ──► UI shows partial reply
        │  tool_calls  ──► ToolRegistry.execute() (confirmation if required) ──► role "tool" msgs ──► next round
  5. ChatRepository.insertAssistantMessage(final text, toolsUsed, written files)
  6. SpeechOutput.speakReply()  (if enabled; sentence-chunked as text streams)
```

`ChatEngine` exposes `StateFlow<EngineState>`: `Idle`, `Thinking(label)`, `Streaming(partialText)`,
`AwaitingConfirmation(request)`, `Failed(AppError)`. The UI renders history from Room
(`Flow<List<MessageWithAttachments>>`) plus the engine's in-flight state.

## 5. Persistence

### 5.1 Database `edith.db`, schema version 2 — created by C05 (v1), conversations extended by C06b (v2)

Every schema change requires a version bump, an explicit `Migration` (`EdithDatabase.MIGRATIONS`), and a
migration test (`MigrationTest`, against the exported `app/schemas/` JSON). Never destructive migration. Prefer
`ALTER TABLE … ADD COLUMN`: rebuilding `conversations` (drop + rename) can cascade-delete every message.

```sql
conversations(
  id            INTEGER PRIMARY KEY,            -- many conversations (D6); new rows get their id from SQLite
  title         TEXT NOT NULL,                  -- '' until titled
  title_source  TEXT NOT NULL DEFAULT 'none',   -- 'none' | 'first_message' | 'edith' | 'owner' (D12; owner wins)
  created_at    INTEGER NOT NULL,               -- epoch millis
  updated_at    INTEGER NOT NULL DEFAULT 0      -- newest message time; the conversation list sorts by it (v2)
)
messages(
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  role            TEXT NOT NULL,                -- 'user' | 'assistant'  (never 'system' or 'tool')
  content         TEXT NOT NULL,                -- what the user typed / Edith said (see 5.2)
  created_at      INTEGER NOT NULL,
  status          TEXT NOT NULL,                -- 'complete' | 'failed' | 'cancelled'
  source          TEXT NOT NULL,                -- 'typed' | 'dictated' | 'voice_memo' | 'hands_free'
  model           TEXT,                         -- assistant: model that produced it
  tools_used      TEXT,                         -- assistant: JSON array of tool names, DISPLAY ONLY
  error_message   TEXT                          -- when status = 'failed'
)
attachments(
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  message_id      INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  number          INTEGER NOT NULL,             -- "Attachment N", unique per conversation
  direction       TEXT NOT NULL,                -- 'from_user' | 'from_edith'
  kind            TEXT NOT NULL,                -- 'image' | 'audio' | 'file'
  file_name       TEXT NOT NULL,
  mime_type       TEXT NOT NULL,
  size_bytes      INTEGER NOT NULL,
  stored_path     TEXT,                         -- app-private file; NULL once removed by retention
  resend_of       INTEGER REFERENCES attachments(id),  -- Edith resending an earlier attachment
  created_at      INTEGER NOT NULL,
  removed_at      INTEGER
)
-- indexes: conversations(updated_at); messages(conversation_id, created_at); attachments(message_id); attachments(direction, created_at)
```

### 5.2 What is stored — and what never is
- Stored: user text; Edith's final reply text (with `<file>` blocks reduced to `[Attached file: name]`
  — the file itself is an `attachments` row, kind `file`); attachment metadata; tool names used.
- **Never stored as messages:** the system prompt, tool calls, tool results, `role: "tool"` messages,
  status labels. v1 stored tool results as `system` rows and the model learned to fake tool calls from
  them (see `04-lessons-learned.md` L3). Tool exchanges live only in memory for the duration of a turn.
- Attachment **files** live in `filesDir/attachments/` and are subject to retention (C16).

### 5.3 Settings (DataStore `settings`) — the key registry

| Key | Type | Default | Owner chunk |
|---|---|---|---|
| `server_url` | String | `http://10.0.2.2:11434` (emulator → PC) | C04 |
| `model` | String? | null → first installed model with `completion`+`tools`, preferring `gemma4:12b*` | C04 |
| `speak_replies` | Boolean | `true` | C13 |
| `speech_rate` | Float | `1.0` | C13 |
| `units` | `auto` \| `metric` \| `imperial` | `auto` (from device locale) | C09 |
| `use_device_location` | Boolean | `false` (asks permission on first "here") | C11 |
| `search_provider` | String | see `06-decisions.md` D3 | C10 |
| `keep_recent_attachments` | Int 0..50 | `10` (per direction) | C16 |
| `hands_free_enabled` | Boolean | `false` | C14 |
| `wake_word_sensitivity` | Float 0..1 | `0.5` | C14 |
| `follow_up_seconds` | Int | `8` | C14 |
| `google_account_email` | String? | null | C17 |
| `current_conversation_id` | Long? | null | C06b (the chat the app reopens on) |

Secrets (API keys) are **not** in this table; they go through `SecretStore` (§9).

## 6. The system prompt

Built fresh for every request by `PromptBuilder` (C06):

```
<persona — 00-product.md §5, verbatim>

<capability sections, each contributed by the feature that owns it, only when active:>
- "Current date and time: Thursday, October 29, 2026, 2:30 PM (America/Detroit)."         (C06)
- Tool guidance generated from the registered, enabled tools (names + one-line purpose)    (C08)
- Attachment conventions (<file>, <attachment number="N"/>, how labels look)               (C15/C16)
- Integration status lines, e.g. "Google Calendar: connected as x@gmail.com."              (C17)
```

Rules: no capability text for features that are off or disconnected; never claim a capability that
isn't registered; keep each section short (small models follow short, concrete instructions best).

## 7. Talking to Ollama (summary — details in `03-ollama-reference.md`)

- Every `/api/chat` request: `"think": false`, `"options": {"num_ctx": 16384}`. No `keep_alive` unless the
  owner decides otherwise (`06-decisions.md` D8).
  The same `num_ctx` on **every** request to a model, or Ollama reloads it (3–4 s each time).
- Streaming (`"stream": true`, NDJSON) for chat replies; non-streaming for transcription and short helper calls.
- Tool calls arrive in `message.tool_calls[*].function.{name, arguments}`; `arguments` is a JSON object.
- Results go back as `{"role": "tool", "tool_name": "...", "content": "..."}` after the assistant message that contained the calls.
- Capabilities from `/api/show` gate features: `tools`, `vision`, `audio`, `thinking`.

## 8. Errors, logging and crashes

### 8.1 Error model
`sealed interface AppError` with a `userMessage: String` (plain language, says what to do) and a
`cause: Throwable?`. Minimum set: `ServerUnreachable(url)`, `Timeout`, `ModelNotFound(model)`,
`ModelLacksCapability(model, capability)`, `OllamaHttp(code, body)`, `MalformedResponse`,
`ToolFailed(tool, detail)`, `PermissionDenied(permission)`, `GoogleNotConnected`, `GoogleAuth(detail)`,
`Unknown`. Functions that can fail return `Outcome<T>` (`Success` | `Failure(AppError)`) or throw only
inside a layer that converts to `AppError` at its boundary. **Never** swallow an exception silently.

### 8.2 Logging
`Log` wrapper with fixed tags (`Edith/Chat`, `Edith/Ollama`, `Edith/Tools`, `Edith/Voice`, …). In
release builds: no message content, no attachment data, no tokens/keys, no email bodies. Debug builds
may log tool names and arguments (they're needed to diagnose tool calling — v1's key evidence came
from a `Tool get_weather({location=Boston})` log line).

### 8.3 Crash capture (C02)
A default uncaught-exception handler writes `filesDir/crash/last-crash.txt` (time, thread, stack,
app version, last 50 log lines from an in-memory ring buffer) before delegating to the system handler.
On next launch, if the file exists, show a dismissible banner "Edith closed unexpectedly last time —
[View details] [Copy]". This makes every crash investigable even on the owner's phone.

### 8.4 ANRs
No disk or network on the main thread (StrictMode `detectAll().penaltyLog()` in debug builds).
Long work uses injected `AppDispatchers.io/default`.

## 9. Security and privacy

- `android:allowBackup="false"`; `dataExtractionRules`/`fullBackupContent` exclude everything. (v1 had
  backup on, which could have copied chat history to Google Drive.)
- Cleartext HTTP is allowed (Ollama on the LAN speaks plain HTTP) via `network_security_config.xml`
  `base-config cleartextTrafficPermitted="true"`. All other traffic (Open-Meteo, search, Google) is
  HTTPS. Document the trade-off in the PR that adds it (C03).
- `SecretStore`: AES/GCM key in Android Keystore (`KeyGenParameterSpec`, non-exportable); ciphertext +
  IV stored in DataStore. Used for search API keys and the wake-word key. Google tokens are not stored
  by the app (C17 uses `AuthorizationClient`, which caches them in Google Play services).
- Permissions are requested only at the moment of use, with a one-line reason: microphone (record,
  dictate, hands-free), location (only when the owner asks about "here"), notifications (hands-free
  foreground service, Android 13+).
- Ollama has no authentication; anyone on the LAN can use it. Don't send secrets to it.

## 10. Navigation and screens

Single activity, Navigation Compose. Bottom bar destinations: **Chat** (start), **Calendar** (C18;
shows "Connect Google Calendar" until C17/C18), **Settings**. Smart home ("Home") is added in v2.
Settings sections in order: Server & model · Voice · Hands-free · Attachments · Web search · Google
account · About (version, crash report, licenses).

## 11. Performance budgets (measure; put numbers in PRs)

| Metric | Budget |
|---|---|
| App cold start to interactive chat | < 1.5 s on the emulator |
| Time to first streamed token, warm model, short history | < 2 s (`gemma4:e4b`) |
| Tool round overhead in the app (excluding model + tool network time) | < 100 ms |
| Scrolling a 500-message history | no dropped frames in the Compose layout inspector |
| Voice memo: stop → transcript shown | < 4 s for a 10 s memo |

## 12. Extension points

- **Tools:** implement `Tool` (C08) and register it in `AppContainer`. A tool declares its JSON schema,
  a status label, whether it needs confirmation, and which integration it belongs to (for enable/disable).
- **Integrations** (Google now, smart home later): implement `Integration` — `id`, `displayName`,
  `status: StateFlow<IntegrationStatus>` (`NotConfigured`/`Connected(account)`/`Error`), `tools()`,
  `promptLines()`, and a Settings section composable. The chat engine only offers tools from
  integrations that are `Connected`. This is how smart home plugs in for v2 (C20).
