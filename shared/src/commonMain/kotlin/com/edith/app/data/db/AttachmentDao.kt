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

    // --- C16: storage and retention ------------------------------------------------------------------------------

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun byId(id: Long): AttachmentEntity?

    /** What retention counts (C16 §2): kept files of one direction, newest first. Session files and resends don't count. */
    @Query("SELECT * FROM attachments WHERE direction = :direction AND stored_path LIKE 'attachments/%' " +
           "AND resend_of IS NULL ORDER BY created_at DESC, id DESC")
    suspend fun keptNewestFirst(direction: AttachmentDirection): List<AttachmentEntity>

    @Query("SELECT stored_path FROM attachments WHERE stored_path IS NOT NULL")
    suspend fun allStoredPaths(): List<String>

    @Query("SELECT a.stored_path FROM attachments a JOIN messages m ON a.message_id = m.id " +
           "WHERE m.conversation_id = :cid AND a.stored_path IS NOT NULL")
    suspend fun storedPathsInConversation(cid: Long): List<String>

    @Query("SELECT stored_path FROM attachments WHERE message_id = :messageId AND stored_path IS NOT NULL")
    suspend fun storedPathsOfMessage(messageId: Long): List<String>

    /** Last run's session files are gone (C16 §2: keep = 0). */
    @Query("UPDATE attachments SET stored_path = NULL, removed_at = :at WHERE stored_path LIKE 'session/%'")
    suspend fun markSessionRemoved(at: Long): Int

    /** Settings → Delete all attachments now. */
    @Query("UPDATE attachments SET stored_path = NULL, removed_at = :at WHERE stored_path IS NOT NULL")
    suspend fun markAllRemoved(at: Long): Int
}
