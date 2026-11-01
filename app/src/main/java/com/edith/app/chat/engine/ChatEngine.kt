package com.edith.app.chat.engine

import com.edith.app.core.error.AppError
import com.edith.app.core.error.AppErrorException
import com.edith.app.core.error.ErrorMapper
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.ollama.ModelInfo
import com.edith.app.settings.ModelSelection
import com.edith.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** What the engine is doing. Every state but Idle names the conversation it belongs to (C06b). */
sealed interface EngineState {
    data object Idle : EngineState
    data class Working(val conversationId: Long, val label: String, val partialText: String) : EngineState
    data class AwaitingConfirmation(val conversationId: Long, val request: ConfirmationRequest, val partialText: String) : EngineState
    /** The last turn failed; cleared by the next send or regenerate. */
    data class Failed(val conversationId: Long, val error: AppError) : EngineState
}

/** Follows a reply as it streams (C13 speaks it; the titler names new chats). Called from the engine's coroutine. */
interface ReplyListener {
    fun onDelta(conversationId: Long, text: String) {}
    fun onFinished(conversationId: Long, text: String) {}
    fun onCancelled(conversationId: Long) {}
}

/** An attachment chosen in the input row and not yet sent (C15 fills this in). */
data class PendingAttachment(val fileName: String, val mimeType: String)

/**
 * One chat turn at a time, across all conversations (C06 §4, C06b §5): store the user message, make sure there's
 * a usable model, build the prompt and context, run the turn, store the result. Each turn is bound to the
 * conversation it started in, so switching chats mid-reply can't move it. Runs in an app-wide [scope], so a reply
 * carries on when the screen is rotated or closed; the UI only observes [state] and the history in
 * [ChatRepository]. Database and network work already run on their own threads (ChatRepository, OllamaClient),
 * so the engine needs no dispatcher of its own.
 */
class ChatEngine(
    private val repo: ChatRepository,
    private val settings: SettingsRepository,
    private val modelSelection: ModelSelection,
    private val promptBuilder: PromptBuilder,
    private val contextBuilder: ContextBuilder,
    private val turnRunner: TurnRunner,
    private val listeners: List<ReplyListener>,
    private val scope: CoroutineScope,
    private val localNetwork: LocalNetworkAccess,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Set synchronously when a turn starts, so a second tap can't start another (the "Mutex" of C06 §4). */
    private val busy = AtomicBoolean(false)
    @Volatile private var job: Job? = null
    @Volatile private var jobConversationId: Long? = null

    /** The conversation whose turn is running, or null when idle. */
    val activeConversationId: Long? get() = jobConversationId

    fun send(conversationId: Long, text: String, source: MessageSource, attachments: List<PendingAttachment> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return
        start(conversationId) {
            // C15/C16 add attachment rows here, in one transaction. Null: the conversation was deleted meanwhile.
            if (repo.addUserMessage(conversationId, text.trim(), source) == null) return@start EngineState.Idle
            runTurn(conversationId)
        }
    }

    /** Replaces the conversation's newest reply (complete, failed or cancelled) with a fresh one. Only when idle. */
    fun regenerateLast(conversationId: Long) {
        start(conversationId) {
            val newest = repo.latest(conversationId)
            if (newest == null || newest.message.role != MessageRole.Assistant) return@start EngineState.Idle
            repo.deleteMessage(newest.message.id)
            runTurn(conversationId)
        }
    }

    /** Stops the reply; the HTTP call is cancelled with it (C03). Partial text is kept as a cancelled message. */
    fun cancel() {
        job?.cancel()
    }

    /**
     * If a turn is running in [conversationId], stops it and waits until its cancelled message is stored. Call before
     * deleting that conversation, so nothing is written into it afterwards.
     */
    suspend fun cancelAndJoin(conversationId: Long) {
        val running = job
        if (running != null && jobConversationId == conversationId) running.cancelAndJoin()
    }

    /**
     * Runs [block] as the one active turn. Its result (Idle or Failed) is published only AFTER the turn is released,
     * so anyone who sees Idle can send straight away (otherwise a send right after a reply was silently ignored).
     */
    private fun start(conversationId: Long, block: suspend () -> EngineState) {
        if (!busy.compareAndSet(false, true)) return
        jobConversationId = conversationId
        _state.value = EngineState.Working(conversationId, PlainTurnRunner.THINKING, "")
        job = scope.launch {
            var end: EngineState = EngineState.Idle   // also the outcome of a cancelled turn
            try {
                end = block()
            } finally {
                jobConversationId = null
                busy.set(false)
                _state.value = end
            }
        }
    }

    /** Returns how the turn ended: Idle, or Failed. Cancellation is rethrown after the partial reply is kept. */
    private suspend fun runTurn(conversationId: Long): EngineState {
        var model: ModelInfo? = null
        val partial = StringBuilder()
        var label = PlainTurnRunner.THINKING
        var lastEmit = 0L
        fun publish(force: Boolean = false) {
            val now = nanoTime()
            if (force || now - lastEmit >= MIN_EMIT_INTERVAL_NS) {
                lastEmit = now
                _state.value = EngineState.Working(conversationId, label, partial.toString())
            }
        }

        try {
            if (!localNetwork.isGranted()) throw AppErrorException(AppError.PermissionDenied("local network"))
            model = when (val result = modelSelection.ensureModel()) {
                is Outcome.Success -> result.value
                is Outcome.Failure -> throw AppErrorException(result.error)
            }
            val baseUrl = settings.current().serverUrl
            val history = repo.recent(conversationId, HISTORY_LIMIT)
            val messages = contextBuilder.build(history, promptBuilder.system(model), model)

            var outcome: EngineState? = null
            turnRunner.run(baseUrl, model, messages).collect { event ->
                when (event) {
                    is TurnEvent.Status -> { label = event.label; publish(force = true) }
                    is TurnEvent.TextDelta -> {
                        partial.append(event.text)
                        listeners.forEach { it.onDelta(conversationId, event.text) }
                        publish()
                    }
                    is TurnEvent.NeedsConfirmation ->
                        _state.value = EngineState.AwaitingConfirmation(conversationId, event.request, partial.toString())
                    is TurnEvent.Finished -> outcome = finish(conversationId, event, model)
                }
            }
            return outcome ?: throw AppErrorException(AppError.MalformedResponse("The reply ended without finishing"))
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (partial.isNotBlank()) repo.addCancelledAssistantMessage(conversationId, partial.toString().trim(), model?.name)
                listeners.forEach { it.onCancelled(conversationId) }
            }
            throw e
        } catch (e: Exception) {
            val error = ErrorMapper.map(e, runCatching { settings.current().serverUrl }.getOrDefault("your server"))
            Log.w(LogTag.Chat, "Turn failed: ${error::class.simpleName}", e)
            return fail(conversationId, error, model)
        }
    }

    private suspend fun finish(conversationId: Long, event: TurnEvent.Finished, model: ModelInfo): EngineState {
        val text = event.text.trim()   // models often start with a space or newline
        if (text.isEmpty()) return fail(conversationId, AppError.EmptyReply, model)
        repo.addAssistantMessage(conversationId, text, model.name, event.toolsUsed)
        listeners.forEach { it.onFinished(conversationId, text) }
        event.stats?.let { Log.d(LogTag.Chat, "Reply finished: ${it.totalMs} ms, ${it.evalCount} tokens") }
        return EngineState.Idle
    }

    private suspend fun fail(conversationId: Long, error: AppError, model: ModelInfo?): EngineState {
        withContext(NonCancellable) { repo.addFailedAssistantMessage(conversationId, model?.name, error) }
        return EngineState.Failed(conversationId, error)
    }

    companion object {
        /** Messages read for context; the token budget (ContextBuilder) usually keeps fewer. */
        const val HISTORY_LIMIT = 200
        /** ~30 UI updates a second at most (C06 §4); the listeners still get every delta. */
        const val MIN_EMIT_INTERVAL_NS = 33_000_000L
    }
}
