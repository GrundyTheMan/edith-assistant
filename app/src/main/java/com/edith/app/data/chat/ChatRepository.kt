package com.edith.app.data.chat

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.time.Clock
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.MessageEntity
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.MessageStatus
import com.edith.app.data.db.MessageWithAttachments
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The chat history (C05 §4), and the rules for what may be stored (lesson L3): only user and assistant messages
 * exist (no API for system/tool roles), and assistant text never keeps text-form tool calls — stored history
 * that contains them teaches the model to write tool calls as text instead of making real ones.
 */
class ChatRepository(
    private val db: EdithDatabase,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
) {
    /** v1 has one continuous conversation (decision D6). */
    val conversationId = CONVERSATION_ID

    /** Every message, oldest first, with its attachments. Room runs the query off the main thread. */
    fun observe(): Flow<List<MessageWithAttachments>> = db.messageDao().observeConversation(conversationId)

    /** The newest [limit] messages, oldest first. */
    suspend fun recent(limit: Int): List<MessageWithAttachments> = withContext(dispatchers.io) {
        db.messageDao().recent(conversationId, limit).reversed()
    }

    suspend fun addUserMessage(text: String, source: MessageSource): Long =
        insert(MessageEntity(conversationId = conversationId, role = MessageRole.User, content = text,
            createdAt = now(), status = MessageStatus.Complete, source = source))

    /** Stores Edith's reply with any `<tool_call>…</tool_call>` text removed (and a warning logged). */
    suspend fun addAssistantMessage(text: String, model: String, toolsUsed: List<String>): Long {
        val cleaned = stripToolCalls(text)
        if (cleaned != text) Log.w(LogTag.Chat, "Stripped tool-call text from an assistant message")
        return insert(MessageEntity(conversationId = conversationId, role = MessageRole.Assistant, content = cleaned,
            createdAt = now(), status = MessageStatus.Complete, source = MessageSource.Typed, model = model,
            toolsUsed = toolsUsed))
    }

    /** A reply that failed: empty content, the error's message kept for the UI. */
    suspend fun addFailedAssistantMessage(model: String?, error: AppError): Long =
        insert(MessageEntity(conversationId = conversationId, role = MessageRole.Assistant, content = "",
            createdAt = now(), status = MessageStatus.Failed, source = MessageSource.Typed, model = model,
            errorMessage = error.userMessage))

    /** Deletes the message; its attachment rows go with it (foreign key cascade). */
    suspend fun deleteMessage(id: Long) = withContext(dispatchers.io) { db.messageDao().delete(id) }

    suspend fun clearConversation() = withContext(dispatchers.io) { db.messageDao().clearConversation(conversationId) }

    private suspend fun insert(message: MessageEntity): Long = withContext(dispatchers.io) { db.messageDao().insert(message) }

    private fun now() = clock.now().toEpochMilli()

    companion object {
        const val CONVERSATION_ID = 1L

        private val toolCallText = Regex("""(?s)\s*<tool_call>.*?</tool_call>\s*""")

        /** Removes text-form tool calls (lesson L3), keeping one space where they were between words. */
        fun stripToolCalls(text: String): String {
            if (!toolCallText.containsMatchIn(text)) return text   // ordinary replies are stored exactly as written
            return text.replace(toolCallText, " ").replace(Regex(" {2,}"), " ").trim()
        }
    }
}
