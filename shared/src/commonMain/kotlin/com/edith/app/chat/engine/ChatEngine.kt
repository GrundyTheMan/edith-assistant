package com.edith.app.chat.engine

import com.edith.app.core.error.AppError
import com.edith.app.core.error.AppErrorException
import com.edith.app.core.error.ErrorMapper
import com.edith.app.core.error.isClassLoadFailure
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.dispatchers.platformIoDispatcher
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.attachments.AttachmentWriter
import com.edith.app.attachments.PendingAttachment
import com.edith.app.attachments.ReplyFormat
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.AttachmentEntity
import com.edith.app.data.db.AttachmentKind
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.ollama.ModelInfo
import com.edith.app.settings.ModelSelection
import com.edith.app.settings.SettingsRepository
import com.edith.app.tools.framework.ApprovalChannel
import com.edith.app.tools.framework.Confirmations
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
import kotlinx.io.IOException
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/** What the engine is doing. Every state but Idle names the conversation it belongs to (C06b). */
sealed interface EngineState {
    data object Idle : EngineState
    data class Working(val conversationId: Long, val label: String, val partialText: String) : EngineState
    data class AwaitingConfirmation(val conversationId: Long, val request: ConfirmationRequest, val partialText: String) : EngineState
    /** The last turn failed; cleared by the next send or regenerate. */
    data class Failed(val conversationId: Long, val error: AppError) : EngineState
}

/** True while a turn is being written (Working or waiting for the owner's OK). */
fun EngineState.isRunning(): Boolean = this is EngineState.Working || this is EngineState.AwaitingConfirmation

/** Follows a reply as it streams (C13 speaks it; the titler names new chats). Called from the engine's coroutine. */
interface ReplyListener {
    fun onDelta(conversationId: Long, text: String) {}
    /** The text streamed so far was dropped: it came before a tool call, and the next round writes the reply. */
    fun onReset(conversationId: Long) {}
    /** [messageId] is the stored reply, or null if its conversation was deleted meanwhile. */
    fun onFinished(conversationId: Long, messageId: Long?, text: String) {}
    fun onCancelled(conversationId: Long) {}
    fun onFailed(conversationId: Long) {}
}

/**
 * One chat turn at a time, across all conversations (C06 §4, C06b §5): store the user message, make sure there's
 * a usable model, build the prompt and context, run the turn, store the result. Each turn is bound to the
 * conversation it started in, so switching chats mid-reply can't move it. Runs in an app-wide [scope], so a reply
 * carries on when the screen is rotated or closed; the UI only observes [state] and the history in
 * [ChatRepository]. Database and network work already run on their own threads (ChatRepository, OllamaClient),
 * so the engine needs no dispatcher of its own.
 */
@OptIn(ExperimentalAtomicApi::class)
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
    private val confirmations: Confirmations = Confirmations(),
    private val nanoTime: () -> Long = monotonicNanos,
    /** Where attachments' files go (C15, C16). */
    private val attachmentStore: AttachmentWriter? = null,
    /** Runs after a message with attachments is stored: retention (C16 §2). */
    private val afterAttachments: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Set synchronously when a turn starts, so a second tap can't start another (the "Mutex" of C06 §4). */
    private val busy = AtomicBoolean(false)
    @Volatile private var job: Job? = null
    @Volatile private var jobConversationId: Long? = null

    /** The conversation whose turn is running, or null when idle. */
    val activeConversationId: Long? get() = jobConversationId

    /**
     * Sends the owner's message, with any attachments (C15). False if a turn is already running: nothing was sent, and
     * the attachments are still the caller's.
     */
    fun send(conversationId: Long, text: String, source: MessageSource, attachments: List<PendingAttachment> = emptyList()): Boolean {
        if (text.isBlank() && attachments.isEmpty()) return false
        return start(conversationId) {
            // Files first, so a row never points at a file that isn't there yet (C16 §1).
            val session = keepsNothing()
            val new = attachments.map { a ->
                val path = withContext(platformIoDispatcher) { attachmentStore?.keep(a, session) }
                if (path == null) attachmentStore?.discard(a)
                ChatRepository.NewAttachment(a.kind, a.fileName, a.mimeType, a.sizeBytes, path)
            }
            if (repo.addUserMessage(conversationId, text.trim(), source, new) == null) {   // the chat was deleted meanwhile
                new.forEach { attachmentStore?.delete(it.storedPath) }
                return@start EngineState.Idle
            }
            if (new.isNotEmpty()) afterAttachments()
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
     * The owner's answer to [EngineState.AwaitingConfirmation] (C08 §6). Approved runs the tool; denied tells Edith so.
     * A screen-only card (D10) takes only a [ApprovalChannel.Tap].
     */
    fun resolveConfirmation(id: String, approved: Boolean, by: ApprovalChannel) = confirmations.resolve(id, approved, by)

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
    private fun start(conversationId: Long, block: suspend () -> EngineState): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        jobConversationId = conversationId
        _state.value = EngineState.Working(conversationId, PlainTurnRunner.THINKING, "")
        job = scope.launch {
            var end: EngineState = EngineState.Idle   // also the outcome of a cancelled turn
            try {
                end = block()
            } finally {
                jobConversationId = null
                busy.store(false)
                _state.value = end
            }
        }
        return true
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
            // C15: the newest message's attachments must be usable by this model; don't call it otherwise.
            history.lastOrNull { it.message.role == MessageRole.User }?.let { cantUse(it.attachments, model) }
                ?.let { throw AppErrorException(it) }
            val hasAttachments = history.any { it.attachments.isNotEmpty() }
            val messages = contextBuilder.build(history, promptBuilder.system(model, hasAttachments), model)

            var outcome: EngineState? = null
            turnRunner.run(baseUrl, model, messages).collect { event ->
                when (event) {
                    is TurnEvent.Status -> { label = event.label; publish(force = true) }
                    is TurnEvent.TextDelta -> {
                        partial.append(event.text)
                        listeners.forEach { it.onDelta(conversationId, event.text) }
                        publish()
                    }
                    is TurnEvent.TextReset -> {   // a new round after tool calls
                        partial.clear()
                        listeners.forEach { it.onReset(conversationId) }
                        publish(force = true)
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
        } catch (e: Throwable) {
            if (!e.isClassLoadFailure()) throw e
            // A class that failed to load (e.g. ExceptionInInitializerError) is a bug, but it must fail this turn,
            // not crash the app: it once did, from a regex Android couldn't compile (L25).
            Log.e(LogTag.Chat, "Turn failed: a part of Edith couldn't load", e)
            return fail(conversationId, AppError.Unknown(e), model)
        }
    }

    private suspend fun finish(conversationId: Long, event: TurnEvent.Finished, model: ModelInfo): EngineState {
        val text = event.text.trim()   // models often start with a space or newline
        if (text.isEmpty()) return fail(conversationId, AppError.EmptyReply, model)
        val written = withContext(NonCancellable) { edithAttachments(conversationId, text) }
        val reply = if (event.images.isEmpty()) written else withContext(NonCancellable) { written.withPictures(event.images) }
        val messageId = repo.addAssistantMessage(conversationId, reply.text, model.name, event.toolsUsed, reply.files, reply.resends)
        if (messageId == null) reply.files.forEach { attachmentStore?.delete(it.storedPath) }
        if (reply.files.isNotEmpty()) afterAttachments()
        listeners.forEach { it.onFinished(conversationId, messageId, text) }
        event.stats?.let { Log.d(LogTag.Chat, "Reply finished: ${it.totalMs} ms, ${it.evalCount} tokens") }
        return EngineState.Idle
    }

    private class EdithReply(val text: String, val files: List<ChatRepository.NewAttachment>, val resends: List<AttachmentEntity>)

    /** Tools' pictures (C25: camera snapshots) kept as Edith's image attachments; one that can't be kept is skipped. */
    private suspend fun EdithReply.withPictures(images: List<com.edith.app.tools.framework.ToolImage>): EdithReply {
        val store = attachmentStore ?: return this
        val session = keepsNothing()
        val pictures = images.mapNotNull { image ->
            try {
                val path = withContext(platformIoDispatcher) { store.writeBytes(image.fileName, image.jpeg, session) }
                ChatRepository.NewAttachment(AttachmentKind.Image, image.fileName, "image/jpeg", image.jpeg.size.toLong(), path)
            } catch (e: IOException) {
                Log.w(LogTag.Chat, "Couldn't keep a tool's picture", e)
                null
            }
        }
        return EdithReply(text, files + pictures, resends)
    }

    /**
     * Edith's `<file>` blocks become stored files, replaced in the text by `[Attached file: name]`; each
     * `<attachment number="N"/>` becomes a resend of the owner's attachment N, or "Attachment N isn't available"
     * (C16 §3). Without a store (tests), the reply is kept as written.
     */
    private suspend fun edithAttachments(conversationId: Long, text: String): EdithReply {
        val store = attachmentStore ?: return EdithReply(text, emptyList(), emptyList())
        val parts = ReplyFormat.parse(text)
        if (parts.none { it !is ReplyFormat.Part.Text }) return EdithReply(text, emptyList(), emptyList())
        val session = keepsNothing()
        val out = StringBuilder()
        val files = mutableListOf<ChatRepository.NewAttachment>()
        val resends = mutableListOf<AttachmentEntity>()
        fun line(s: String) {   // on its own line, after a blank one
            out.setLength(out.trimEnd().length)
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(s)
        }
        for (part in parts) when (part) {
            is ReplyFormat.Part.Text -> out.append(part.text)
            is ReplyFormat.Part.File -> try {
                val path = withContext(platformIoDispatcher) { store.write(part.name, part.content, session) }
                files += ChatRepository.NewAttachment(AttachmentKind.File, part.name, ReplyFormat.mimeType(part.name),
                    part.content.encodeToByteArray().size.toLong(), path)
                line(ReplyFormat.placeholder(part.name))
            } catch (e: IOException) {
                Log.w(LogTag.Chat, "Couldn't keep Edith's file", e)
                line("```\n${part.content}\n```")   // she still sees and shares the content as text
            }
            is ReplyFormat.Part.Resend -> {
                val original = repo.attachmentByNumber(conversationId, part.number)
                if (original != null && resends.none { it.id == original.id }) resends += original
                else if (original == null) line("Attachment ${part.number} isn't available.")
            }
        }
        return EdithReply(out.toString().trim(), files, resends)
    }

    private suspend fun keepsNothing(): Boolean = settings.current().keepRecentAttachments <= 0

    private suspend fun fail(conversationId: Long, error: AppError, model: ModelInfo?): EngineState {
        withContext(NonCancellable) { repo.addFailedAssistantMessage(conversationId, model?.name, error) }
        listeners.forEach { it.onFailed(conversationId) }
        return EngineState.Failed(conversationId, error)
    }

    companion object {
        /** Why [model] can't take these attachments (images without "vision", audio without "audio"), or null (C15 §3). */
        fun cantUse(attachments: List<AttachmentEntity>, model: ModelInfo): AppError? = when {
            attachments.any { it.kind == AttachmentKind.Image } && !model.canSee ->
                AppError.ModelLacksCapability(model.name, "see images", badge = "Sees images")
            attachments.any { it.kind == AttachmentKind.Audio } && !model.canHear ->
                AppError.ModelLacksCapability(model.name, "hear audio", badge = "Hears audio")
            else -> null
        }

        /** Messages read for context; the token budget (ContextBuilder) usually keeps fewer. */
        const val HISTORY_LIMIT = 200
        /** ~30 UI updates a second at most (C06 §4); the listeners still get every delta. */
        const val MIN_EMIT_INTERVAL_NS = 33_000_000L
    }
}

private val engineStart = TimeSource.Monotonic.markNow()

/** A clock that only goes forward, in nanoseconds: how long the engine has run. */
private val monotonicNanos: () -> Long = { engineStart.elapsedNow().inWholeNanoseconds }
