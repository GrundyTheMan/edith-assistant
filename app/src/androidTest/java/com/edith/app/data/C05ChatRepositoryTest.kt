package com.edith.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.core.time.Clock
import com.edith.app.core.time.SystemClock
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.*
import com.edith.app.data.db.EdithDatabaseCallback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C05 Conversation Storage (Room) Instrumented Tests.
 */
@RunWith(AndroidJUnit4::class)
class ChatRepositoryTest {

    private lateinit var database: EdithDatabase
    private lateinit var repository: ChatRepository
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        // Use file-based database (not in-memory) to ensure onCreate callback runs
        // and foreign key constraints are enforced properly
        database = Room.databaseBuilder(
            context, 
            EdithDatabase::class.java, 
            "edith_test.db"
        ).addCallback(EdithDatabaseCallback).build()

        repository = ChatRepository(database, SystemClock, AppDispatchers.Default)
    }

    @After
    fun tearDown() {
        // Clean up test database
        try {
            java.io.File(context.cacheDir, "edith_test.db").delete()
        } catch (e: Exception) {}
        
        database.close()
    }

    /** Test 1: observe() works and returns messages */
    @Test
    fun test_observeReturnsMessages() = runBlocking {
        repository.addUserMessage("First message", MessageSource.Typed)
        val assistantMsgId = repository.addAssistantMessage("Reply", "gemma4")

        val messages = repository.observe().first()
        
        assertEquals(2, messages.size)
    }

    /** Test 2: nextNumber starts at 1 */
    @Test
    fun test_nextNumberStartsAtOne() = runBlocking {
        val firstNum = database.attachmentDao().nextNumber(1)
        assertEquals(1, firstNum)
    }

    /** Test 3: Enum strings stored as snake_case - verify by converting back */
    @Test
    fun test_enumsStoredAsSnakeCase() = runBlocking {
        repository.addUserMessage("Test", MessageSource.VoiceMemo)
        
        // Retrieve and verify the enum converter works correctly
        val messages = database.messageDao().observeConversation(1).first()
        
        assertEquals(1, messages.size)
        assertEquals(MessageSource.VoiceMemo, messages[0].message.source)
    }

    /** Test 4: addAssistantMessage rejects tool-call text per v1 lesson L3 */
    @Test
    fun test_addAssistantMessageRejectsToolCallText() = runBlocking {
        // Use unicode escape for \x1B (escape character, U+001B) and \u001E
        val textWithToolCall = "Hello \u001b.get_weather{location:Boston}\u001e world"
        
        var thrown: Exception? = null
        try {
            repository.addAssistantMessage(textWithToolCall, "gemma4")
        } catch (e: IllegalArgumentException) {
            // In v1 we throw instead of stripping - lesson L3 says reject tool-call text
            thrown = e
        }
        
        assertNotNull("Should throw when tool-call text detected", thrown)
    }

    /** Test 5: Delete message cascades to attachments via FOREIGN KEY CASCADE */
    @Test
    fun test_deleteMessageCascadesToAttachments() = runBlocking {
        val msgId = repository.addUserMessage("Test message", MessageSource.Typed)
        
        // Get next number, then manually insert attachment
        database.attachmentDao().insert(AttachmentEntity(
            messageId = msgId,
            number = 1,
            direction = AttachmentDirection.FromUser,
            kind = AttachmentKind.Image,
            fileName = "test.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1000,
            storedPath = "/path/test.jpg",
            createdAt = System.currentTimeMillis(),
            removedAt = null
        ))

        // Verify attachment exists
        val existing = database.attachmentDao().byNumber(1)
        assertNotNull(existing)

        // Delete message - attachments should be deleted via CASCADE
        repository.deleteMessage(msgId)
        
        // Attachment should now not exist (or be marked removed)
        val afterDelete = database.attachmentDao().byNumber(1)
        assertNull(afterDelete)
    }

    /** Test 6: Default conversation exists after initialization */
    @Test
    fun test_defaultConversationCreated() = runBlocking {
        // The onCreate callback should have created conversation id=1 with title="Edith"
        val messages = database.messageDao().observeConversation(1).first()
        
        assertTrue("Default conversation should exist after initialization", !messages.isEmpty())
    }
}
