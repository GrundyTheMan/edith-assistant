package com.edith.app.data.chat

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.time.Clock
import com.edith.app.attachments.AttachmentStore
import com.edith.app.data.db.AttachmentDirection
import com.edith.app.data.db.AttachmentEntity
import com.edith.app.data.db.AttachmentKind
import com.edith.app.data.db.ConversationEntity
import com.edith.app.data.db.ConversationRow
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.MessageEntity
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.MessageStatus
import com.edith.app.data.db.MessageWithAttachments
import com.edith.app.data.db.TitleSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The chat history (C05 §4, C06b), and the rules for what may be stored (lesson L3): only user and assistant
 * messages exist (no API for system/tool roles), and assistant text never keeps text-form tool calls — stored
 * history that contains them teaches the model to write tool calls as text instead of making real ones.
 * Every message belongs to a conversation (decision D6); callers always say which.
 */
class ChatRepository(
    private val db: EdithDatabase,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    /** Deletes attachment files when their messages go (C16 §2); null in tests that store no files. */
    private val files: AttachmentStore? = null,
) {
    // --- Conversations ---------------------------------------------------------------------------------------

    /** Every conversation, most recently active first. */
    fun conversations(): Flow<List<ConversationRow>> = db.conversationDao().observeAll()

    fun observeConversation(id: Long): Flow<ConversationEntity?> = db.conversationDao().observe(id)

    suspend fun conversation(id: Long): ConversationEntity? = io { db.conversationDao().get(id) }

    suspend fun mostRecentConversation(): ConversationEntity? = io { db.conversationDao().mostRecent() }

    /** A new, empty, untitled conversation. */
    suspend fun createConversation(): Long = io { db.conversationDao().insertNew(now()) }

    /** The owner's name for a conversation; never overwritten by automatic titles (D12). Blank is ignored. */
    suspend fun rename(id: Long, title: String) {
        val clean = title.trim().replace(whitespace, " ")
        if (clean.isNotEmpty()) io { db.conversationDao().setTitle(id, clean, TitleSource.Owner) }
    }

    /** A title Edith wrote (D12). Replaces the provisional first-message title, never the owner's. */
    suspend fun setEdithTitle(id: Long, title: String): Boolean =
        io { db.conversationDao().setTitle(id, title, TitleSource.Edith) } > 0

    /** Deletes the conversation with its messages and attachment rows. Stop its turn first (ChatEngine.cancelAndJoin). */
    /** Deletes the conversation; its attachment files go first (the rows cascade, the files wouldn't; C16 §2). */
    suspend fun deleteConversation(id: Long) = io {
        deleteFiles(db.attachmentDao().storedPathsInConversation(id))
        db.conversationDao().delete(id)
    }

    suspend fun isEmpty(id: Long): Boolean = io { db.conversationDao().isEmpty(id) }

    suspend fun deleteEmptyConversationsExcept(keepId: Long): Int = io { db.conversationDao().deleteEmptyExcept(keepId) }

    // --- Messages --------------------------------------------------------------------------------------------

    /** Every message of a conversation, oldest first, with its attachments. Room runs the query off the main thread. */
    fun observe(conversationId: Long): Flow<List<MessageWithAttachments>> =
        db.messageDao().observeConversation(conversationId)

    /** The newest [limit] messages, oldest first. */
    suspend fun recent(conversationId: Long, limit: Int): List<MessageWithAttachments> =
        io { db.messageDao().recent(conversationId, limit).reversed() }

    /** The newest message, or null if the conversation is empty. */
    suspend fun latest(conversationId: Long): MessageWithAttachments? = recent(conversationId, 1).lastOrNull()

    /** Stores the owner's message. The first one also gives an untitled conversation a provisional title (D12). */
    suspend fun addUserMessage(conversationId: Long, text: String, source: MessageSource): Long? =
        addUserMessage(conversationId, text, source, emptyList())?.messageId

    /** The ids of a stored message and its attachment rows, in the order given. */
    data class StoredMessage(val messageId: Long, val attachmentIds: List<Long>)

    /**
     * Stores the owner's message and its attachment rows in one transaction (C15 §3), numbered per conversation
     * (`[Attachment 3: …]`). The files themselves go to the attachment store; null if the conversation is gone.
     */
    suspend fun addUserMessage(
        conversationId: Long, text: String, source: MessageSource, attachments: List<NewAttachment>,
    ): StoredMessage? {
        val createdAt = now()
        val ids = mutableListOf<Long>()
        val messageId = insert(MessageEntity(conversationId = conversationId, role = MessageRole.User, content = text,
            createdAt = createdAt, status = MessageStatus.Complete, source = source)) { messageId ->
            val title = firstMessageTitle(text) ?: attachments.firstOrNull()?.fileName?.let { firstMessageTitle(it) }
            title?.let { db.conversationDao().setTitle(conversationId, it, TitleSource.FirstMessage) }
            var number = db.attachmentDao().nextNumber(conversationId)
            for (a in attachments) {
                ids += db.attachmentDao().insert(AttachmentEntity(
                    messageId = messageId, number = number++, direction = AttachmentDirection.FromUser, kind = a.kind,
                    fileName = a.fileName, mimeType = a.mimeType, sizeBytes = a.sizeBytes, storedPath = a.storedPath,
                    createdAt = createdAt,
                ))
            }
        } ?: return null
        return StoredMessage(messageId, ids)
    }

    /** What the repository needs to number and describe an attachment; [storedPath] is where the store put its file. */
    data class NewAttachment(
        val kind: AttachmentKind, val fileName: String, val mimeType: String, val sizeBytes: Long, val storedPath: String? = null,
    )

    /** Stores Edith's reply with any `<tool_call>…</tool_call>` text removed (and a warning logged). */
    suspend fun addAssistantMessage(
        conversationId: Long, text: String, model: String, toolsUsed: List<String>,
        /** Files Edith wrote (C16 §3), already in the store. */
        files: List<NewAttachment> = emptyList(),
        /** The owner's attachments she sent back: rows pointing at these originals. */
        resends: List<AttachmentEntity> = emptyList(),
    ): Long? {
        val cleaned = stripToolCalls(text)
        if (cleaned != text) Log.w(LogTag.Chat, "Stripped tool-call text from an assistant message")
        val createdAt = now()
        return insert(MessageEntity(conversationId = conversationId, role = MessageRole.Assistant, content = cleaned,
            createdAt = createdAt, status = MessageStatus.Complete, source = MessageSource.Typed, model = model,
            toolsUsed = toolsUsed)) { messageId ->
            val dao = db.attachmentDao()
            var number = if (files.isEmpty()) 0 else dao.nextNumber(conversationId)
            for (f in files) {
                dao.insert(AttachmentEntity(
                    messageId = messageId, number = number++, direction = AttachmentDirection.FromEdith, kind = f.kind,
                    fileName = f.fileName, mimeType = f.mimeType, sizeBytes = f.sizeBytes, storedPath = f.storedPath,
                    createdAt = createdAt,
                ))
            }
            for (o in resends) {
                // No copy: the row points at the original and keeps its number (C16 §3).
                dao.insert(AttachmentEntity(
                    messageId = messageId, number = o.number, direction = AttachmentDirection.FromEdith, kind = o.kind,
                    fileName = o.fileName, mimeType = o.mimeType, sizeBytes = o.sizeBytes, storedPath = null,
                    resendOf = o.id, createdAt = createdAt,
                ))
            }
        }
    }

    /** The attachment numbered [number] in a conversation (for a resend), the original rather than a resend of it. */
    suspend fun attachmentByNumber(conversationId: Long, number: Int): AttachmentEntity? = io {
        db.attachmentDao().byNumber(conversationId, number)?.let { a -> a.resendOf?.let { db.attachmentDao().byId(it) } ?: a }
    }

    suspend fun attachment(id: Long): AttachmentEntity? = io { db.attachmentDao().byId(id) }

    /** A reply that failed: empty content, the error's message kept for the UI. */
    suspend fun addFailedAssistantMessage(conversationId: Long, model: String?, error: AppError): Long? =
        insert(MessageEntity(conversationId = conversationId, role = MessageRole.Assistant, content = "",
            createdAt = now(), status = MessageStatus.Failed, source = MessageSource.Typed, model = model,
            errorMessage = error.userMessage))

    /** A reply the owner stopped part-way (C06): shown in the chat, but never sent back to the model as context. */
    suspend fun addCancelledAssistantMessage(conversationId: Long, partialText: String, model: String?): Long? =
        insert(MessageEntity(conversationId = conversationId, role = MessageRole.Assistant,
            content = stripToolCalls(partialText), createdAt = now(), status = MessageStatus.Cancelled,
            source = MessageSource.Typed, model = model))

    /** Deletes the message; its attachment rows go with it (foreign key cascade). */
    suspend fun deleteMessage(id: Long) = io {
        deleteFiles(db.attachmentDao().storedPathsOfMessage(id))   // e.g. Edith's file in a regenerated reply
        db.messageDao().delete(id)
    }

    suspend fun clearConversation(conversationId: Long) = io {
        deleteFiles(db.attachmentDao().storedPathsInConversation(conversationId))
        db.messageDao().clearConversation(conversationId)
    }

    private fun deleteFiles(paths: List<String>) {
        paths.forEach { files?.delete(it) }
    }

    /**
     * Inserts the message and bumps the conversation's `updated_at`, in one transaction. Returns null (and logs)
     * if the conversation no longer exists — it was deleted while a reply was running — instead of crashing.
     */
    private suspend fun insert(message: MessageEntity, alsoInTransaction: suspend (messageId: Long) -> Unit = {}): Long? = io {
        try {
            db.withTransaction {
                val id = db.messageDao().insert(message)
                db.conversationDao().touch(message.conversationId, message.createdAt)
                alsoInTransaction(id)
                id
            }
        } catch (e: SQLiteConstraintException) {
            Log.w(LogTag.Chat, "Message not stored: conversation ${message.conversationId} no longer exists", e)
            null
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(dispatchers.io) { block() }

    private fun now() = clock.now().toEpochMilli()

    companion object {
        private val toolCallText = Regex("""(?s)\s*<tool_call>.*?</tool_call>\s*""")
        private val whitespace = Regex("""\s+""")

        /** Provisional titles (D12) are at most this long. */
        const val TITLE_MAX_CHARS = 40

        /** Removes text-form tool calls (lesson L3), keeping one space where they were between words. */
        fun stripToolCalls(text: String): String {
            if (!toolCallText.containsMatchIn(text)) return text   // ordinary replies are stored exactly as written
            return text.replace(toolCallText, " ").replace(Regex(" {2,}"), " ").trim()
        }

        /** The first message as a title: one line, at most [TITLE_MAX_CHARS], cut at a word with "…". Null if blank. */
        fun firstMessageTitle(text: String): String? {
            val line = text.trim().replace(whitespace, " ")
            if (line.isEmpty()) return null
            if (line.length <= TITLE_MAX_CHARS) return line
            val cut = line.take(TITLE_MAX_CHARS - 1)
            val atWord = cut.substringBeforeLast(' ', cut).takeIf { it.length >= TITLE_MAX_CHARS / 2 } ?: cut
            return atWord.trimEnd(' ', ',', '.', ';', ':', '-') + "…"
        }
    }
}
