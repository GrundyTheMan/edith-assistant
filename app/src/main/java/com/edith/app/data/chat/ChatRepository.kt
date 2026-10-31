package com.edith.app.data.chat

import android.util.Log
import com.edith.app.core.error.AppError
import com.edith.app.core.log.LogTag
import com.edith.app.core.time.Clock
import com.edith.app.data.db.*
import com.edith.app.core.dispatchers.AppDispatchers
import kotlinx.coroutines.flow.Flow

/**
 * Repository for chat messages and attachments.
 * Enforces lesson L3: no system/tool roles stored, strip tool-call text.
 */
class ChatRepository(
    private val db: EdithDatabase,
    private val clock: Clock,
    private val dispatchers: AppDispatchers
) {
    companion object {
        const val CONVERSATION_ID = 1L
    }

    /** Current conversation ID (fixed for v1). */
    val currentConversationId get() = ChatRepository.CONVERSATION_ID

    /** All messages with attachments, ordered by time. */
    fun observe(): Flow<List<MessageWithAttachments>> {
        return db.messageDao().observeConversation(ChatRepository.CONVERSATION_ID)
    }

    /** Recent messages (oldest first) for display on chat screen. */
    suspend fun recent(limit: Int = 50): List<MessageWithAttachments> {
        val newestFirst = db.messageDao().recent(
            ChatRepository.CONVERSATION_ID, limit
        )
        return newestFirst.reversed()
    }

    /** Insert a user message. */
    suspend fun addUserMessage(text: String, source: MessageSource): Long {
        val createdAt = clock.now().toEpochMilli()
        val entity = MessageEntity(
            conversationId = ChatRepository.CONVERSATION_ID,
            role = MessageRole.User,
            content = text,
            createdAt = createdAt,
            status = MessageStatus.Complete,
            source = source
        )
        return db.messageDao().insert(entity)
    }

    /** Insert an assistant message. Guards against storing tool-call text (lesson L3). */
    suspend fun addAssistantMessage(text: String, model: String?, toolsUsed: List<String> = emptyList()): Long {
        // Strip tool-call text and log warning (lesson L3) - check for Ollama's \x1b marker pattern
        val cleaned = text.replace(Regex("(?s)\\u001B\\.\\?[a-zA-Z]+\\{[^}]*\\}\\u001E"), "").trim()
        
        if (cleaned != text) {
            Log.w("Chat", "Stripped tool-call text from an assistant message")
            throw IllegalArgumentException("Tool-call text detected and stripped: $text")
        }

        val createdAt = clock.now().toEpochMilli()
        val entity = MessageEntity(
            conversationId = ChatRepository.CONVERSATION_ID,
            role = MessageRole.Assistant,
            content = cleaned,
            createdAt = createdAt,
            status = MessageStatus.Complete,
            source = MessageSource.Typed, // Assistant messages come from typing/speaking
            model = model,
            toolsUsed = toolsUsed
        )
        return db.messageDao().insert(entity)
    }

    /** Insert a failed assistant message with error details. */
    suspend fun addFailedAssistantMessage(model: String?, error: AppError): Long {
        val createdAt = clock.now().toEpochMilli()
        val entity = MessageEntity(
            conversationId = ChatRepository.CONVERSATION_ID,
            role = MessageRole.Assistant,
            content = "", // Empty content for failed messages
            createdAt = createdAt,
            status = MessageStatus.Failed,
            source = MessageSource.Typed,
            model = model,
            errorMessage = error.userMessage
        )
        return db.messageDao().insert(entity)
    }

    /** Delete a single message (and cascading attachments). */
    suspend fun deleteMessage(id: Long) {
        db.messageDao().delete(id)
    }

    /** Clear all messages in the conversation. */
    suspend fun clearConversation() {
        db.messageDao().clearConversation(ChatRepository.CONVERSATION_ID)
    }
}
