package com.edith.app.tools.framework

import com.edith.app.BuildConfig
import com.edith.app.chat.engine.PlainTurnRunner
import com.edith.app.chat.engine.TurnEvent
import com.edith.app.chat.engine.TurnRunner
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.permissions.ToolPermissions
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.time.Clock
import com.edith.app.ollama.ChatCall
import com.edith.app.ollama.ChatEvent
import com.edith.app.ollama.ModelInfo
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaFunctionCall
import com.edith.app.ollama.OllamaMessage
import com.edith.app.ollama.OllamaTool
import com.edith.app.ollama.OllamaToolCall
import com.edith.app.ollama.ReplyStats
import com.edith.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.util.Base64
import java.util.UUID

/**
 * The tool loop (C08 §5), following Ollama's protocol exactly (03-ollama-reference §4):
 * - tool calls are read only from `message.tool_calls`; the assistant message goes back verbatim, then one
 *   `role:"tool"` message (with `tool_name`) per call, in order;
 * - every round runs the same code; at most [MAX_ROUNDS], then one last request without tools;
 * - the exchange is never stored (L3): only the final round's text and the names of the tools used reach the engine.
 * Text written before a tool call ("Let me check…") is shown while it streams, then cleared ([TurnEvent.TextReset]).
 */
class ToolTurnRunner(
    private val client: OllamaClient,
    private val registry: ToolRegistry,
    private val confirmations: Confirmations,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    private val toolTimeoutMs: Long = TOOL_TIMEOUT_MS,
    private val textCallFallback: Boolean = TEXT_CALL_FALLBACK,
    private val permissions: ToolPermissions = ToolPermissions { true },
) : TurnRunner {

    override fun run(baseUrl: String, model: ModelInfo, messages: List<OllamaMessage>): Flow<TurnEvent> = flow {
        val offered = registry.available(model)
        if (BuildConfig.DEBUG) Log.d(LogTag.Tools, "Offering tools: ${offered.joinToString { it.spec.name }.ifEmpty { "none" }}")
        val toolsJson = offered.map { it.spec.toOllama() }.ifEmpty { null }
        val ctx = ToolContext(clock, settings.current(), dispatchers)
        val history = messages.toMutableList()
        val used = linkedSetOf<String>()
        val images = mutableListOf<ToolImage>()

        repeat(MAX_ROUNDS) { round ->
            if (round > 0) emit(TurnEvent.TextReset)
            emit(TurnEvent.Status(PlainTurnRunner.THINKING))
            val reply = streamRound(baseUrl, model, history, toolsJson)
            var assistant = reply.assistant
            if (assistant == null && toolsJson != null && textCallFallback) {
                val parsed = TextToolCallParser.extract(reply.text)
                if (parsed.isNotEmpty()) {
                    Log.w(LogTag.Tools, "Fallback tool call parsed: ${parsed.joinToString { it.name }}")
                    // Sent back in the proper form, so the model sees what a real call looks like.
                    assistant = OllamaMessage("assistant", "", toolCalls = parsed.map { OllamaToolCall(function = OllamaFunctionCall(it.name, it.arguments)) })
                }
            }
            if (assistant == null) {
                emit(TurnEvent.Finished(reply.text, used.toList(), reply.stats, images.toList()))
                return@flow
            }
            history += assistant   // exactly as received (03 §4 rule 1)
            for (call in assistant.toolCalls.orEmpty()) {
                val (name, result) = runCall(call, offered, ctx)
                if (name != null) used += name
                val limit = offered.firstOrNull { it.spec.name == name }?.maxResultChars ?: RESULT_MAX_CHARS
                val pictures = (result as? ToolResult.Ok)?.images.orEmpty()
                images += pictures
                // The model sees a picture only if it can (C25 §4, C15's vision check); the owner always does.
                val seen = if (model.canSee && pictures.isNotEmpty()) pictures.map { Base64.getEncoder().encodeToString(it.jpeg) } else null
                history += OllamaMessage("tool", result.text.take(limit), images = seen, toolName = name ?: call.function.name)
            }
        }
        // Still asking for tools after MAX_ROUNDS: answer with what she has.
        Log.w(LogTag.Tools, "Tool round cap ($MAX_ROUNDS) reached; asking for a final answer without tools")
        emit(TurnEvent.TextReset)
        emit(TurnEvent.Status(PlainTurnRunner.THINKING))
        val final = streamRound(baseUrl, model, history, tools = null)
        emit(TurnEvent.Finished(final.text, used.toList(), final.stats, images.toList()))
    }

    private class Round(val text: String, val assistant: OllamaMessage?, val stats: ReplyStats?)

    private suspend fun FlowCollector<TurnEvent>.streamRound(
        baseUrl: String, model: ModelInfo, history: List<OllamaMessage>, tools: List<OllamaTool>?,
    ): Round {
        val text = StringBuilder()
        var assistant: OllamaMessage? = null
        var stats: ReplyStats? = null
        client.chatStream(baseUrl, ChatCall(model.name, history.toList(), tools)).collect { event ->
            when (event) {
                is ChatEvent.Delta -> { text.append(event.text); emit(TurnEvent.TextDelta(event.text)) }
                is ChatEvent.ToolCalls -> assistant = event.assistantMessage
                is ChatEvent.Done -> stats = event.stats
            }
        }
        return Round(text.toString(), assistant?.takeIf { !it.toolCalls.isNullOrEmpty() }, stats)
    }

    /** Runs one call. Returns the tool's real name (null if there's no such tool) and the text for the model. */
    private suspend fun FlowCollector<TurnEvent>.runCall(call: OllamaToolCall, offered: List<Tool>, ctx: ToolContext): Pair<String?, ToolResult> {
        val requested = call.function.name
        val tool = registry.resolve(requested, offered)
            ?: return null to ToolResult.Error("Error: there's no tool called '$requested'. Available: ${offered.joinToString { it.spec.name }.ifEmpty { "none" }}.")
        val name = tool.spec.name
        val args = ToolArgs(call.function.arguments, tool.spec)
        val result = try {
            // Outside the tool's timeout, like confirmations: the owner may take a while to answer Android's dialog.
            tool.permission(args)?.let { need ->
                if (!permissions.isGranted(need.permission)) emit(TurnEvent.Status(WAITING_FOR_PERMISSION))
                if (!permissions.request(need.permission)) {
                    log(name, call, "permission denied")
                    return name to ToolResult.Error(need.deniedResult)
                }
            }
            val request = tool.confirmation(args)?.copy(id = UUID.randomUUID().toString(), toolName = name)
            if (request != null) {
                val answer = confirmations.register(request)   // before the screen can show it
                emit(TurnEvent.NeedsConfirmation(request))
                if (!confirmations.await(request, answer)) {
                    log(name, call, "declined")
                    return name to ToolResult.Ok("The user declined: ${request.title}. Don't retry unless they ask.")
                }
            }
            emit(TurnEvent.Status(tool.statusLabelFor(args)))
            withTimeout(toolTimeoutMs) { tool.execute(args, ctx) }
        } catch (e: ArgumentError) {
            ToolResult.Error("Error: ${e.message} for $name.")
        } catch (e: TimeoutCancellationException) {
            ToolResult.Error("Error: $name took too long and was stopped.")
        } catch (e: CancellationException) {
            throw e   // the owner stopped the reply: stop the tool too
        } catch (e: Exception) {
            Log.w(LogTag.Tools, "Tool $name failed", e)
            ToolResult.Error("Error: $name failed (${e.message ?: e::class.simpleName}).")
        }
        log(name, call, if (result is ToolResult.Error) "error: ${result.text}" else result.text)
        return name to result
    }

    /** Debug builds log arguments and the start of the result; release builds only the name and outcome. */
    private fun log(name: String, call: OllamaToolCall, outcome: String) {
        if (BuildConfig.DEBUG) Log.d(LogTag.Tools, "Tool $name(${call.function.arguments}) -> ${outcome.take(120)}")
        else Log.i(LogTag.Tools, "Tool $name -> ${if (outcome.startsWith("error")) "error" else "ok"}")
    }

    companion object {
        const val MAX_ROUNDS = 5
        const val TOOL_TIMEOUT_MS = 20_000L
        const val RESULT_MAX_CHARS = 2_000
        const val WAITING_FOR_PERMISSION = "Waiting for your permission…"
        /** C08 §7: parse tool calls a model wrote as text. On by default; every use is logged. */
        const val TEXT_CALL_FALLBACK = true
    }
}
