package com.edith.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.OnConflictStrategy
import androidx.room.Embedded
import androidx.room.Relation
import kotlinx.coroutines.flow.Flow

/**
 * DAO for message operations.
 */
@Dao
interface MessageDao {
    /**
     * Get all messages for a conversation with attached media, ordered chronologically.
     */
    @Transaction
    @Query("SELECT * FROM messages WHERE conversation_id = :cid ORDER BY created_at, id")
    fun observeConversation(cid: Long): Flow<List<MessageWithAttachments>>

    /**
     * Get recent messages for a conversation.
     */
    @Transaction
    @Query("SELECT * FROM messages WHERE conversation_id = :cid ORDER BY created_at DESC, id DESC LIMIT :limit")
    suspend fun recent(cid: Long, limit: Int): List<MessageWithAttachments>

    /**
     * Insert a message and return its ID.
     */
    @Insert
    suspend fun insert(m: MessageEntity): Long

    /**
     * Delete a single message (and its attachments via CASCADE).
     */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Clear all messages in a conversation.
     */
    @Query("DELETE FROM messages WHERE conversation_id = :cid")
    suspend fun clearConversation(cid: Long)
}

/**
 * Message with its attachments for querying purposes.
 */
data class MessageWithAttachments(@Embedded val message: MessageEntity, @Relation(parentColumn = "id", entityColumn = "message_id") val attachments: List<AttachmentEntity>)
