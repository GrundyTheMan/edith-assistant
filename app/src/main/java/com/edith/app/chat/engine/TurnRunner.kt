package com.edith.app.chat.engine

import com.edith.app.ollama.ChatCall
import com.edith.app.ollama.ChatEvent
import com.edith.app.ollama.ModelInfo
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaMessage
import com.edith.app.ollama.ReplyStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Runs one assistant turn (C06 §3). The pluggable part of the engine: this chunk has [PlainTurnRunner];
 * C08 swaps in a tool-calling runner in AppContainer without touching [ChatEngine].
 * Failures are thrown (as AppErrorException where the cause is known); the engine maps them.
 */
interface TurnRunner {
    fun run(baseUrl: String, model: ModelInfo, messages: List<OllamaMessage>): Flow<TurnEvent>
}

sealed interface TurnEvent {
    /** Shown while Edith works, e.g. "Edith is thinking…", "Checking the weather…". */
    data class Status(val label: String) : TurnEvent
    data class TextDelta(val text: String) : TurnEvent
    /** A new round starts after tool calls: what was written so far ("Let me check…") is cleared from the screen. */
    data object TextReset : TurnEvent
    data class NeedsConfirmation(val request: ConfirmationRequest) : TurnEvent
    /** [text] is the final round's text only: that's what's stored (C08 §5). */
    data class Finished(
        val text: String,
        val toolsUsed: List<String>,
        val stats: ReplyStats?,
        /** Pictures tools brought back (C25: camera snapshots), kept with the reply as Edith's attachments. */
        val images: List<com.edith.app.tools.framework.ToolImage> = emptyList(),
    ) : TurnEvent
}

/**
 * What Edith wants the owner to approve before a tool changes something outside the app (C08 §6, decision D11),
 * e.g. title "Add to your calendar?" with details "When" to "Fri Oct 30, 12:00–1:00 PM". The turn runner fills in
 * [id] and [toolName].
 */
data class ConfirmationRequest(
    val title: String,
    val details: List<Pair<String, String>> = emptyList(),
    val confirmLabel: String = "Confirm",
    val denyLabel: String = "Cancel",
    val id: String = "",
    val toolName: String = "",
    /**
     * Only a tap on the screen may approve it, never a voice (D10: unlocking a door, opening the garage). Hands-free
     * says so out loud instead of waiting silently.
     */
    val screenOnly: Boolean = false,
)

/** No tools: one streamed chat call. */
class PlainTurnRunner(private val client: OllamaClient) : TurnRunner {
    override fun run(baseUrl: String, model: ModelInfo, messages: List<OllamaMessage>): Flow<TurnEvent> = flow {
        emit(TurnEvent.Status(THINKING))
        val text = StringBuilder()
        client.chatStream(baseUrl, ChatCall(model.name, messages)).collect { event ->
            when (event) {
                is ChatEvent.Delta -> {
                    text.append(event.text)
                    emit(TurnEvent.TextDelta(event.text))
                }
                is ChatEvent.ToolCalls -> Unit   // no tools are offered in this runner, so none are expected
                is ChatEvent.Done -> emit(TurnEvent.Finished(text.toString(), emptyList(), event.stats))
            }
        }
    }

    companion object {
        const val THINKING = "Edith is thinking…"
    }
}
