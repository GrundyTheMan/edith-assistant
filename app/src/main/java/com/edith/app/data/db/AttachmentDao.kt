package com.edith.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.OnConflictStrategy

/**
 * DAO for attachment operations.
 */
@Dao
interface AttachmentDao {
    /**
     * Insert an attachment.
     */
    @Insert
    suspend fun insert(a: AttachmentEntity): Long

    /**
     * Get the next attachment number for a conversation (starts at 1).
     */
    @Query("SELECT COALESCE(MAX(number), 0) + 1 FROM attachments a " +
           "JOIN messages m ON a.message_id = m.id " +
           "WHERE m.conversation_id = :cid")
    suspend fun nextNumber(cid: Long): Int

    /**
     * Get an attachment by its number within a conversation.
     */
    @Query("SELECT a.* FROM attachments a JOIN messages m ON a.message_id = m.id " +
           "WHERE m.conversation_id = :cid AND a.number = :number LIMIT 1")
    suspend fun byNumber(cid: Long, number: Int): AttachmentEntity?

    /**
     * Get stored attachments (not removed) newest first, per direction.
     */
    @Query("SELECT * FROM attachments WHERE direction = :direction AND stored_path IS NOT NULL " +
           "ORDER BY created_at DESC")
    suspend fun storedNewestFirst(direction: AttachmentDirection): List<AttachmentEntity>

    /**
     * Mark an attachment as removed (path cleared, timestamp set).
     */
    @Query("UPDATE attachments SET stored_path = NULL, removed_at = :at WHERE id = :id")
    suspend fun markRemoved(id: Long, at: Long)
}
