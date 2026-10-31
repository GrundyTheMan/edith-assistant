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
@TypeConverters(EnumConverters::class, ListConverters::class)
abstract class EdithDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
}

/**
 * Callback that creates the default conversation on database open (C05).
 */
object EdithDatabaseCallback : androidx.room.RoomDatabase.Callback() {
    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        // Create initial conversation (id = 1, title = "Edith")
        db.execSQL("INSERT OR REPLACE INTO conversations (id, title, created_at) " +
            "VALUES (1, 'Edith', ${System.currentTimeMillis()})")
    }
}
