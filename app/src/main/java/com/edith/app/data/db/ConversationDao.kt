package com.edith.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Conversations (C06b, decision D6). Message and attachment rows go with a deleted conversation (cascade). */
@Dao
interface ConversationDao {
    /** Every conversation, most recently active first, with its newest completed message as a preview. */
    @Query(
        "SELECT c.id, c.title, c.title_source, c.updated_at, " +
            "(SELECT m.content FROM messages m WHERE m.conversation_id = c.id AND m.status = 'complete' " +
            "ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS last_message " +
            "FROM conversations c ORDER BY c.updated_at DESC, c.id DESC",
    )
    fun observeAll(): Flow<List<ConversationRow>>

    /** Inserts a new, untitled conversation; SQLite picks the id. */
    @Query("INSERT INTO conversations (title, title_source, created_at, updated_at) VALUES ('', 'none', :at, :at)")
    suspend fun insertNew(at: Long): Long

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: Long): Flow<ConversationEntity?>

    /** The most recently active conversation, if any. */
    @Query("SELECT * FROM conversations ORDER BY updated_at DESC, id DESC LIMIT 1")
    suspend fun mostRecent(): ConversationEntity?

    /**
     * Sets the title unless a higher-ranked source already set it (Owner > Edith > FirstMessage, D12), so an
     * owner's rename is never overwritten. Owner and Edith may replace their own titles; a first-message title is
     * set only once (a later message doesn't retitle). Returns 1 if it was set.
     */
    @Query(
        "UPDATE conversations SET title = :title, title_source = :source WHERE id = :id AND (" +
            "(CASE :source WHEN 'owner' THEN 3 WHEN 'edith' THEN 2 WHEN 'first_message' THEN 1 ELSE 0 END) > " +
            "(CASE title_source WHEN 'owner' THEN 3 WHEN 'edith' THEN 2 WHEN 'first_message' THEN 1 ELSE 0 END) " +
            "OR (title_source = :source AND :source IN ('owner', 'edith')))",
    )
    suspend fun setTitle(id: Long, title: String, source: TitleSource): Int

    @Query("UPDATE conversations SET updated_at = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT NOT EXISTS (SELECT 1 FROM messages WHERE conversation_id = :id)")
    suspend fun isEmpty(id: Long): Boolean

    /** Removes conversations with no messages, except [keepId] (abandoned "New chat"s). */
    @Query("DELETE FROM conversations WHERE id != :keepId AND id NOT IN (SELECT DISTINCT conversation_id FROM messages)")
    suspend fun deleteEmptyExcept(keepId: Long): Int
}

/** One row of the conversation list. [title] is "" while untitled. */
data class ConversationRow(
    val id: Long,
    val title: String,
    @ColumnInfo(name = "title_source") val titleSource: TitleSource,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_message") val lastMessage: String?,
)
