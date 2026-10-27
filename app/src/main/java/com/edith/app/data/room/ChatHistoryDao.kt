package com.edith.app.data.room

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatHistoryDao {
    @Query("SELECT * FROM chat_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<ChatHistoryEntity>>

    /** Returns the new row's id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: ChatHistoryEntity): Long

    @Query("DELETE FROM chat_history")
    suspend fun clearHistory()

    @Query("DELETE FROM chat_history WHERE id = (SELECT id FROM chat_history WHERE role = 'assistant' ORDER BY timestamp DESC LIMIT 1)")
    suspend fun deleteLatestReply()
}
