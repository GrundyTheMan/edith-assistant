package com.edith.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Edith database - schema version 1 (v1 complete).
 * All tables are created up front; no migrations needed during v1.
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, AttachmentEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(EnumConverters::class, JsonConverters::class)
abstract class EdithDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
}
