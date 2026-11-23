package com.edith.app.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.edith.app.home.HomeCommandDao
import com.edith.app.home.HomeCommandEntity

/**
 * Edith's database, schema version 3 (01-architecture §5.1). Version 1 (C05) had one conversation; version 2
 * (C06b, decision D6) adds what a list of conversations needs; version 3 (C25) the smart home's command log. The
 * schema is exported to app/schemas/ at build time. Every schema change needs a version bump, a [Migration] and a migration test — never destructive
 * migration: the owner's chat history lives here.
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, AttachmentEntity::class, HomeCommandEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class, ListConverters::class)
@ConstructedBy(EdithDatabaseConstructor::class)
abstract class EdithDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun homeCommandDao(): HomeCommandDao

    companion object {
        const val FILE_NAME = "edith.db"

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}

/** Room's processor writes the actual of this for each platform. */
@Suppress("KotlinNoActualForExpect")
expect object EdithDatabaseConstructor : RoomDatabaseConstructor<EdithDatabase> {
    override fun initialize(): EdithDatabase
}

/**
 * v1 → v2: adds `title_source` and `updated_at` to conversations, plus an index. Only ADD COLUMN, never a table
 * rebuild: `messages` references `conversations` with ON DELETE CASCADE, so dropping the old table could delete
 * every message. Existing conversations keep their id and title ("Edith" counts as the owner's: it's the v1 name).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE conversations ADD COLUMN title_source TEXT NOT NULL DEFAULT 'none'")
        connection.execSQL("ALTER TABLE conversations ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("UPDATE conversations SET title_source = CASE WHEN title = '' THEN 'none' ELSE 'owner' END")
        connection.execSQL(
            "UPDATE conversations SET updated_at = " +
                "COALESCE((SELECT MAX(created_at) FROM messages WHERE conversation_id = conversations.id), created_at)",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_updated_at ON conversations (updated_at)")
    }
}

/** v2 → v3 (C25): the smart home's command log, a new table; nothing else changes. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `home_commands` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, " +
                "`device_name` TEXT NOT NULL, `backend` TEXT NOT NULL, `action` TEXT NOT NULL, `value` TEXT, `ok` INTEGER NOT NULL, " +
                "`result` TEXT NOT NULL, `source` TEXT NOT NULL)",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_home_commands_at` ON `home_commands` (`at`)")
    }
}
