package com.edith.app.attachments

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * K2 G2: the desktop copy of C15 §3 and C16 (the Android one stays in androidTest), through the real engine, Room (bundled
 * SQLite) and the file store: attachment rows numbered per conversation in the same transaction as the message, files handed
 * to the store, media and labels in the request, and the capability gate.
 */
class AttachmentSendTest {
    private val work = Files.createTempDirectory("edith-send").toFile()
    private val ollama = ScriptedOllama().apply { wordDelayMs = 1 }
    private val settings = MemorySettings()
    private val store = PathAttachmentStore(File(work, "files").path, File(work, "cache").path)
    private val dispatchers = AppDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Default)
    private lateinit var db: EdithDatabase
    private lateinit var repo: ChatRepository
    private lateinit var scope: CoroutineScope
    private lateinit var engine: ChatEngine
    private var cid = 0L

    @BeforeTest fun setUp() {
        db = Room.inMemoryDatabaseBuilder<EdithDatabase>().setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.Default).build()
        val clock = TickingClock()
        repo = ChatRepository(db, clock, dispatchers, store)
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

    @AfterTest fun tearDown() {
        scope.cancel()
        db.close()
        work.deleteRecursively()
    }

    private fun file(storedPath: String?): File? = storedPath?.let { File(store.resolve(it)) }?.takeIf { it.isFile }

    private fun pending(name: String, kind: AttachmentKind, bytes: ByteArray) = PendingAttachment(
        kind, name, if (kind == AttachmentKind.Image) "image/jpeg" else "audio/wav",
        File(work, "p-$name").apply { writeBytes(bytes) }.path, bytes.size.toLong(), durationMs = if (kind == AttachmentKind.Audio) 1_000 else null,
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
        val stored = user.attachments.map { file(it.storedPath) }
        assertTrue(stored.all { it != null } && !File(photo.path).exists() && !File(note.path).exists(), "files moved into the store")
        assertEquals(1_000L, store.durationMs(user.attachments[1].storedPath))

        val sent = ollama.calls.single()
        val last = sent.messages.last()
        assertEquals("what's on this list?\n[Attachment 1: list.jpg (image)]\n[Attachment 2: note.wav (audio)]", last.content)
        assertEquals(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5)).map { java.util.Base64.getEncoder().encodeToString(it) }, last.images)
        assertTrue("[Attachment 3: receipt.jpg (image)]" in sent.messages.first().content, "prompt explains labels")

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
        assertTrue(ollama.calls.isEmpty(), "no request was sent")
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
        assertTrue(File(photo.path).exists(), "still the caller's")
        awaitDone()
    }

    // --- C16: Edith's files and resends ------------------------------------------------------------------------

    @Test fun edithsFileIsStoredAndHerTextKeepsAPlaceholder(): Unit = runBlocking {
        ollama.words = listOf("Here you go.\n", "<file name=\"beach list.md\">", "- towel\n- sunscreen\n", "</file>", "\nHave fun!")
        engine.send(cid, "make me a beach list as a file", MessageSource.Typed)
        assertEquals(EngineState.Idle, awaitDone())

        val reply = repo.observe(cid).first().last()
        assertEquals("Here you go.\n\n[Attached file: beach-list.md]\nHave fun!", reply.message.content)
        val file = reply.attachments.single()
        assertEquals(AttachmentDirection.FromEdith, file.direction)
        assertEquals(AttachmentKind.File, file.kind)
        assertEquals("beach-list.md", file.fileName)
        assertEquals("text/markdown", file.mimeType)
        assertTrue(file.storedPath!!.startsWith("attachments/"))
        assertEquals("- towel\n- sunscreen", store.readText(file.storedPath))
    }

    @Test fun aResendPointsAtTheOriginalAndAnUnknownNumberIsSaid(): Unit = runBlocking {
        ollama.capabilities = listOf("completion", "tools", "vision")
        engine.send(cid, "my receipt", MessageSource.Typed, listOf(pending("receipt.jpg", AttachmentKind.Image, byteArrayOf(1, 2))))
        awaitDone()
        val original = repo.observe(cid).first().first { it.message.role == MessageRole.User }.attachments.single()

        ollama.words = listOf("Here it is: ", "<attachment number=\"1\"/>", " And <attachment number=\"7\"/>")
        engine.send(cid, "send me that receipt again", MessageSource.Typed)
        awaitDone()
        val reply = repo.observe(cid).first().last()
        val resend = reply.attachments.single()
        assertEquals(original.id, resend.resendOf)
        assertEquals(1, resend.number)
        assertEquals(null, resend.storedPath)   // no copy
        assertEquals("Here it is:  And\n\nAttachment 7 isn't available.", reply.message.content)
        assertEquals(1, store.storedFiles().size, "receipt still stored once")
    }

    @Test fun noAttachmentBytesAreStoredInTheDatabase(): Unit = runBlocking {
        // A file-backed database this time, scanned byte by byte for a JPEG and a WAV signature (C16 tests).
        val dbFile = File(work, "bytes-test.db")
        val fileDb = Room.databaseBuilder<EdithDatabase>(dbFile.path).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.Default).build()
        val fileRepo = ChatRepository(fileDb, TickingClock(), dispatchers, store)
        val chat = fileRepo.createConversation()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64) { 7 }
        val wav = "RIFF____WAVEfmt ".toByteArray() + ByteArray(64) { 3 }
        val kept = listOf(pending("p.jpg", AttachmentKind.Image, jpeg), pending("a.wav", AttachmentKind.Audio, wav)).map {
            ChatRepository.NewAttachment(it.kind, it.fileName, it.mimeType, it.sizeBytes, store.keep(it, session = false))
        }
        fileRepo.addUserMessage(chat, "two files", MessageSource.Typed, kept)
        fileDb.close()
        val bytes = listOf("", "-wal", "-journal").map { File(dbFile.path + it) }.filter { it.exists() }.flatMap { it.readBytes().asList() }.toByteArray()
        assertTrue(bytes.size > 1_000, "the database file was read")
        assertFalse(indexOf(bytes, jpeg.copyOf(4)) >= 0, "JPEG bytes in the database")
        assertFalse(indexOf(bytes, "WAVEfmt ".toByteArray()) >= 0, "WAV bytes in the database")
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).firstOrNull { i -> needle.indices.all { haystack[i + it] == needle[it] } } ?: -1
}
