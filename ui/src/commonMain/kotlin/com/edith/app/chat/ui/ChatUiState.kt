package com.edith.app.chat.ui

import androidx.compose.runtime.Immutable
import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.data.db.AttachmentKind
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageStatus

/** Everything the chat screen shows (C07 §1). The draft lives in [ChatViewModel.draft] so typing stays instant. */
@Immutable
data class ChatUiState(
    /** The open conversation; null only until startup has picked one. */
    val conversationId: Long? = null,
    val title: String = NEW_CHAT_TITLE,
    val messages: List<MessageUi> = emptyList(),
    /** Edith's reply while it's being written, shown as a trailing bubble. Only for this conversation. */
    val inFlight: InFlight? = null,
    /** The owner's message from the moment Send is tapped until it's stored, so it never "jumps in" late. */
    val pendingUserText: String? = null,
    /** A reply is running (in any conversation): one turn at a time, so Send is off. */
    val busy: Boolean = false,
    /** Why Send is off when the reply is running in a different chat. */
    val sendHint: String? = null,
    /** An error the failed bubble can't fix on its own (the local-network permission). */
    val error: ChatError? = null,
    val conversations: List<ConversationItem> = emptyList(),
    val features: ChatFeatures = ChatFeatures(),
    val speakingMessageId: Long? = null,
    /** False until the open conversation's messages have loaded (so the empty-chat hint doesn't flash). */
    val loaded: Boolean = false,
    /** A voice memo is being recorded (C12): the input row shows the recording controls. */
    val recording: RecordingUi? = null,
    /** A voice memo is starting, recording or being transcribed: Send and Record are off meanwhile. */
    val voiceBusy: Boolean = false,
    /** Dictation is listening (C12): the mic in the text field is highlighted. */
    val dictating: Boolean = false,
    /** Hands-free mode (C14); null when it isn't available. */
    val handsFree: HandsFreeUi? = null,
    /** Attachments in the input row, waiting to be sent (C15). */
    val pending: List<PendingUi> = emptyList(),
    /** The audio attachment playing in a bubble, by attachment id. */
    val playingAttachmentId: Long? = null,
) {
    /** Text or attachments, and nothing still being prepared. */
    fun canSend(draft: String): Boolean = conversationId != null && !busy && !voiceBusy &&
        (draft.isNotBlank() || pending.isNotEmpty()) && pending.all { it.ready }
    /** Hands-free holds the microphone while it's on, so Record and dictation wait. */
    val micFree: Boolean get() = handsFree?.on != true
    val canRecord: Boolean get() = conversationId != null && !busy && !voiceBusy && micFree
    /** Hands-free can't start while a memo or dictation has the microphone. */
    val canStartHandsFree: Boolean get() = conversationId != null && !voiceBusy && !dictating

    companion object {
        const val NEW_CHAT_TITLE = "New chat"
    }
}

@Immutable
data class MessageUi(
    val id: Long,
    val role: MessageRole,
    val text: String,
    val time: String,
    val status: MessageStatus,
    val errorMessage: String?,
    val toolsUsed: List<String>,
    val attachments: List<AttachmentUi>,
    /** The newest message in the conversation (Retry only makes sense there). */
    val isLatest: Boolean,
    /** The newest message, and it's Edith's: Regenerate is offered here only. */
    val isLatestAssistant: Boolean,
    /** Said hands-free (C14): an ear icon by its time. */
    val handsFree: Boolean = false,
)

/** Hands-free on the chat screen (C14 §3): the top-bar button and, while on, what it's doing. */
@Immutable
data class HandsFreeUi(val on: Boolean, val status: String? = null)

@Immutable
data class AttachmentUi(
    val number: Int,
    val fileName: String,
    val kind: AttachmentKind,
    val id: Long = 0,
    /** Where the file is on the device (shown through Coil as a file:// address); null when it's no longer available (removed by retention, C16). */
    val path: String? = null,
    val durationMs: Long? = null,
    /** In Edith's reply: a file she wrote, or (with [isResend]) one of the owner's sent back (C16 §3). */
    val fromEdith: Boolean = false,
    val isResend: Boolean = false,
    val mimeType: String = "",
    /** The first lines of Edith's file, for its card. */
    val preview: String? = null,
)

/** A chip above the input row (C15 §1): a picked file being prepared, or ready to send. */
@Immutable
data class PendingUi(val key: Long, val name: String, val kind: AttachmentKind, val path: String?, val ready: Boolean)

/** A reply being written. [canStop] is false for the moment between "done" and the stored reply appearing. */
@Immutable
data class InFlight(
    val label: String,
    val partialText: String,
    val canStop: Boolean = true,
    /** Set while a tool waits for the owner's OK (C08 §6): shown as a card with Confirm / Cancel. */
    val confirmation: ConfirmationRequest? = null,
)

/** A memo being recorded; the elapsed time is counted on screen from [startedAtMs]. */
@Immutable
data class RecordingUi(val startedAtMs: Long)

/** [opensAppSettings]: the action is Open settings (the microphone was refused, C12 §1). */
@Immutable
data class ChatError(val message: String, val needsLocalNetworkPermission: Boolean, val opensAppSettings: Boolean = false)

@Immutable
data class ConversationItem(
    val id: Long,
    val title: String,
    val preview: String?,
    val time: String,
    val isCurrent: Boolean,
    /** A reply is being written in this conversation right now. */
    val isReplying: Boolean,
)

/** Input-row buttons later chunks switch on (C07 §6): attach (C15), record and dictation (C12). */
@Immutable
data class ChatFeatures(val attach: Boolean = false, val record: Boolean = false, val dictation: Boolean = false)
