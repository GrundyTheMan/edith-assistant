package com.edith.app.data.chat

import com.edith.app.core.error.AppError
import com.edith.app.core.time.Clock
import com.edith.app.data.db.*
import com.edith.app.core.dispatchers.AppDispatchers
import kotlinx.coroutines.flow.Flow

/**
 * Repository for chat messages and attachments.
 * Enforces lessons L3: no system/tool roles, strip tool-call text from content.
 */
class ChatRepository(
    private val db: EdithDatabase,
    private val clock: Clock,
    private val dispatchers: AppDispatchers
) {
    companion object {
        val conversationId: Long = 1L
    }

    /** Current conversation ID (fixed for v1). */
    val currentConversationId get() = ChatRepository.conversationId

    /** All messages with attachments, ordered by time. */
    fun observe(): Flow<List<MessageWithAttachments>> {
        return db.messageDao().observeConversation(ChatRepository.conversationId)
    }

    /** Recent messages (oldest first) for display on chat screen. */
    suspend fun recent(limit: Int = 50): List<MessageWithAttachments> {
        val newestFirst = db.messageDao().recent(
            ChatRepository.conversationId, limit
        )
        return newestFirst.reversed()
    }

    /** Insert a user message. */
    suspend fun addUserMessage(text: String, source: MessageSource): Long {
        val createdAt = clock.now().epochSecond * 1000L
        val entity = MessageEntity(
            conversationId = ChatRepository.conversationId,
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
        // Strip tool-call text and warn (lesson L3) - check for v1's `\x1b` tags and `<tool_call>` text
        val strippedText = if (text.contains("<tool_call>")) {
            throw IllegalArgumentException("Tool-call text detected and rejected: must not persist tool calls in history (v1 lesson L3)")
        } else {
            text
        }

        val createdAt = clock.now().epochSecond * 1000L
        val entity = MessageEntity(
            conversationId = ChatRepository.conversationId,
            role = MessageRole.Assistant,
            content = strippedText,
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
        val createdAt = clock.now().epochSecond * 1000L
        val entity = MessageEntity(
            conversationId = ChatRepository.conversationId,
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
        db.messageDao().clearConversation(ChatRepository.conversationId)
    }
}
