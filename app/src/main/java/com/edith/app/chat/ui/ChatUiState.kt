package com.edith.app.chat.ui

import androidx.compose.runtime.Immutable
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
) {
    fun canSend(draft: String): Boolean = conversationId != null && !busy && draft.isNotBlank()

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
)

@Immutable
data class AttachmentUi(val number: Int, val fileName: String, val kind: AttachmentKind)

/** A reply being written. [canStop] is false for the moment between "done" and the stored reply appearing. */
@Immutable
data class InFlight(val label: String, val partialText: String, val canStop: Boolean = true)

@Immutable
data class ChatError(val message: String, val needsLocalNetworkPermission: Boolean)

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
