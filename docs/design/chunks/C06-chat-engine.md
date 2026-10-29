# C06 — Chat engine

| | |
|---|---|
| Phase | 1 |
| Depends on | C03, C04, C05 |
| Unblocks | C07, C08, C13, C15 |
| Size | Medium–large |
| Read first | 00-product §5; 01-architecture §4, §6; 03-ollama-reference §2–4, §7; 04-lessons L3, L10, L14 |

## Goal

The brain of a chat turn: build the system prompt, turn history into Ollama messages safely, stream
the reply, persist the result, support cancel and regenerate, and expose state the UI can render.
Tools are **not** in this chunk, but the engine must be built around a pluggable turn runner so C08
can add them without restructuring.

## Specification

### 1. `PromptBuilder` (`chat/engine/`)
```kotlin
interface PromptSection { fun text(ctx: PromptContext): String? }   // null = omit
data class PromptContext(val now: ZonedDateTime, val model: ModelInfo)
class PromptBuilder(private val clock: Clock, private val sections: () -> List<PromptSection>) {
    fun system(model: ModelInfo): String   // PERSONA + "\n\n" + non-null sections joined with "\n"
}
const val PERSONA = """…"""   // 00-product §5, verbatim
```
Built-in section for this chunk: **date/time** — `"Current date and time: Thursday, October 29, 2026, 2:30 PM (America/Detroit)."`
(device zone, device locale formatting). Later chunks register more sections via `AppContainer`.

### 2. `ContextBuilder` — history → Ollama messages
```kotlin
class ContextBuilder(private val attachmentContext: AttachmentContext = NoAttachments) {
    fun build(history: List<MessageWithAttachments>, system: String, model: ModelInfo): List<OllamaMessage>
}
```
Rules, in order:
1. Start with `OllamaMessage("system", system)`.
2. Include only `Complete` messages with role user/assistant. Skip `Failed`/`Cancelled` (a failed turn's
   error text must not teach the model anything — lessons L3).
3. Strip `<tool_call>…</tool_call>` (and an unclosed `<tool_call>` to the end) from assistant text; drop the
   message if nothing remains.
4. Token budget: estimate tokens as `ceil(chars / 3.5)`; keep the newest messages whose total fits in
   **10,000 tokens** (leaves room for tools, media, and the reply within `num_ctx` 16384). Always include
   the latest user message even if it alone exceeds the budget (then truncate its *middle* with "[…]").
5. Never start the kept window with an assistant message (drop a leading assistant message so the model
   sees a user turn first).
6. Attachments: delegate to `AttachmentContext` (C15 implements labels + media window). Default: none.

### 3. `TurnRunner` — the pluggable part
```kotlin
interface TurnRunner {
    /** Runs one assistant turn. Emits progress; returns the final assistant text and tools used. */
    fun run(baseUrl: String, model: ModelInfo, messages: List<OllamaMessage>): Flow<TurnEvent>
}
sealed interface TurnEvent {
    data class Status(val label: String) : TurnEvent                // "Edith is thinking…", "Checking the weather…"
    data class TextDelta(val text: String) : TurnEvent
    data class NeedsConfirmation(val request: ConfirmationRequest) : TurnEvent   // C08
    data class Finished(val text: String, val toolsUsed: List<String>, val stats: ReplyStats?) : TurnEvent
}
class PlainTurnRunner(private val client: OllamaClient) : TurnRunner   // this chunk: single streamed call, no tools
```
C08 replaces `PlainTurnRunner` with `ToolTurnRunner` in `AppContainer`. Define `ConfirmationRequest`
now as an empty-ish placeholder type in `chat/engine` so the event compiles (C08 fills it in).

### 4. `ChatEngine`
```kotlin
class ChatEngine(
    repo: ChatRepository, settings: SettingsRepository, modelSelection: ModelSelection,
    promptBuilder: PromptBuilder, contextBuilder: ContextBuilder, turnRunner: TurnRunner,
    listeners: List<ReplyListener>, scope: CoroutineScope /* app-scoped */, dispatchers: AppDispatchers,
) {
    val state: StateFlow<EngineState>
    fun send(text: String, source: MessageSource, attachments: List<PendingAttachment> = emptyList())
    fun regenerateLast()
    fun cancel()
}
sealed interface EngineState {
    data object Idle : EngineState
    data class Working(val label: String, val partialText: String) : EngineState
    data class AwaitingConfirmation(val request: ConfirmationRequest, val partialText: String) : EngineState
    data class Failed(val error: AppError) : EngineState   // cleared on next send
}
interface ReplyListener { fun onDelta(text: String) {} ; fun onFinished(text: String) {} ; fun onCancelled() {} }  // C13 speech
```
Behaviour:
- `send`: ignore if blank and no attachments, or if already working (UI disables send). Persist the user
  message first (`ChatRepository.addUserMessage`; C15/C16 add attachment rows in the same transaction),
  then run the turn in the **app-scoped** coroutine (survives screen rotation; ViewModel only observes).
- Before calling the model: `modelSelection.ensureModel()`; failure → `Failed`.
- During the turn: `Working(label, partialText)` updated on every Status/TextDelta (throttle UI-facing
  emissions to ~30/s). Forward deltas to listeners.
- On `Finished`: `repo.addAssistantMessage(text, model, toolsUsed)`; state `Idle`; listeners `onFinished`.
  If `text` is blank → store a Failed message "Edith didn't reply. Try again." (never an empty bubble).
- On error: map to `AppError`; `repo.addFailedAssistantMessage(model, error)`; state `Failed(error)`.
- `cancel()`: cancels the job (which cancels the HTTP call — C03); if partial text exists, store it as an
  assistant message with status `Cancelled` (shown, but excluded from context); listeners `onCancelled`.
- `regenerateLast()`: only when idle and the newest message is an assistant message (Complete/Failed/Cancelled):
  delete it, re-run the turn from history.
- Only one turn at a time (a `Mutex`).

## Tests (fakes: `FakeOllamaClient` scripted with fixtures, in-memory Room, `FakeClock`)
- PromptBuilder: persona verbatim + date line; omitted null sections.
- ContextBuilder: excludes failed/cancelled; strips `<tool_call>` text (unclosed too); drops leading assistant;
  respects the 10k budget keeping newest; huge last user message truncated in the middle.
- Engine: send → user row, Working with growing partial text, assistant row, Idle.
- Engine: server unreachable → Failed state + failed assistant row with the error message; next send clears Failed.
- Engine: cancel mid-stream → Cancelled row with partial text; HTTP call cancelled.
- Engine: regenerate replaces the last assistant row.
- Engine: turn survives the ViewModel being cleared (engine scope is app-level).
- Engine: blank reply → failed row, not an empty assistant bubble.

## Acceptance criteria
- [ ] All tests pass.
- [ ] Live check on the emulator (via a temporary debug UI or C07 if done together): a reply streams in
      word by word; time to first token and total reply time noted in the PR (budget: first token < 2 s warm).

## Pitfalls
- Never persist the system prompt, tool calls or tool results (01-architecture §5.2).
- Engine coroutines run in an app-scoped `CoroutineScope(SupervisorJob() + dispatchers.default)` from `AppContainer`, not `viewModelScope`.
