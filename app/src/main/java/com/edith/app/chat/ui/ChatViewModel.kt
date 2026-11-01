package com.edith.app.chat.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edith.app.chat.CurrentConversation
import com.edith.app.chat.engine.ChatEngine
import com.edith.app.chat.engine.EngineState
import com.edith.app.core.error.AppError
import com.edith.app.core.time.Clock
import com.edith.app.core.time.formatListTime
import com.edith.app.core.time.formatMessageTime
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.MessageWithAttachments
import com.edith.app.core.permissions.ToolPermissions
import com.edith.app.voice.capture.LiveSpeech
import com.edith.app.voice.capture.SpeechInput
import com.edith.app.voice.capture.VoiceMemos
import com.edith.app.di.viewModelFactory
import com.edith.app.voice.speech.SpeechController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The chat screen's state and actions (C07 §1, C06b). Follows the open conversation ([CurrentConversation]) and
 * combines its messages with the engine's state. Turns run in the app-wide engine, never here, so a reply keeps
 * going through rotation or leaving the screen (lessons L10); this only observes and forwards taps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val repo: ChatRepository,
    private val engine: ChatEngine,
    private val current: CurrentConversation,
    private val speech: SpeechController,
    private val features: ChatFeatures,
    private val clock: Clock,
    private val savedState: SavedStateHandle,
    /** Names in the "Used: …" line: "get_current_time" → "time" (C08). */
    private val toolLabel: (String) -> String = { it },
    /** Android permissions a tool is waiting for (C11): the screen shows the system dialog. */
    private val permissions: ToolPermissions = ToolPermissions { true },
    /** Voice memos (C12); null when recording isn't available. */
    private val voiceMemos: VoiceMemos? = null,
    /** Dictation into the text field (C12); null when the phone has no speech recognizer. */
    private val dictation: SpeechInput? = null,
) : ViewModel() {

    val toolPermissionRequest: StateFlow<ToolPermissions.Request?> get() = permissions.pending

    fun onToolPermissionResult(id: String, granted: Boolean) = permissions.resolve(id, granted)

    /** The open conversation's unsent text. Each chat keeps its own (C07 §8), and it survives process death. */
    var draft by mutableStateOf("")
        private set

    /** The open conversation as stored: its id, title and messages. */
    private data class Opened(val id: Long, val title: String, val messages: List<MessageUi>, val latestId: Long?)

    private val opened: StateFlow<Opened?> = current.id.filterNotNull().flatMapLatest { id ->
        combine(repo.observe(id), repo.observeConversation(id)) { rows, conversation ->
            Opened(id, conversation?.title.orEmpty().ifBlank { ChatUiState.NEW_CHAT_TITLE }, rows.toUi(), rows.lastOrNull()?.message?.id)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Bridges the moments when the engine and the database disagree, so nothing flickers: the owner's message
     * from Send until its row is stored, and Edith's finished text from "done" until her stored reply appears.
     * Each is shown only while the conversation's newest stored message is still the one from before.
     */
    private data class Bridge(val conversationId: Long, val text: String, val afterMessageId: Long?)
    private data class Transient(
        val pendingUser: Bridge? = null,
        val endingReply: Bridge? = null,
        val dismissedFailure: EngineState.Failed? = null,
    )
    private val transient = MutableStateFlow(Transient())

    // --- Voice (C12) ---------------------------------------------------------------------------------------------

    private val dictating = MutableStateFlow(false)
    /** A message from dictation or the microphone permission (the memo's own messages come from [VoiceMemos]). */
    private val voiceError = MutableStateFlow<ChatError?>(null)
    /** The draft came (at least partly) from dictation, so it's sent with source "dictated". */
    private var draftDictated = false
    private var dictationBase = ""

    private data class Voice(val memo: VoiceMemos.State, val memoNotice: String?, val dictating: Boolean, val error: ChatError?)
    private val voice = combine(
        voiceMemos?.state ?: MutableStateFlow(VoiceMemos.State.Idle),
        voiceMemos?.notice ?: MutableStateFlow(null),
        dictating,
        voiceError,
    ) { memo, notice, listening, error -> Voice(memo, notice, listening, error) }

    /** The memo's level meter, 0..1 (separate, so the meter doesn't redraw the whole screen). */
    val recordingLevel: StateFlow<Float> = voiceMemos?.level ?: MutableStateFlow(0f)

    val state: StateFlow<ChatUiState> = combine(
        opened, engine.state, repo.conversations(), speech.speakingMessageId, combine(transient, voice, ::Pair),
    ) { open, engineState, conversations, speaking, (t, v) ->
        val openId = open?.id
        val runningIn = engineState.runningConversationId()
        val inFlight = when {
            runningIn != null && runningIn == openId -> when (engineState) {
                is EngineState.Working -> InFlight(engineState.label, engineState.partialText)
                is EngineState.AwaitingConfirmation -> InFlight(CONFIRMING, engineState.partialText, confirmation = engineState.request)
                else -> null
            }
            t.endingReply?.showsIn(open) == true -> InFlight("", t.endingReply.text, canStop = false)
            (v.memo as? VoiceMemos.State.Transcribing)?.conversationId == openId -> InFlight(TRANSCRIBING, "", canStop = false)
            else -> null
        }
        val recording = v.memo as? VoiceMemos.State.Recording
        val failure = (engineState as? EngineState.Failed)
            ?.takeIf { it.conversationId == openId && it.error is AppError.PermissionDenied && it != t.dismissedFailure }
        ChatUiState(
            conversationId = openId,
            title = open?.title ?: ChatUiState.NEW_CHAT_TITLE,
            messages = open?.messages.orEmpty(),
            inFlight = inFlight,
            pendingUserText = t.pendingUser?.takeIf { it.showsIn(open) }?.text,
            busy = runningIn != null,
            voiceBusy = v.memo != VoiceMemos.State.Idle,
            sendHint = if (runningIn != null && runningIn != openId) OTHER_CHAT_HINT else null,
            error = failure?.let { ChatError(LOCAL_NETWORK_MESSAGE, needsLocalNetworkPermission = true) }
                ?: v.error
                ?: v.memoNotice?.let { ChatError(it, needsLocalNetworkPermission = false) },
            recording = recording?.let { RecordingUi(it.startedAtMs) },
            dictating = v.dictating,
            conversations = conversations.map {
                ConversationItem(
                    id = it.id,
                    title = it.title.ifBlank { ChatUiState.NEW_CHAT_TITLE },
                    preview = it.lastMessage?.let { text -> markdownToPlainLine(text, PREVIEW_CHARS) },
                    time = formatListTime(Instant.ofEpochMilli(it.updatedAt), clock),
                    isCurrent = it.id == openId,
                    isReplying = it.id == runningIn,
                )
            },
            features = features,
            speakingMessageId = speaking,
            loaded = open != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(features = features))

    init {
        // Load each conversation's own draft when it's opened.
        viewModelScope.launch {
            current.id.filterNotNull().distinctUntilChanged().collect { draft = savedState[draftKey(it)] ?: "" }
        }
        // Keep Edith's text on screen between the engine finishing and her stored reply arriving.
        viewModelScope.launch {
            var last: EngineState = engine.state.value
            engine.state.collect { now ->
                val was = last
                last = now
                if (was is EngineState.Working && now !is EngineState.Working && was.partialText.isNotBlank()) {
                    val open = opened.value
                    if (open?.id == was.conversationId) {
                        bridgeBriefly(Bridge(was.conversationId, was.partialText.trim(), open.latestId)) { t, b -> t.copy(endingReply = b) }
                    }
                }
            }
        }
    }

    // --- Typing and sending --------------------------------------------------------------------------------------

    fun onDraftChange(text: String) {
        draft = text
        if (text.isBlank()) draftDictated = false
        current.id.value?.let { savedState[draftKey(it)] = text }
    }

    fun send() {
        val id = current.id.value ?: return
        val text = draft.trim()
        if (text.isEmpty() || engine.activeConversationId != null) return   // one turn at a time (C06)
        if (voiceMemos?.state?.value?.let { it != VoiceMemos.State.Idle } == true) return
        speech.stop()   // a new message interrupts Edith speaking (C13)
        if (dictating.value) dictation?.cancel().also { dictating.value = false }
        bridgeBriefly(Bridge(id, text, opened.value?.takeIf { it.id == id }?.latestId)) { t, b -> t.copy(pendingUser = b) }
        engine.send(id, text, if (draftDictated) MessageSource.Dictated else MessageSource.Typed)
        draftDictated = false
        draft = ""
        savedState[draftKey(id)] = ""
    }

    // --- Voice memos and dictation (C12) ----------------------------------------------------------------------

    /** Record: the screen has already got the microphone permission. */
    fun startRecording() {
        val id = current.id.value ?: return
        if (engine.activeConversationId != null) return
        voiceError.value = null
        if (dictating.value) dictation?.cancel().also { dictating.value = false }
        voiceMemos?.start(id)
    }

    fun stopAndSendRecording() = voiceMemos?.stopAndSend()

    fun cancelRecording() = voiceMemos?.cancel()

    /** The mic in the text field: starts listening, or stops (the final text still arrives). */
    fun toggleDictation() {
        val input = dictation ?: return
        if (dictating.value) {
            input.stop()
            return
        }
        speech.stop()
        voiceError.value = null
        voiceMemos?.dismissNotice()
        dictationBase = draft.trimEnd()
        dictating.value = true
        input.start(object : SpeechInput.Listener {
            override fun onPartial(text: String) = fill(text)

            override fun onFinal(text: String) {
                dictating.value = false
                if (text.isBlank()) voiceError.value = ChatError(LiveSpeech.DIDNT_CATCH, needsLocalNetworkPermission = false)
                else fill(text)
            }

            override fun onError(message: String) {
                dictating.value = false
                voiceError.value = ChatError(message, needsLocalNetworkPermission = false, opensAppSettings = message == VoiceMemos.MIC_DENIED)
            }
        })
    }

    private fun fill(spoken: String) {
        draftDictated = true
        onDraftChange(listOf(dictationBase, spoken.trim()).filter { it.isNotEmpty() }.joinToString(" "))
    }

    /** The owner said no to the microphone: say why it's needed, with Open settings. */
    fun onMicrophoneDenied() {
        voiceError.value = ChatError(VoiceMemos.MIC_DENIED, needsLocalNetworkPermission = false, opensAppSettings = true)
    }

    /** The app went to the background: never leave the microphone open (C12 §2). */
    fun onLeftApp() {
        voiceMemos?.cancel(reason = VoiceMemos.LEFT_APP)
        if (dictating.value) {
            dictation?.cancel()
            dictating.value = false
        }
    }

    override fun onCleared() {
        if (dictating.value) dictation?.cancel()
    }

    /** Stops the reply being written; what Edith had written so far is kept (C06). */
    fun stopReply() = engine.cancel()

    /** The owner's answer to a tool's confirmation card (C08 §6, D11). */
    fun answerConfirmation(approved: Boolean) {
        val waiting = engine.state.value as? EngineState.AwaitingConfirmation ?: return
        engine.resolveConfirmation(waiting.request.id, approved)
    }

    /** Regenerate on the newest reply, and Retry on a failed one: both replace the newest reply (C06). */
    fun regenerate() {
        val id = current.id.value ?: return
        speech.stop()
        engine.regenerateLast(id)
    }

    // --- Playback (C13 provides the voice) -----------------------------------------------------------------------

    fun play(message: MessageUi) = speech.play(message.id, message.text)
    fun stopPlayback() = speech.stop()

    // --- Errors --------------------------------------------------------------------------------------------------

    fun dismissError() {
        voiceError.value = null
        voiceMemos?.dismissNotice()
        val failed = engine.state.value as? EngineState.Failed ?: return
        transient.update { it.copy(dismissedFailure = failed) }
    }

    /** The result of the local-network permission prompt; when allowed, the failed turn is tried again. */
    fun onLocalNetworkPermissionResult(granted: Boolean) {
        if (!granted) return
        dismissError()
        regenerate()
    }

    // --- Conversations (C07 §8) -----------------------------------------------------------------------------------

    fun newChat() {
        viewModelScope.launch { current.newChat() }
    }

    fun select(conversationId: Long) {
        viewModelScope.launch { current.select(conversationId) }
    }

    fun rename(conversationId: Long, title: String) {
        viewModelScope.launch { repo.rename(conversationId, title) }
    }

    /** Stops a reply running in it first (C06b), then deletes; the next most recent chat opens if it was open. */
    fun delete(conversationId: Long) {
        viewModelScope.launch { current.delete(conversationId) }
    }

    // --- Helpers -------------------------------------------------------------------------------------------------

    private fun Bridge.showsIn(open: Opened?) = open != null && open.id == conversationId && open.latestId == afterMessageId

    /** Shows [bridge] until the database catches up, or [BRIDGE_MAX_MS] at most (in case nothing is ever stored). */
    private fun bridgeBriefly(bridge: Bridge, set: (Transient, Bridge?) -> Transient) {
        transient.update { set(it, bridge) }
        viewModelScope.launch {
            delay(BRIDGE_MAX_MS)
            transient.update { t -> if (t.pendingUser === bridge || t.endingReply === bridge) set(t, null) else t }
        }
    }

    private fun List<MessageWithAttachments>.toUi(): List<MessageUi> {
        val newest = lastOrNull()?.message
        return map { row ->
            val m = row.message
            MessageUi(
                id = m.id,
                role = m.role,
                text = m.content,
                time = formatMessageTime(Instant.ofEpochMilli(m.createdAt), clock),
                status = m.status,
                errorMessage = m.errorMessage,
                toolsUsed = m.toolsUsed.map(toolLabel),
                attachments = row.attachments.map { AttachmentUi(it.number, it.fileName, it.kind) },
                isLatest = m.id == newest?.id,
                isLatestAssistant = m.id == newest?.id && m.role == MessageRole.Assistant,
            )
        }
    }

    companion object {
        const val OTHER_CHAT_HINT = "Edith is replying in another chat"
        const val LOCAL_NETWORK_MESSAGE = "Edith needs permission to reach your PC on the local network."
        private const val CONFIRMING = "Waiting for your OK…"
        const val TRANSCRIBING = "Transcribing…"
        private const val PREVIEW_CHARS = 80
        private const val BRIDGE_MAX_MS = 2_000L

        private fun draftKey(conversationId: Long) = "draft_$conversationId"

        private fun EngineState.runningConversationId(): Long? = when (this) {
            is EngineState.Working -> conversationId
            is EngineState.AwaitingConfirmation -> conversationId
            else -> null
        }

        val Factory = viewModelFactory { container, savedState ->
            ChatViewModel(
                repo = container.chatRepository,
                engine = container.chatEngine,
                current = container.currentConversation,
                speech = container.speechController,
                features = container.chatFeatures,
                clock = container.clock,
                toolLabel = container.toolRegistry::displayName,
                savedState = savedState,
                permissions = container.toolPermissions,
                voiceMemos = container.voiceMemos.takeIf { container.chatFeatures.record },
                dictation = container.dictation.takeIf { container.chatFeatures.dictation },
            )
        }
    }
}
