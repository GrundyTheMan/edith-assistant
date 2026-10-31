# C03 — Ollama client

| | |
|---|---|
| Phase | 1 |
| Status | **Done** — see "As built" at the end |
| Depends on | C02 |
| Unblocks | C04, C06, C08, C12, C15 |
| Size | Medium |
| Read first | **03-ollama-reference (all of it)**, 01-architecture §7, §8.1, §9; 04-lessons L2, L8, L9 |

## Goal

A small, correct, fully tested client for the Ollama HTTP API: streaming chat with tool calls,
non-streaming chat, model list, model details/capabilities, version check. All request policy
(`think:false`, fixed `num_ctx`) lives here so no caller can get it wrong.

## Specification

### 1. Dependencies
OkHttp, kotlinx-serialization-json, kotlinx-coroutines; test: MockWebServer, coroutines-test, Turbine.
Add the `kotlinx-serialization` Gradle plugin.

### 2. DTOs (`ollama/OllamaDtos.kt`) — mirror 03-ollama-reference exactly
```kotlin
@Serializable data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean,
    val think: JsonElement = JsonPrimitive(false),      // see ThinkPolicy
    val options: OllamaOptions = OllamaOptions(),
    val tools: List<OllamaTool>? = null,
    @SerialName("keep_alive") val keepAlive: String? = null,
)
@Serializable data class OllamaOptions(@SerialName("num_ctx") val numCtx: Int = CONTEXT_LENGTH)
@Serializable data class OllamaMessage(
    val role: String,                                    // "system" | "user" | "assistant" | "tool"
    val content: String = "",
    val images: List<String>? = null,                    // base64, also carries WAV/MP3 audio
    @SerialName("tool_calls") val toolCalls: List<OllamaToolCall>? = null,
    @SerialName("tool_name") val toolName: String? = null,
)
@Serializable data class OllamaToolCall(val id: String? = null, val function: OllamaFunctionCall)
@Serializable data class OllamaFunctionCall(val name: String, val arguments: JsonObject = JsonObject(emptyMap()), val index: Int? = null)
@Serializable data class OllamaTool(val type: String = "function", val function: OllamaFunctionSpec)
@Serializable data class OllamaFunctionSpec(val name: String, val description: String, val parameters: JsonObject)
@Serializable data class OllamaChatChunk(                // one NDJSON line, or the whole non-streamed reply
    val model: String = "", val message: OllamaMessage? = null, val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("total_duration") val totalDuration: Long? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    val error: String? = null,
)
@Serializable data class OllamaTags(val models: List<OllamaModelTag>)
@Serializable data class OllamaModelTag(val name: String, val size: Long? = null, @SerialName("modified_at") val modifiedAt: String? = null)
@Serializable data class OllamaShow(val capabilities: List<String> = emptyList(), @SerialName("model_info") val modelInfo: JsonObject? = null)
```
`Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }`. `const val CONTEXT_LENGTH = 16384`.
Note the `arguments` field is a `JsonObject` — **never** a `String` or `Map<String, String>` (lessons L2).

### 3. Policy
```kotlin
object ThinkPolicy {
    /** gemma4 etc. accept false; gpt-oss expects a level. Verify gpt-oss behaviour live and note the result in the PR. */
    fun forModel(model: String): JsonElement = if (model.startsWith("gpt-oss")) JsonPrimitive("low") else JsonPrimitive(false)
}
```
Every request built by the client uses `ThinkPolicy.forModel(model)` and `CONTEXT_LENGTH`. Callers cannot
override `num_ctx`. `keep_alive` from settings decision D8 (default: not sent).

### 4. Client API (`ollama/OllamaClient.kt`)
```kotlin
interface OllamaClient {
    /** Streams a chat reply. Emits Delta for text, ToolCalls when the model asks for tools, then Done. */
    fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent>
    suspend fun chat(baseUrl: String, request: ChatCall): Outcome<OllamaMessage>   // non-streaming
    suspend fun listModels(baseUrl: String): Outcome<List<OllamaModelTag>>
    suspend fun show(baseUrl: String, model: String): Outcome<OllamaShow>
    suspend fun version(baseUrl: String): Outcome<String>
}
data class ChatCall(val model: String, val messages: List<OllamaMessage>, val tools: List<OllamaTool>? = null)
sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    data class ToolCalls(val assistantMessage: OllamaMessage) : ChatEvent  // the assistant message to append verbatim
    data class Done(val stats: ReplyStats) : ChatEvent
}
data class ReplyStats(val totalMs: Long?, val evalCount: Int?, val promptEvalCount: Int?)
```
Errors from `chatStream` are thrown as `AppErrorException(AppError)` inside the flow (collectors catch
and map). Implementation `OkHttpOllamaClient(okHttp, json, dispatchers)`:
- POST `/api/chat`; for streaming read `response.body.source()` line by line on `dispatchers.io`;
  parse each line as `OllamaChatChunk`; `chunk.error != null` → `OllamaHttp`/`ModelNotFound`.
- Accumulate `message.toolCalls` across lines; when `done`, if any tool calls were seen emit
  `ToolCalls(OllamaMessage(role="assistant", content=<accumulated text>, toolCalls=<all calls>))`
  **before** `Done`. Text deltas are emitted as they arrive even if tool calls follow.
- Honour cancellation: cancelling the flow cancels the OkHttp `Call` (so Ollama stops generating).
- HTTP errors: read body, parse `{"error": "..."}`; 404 + "not found" → `ModelNotFound`.

### 5. OkHttp configuration (in `AppContainer`)
`connectTimeout 5s`, `readTimeout 180s` (first token after a model load can take a while),
`writeTimeout 60s` (large image/audio uploads), `callTimeout 0` for streams. One shared client.
Add `res/xml/network_security_config.xml` with `<base-config cleartextTrafficPermitted="true">` and
reference it from the manifest; explain in the PR that Ollama on the LAN is plain HTTP.
Add `<uses-permission android:name="android.permission.INTERNET"/>`.

### 6. `ModelCatalog` (`ollama/ModelCatalog.kt`)
```kotlin
class ModelCatalog(private val client: OllamaClient) {
    /** Chat-capable models with their capabilities; hides models lacking "completion". */
    suspend fun chatModels(baseUrl: String): Outcome<List<ModelInfo>>
    suspend fun capabilities(baseUrl: String, model: String): Outcome<Set<String>>   // cached per (baseUrl, model)
    fun invalidate()
}
data class ModelInfo(val name: String, val capabilities: Set<String>) {
    val canUseTools get() = "tools" in capabilities; val canSee get() = "vision" in capabilities; val canHear get() = "audio" in capabilities
}
```
Fetch `show` for all tags concurrently (`coroutineScope { async }`); if one `show` fails, include the
model with empty capabilities rather than hiding it.

### 7. Test fixtures
Copy the real samples from 03-ollama-reference §3 into `app/src/test/resources/ollama/`:
`tool_call_nonstream.json`, `text_stream.ndjson` (≥ 4 lines + done), `tool_call_stream.ndjson`,
`error_model_not_found.json`, `tags.json`, `show_gemma4.json`, `show_embedding.json`.

## Tests (MockWebServer)
1. Request body for a chat has `"think":false`, `"options":{"num_ctx":16384}`, `"stream":true`, the tools array when given, and no `keep_alive` by default.
2. `gpt-oss:20b` request has `"think":"low"`.
3. Text stream → Delta("The"), Delta(" weather"), …, Done; concatenation equals the full text.
4. Tool-call stream → ToolCalls with one call `get_weather` whose `arguments["location"]` is `"Boston"`; then Done; no exception.
5. Non-stream tool call parses (`function` object, `arguments` object with string, number, boolean and nested values).
6. 404 model-not-found → `ModelNotFound("no-such-model")`.
7. Connection refused (shut down server) → `ServerUnreachable`.
8. Malformed NDJSON line → `MalformedResponse`.
9. Cancellation mid-stream cancels the HTTP call (MockWebServer sees the connection closed).
10. ModelCatalog hides `nomic-embed-text` (embedding only), keeps a model whose `show` failed, caches capabilities.

## Acceptance criteria
- [ ] All tests above pass.
- [ ] A debug-only smoke check (e.g. a unit-test-like instrumented test or a temporary debug screen) lists the
      owner's real models from the emulator via `http://10.0.2.2:11434` with correct capabilities; paste the output in the PR.
- [ ] No caller can send a chat without `think` and `num_ctx` set by policy.

## Pitfalls
- Don't use `Map<String, Any>` for JSON. Don't parse `function` as a string. Don't read tool calls from the top level.
- Read the stream on IO; never on main.

## As built (2026-10-31)
- Live check: `OllamaLiveCheckTest` (androidTest, opt-in so CI and `check.sh` skip it):
  `adb shell am instrument -w -e live true -e class com.edith.app.ollama.OllamaLiveCheckTest com.edith.app.test/androidx.test.runner.AndroidJUnitRunner`,
  results in `adb logcat -d -s Edith/Ollama:*`. Result on the owner's emulator via `http://10.0.2.2:11434`, Ollama 0.40.13:
  `gemma4:12b-it-q8_0` and `gemma4:e4b-it-q8_0` audio, completion, thinking, tools, vision · `gpt-oss:20b` completion,
  thinking, tools · `ornith:9b` completion, thinking, tools · `qwen3.5:9b-q8_0` completion, thinking, tools, vision ·
  `nomic-embed-text` hidden. Streamed reply "Hello"; real tool call `get_weather({"location":"Boston"})`.
- **Android 17 needs `ACCESS_LOCAL_NETWORK`** (declared in the manifest; runtime request is C04's job): without it
  every request times out (04-lessons L23).
- `gpt-oss:20b` live: `think:"low"` → "hello" with 23 chars of thinking; `think:false` → still thinks (288 chars).
  So `ThinkPolicy`'s "low" for gpt-oss is right: it can't switch thinking off.
