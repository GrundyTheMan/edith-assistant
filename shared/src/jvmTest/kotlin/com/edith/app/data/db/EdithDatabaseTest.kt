package com.edith.app.data.db

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K1.5: the database that moved to shared code, on the desktop with the bundled SQLite (the same Room code that runs on Android
 * and the iPhone). The schema files in shared/schemas/ are the ones Android wrote when each version shipped.
 */
class EdithDatabaseTest {
    private val schemas = File("schemas/com.edith.app.data.db.EdithDatabase")

    private fun inMemory() = Room.inMemoryDatabaseBuilder<EdithDatabase>()
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.Unconfined).build()

    @Test
    fun conversationsAndMessagesAreKeptInOrderAndGoTogether() = runBlocking {
        val db = inMemory()
        try {
            val id = db.conversationDao().insertNew(1_000)
            db.messageDao().insert(MessageEntity(conversationId = id, role = MessageRole.User, content = "hi", createdAt = 2_000, status = MessageStatus.Complete, source = MessageSource.Typed))
            db.messageDao().insert(
                MessageEntity(
                    conversationId = id, role = MessageRole.Assistant, content = "hello", createdAt = 3_000, status = MessageStatus.Complete,
                    source = MessageSource.Typed, model = "m", toolsUsed = listOf("get_weather", "web search"),
                ),
            )
            val all = db.conversationDao().observeAll().first()
            assertEquals(listOf("hello"), all.map { it.lastMessage })
            val messages = db.messageDao().recent(id, 10)
            assertEquals(listOf("hello", "hi"), messages.map { it.message.content })
            assertEquals(listOf("get_weather", "web search"), messages.first().message.toolsUsed)
            db.conversationDao().delete(id)
            assertTrue(db.messageDao().recent(id, 10).isEmpty(), "messages go with their conversation")
        } finally {
            db.close()
        }
    }

    @Test
    fun theCommandLogKeepsTheNewest() = runBlocking {
        val db = inMemory()
        try {
            val log = com.edith.app.home.RoomCommandLog(db.homeCommandDao())
            repeat(3) { n -> log.add(com.edith.app.home.LoggedCommand(n.toLong(), "Door", "meross", "open", null, true, "ok", com.edith.app.home.CommandSource.Chat)) }
            assertEquals(listOf(2L, 1L, 0L), log.recent.first().map { it.at })
        } finally {
            db.close()
        }
    }

    /** What Room wrote for [version]: the tables and indexes, and its identity record. Nothing of it is made up here. */
    private fun createVersion(connection: SQLiteConnection, version: Int) {
        val database = Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject.getValue("database").jsonObject
        for (entity in database.getValue("entities").jsonArray) {
            val table = (entity as JsonObject).getValue("tableName").jsonPrimitive.content
            connection.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            for (index in entity["indices"]?.jsonArray.orEmpty()) {
                connection.execSQL((index as JsonObject).getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        for (query in database.getValue("setupQueries").jsonArray) connection.execSQL(query.jsonPrimitive.content)
        connection.execSQL("PRAGMA user_version = $version")
    }

    @Test
    fun aVersion1DatabaseOpensAtVersion3WithItsRowsAndTheNewColumns() = runBlocking {
        val path = Files.createTempDirectory("edith-db").resolve(EdithDatabase.FILE_NAME).toString()
        BundledSQLiteDriver().open(path).use { c ->
            createVersion(c, 1)
            c.execSQL("INSERT INTO conversations (id, title, created_at) VALUES (1, 'Trip', 1000)")
            c.execSQL("INSERT INTO conversations (id, title, created_at) VALUES (2, '', 1500)")
            c.execSQL("INSERT INTO messages (conversation_id, role, content, created_at, status, source, tools_used) VALUES (1, 'user', 'hello', 4000, 'complete', 'typed', '[]')")
        }
        val db = EdithDatabase.open(path)
        try {
            val trip = assertNotNull(db.conversationDao().get(1))
            assertEquals("Trip", trip.title)
            assertEquals(TitleSource.Owner, trip.titleSource, "a v1 title was the owner's")
            assertEquals(4_000L, trip.updatedAt, "updated_at starts from the newest message")
            assertEquals(TitleSource.None, assertNotNull(db.conversationDao().get(2)).titleSource)
            assertEquals(listOf("hello"), db.messageDao().recent(1, 10).map { it.message.content })
            assertEquals(0, db.homeCommandDao().recentRows(10).first().size, "the v3 table is there, empty")
        } finally {
            db.close()
        }
    }

    @Test
    fun theSchemaFilesAreThereForEveryVersionAndTheLatestIsTheDatabasesVersion() {
        assertTrue(listOf(1, 2, 3).all { File(schemas, "$it.json").isFile })
        val latest = Json.parseToJsonElement(File(schemas, "3.json").readText()).jsonObject.getValue("database").jsonObject.getValue("version").jsonPrimitive.content
        assertEquals("3", latest)
    }
}
