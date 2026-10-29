# C08 — Tool framework and tool loop

| | |
|---|---|
| Phase | 2 — Tools |
| Depends on | C03, C06, C07 |
| Unblocks | C09, C10, C11, C18, C19, (v2) C20 |
| Size | Large — the most important chunk in the project |
| Read first | **03-ollama-reference §3–4 (all)**, 04-lessons L2–L7, 01-architecture §6, §12 |

## Goal

Tool calling that works reliably: a typed way to define tools, a registry that only offers tools that
are implemented, enabled and connected, and a turn runner that follows Ollama's protocol exactly —
multi-round, capped, with confirmations for actions that change things, never persisting the exchange.
v1 failed here (wrong JSON shape, results as system messages saved to history, ignored follow-up rounds,
no cap, fake data, desktop tools on a phone). Every one of those is designed out below.

## Specification

### 1. Defining tools (`tools/framework/`)
```kotlin
interface Tool {
    val spec: ToolSpec
    /** Short present-tense label for the thinking bubble, e.g. "Checking the weather…" */
    val statusLabel: String
    /** Integration this tool belongs to (null = built in). Tools of disconnected integrations aren't offered. */
    val integrationId: String? get() = null
    /** Non-null when the call changes something outside the app and needs the owner's approval first. */
    fun confirmation(args: ToolArgs): ConfirmationRequest? = null
    suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult
}
data class ToolSpec(val name: String, val description: String, val parameters: List<ToolParam>)
data class ToolParam(val name: String, val type: ParamType, val description: String, val required: Boolean = false,
                     val enumValues: List<String>? = null, val aliases: List<String> = emptyList())
enum class ParamType { String, Integer, Number, Boolean, DateTime /* ISO-8601 string */ }
sealed interface ToolResult {
    data class Ok(val text: String, val display: ToolDisplay? = null) : ToolResult
    data class Error(val text: String) : ToolResult          // model-facing, plain, says what failed
}
data class ToolContext(val clock: Clock, val settings: Settings, val dispatchers: AppDispatchers)
```
`ToolSpec.toOllama(): OllamaTool` builds `{"type":"function","function":{"name","description","parameters":{"type":"object","properties":{…},"required":[…]}}}`
(`DateTime` → `{"type":"string","description":"… (ISO-8601, e.g. 2026-10-30T12:00:00-04:00)"}`).

Naming rules: `snake_case` verbs (`get_weather`, `search_web`, `read_web_page`, `get_current_time`,
`list_calendar_events`). Descriptions: one sentence saying when to use it. Keep total tool JSON small.

### 2. `ToolArgs` — tolerant argument reading
Wraps the call's `JsonObject` and the spec. `string(name)`, `int(name)`, `double(name)`, `bool(name)`,
`dateTime(name, zone)` with coercion: numbers given as strings, `"true"`/`"yes"`, ISO dates with or without
offset (assume device zone), missing optional → null, missing required → `ArgumentError(name)`.
Key repair: if a required key is missing, accept a declared alias or a case/underscore-insensitive match
(`Location` → `location`, `place` → `location` when declared as an alias). Never guess beyond declared aliases.

### 3. `ToolRegistry`
```kotlin
class ToolRegistry(tools: List<Tool>, integrations: List<Integration>) {
    /** Tools to offer for this model right now (empty if the model lacks the "tools" capability). */
    fun available(model: ModelInfo): List<Tool>
    fun resolve(name: String, offered: List<Tool>): Tool?   // exact, then normalised (case, '-', '.', "functions." prefix), then edit distance ≤ 1 for names ≥ 8 chars
}
```
Offered tools are only: built-in tools + tools of integrations whose status is `Connected` (L6). No
coding/desktop tools ever (L7).

### 4. Tool guidance prompt section (registered with `PromptBuilder`)
Generated from the offered tools, e.g.:
```
You can use these tools when they help: get_weather (current weather for a place), search_web (search the internet for current information), read_web_page (read a page from a search result).
Use a tool instead of guessing whenever the answer depends on current or personal information. After using tools, answer in your own words and mention where information came from when it's from the web.
```
Omitted entirely when no tools are offered. If the model lacks `tools`, add instead:
`You can't look things up right now; if asked for current information, say so briefly.`

### 5. `ToolTurnRunner` — the loop (replaces `PlainTurnRunner` from C06)
```
messages = context (from ContextBuilder)
offered = registry.available(model); toolsJson = offered.map { it.spec.toOllama() }.ifEmpty { null }
toolsUsed = []
repeat (MAX_ROUNDS = 5):
    emit Status("Edith is thinking…")
    stream chat(messages, toolsJson):
        TextDelta → emit (the user sees any text the model writes before/around tool calls)
        ToolCalls(assistantMessage) → calls = assistantMessage.toolCalls
    if no calls: emit Finished(text, toolsUsed); return
    messages += assistantMessage                         // exactly as received (03 §4 rule 1)
    for call in calls (in order):
        tool = registry.resolve(call.function.name, offered)
        result = when {
            tool == null -> "Error: there's no tool called '<name>'. Available: <names>."
            else -> {
                args = ToolArgs(call.function.arguments, tool.spec)   // ArgumentError → "Error: missing <arg> for <tool>."
                tool.confirmation(args)?.let { req -> emit NeedsConfirmation(req); await decision;
                                                     if denied → "The user declined: <req.summary>. Don't retry unless they ask." }
                emit Status(tool.statusLabel)
                withTimeout(20s) { tool.execute(args, ctx) }  // timeout/exception → ToolResult.Error with a plain reason
            }
        }
        toolsUsed += tool.name (once)
        messages += OllamaMessage(role="tool", toolName=<resolved or raw name>, content=result.text.take(2000))
        log (debug): "Tool <name>(<args>) -> <first 120 chars>" ; (release): name + ok/error only
// cap reached
emit Status("Edith is thinking…")
final = chat(messages, tools = null)   // answer with what she has
emit Finished(final.text, toolsUsed)
```
- Text deltas from rounds that end in tool calls are shown while streaming, but the **stored** reply is
  the final round's text only (pre-tool chatter like "Let me check…" is not saved).
- Cancellation at any point cancels the HTTP call and any running tool; nothing partial is stored except
  per C06's cancel rule.
- The exchange (assistant tool-call messages, tool results) is **never** persisted (L3). Only `toolsUsed`
  names are saved, for the UI chip.

### 6. Confirmations
```kotlin
data class ConfirmationRequest(val id: String, val toolName: String, val title: String,   // "Add to your calendar?"
                               val details: List<Pair<String, String>>,                    // "When" to "Fri Oct 30, 12:00–1:00 PM"
                               val confirmLabel: String = "Confirm", val denyLabel: String = "Cancel")
```
`ChatEngine` exposes `AwaitingConfirmation(request)` and `fun resolveConfirmation(id: String, approved: Boolean)`.
UI (in C07's in-flight area): a card in Edith's bubble with title, detail rows, Confirm / Cancel buttons
(content descriptions "Confirm action", "Cancel action"). If the app is killed while waiting, the turn is
cancelled (no action taken). Policy (decision D11): every action that changes data outside the app.

### 7. Optional fallback: text-written tool calls (03 §4.2)
`TextToolCallParser.extract(text): List<ParsedCall>` recognising `<tool_call>name{…}</tool_call>` (gemma
style with `<|"|>` quotes), `<tool_call>{"name":…,"arguments":…}</tool_call>`, `[TOOL_CALLS]`, and bare JSON
`{"name":…,"arguments":…}` as the whole reply. Used **only** when a round returns no structured calls and the
text matches; log a warning `Fallback tool call parsed` each time. Behind a constant flag, default on.

### 7a. Built-in tool shipped in this chunk: `get_current_time`
Parameters: none (optional `timezone` IANA string). Returns
`"Thursday, October 29, 2026, 2:30 PM, America/Detroit (UTC-4)"`. Status label: "Checking the time…".
Exists so the framework ships with one real, deterministic tool for live testing.

## Tests (fixtures + scripted FakeOllamaClient)
1. Single round: model returns tool call → tool executed with parsed args → second request contains the
   assistant message with `tool_calls` unchanged and a `role:"tool"` message with `tool_name` and the result → final text stored.
2. Two tools in one round: both executed in order; two tool messages.
3. Multi-round: round 2 asks for another tool → executed (v1 ignored this — L4).
4. Cap: model asks for tools 6 times → after 5 rounds a final request **without `tools`** is sent; reply stored.
5. Unknown tool name → error result text sent back; loop continues; no crash.
6. Name repair: `functions.get_weather` and `Get-Weather` resolve; `get_wether` (distance 1, length ≥ 8) resolves.
7. Argument coercion: `"5"` for an Integer, `"yes"` for Boolean, alias key accepted; missing required → error result.
8. Tool throws → `ToolResult.Error` text sent; turn finishes normally. Tool exceeds 20 s → timeout error text.
9. Confirmation: approve → executes; deny → "declined" result, tool not executed; cancel while waiting → nothing executed.
10. Model without `tools` capability → no `tools` in the request; prompt has the "can't look things up" line.
11. Persistence: after a tool turn the DB contains exactly the user row and one assistant row; no tool text, `tools_used = ["get_current_time"]`.
12. Fallback parser: gemma-style text call `<tool_call>get_weather{location:<|"|>Boston<|"|>}</tool_call>` → parsed and executed; plain prose untouched.
13. Disconnected integration's tools are not offered.

## Acceptance criteria
- [ ] All tests pass.
- [ ] Live on the emulator with `gemma4:e4b-it-q8_0` and with `gemma4:12b-it-q8_0`: "What time is it?" →
      the app log shows `Tool get_current_time` and Edith answers with the right time; the "Used: time" chip shows.
      Repeat 5 times per model; report the success rate in the PR (target 5/5).
- [ ] Ollama's server log shows two `/api/chat` calls per tool turn.
- [ ] Database after the test contains no tool text (inspect and paste counts).

## Pitfalls
- Read tool calls only from `message.tool_calls`. Append the assistant message verbatim. Results are `role:"tool"` with `tool_name`.
- Never put tool exchanges in `ChatRepository`. Never offer a tool that returns placeholder data.
