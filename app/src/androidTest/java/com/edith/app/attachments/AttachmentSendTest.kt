package com.edith.app.attachments

import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.chat.engine.ChatEngine
import com.edith.app.chat.engine.ContextBuilder
import com.edith.app.chat.engine.DateTimeSection
import com.edith.app.chat.engine.EngineState
import com.edith.app.chat.engine.PlainTurnRunner
import com.edith.app.chat.engine.PromptBuilder
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.AttachmentDirection
import com.edith.app.data.db.AttachmentKind
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.MessageStatus
import com.edith.app.ollama.ModelCatalog
import com.edith.app.settings.ModelSelection
import com.edith.app.testing.MemorySettings
import com.edith.app.testing.ScriptedOllama
import com.edith.app.testing.TickingClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C15 §3 through the real engine, Room and store: attachment rows numbered per conversation in the same transaction
 * as the message, files handed to the store, media and labels in the request, and the capability gate.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentSendTest {
    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val work = File(app.cacheDir, "send-test").apply { deleteRecursively(); mkdirs() }
    private val ollama = ScriptedOllama().apply { wordDelayMs = 1 }
    private val settings = MemorySettings()
    private val store = SessionAttachmentStore(File(work, "attachments"))
    private lateinit var db: EdithDatabase
    private lateinit var repo: ChatRepository
    private lateinit var scope: CoroutineScope
    private lateinit var engine: ChatEngine
    private var cid = 0L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, EdithDatabase::class.java).build()
        val clock = TickingClock()
        repo = ChatRepository(db, clock, AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default))
        cid = runBlocking { repo.createConversation() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = ChatEngine(
            repo, settings, ModelSelection(settings, ModelCatalog(ollama)),
            PromptBuilder(clock) { listOf(DateTimeSection(), AttachmentsSection()) },
            ContextBuilder(MediaAttachmentContext(store)), PlainTurnRunner(ollama), emptyList(), scope,
            object : LocalNetworkAccess { override fun isGranted() = true },
            attachmentStore = store,
        )
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
        work.deleteRecursively()
    }

    private fun pending(name: String, kind: AttachmentKind, bytes: ByteArray) = PendingAttachment(
        kind, name, if (kind == AttachmentKind.Image) "image/jpeg" else "audio/wav",
        File(work, "p-$name").apply { writeBytes(bytes) }, bytes.size.toLong(), durationMs = if (kind == AttachmentKind.Audio) 1_000 else null,
    )

    private suspend fun awaitDone() = withTimeout(10_000) { engine.state.first { it is EngineState.Idle || it is EngineState.Failed } }

    @Test fun attachmentsAreStoredNumberedAndSentAsMediaWithLabels(): Unit = runBlocking {
        ollama.capabilities = listOf("completion", "tools", "vision", "audio")
        val photo = pending("list.jpg", AttachmentKind.Image, byteArrayOf(1, 2, 3))
        val note = pending("note.wav", AttachmentKind.Audio, byteArrayOf(4, 5))
        assertTrue(engine.send(cid, "what's on this list?", MessageSource.Typed, listOf(photo, note)))
        assertEquals(EngineState.Idle, awaitDone())

        val user = repo.observe(cid).first().first { it.message.role == MessageRole.User }
        assertEquals(listOf(1, 2), user.attachments.map { it.number })
        assertTrue(user.attachments.all { it.direction == AttachmentDirection.FromUser })
        assertEquals(listOf("list.jpg", "note.wav"), user.attachments.map { it.fileName })
        val stored = user.attachments.map { store.file(it.id) }
        assertTrue("files moved into the store", stored.all { it != null && it.isFile } && !photo.file.exists() && !note.file.exists())
        assertEquals(1_000L, store.durationMs(user.attachments[1].id))

        val sent = ollama.calls.single()
        val last = sent.messages.last()
        assertEquals("what's on this list?\n[Attachment 1: list.jpg (image)]\n[Attachment 2: note.wav (audio)]", last.content)
        assertEquals(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5)).map { Base64.encodeToString(it, Base64.NO_WRAP) }, last.images)
        assertTrue("prompt explains labels", "[Attachment 3: receipt.jpg (image)]" in sent.messages.first().content)

        // The next message in the same chat continues the numbering.
        assertTrue(engine.send(cid, "", MessageSource.Typed, listOf(pending("b.jpg", AttachmentKind.Image, byteArrayOf(9)))))
        awaitDone()
        val second = repo.observe(cid).first().filter { it.message.role == MessageRole.User }.last()
        assertEquals(listOf(3), second.attachments.map { it.number })
        assertEquals("[Attachment 3: b.jpg (image)]", ollama.calls.last().messages.last().content)
    }

    @Test fun aModelThatCantSeeGetsNoRequestAndTheFriendlyMessage(): Unit = runBlocking {
        ollama.capabilities = listOf("completion", "tools")
        assertTrue(engine.send(cid, "what is this?", MessageSource.Typed, listOf(pending("a.jpg", AttachmentKind.Image, byteArrayOf(1)))))
        val end = awaitDone()
        assertTrue(end is EngineState.Failed)
        assertTrue("no request was sent", ollama.calls.isEmpty())
        val reply = repo.observe(cid).first().last().message
        assertEquals(MessageStatus.Failed, reply.status)
        assertEquals("gemma4:12b-it-q8_0 can't see images — pick a model marked ‘Sees images’ in Settings.", reply.errorMessage)
    }

    @Test fun sendingWhileATurnRunsLeavesTheAttachmentsWithTheCaller(): Unit = runBlocking {
        ollama.capabilities = listOf("completion", "tools", "vision")
        ollama.wordDelayMs = 200
        assertTrue(engine.send(cid, "first", MessageSource.Typed))
        val photo = pending("a.jpg", AttachmentKind.Image, byteArrayOf(1))
        assertFalse(engine.send(cid, "second", MessageSource.Typed, listOf(photo)))
        assertTrue("still the caller's", photo.file.exists())
        awaitDone()
    }
}
