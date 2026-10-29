# C02 — Core infrastructure

| | |
|---|---|
| Phase | 1 |
| Depends on | C01 |
| Unblocks | all later chunks |
| Size | Medium |
| Read first | 01-architecture §3, §8, §9; 02-standards §2–3 |

## Goal

The plumbing every feature uses: dependency container, dispatchers, clock, error model, logging,
crash capture and reporting, StrictMode, secret storage, and shared test fakes.

## Specification

### 1. `EdithApp` and `AppContainer`
```kotlin
class EdithApp : Application() {
    lateinit var container: AppContainer; private set
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)            // first, so later failures are captured
        if (BuildConfig.DEBUG) enableStrictMode()
        container = AppContainer(this)
    }
}

/** Builds and holds the app's long-lived objects. Later chunks add fields here. */
class AppContainer(val app: Application) {
    val dispatchers: AppDispatchers = AppDispatchers.Default
    val clock: Clock = SystemClock
    val secretStore: SecretStore = KeystoreSecretStore(app)
    // C03+: okHttp, ollamaClient, settings, database, …
}
```
Register `android:name=".EdithApp"` in the manifest. Provide `val Context.container` extension and a
`viewModelFactory` helper so screens do `viewModel(factory = ChatViewModel.Factory)`.

### 2. `AppDispatchers`, `Clock`
```kotlin
data class AppDispatchers(val main: CoroutineDispatcher, val io: CoroutineDispatcher, val default: CoroutineDispatcher) {
    companion object { val Default = AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default) }
}
interface Clock { fun now(): Instant; fun zone(): ZoneId }
object SystemClock : Clock { … }
```

### 3. Error model (`core/error`)
```kotlin
sealed interface AppError {
    val userMessage: String
    val cause: Throwable? get() = null
    data class ServerUnreachable(val url: String, override val cause: Throwable? = null) : AppError {
        override val userMessage get() = "Can't reach your Ollama server at $url. Is the PC on and Ollama running?" }
    data object Timeout : AppError { override val userMessage get() = "Edith took too long to answer. Try again." }
    data class ModelNotFound(val model: String) : AppError { override val userMessage get() = "The model \"$model\" isn't installed on your server. Pick another in Settings." }
    data class ModelLacksCapability(val model: String, val capability: String) : AppError { … }
    data class OllamaHttp(val code: Int, val body: String) : AppError { … }
    data class MalformedResponse(val detail: String, override val cause: Throwable? = null) : AppError { … }
    data class ToolFailed(val tool: String, val detail: String) : AppError { … }
    data class PermissionDenied(val permission: String) : AppError { … }
    data object GoogleNotConnected : AppError { … }
    data class GoogleAuth(val detail: String, override val cause: Throwable? = null) : AppError { … }
    data class Unknown(override val cause: Throwable) : AppError { … }
}
sealed interface Outcome<out T> { data class Success<T>(val value: T) : Outcome<T>; data class Failure(val error: AppError) : Outcome<Nothing> }
```
User messages are plain language and say what to do. Add `inline fun <T> outcome(block: () -> T): Outcome<T>`
that rethrows `CancellationException` and maps everything else via an `ErrorMapper`.

### 4. Logging (`core/log`)
`object Log { fun d/i/w/e(tag: LogTag, msg: String, t: Throwable? = null) }` wrapping `android.util.Log`,
with `enum class LogTag(val value: String) { App("Edith/App"), Chat("Edith/Chat"), Ollama("Edith/Ollama"),
Tools("Edith/Tools"), Voice("Edith/Voice"), Attachments("Edith/Attachments"), Google("Edith/Google") }`.
Also appends each line to an in-memory ring buffer (last 200 lines) used by crash reports.
`fun redact(text: String): String` — in release builds returns `"<${text.length} chars>"`; in debug returns text.

### 5. Crash capture and report banner
`CrashReporter.install(app)`: sets a default uncaught exception handler that writes
`filesDir/crash/last-crash.txt` (UTC time, thread, exception + stack, versionName, Android version,
device model, last 50 ring-buffer lines), then delegates to the previous handler.
`CrashReporter.pending(context): CrashReport?` reads it. `MainActivity` shows a dismissible banner at
the top of the content when pending: "Edith closed unexpectedly last time." with **View** (dialog with
the text, selectable) / **Copy** / **Dismiss** (deletes the file). Settings → About later links here (C04).

### 6. StrictMode (debug only)
Thread policy `detectAll().penaltyLog()`, VM policy `detectAll().penaltyLog()`.

### 7. `SecretStore`
```kotlin
interface SecretStore { suspend fun put(key: String, value: String?); suspend fun get(key: String): String? }
```
`KeystoreSecretStore`: AES/GCM 256 key alias `edith_secrets` in AndroidKeyStore (`PURPOSE_ENCRYPT or
PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, no user auth). Stores `iv+ciphertext`
base64 in a private DataStore file `secrets`. `put(key, null)` deletes. If decryption fails (key lost
after restore), delete the value and return null (log a warning).

### 8. Shared test fakes (`src/test/java/com/edith/app/testing/`)
`TestDispatchers` (all = `UnconfinedTestDispatcher` or a `StandardTestDispatcher`), `FakeClock(fixed instant, zone)`,
`InMemorySecretStore`. Later chunks add more here.

## Tests
- `ErrorMapperTest`: `ConnectException` → `ServerUnreachable`; `SocketTimeoutException` → `Timeout`;
  `CancellationException` is rethrown, not mapped.
- `LogRingBufferTest`: keeps the last 200; order preserved.
- `CrashReporterTest` (Robolectric or JVM with temp dir): writing then reading a report; dismissal deletes.
- `KeystoreSecretStoreTest` as an instrumented test (Keystore isn't available on the JVM): round-trip,
  delete, overwrite.

## Acceptance criteria
- [ ] App still launches; `AppContainer` is created once (log line in debug).
- [ ] Throwing a test exception from a debug-only menu/adb command (e.g. a hidden `adb shell am start` intent
      `com.edith.app.DEBUG_CRASH` handled in debug builds) produces `last-crash.txt`; next launch shows the banner;
      View/Copy/Dismiss work.
- [ ] StrictMode active in debug; no violations logged at startup.
- [ ] All tests pass; secret store instrumented test passes on the emulator.

## Pitfalls
- Install the crash handler before anything else. Don't swallow the crash — always delegate.
- Never log secrets; `redact` is for message bodies.
