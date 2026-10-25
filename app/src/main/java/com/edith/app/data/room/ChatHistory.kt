package com.edith.app.data.room

import androidx.room.*
import java.util.Date

@Entity(tableName = "chat_history")
data class ChatHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val role: String,
    val content: String,
    val timestamp: Long
)
