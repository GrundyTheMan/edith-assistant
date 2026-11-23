package com.edith.app.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.time.Clock
import com.edith.app.core.time.SystemClock
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.MessageEntity
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.MessageStatus
import com.edith.app.data.db.inTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K1.6: the chat history on the desktop, with the bundled SQLite: the rules that used to lean on Android's own exceptions. */
class ChatRepositoryTest {
    private fun db() = Room.inMemoryDatabaseBuilder<EdithDatabase>()
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.Unconfined).build()

    private fun repository(db: EdithDatabase, clock: Clock = SystemClock) =
        ChatRepository(db, clock, AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined))

    @Test
    fun aMessageForAConversationThatIsGoneIsNotStoredAndNothingIsThrown() = runBlocking {
        val db = db()
        try {
            assertNull(repository(db).addUserMessage(conversationId = 999, text = "hello?", source = MessageSource.Typed))
        } finally {
            db.close()
        }
    }

    @Test
    fun aMessageIsStoredAndTheConversationMovesUp() = runBlocking {
        val db = db()
        try {
            val repo = repository(db)
            val id = repo.createConversation()
            assertNotNull(repo.addUserMessage(id, "hi there", MessageSource.Typed))
            assertEquals(listOf("hi there"), db.messageDao().recent(id, 10).map { it.message.content })
        } finally {
            db.close()
        }
    }

    @Test
    fun aTransactionThatThrowsKeepsNothing() = runBlocking {
        val db = db()
        try {
            val id = db.conversationDao().insertNew(1)
            val failed = runCatching {
                db.inTransaction {
                    db.messageDao().insert(MessageEntity(conversationId = id, role = MessageRole.User, content = "gone", createdAt = 2, status = MessageStatus.Complete, source = MessageSource.Typed))
                    throw IllegalStateException("stop")
                }
            }
            assertTrue(failed.isFailure)
            assertTrue(db.messageDao().recent(id, 10).isEmpty(), "the message went with the transaction")
        } finally {
            db.close()
        }
    }
}
