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
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.TitleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** C05 and C06b tests against a real (in-memory) Room database. */
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
    private var cid = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EdithDatabase::class.java).build()
        repo = ChatRepository(db, clock, AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default))
        cid = repo.createConversation()
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

    private suspend fun user(text: String, conversation: Long = cid, source: MessageSource = MessageSource.Typed) =
        repo.addUserMessage(conversation, text, source)!!

    // --- C05 ---------------------------------------------------------------------------------------------------

    @Test fun observeEmitsMessagesOldestFirstWithTheirAttachments() = runBlocking {
        val userId = user("What's in this photo?")
        db.attachmentDao().insert(attachment(userId, 1))
        repo.addAssistantMessage(cid, "A beach.", "gemma4:12b-it-q8_0", emptyList())

        val messages = repo.observe(cid).first()

        assertEquals(listOf("What's in this photo?", "A beach."), messages.map { it.message.content })
        assertEquals(listOf("photo1.jpg"), messages[0].attachments.map { it.fileName })
        assertEquals(0, messages[1].attachments.size)
    }

    @Test fun recentReturnsTheNewestNOldestFirst() = runBlocking {
        listOf("one", "two", "three").forEach { user(it) }
        assertEquals(listOf("two", "three"), repo.recent(cid, 2).map { it.message.content })
    }

    @Test fun nextNumberStartsAtOneAndIncrementsAcrossMessages() = runBlocking {
        assertEquals(1, db.attachmentDao().nextNumber(cid))
        db.attachmentDao().insert(attachment(user("a"), 1))
        assertEquals(2, db.attachmentDao().nextNumber(cid))
        db.attachmentDao().insert(attachment(user("b"), 2))
        assertEquals(3, db.attachmentDao().nextNumber(cid))
    }

    @Test fun enumsAreStoredAsTheSqlStrings() = runBlocking {
        db.attachmentDao().insert(attachment(user("voice", source = MessageSource.VoiceMemo), 1, AttachmentDirection.FromUser))

        assertEquals(
            Triple("user", "complete", "voice_memo"),
            raw("SELECT role, status, source FROM messages") { Triple(it.getString(0), it.getString(1), it.getString(2)) },
        )
        assertEquals("from_user" to "image", raw("SELECT direction, kind FROM attachments") { it.getString(0) to it.getString(1) })
        assertEquals("first_message", raw("SELECT title_source FROM conversations") { it.getString(0) })
    }

    @Test fun assistantTextLosesToolCallTextAndToolsUsedIsJson() = runBlocking {
        repo.addAssistantMessage(cid, "Sure.<tool_call>{\"name\":\"get_weather\"}</tool_call> Done", "m", listOf("get_weather"))

        assertEquals("Sure. Done" to "[\"get_weather\"]", raw("SELECT content, tools_used FROM messages") { it.getString(0) to it.getString(1) })
        assertEquals(listOf("get_weather"), repo.observe(cid).first().single().message.toolsUsed)
    }

    @Test fun failedRepliesKeepTheErrorMessage() = runBlocking {
        repo.addFailedAssistantMessage(cid, "m", AppError.Timeout)
        assertEquals(
            Triple("failed", "", AppError.Timeout.userMessage),
            raw("SELECT status, content, error_message FROM messages") { Triple(it.getString(0), it.getString(1), it.getString(2)) },
        )
    }

    @Test fun deletingAMessageDeletesItsAttachments() = runBlocking {
        val id = user("with photo")
        db.attachmentDao().insert(attachment(id, 1))

        repo.deleteMessage(id)

        assertNull(db.attachmentDao().byNumber(cid, 1))
        assertEquals(0, raw("SELECT COUNT(*) FROM attachments") { it.getInt(0) })
    }

    @Test fun clearingTheConversationKeepsTheConversationRow() = runBlocking {
        user("x")
        repo.clearConversation(cid)
        assertEquals(0, raw("SELECT COUNT(*) FROM messages") { it.getInt(0) })
        assertEquals(1, raw("SELECT COUNT(*) FROM conversations") { it.getInt(0) })
    }

    // --- C06b: many conversations ------------------------------------------------------------------------------

    @Test fun conversationsAreSeparate() = runBlocking {
        val other = repo.createConversation()
        user("in first")
        user("in second", other)
        repo.addAssistantMessage(other, "reply in second", "m", emptyList())

        assertEquals(listOf("in first"), repo.observe(cid).first().map { it.message.content })
        assertEquals(listOf("in second", "reply in second"), repo.recent(other, 10).map { it.message.content })
        assertEquals("in first", repo.latest(cid)!!.message.content)

        repo.clearConversation(cid)
        assertEquals(2, repo.recent(other, 10).size)
    }

    @Test fun aNewMessageBumpsTheConversationToTheTopOfTheList() = runBlocking {
        val second = repo.createConversation()
        user("old chat", cid)
        user("newer chat", second)
        assertEquals(listOf(second, cid), repo.conversations().first().map { it.id })

        repo.addAssistantMessage(cid, "Edith answers the old chat", "m", emptyList())

        val list = repo.conversations().first()
        assertEquals(listOf(cid, second), list.map { it.id })
        assertEquals("Edith answers the old chat", list.first().lastMessage)
        assertEquals(repo.latest(cid)!!.message.createdAt, list.first().updatedAt)
    }

    @Test fun theFirstMessageGivesAProvisionalTitleThatEdithAndTheOwnerCanReplace() = runBlocking {
        user("What's the weather going to be like in Detroit this weekend, roughly?")
        assertEquals("What's the weather going to be like in…" to TitleSource.FirstMessage, repo.conversation(cid)!!.let { it.title to it.titleSource })

        user("A second message doesn't retitle")
        assertTrue(repo.conversation(cid)!!.title.startsWith("What's the weather"))

        assertTrue(repo.setEdithTitle(cid, "Detroit weekend weather"))
        assertEquals("Detroit weekend weather" to TitleSource.Edith, repo.conversation(cid)!!.let { it.title to it.titleSource })

        repo.rename(cid, "  Weekend   plans ")
        assertEquals("Weekend plans" to TitleSource.Owner, repo.conversation(cid)!!.let { it.title to it.titleSource })

        assertFalse("Edith never overwrites the owner's title", repo.setEdithTitle(cid, "Something else"))
        repo.rename(cid, "   ")   // blank rename ignored
        assertEquals("Weekend plans", repo.conversation(cid)!!.title)
    }

    @Test fun anOwnerRenameBeforeTheFirstMessageIsKept() = runBlocking {
        repo.rename(cid, "Groceries")
        user("Milk, eggs, bread")
        assertEquals("Groceries" to TitleSource.Owner, repo.conversation(cid)!!.let { it.title to it.titleSource })
    }

    @Test fun deletingAConversationDeletesItsMessagesAndAttachments() = runBlocking {
        val keep = repo.createConversation()
        user("keep me", keep)
        db.attachmentDao().insert(attachment(user("photo"), 1))

        repo.deleteConversation(cid)

        assertNull(repo.conversation(cid))
        assertEquals(listOf("keep me"), raw("SELECT content FROM messages") { c -> buildList { do add(c.getString(0)) while (c.moveToNext()) } })
        assertEquals(0, raw("SELECT COUNT(*) FROM attachments") { it.getInt(0) })
    }

    @Test fun storingIntoADeletedConversationDoesNotCrash() = runBlocking {
        repo.deleteConversation(cid)
        assertNull(repo.addUserMessage(cid, "too late", MessageSource.Typed))
        assertNull(repo.addCancelledAssistantMessage(cid, "partial", "m"))
        assertEquals(0, raw("SELECT COUNT(*) FROM messages") { it.getInt(0) })
    }

    @Test fun attachmentNumbersAreLookedUpInTheirOwnConversation() = runBlocking {
        val other = repo.createConversation()
        db.attachmentDao().insert(attachment(user("mine"), 1).copy(fileName = "first-chat.jpg"))
        db.attachmentDao().insert(attachment(user("theirs", other), 1).copy(fileName = "second-chat.jpg"))

        assertEquals("first-chat.jpg", db.attachmentDao().byNumber(cid, 1)!!.fileName)
        assertEquals("second-chat.jpg", db.attachmentDao().byNumber(other, 1)!!.fileName)
    }

    @Test fun emptyConversationsArePrunedExceptTheOneKept() = runBlocking {
        val empty1 = repo.createConversation()
        val empty2 = repo.createConversation()
        user("has a message")

        assertEquals(1, repo.deleteEmptyConversationsExcept(empty2))
        assertEquals(setOf(cid, empty2), repo.conversations().first().map { it.id }.toSet())
        assertNull(repo.conversation(empty1))
        assertTrue(repo.isEmpty(empty2))
        assertFalse(repo.isEmpty(cid))
    }
}
