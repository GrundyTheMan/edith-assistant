package com.edith.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.MIGRATION_1_2
import com.edith.app.data.db.MIGRATION_2_3
import com.edith.app.data.db.TitleSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C06b: the owner's version-1 database (C05) must reach version 2 with every message intact. Builds real v1 files
 * from the exported schema (app/schemas/…/1.json) and runs the real migration; Room then validates the result
 * against the v2 schema.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), EdithDatabase::class.java)

    @Test fun v1HistorySurvivesTheMigration() {
        helper.createDatabase(dbName, 1).use { v1 ->
            v1.execSQL("INSERT INTO conversations (id, title, created_at) VALUES (1, 'Edith', 1000)")
            v1.execSQL(
                "INSERT INTO messages (conversation_id, role, content, created_at, status, source, tools_used) VALUES " +
                    "(1, 'user', 'Hi Edith', 2000, 'complete', 'typed', '[]'), " +
                    "(1, 'assistant', 'Hello!', 3000, 'complete', 'typed', '[]')",
            )
            v1.execSQL(
                "INSERT INTO attachments (message_id, number, direction, kind, file_name, mime_type, size_bytes, " +
                    "stored_path, created_at) VALUES (1, 1, 'from_user', 'image', 'a.jpg', 'image/jpeg', 10, 'p', 2000)",
            )
        }

        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).use { v2 ->
            v2.query("PRAGMA foreign_key_check").use { assertEquals("foreign key problems", 0, it.count) }
            v2.query("SELECT COUNT(*) FROM messages").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            v2.query("SELECT COUNT(*) FROM attachments").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            v2.query("SELECT id, title, title_source, updated_at FROM conversations").use {
                it.moveToFirst()
                assertEquals(listOf("1", "Edith", "owner", "3000"), (0..3).map { i -> it.getString(i) })
            }
        }

        // And Room opens it as the app would, with every message where it was.
        val db = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, EdithDatabase::class.java, dbName)
            .addMigrations(*EdithDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                assertEquals(listOf("Hi Edith", "Hello!"), db.messageDao().observeConversation(1).first().map { it.message.content })
                assertEquals(TitleSource.Owner, db.conversationDao().get(1)!!.titleSource)
                assertEquals(2L, db.conversationDao().insertNew(4000))   // new chats get fresh ids
            }
        } finally {
            db.close()
        }
    }

    @Test fun anEmptyV1ConversationMigratesToo() {
        helper.createDatabase(dbName, 1).use { it.execSQL("INSERT INTO conversations (id, title, created_at) VALUES (1, 'Edith', 1000)") }
        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).use { v2 ->
            v2.query("SELECT updated_at FROM conversations WHERE id = 1").use { it.moveToFirst(); assertEquals(1000L, it.getLong(0)) }
        }
    }

    /** C25: v2 → v3 adds the smart home's command log and leaves the chats alone. */
    @Test fun v2GainsTheCommandLog() {
        helper.createDatabase(dbName, 2).use { v2 ->
            v2.execSQL("INSERT INTO conversations (id, title, title_source, created_at, updated_at) VALUES (1, 'Chat', 'owner', 1000, 2000)")
            v2.execSQL("INSERT INTO messages (conversation_id, role, content, created_at, status, source, tools_used) VALUES (1, 'user', 'Hi', 2000, 'complete', 'typed', '[]')")
        }
        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3).use { v3 ->
            v3.query("SELECT COUNT(*) FROM messages").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            v3.execSQL("INSERT INTO home_commands (at, device_name, backend, action, value, ok, result, source) VALUES (5, 'Front door', 'demo', 'unlock', NULL, 1, 'Unlocked', 'chat')")
            v3.query("SELECT device_name, action FROM home_commands").use { it.moveToFirst(); assertEquals("Front door", it.getString(0)) }
        }
    }
}
