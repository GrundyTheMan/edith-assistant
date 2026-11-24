package com.edith.app.chat.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edith.app.attachments.file
import com.edith.app.attachments.AttachmentIntake
import com.edith.app.attachments.AttachmentPlayer
import com.edith.app.attachments.AttachmentStore
import com.edith.app.attachments.PendingAttachment
import com.edith.app.attachments.ReplyFormat
import com.edith.app.chat.CurrentConversation
import com.edith.app.chat.engine.ChatEngine
import com.edith.app.chat.engine.EngineState
import com.edith.app.core.error.AppError
import com.edith.app.core.permissions.ToolPermissions
import com.edith.app.core.time.Clock
import com.edith.app.core.time.formatListTime
import com.edith.app.core.time.formatMessageTime
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.AttachmentDirection
import com.edith.app.data.db.AttachmentEntity
import com.edith.app.data.db.AttachmentKind
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.MessageWithAttachments
import com.edith.app.di.viewModelFactory
import com.edith.app.tools.framework.ApprovalChannel
import com.edith.app.voice.capture.LiveSpeech
import com.edith.app.voice.capture.SpeechInput
import com.edith.app.voice.capture.VoiceMemos
import com.edith.app.voice.handsfree.HandsFree
import com.edith.app.voice.speech.SpeechController
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    /** Attachments (C15): preparing picked files, where sent ones are kept, and playing audio ones. */
    private val intake: AttachmentIntake? = null,
    private val attachmentStore: AttachmentStore? = null,
    private val attachmentPlayer: AttachmentPlayer? = null,
    /** Where the chat's attachments are read from disk. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Hands-free mode (C14); null when it isn't available. */
    private val handsFree: HandsFree? = null,
) : ViewModel() {

    val toolPermissionRequest: StateFlow<ToolPermissions.Request?> get() = permissions.pending

    fun onToolPermissionResult(id: String, granted: Boolean) = permissions.resolve(id, granted)

    /** The open conversation's unsent text. Each chat keeps its own (C07 §8), and it survives process death. */
    var draft by mutableStateOf("")
        private set

    /** The open conversation as stored: its id, title and messages. */
    private data class Opened(val id: Long, val title: String, val messages: List<MessageUi>, val latestId: Long?)

    private val opened: StateFlow<Opened?> = current.id.filterNotNull().flatMapLatest { id ->
        combine(repo.observe(id), repo.observeConversation(id), ::Pair).mapLatest { (rows, conversation) ->
            Opened(id, conversation?.title.orEmpty().ifBlank { ChatUiState.NEW_CHAT_TITLE }, rows.toUi(), rows.lastOrNull()?.message?.id)
        }
    }.flowOn(io).stateIn(viewModelScope, SharingStarted.Eagerly, null)   // io: attachments are looked up on disk (C16)

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

    // --- Attachments (C15) -----------------------------------------------------------------------------------

    /** In the input row: each picked file, being prepared ([attachment] null) or ready to send. */
    private data class PendingItem(val key: Long, val name: String, val kind: AttachmentKind, val attachment: PendingAttachment? = null)
    private val pending = MutableStateFlow<List<PendingItem>>(emptyList())
    private var nextPendingKey = 0L

    private data class Voice(
        val memo: VoiceMemos.State, val memoNotice: String?, val dictating: Boolean, val error: ChatError?,
        val pending: List<PendingItem>, val playing: Long?,
    )
    private val voice = combine(
        voiceMemos?.state ?: MutableStateFlow(VoiceMemos.State.Idle),
        voiceMemos?.notice ?: MutableStateFlow(null),
        dictating,
        voiceError,
        combine(pending, attachmentPlayer?.playing ?: MutableStateFlow(null), ::Pair),
    ) { memo, notice, listening, error, (items, playing) -> Voice(memo, notice, listening, error, items, playing) }

    /** The memo's level meter, 0..1 (separate, so the meter doesn't redraw the whole screen). */
    val recordingLevel: StateFlow<Float> = voiceMemos?.level ?: MutableStateFlow(0f)

    // --- Hands-free (C14) ----------------------------------------------------------------------------------------

    /** The button's state and hands-free's notice (why it stopped). */
    private val handsFreeUi: Flow<Pair<HandsFreeUi, String?>?> = if (handsFree == null) MutableStateFlow(null) else {
        combine(handsFree.state, handsFree.notice) { s, notice -> handsFreeUi(s, handsFree.phrase) to notice }
    }

    val state: StateFlow<ChatUiState> = combine(
        opened, engine.state, repo.conversations(), speech.speakingMessageId, combine(transient, voice, handsFreeUi, ::Triple),
    ) { open, engineState, conversations, speaking, (t, v, hf) ->
        val openId = open?.id
        val runningIn = engineState.runningConversationId()
        val inFlight = when {
            runningIn != null && runningIn == openId -> when (engineState) {
                is EngineState.Working -> InFlight(engineState.label, ReplyFormat.streaming(engineState.partialText))   // file blocks as "Writing …" (C16)
                is EngineState.AwaitingConfirmation -> InFlight(CONFIRMING, ReplyFormat.streaming(engineState.partialText), confirmation = engineState.request)
                else -> null
            }
            t.endingReply?.showsIn(open) == true -> InFlight("", ReplyFormat.streaming(t.endingReply.text), canStop = false)
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
                ?: v.memoNotice?.let { ChatError(it, needsLocalNetworkPermission = false) }
                ?: hf?.second?.let { ChatError(it, needsLocalNetworkPermission = false) },
            recording = recording?.let { RecordingUi(it.startedAtMs) },
            dictating = v.dictating,
            handsFree = hf?.first,
            pending = v.pending.map { PendingUi(it.key, it.name, it.kind, it.attachment?.file, ready = it.attachment != null) },
            playingAttachmentId = v.playing,
            conversations = conversations.map {
                ConversationItem(
                    id = it.id,
                    title = it.title.ifBlank { ChatUiState.NEW_CHAT_TITLE },
                    preview = it.lastMessage?.let { text -> markdownToPlainLine(text, PREVIEW_CHARS) },
                    time = formatListTime(Instant.fromEpochMilliseconds(it.updatedAt), clock),
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
        val items = pending.value
        if (items.any { it.attachment == null }) return   // still preparing
        val attachments = items.mapNotNull { it.attachment }
        if ((text.isEmpty() && attachments.isEmpty()) || engine.activeConversationId != null) return   // one turn at a time (C06)
        if (voiceMemos?.state?.value?.let { it != VoiceMemos.State.Idle } == true) return
        speech.stop()   // a new message interrupts Edith speaking (C13)
        attachmentPlayer?.stop()
        if (dictating.value) dictation?.cancel().also { dictating.value = false }
        if (text.isNotEmpty()) {
            bridgeBriefly(Bridge(id, text, opened.value?.takeIf { it.id == id }?.latestId)) { t, b -> t.copy(pendingUser = b) }
        }
        if (!engine.send(id, text, if (draftDictated) MessageSource.Dictated else MessageSource.Typed, attachments)) return
        pending.update { now -> now.filterNot { it in items } }   // the engine has them now
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
        pending.value.forEach { item -> item.attachment?.let { intake?.discard(it) } }
        attachmentPlayer?.stop()
    }

    // --- Attachments (C15) -----------------------------------------------------------------------------------

    /** Photos from the picker: each is prepared (scaled, upright JPEG) and appears in the input row. */
    fun attachImages(uris: List<Uri>) = uris.forEach { attach(it, AttachmentKind.Image) }

    /** An audio file: kept as WAV/MP3, or converted to WAV. */
    fun attachAudio(uri: Uri) = attach(uri, AttachmentKind.Audio)

    private fun attach(uri: Uri, kind: AttachmentKind) {
        val intake = intake ?: return
        if (pending.value.size >= MAX_ATTACHMENTS) {
            voiceError.value = ChatError("You can attach up to $MAX_ATTACHMENTS files to one message.", needsLocalNetworkPermission = false)
            return
        }
        val key = nextPendingKey++
        pending.update { it + PendingItem(key, if (kind == AttachmentKind.Image) "Photo" else "Audio", kind) }
        viewModelScope.launch {
            val result = if (kind == AttachmentKind.Image) intake.image(uri) else intake.audio(uri)
            when (result) {
                is AttachmentIntake.Result.Ready -> {
                    var kept = false
                    pending.update { list ->
                        list.map { if (it.key == key) it.copy(name = result.attachment.fileName, attachment = result.attachment).also { kept = true } else it }
                    }
                    if (!kept) intake.discard(result.attachment)   // removed while it was being prepared
                }
                is AttachmentIntake.Result.Refused -> {
                    pending.update { list -> list.filterNot { it.key == key } }
                    voiceError.value = ChatError(result.message, needsLocalNetworkPermission = false)
                }
            }
        }
    }

    /** ✕ on a chip in the input row. */
    fun removeAttachment(key: Long) {
        val item = pending.value.firstOrNull { it.key == key } ?: return
        pending.update { list -> list.filterNot { it.key == key } }
        item.attachment?.let { intake?.discard(it) }
    }

    /** ▶/■ on an audio attachment in a bubble. */
    fun toggleAttachmentPlayback(attachment: AttachmentUi) {
        val player = attachmentPlayer ?: return
        if (player.playing.value == attachment.id) player.stop()
        else attachment.file?.let { player.play(attachment.id, it) }
    }

    /** Stops the reply being written; what Edith had written so far is kept (C06). */
    fun stopReply() = engine.cancel()

    /** The owner's tap on a tool's confirmation card (C08 §6, D11). */
    fun answerConfirmation(approved: Boolean) {
        val waiting = engine.state.value as? EngineState.AwaitingConfirmation ?: return
        engine.resolveConfirmation(waiting.request.id, approved, ApprovalChannel.Tap)   // the card's buttons: a tap
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

    /** Turns hands-free off. (The screen turns it on: it must start the service, after the microphone permission.) */
    fun stopHandsFree() {
        handsFree?.stop()
    }

    /** The service couldn't start, or the microphone was refused. */
    fun onHandsFreeNotStarted(message: String, opensAppSettings: Boolean = false) {
        voiceError.value = ChatError(message, needsLocalNetworkPermission = false, opensAppSettings = opensAppSettings)
    }

    fun dismissError() {
        voiceError.value = null
        voiceMemos?.dismissNotice()
        handsFree?.dismissNotice()
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

    /** One attachment as a bubble shows it. A resend shows its original (C16 §4); Edith's files get a preview. */
    private suspend fun attachmentUi(a: AttachmentEntity): AttachmentUi {
        val original = a.resendOf?.let { repo.attachment(it) }
        val shown = original ?: a
        val file = attachmentStore?.file(shown.storedPath)
        return AttachmentUi(
            number = a.number, fileName = shown.fileName, kind = shown.kind, id = shown.id, file = file,
            durationMs = if (shown.kind == AttachmentKind.Audio) attachmentStore?.durationMs(shown.storedPath) else null,
            fromEdith = a.direction == AttachmentDirection.FromEdith, isResend = a.resendOf != null,
            mimeType = shown.mimeType,
            preview = if (shown.kind == AttachmentKind.File && file != null) previewOf(file) else null,
        )
    }

    private suspend fun List<MessageWithAttachments>.toUi(): List<MessageUi> {
        val newest = lastOrNull()?.message
        return map { row ->
            val m = row.message
            MessageUi(
                id = m.id,
                role = m.role,
                text = m.content,
                time = formatMessageTime(Instant.fromEpochMilliseconds(m.createdAt), clock),
                status = m.status,
                errorMessage = m.errorMessage,
                toolsUsed = m.toolsUsed.map(toolLabel),
                attachments = row.attachments.sortedBy { it.number }.map { attachmentUi(it) },
                isLatest = m.id == newest?.id,
                isLatestAssistant = m.id == newest?.id && m.role == MessageRole.Assistant,
                handsFree = m.source == MessageSource.HandsFree,
            )
        }
    }

    companion object {
        const val OTHER_CHAT_HINT = "Edith is replying in another chat"
        const val LOCAL_NETWORK_MESSAGE = "Edith needs permission to reach your PC on the local network."
        private const val CONFIRMING = "Waiting for your OK…"
        const val TRANSCRIBING = "Transcribing…"
        /** Files on one message (the photo picker allows 4 at a time). */
        const val MAX_ATTACHMENTS = 6
        /** Lines of Edith's file shown on its card (C16 §4). */
        const val PREVIEW_LINES = 3

        /** The first [PREVIEW_LINES] lines of a file, for its card. */
        fun previewOf(file: File): String? = runCatching {
            file.bufferedReader().useLines { lines -> lines.take(PREVIEW_LINES).joinToString("\n") { it.take(120) } }
        }.getOrNull()

        private const val PREVIEW_CHARS = 80
        private const val BRIDGE_MAX_MS = 2_000L

        private fun draftKey(conversationId: Long) = "draft_$conversationId"

        /** What the hands-free line under the top bar says (C14 §3). */
        fun handsFreeUi(state: HandsFree.State, phrase: String): HandsFreeUi = when (state) {
            HandsFree.State.Off -> HandsFreeUi(on = false)
            HandsFree.State.Paused -> HandsFreeUi(on = true, status = "Paused")
            HandsFree.State.Listening -> HandsFreeUi(on = true, status = "Listening for “$phrase”")
            is HandsFree.State.Capturing -> HandsFreeUi(on = true, status = state.partial.ifBlank { "Listening…" })
            HandsFree.State.Thinking -> HandsFreeUi(on = true, status = "Thinking…")
            HandsFree.State.Speaking -> HandsFreeUi(on = true, status = "Speaking…")
        }

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
                handsFree = container.handsFree.takeIf { container.chatFeatures.dictation },   // it needs the recognizer too
                intake = container.attachmentIntake,
                attachmentStore = container.attachmentStore,
                attachmentPlayer = container.attachmentPlayer,
            )
        }
    }
}
