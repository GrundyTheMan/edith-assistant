package com.edith.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.time.Clock
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.AttachmentDirection
import com.edith.app.data.db.AttachmentEntity
import com.edith.app.data.db.AttachmentKind
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.EdithDatabaseCallback
import com.edith.app.data.db.MessageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** C05 tests against a real (in-memory) Room database. The onCreate callback runs for in-memory databases too. */
@RunWith(AndroidJUnit4::class)
class ChatRepositoryTest {
    /** Each call is 1 ms later, so ordering by created_at is deterministic. */
    private val clock = object : Clock {
        var t = Instant.parse("2026-10-31T12:00:00Z")
        override fun now(): Instant = t.also { t = t.plusMillis(1) }
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }
    private lateinit var db: EdithDatabase
    private lateinit var repo: ChatRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EdithDatabase::class.java)
            .addCallback(EdithDatabaseCallback)
            .build()
        repo = ChatRepository(db, clock, AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default))
    }

    @After fun tearDown() = db.close()

    private fun attachment(messageId: Long, number: Int, direction: AttachmentDirection = AttachmentDirection.FromUser) =
        AttachmentEntity(
            messageId = messageId, number = number, direction = direction, kind = AttachmentKind.Image,
            fileName = "photo$number.jpg", mimeType = "image/jpeg", sizeBytes = 1000, storedPath = "attachments/$number.jpg",
            createdAt = clock.now().toEpochMilli(),
        )

    private fun <T> raw(sql: String, read: (android.database.Cursor) -> T): T =
        db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); read(it) }

    @Test fun createsTheOneConversationOnCreate() {
        assertEquals(1, raw("SELECT COUNT(*) FROM conversations") { it.getInt(0) })
        assertEquals(1L to "Edith", raw("SELECT id, title FROM conversations") { it.getLong(0) to it.getString(1) })
    }

    @Test fun observeEmitsMessagesOldestFirstWithTheirAttachments() = runBlocking {
        val userId = repo.addUserMessage("What's in this photo?", MessageSource.Typed)
        db.attachmentDao().insert(attachment(userId, 1))
        repo.addAssistantMessage("A beach.", "gemma4:12b-it-q8_0", emptyList())

        val messages = repo.observe().first()

        assertEquals(listOf("What's in this photo?", "A beach."), messages.map { it.message.content })
        assertEquals(listOf("photo1.jpg"), messages[0].attachments.map { it.fileName })
        assertEquals(0, messages[1].attachments.size)
    }

    @Test fun recentReturnsTheNewestNOldestFirst() = runBlocking {
        listOf("one", "two", "three").forEach { repo.addUserMessage(it, MessageSource.Typed) }
        assertEquals(listOf("two", "three"), repo.recent(2).map { it.message.content })
    }

    @Test fun nextNumberStartsAtOneAndIncrementsAcrossMessages() = runBlocking {
        val cid = repo.conversationId
        assertEquals(1, db.attachmentDao().nextNumber(cid))
        val first = repo.addUserMessage("a", MessageSource.Typed)
        db.attachmentDao().insert(attachment(first, 1))
        assertEquals(2, db.attachmentDao().nextNumber(cid))
        val second = repo.addUserMessage("b", MessageSource.Typed)
        db.attachmentDao().insert(attachment(second, 2))
        assertEquals(3, db.attachmentDao().nextNumber(cid))
    }

    @Test fun enumsAreStoredAsTheSqlStrings() = runBlocking {
        val id = repo.addUserMessage("voice", MessageSource.VoiceMemo)
        db.attachmentDao().insert(attachment(id, 1, AttachmentDirection.FromUser))

        assertEquals(
            Triple("user", "complete", "voice_memo"),
            raw("SELECT role, status, source FROM messages") { Triple(it.getString(0), it.getString(1), it.getString(2)) },
        )
        assertEquals("from_user" to "image", raw("SELECT direction, kind FROM attachments") { it.getString(0) to it.getString(1) })
    }

    @Test fun assistantTextLosesToolCallTextAndToolsUsedIsJson() = runBlocking {
        repo.addAssistantMessage("Sure.<tool_call>{\"name\":\"get_weather\"}</tool_call> Done", "m", listOf("get_weather"))

        assertEquals("Sure. Done" to "[\"get_weather\"]", raw("SELECT content, tools_used FROM messages") { it.getString(0) to it.getString(1) })
        assertEquals(listOf("get_weather"), repo.observe().first().single().message.toolsUsed)
    }

    @Test fun failedRepliesKeepTheErrorMessage() = runBlocking {
        repo.addFailedAssistantMessage("m", AppError.Timeout)
        assertEquals(
            Triple("failed", "", AppError.Timeout.userMessage),
            raw("SELECT status, content, error_message FROM messages") { Triple(it.getString(0), it.getString(1), it.getString(2)) },
        )
    }

    @Test fun deletingAMessageDeletesItsAttachments() = runBlocking {
        val id = repo.addUserMessage("with photo", MessageSource.Typed)
        db.attachmentDao().insert(attachment(id, 1))

        repo.deleteMessage(id)

        assertNull(db.attachmentDao().byNumber(1))
        assertEquals(0, raw("SELECT COUNT(*) FROM attachments") { it.getInt(0) })
    }

    @Test fun clearingTheConversationKeepsTheConversationRow() = runBlocking {
        repo.addUserMessage("x", MessageSource.Typed)
        repo.clearConversation()
        assertEquals(0, raw("SELECT COUNT(*) FROM messages") { it.getInt(0) })
        assertEquals(1, raw("SELECT COUNT(*) FROM conversations") { it.getInt(0) })
    }
}
