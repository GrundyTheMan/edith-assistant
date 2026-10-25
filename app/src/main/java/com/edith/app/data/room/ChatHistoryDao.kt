package com.edith.app.data.room

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatHistoryDao {
    @Query("SELECT * FROM chat_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<ChatHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: ChatHistoryEntity)

    @Query("DELETE FROM chat_history")
    suspend fun clearHistory()
}
