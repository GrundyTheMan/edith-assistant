package com.edith.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Edith's database, schema version 2 (01-architecture §5.1). Version 1 (C05) had one conversation; version 2
 * (C06b, decision D6) adds what a list of conversations needs. The schema is exported to app/schemas/ at build
 * time. Every schema change needs a version bump, a [Migration] and a migration test — never destructive
 * migration: the owner's chat history lives here.
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, AttachmentEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class, ListConverters::class)
abstract class EdithDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        const val FILE_NAME = "edith.db"

        val MIGRATIONS = arrayOf(MIGRATION_1_2)

        /** The app's database. Create once per process (AppContainer does). */
        fun create(context: Context): EdithDatabase =
            Room.databaseBuilder(context, EdithDatabase::class.java, FILE_NAME).addMigrations(*MIGRATIONS).build()
    }
}

/**
 * v1 → v2: adds `title_source` and `updated_at` to conversations, plus an index. Only ADD COLUMN, never a table
 * rebuild: `messages` references `conversations` with ON DELETE CASCADE, so dropping the old table could delete
 * every message. Existing conversations keep their id and title ("Edith" counts as the owner's: it's the v1 name).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN title_source TEXT NOT NULL DEFAULT 'none'")
        db.execSQL("ALTER TABLE conversations ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE conversations SET title_source = CASE WHEN title = '' THEN 'none' ELSE 'owner' END")
        db.execSQL(
            "UPDATE conversations SET updated_at = " +
                "COALESCE((SELECT MAX(created_at) FROM messages WHERE conversation_id = conversations.id), created_at)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_updated_at ON conversations (updated_at)")
    }
}
