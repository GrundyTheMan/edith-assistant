package com.edith.app.attachments

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.AttachmentDirection
import com.edith.app.data.db.AttachmentEntity
import com.edith.app.data.db.AttachmentKind
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.MessageSource
import com.edith.app.testing.MemorySettings
import com.edith.app.testing.TickingClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K2 G2: the desktop copy of C16 §2 (the Android one stays in androidTest, where Save and Share are also tested), with real
 * Room (bundled SQLite) and real files: retention per direction (O1), resends not counted, a lower setting, keep = 0
 * (session files), orphans, and deleting chats and messages.
 */
class RetentionTest {
    private val work = Files.createTempDirectory("edith-retention").toFile()
    private val store = PathAttachmentStore(File(work, "files").path, File(work, "cache").path)
    private val settings = MemorySettings()
    private val dispatchers = AppDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Default)
    private lateinit var db: EdithDatabase
    private lateinit var repo: ChatRepository
    private lateinit var retention: Retention
    private var cid = 0L

    @BeforeTest fun setUp() {
        db = Room.inMemoryDatabaseBuilder<EdithDatabase>().setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.Default).build()
        val clock = TickingClock()
        repo = ChatRepository(db, clock, dispatchers, store)
        retention = Retention(db.attachmentDao(), store, settings, clock, dispatchers)
        cid = runBlocking { repo.createConversation() }
    }

    @AfterTest fun tearDown() {
        db.close()
        work.deleteRecursively()
    }

    private fun file(storedPath: String?): File? = storedPath?.let { File(store.resolve(it)) }?.takeIf { it.isFile }

    private var n = 0
    private fun pending(): PendingAttachment {
        val f = File(work, "p${n++}.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return PendingAttachment(AttachmentKind.Image, f.name, "image/jpeg", f.path, 3)
    }

    /** One owner message with one photo; returns its row. */
    private suspend fun ownerPhoto(session: Boolean = false, chat: Long = cid): AttachmentEntity {
        val p = pending()
        val path = store.keep(p, session)
        repo.addUserMessage(chat, "photo", MessageSource.Typed, listOf(ChatRepository.NewAttachment(p.kind, p.fileName, p.mimeType, 3, path)))
        return repo.observe(chat).first().last().attachments.single()
    }

    /** One reply with one file Edith wrote; returns its row. */
    private suspend fun edithFile(): AttachmentEntity {
        val path = store.write("list.md", "- a", session = false)
        repo.addAssistantMessage(cid, "[Attached file: list.md]", "m", emptyList(),
            files = listOf(ChatRepository.NewAttachment(AttachmentKind.File, "list.md", "text/markdown", 3, path)))
        return repo.observe(cid).first().last().attachments.single()
    }

    private suspend fun row(id: Long) = db.attachmentDao().byId(id)!!

    @Test fun keepsTheNewestTenEachWayAndResendsDontCount(): Unit = runBlocking {
        val owner = (1..12).map { ownerPhoto() }
        val edith = (1..3).map { edithFile() }
        repo.addAssistantMessage(cid, "again", "m", emptyList(), resends = listOf(owner.last()))   // a resend: not counted

        assertEquals(2, retention.wouldRemove(10))
        assertEquals(2, retention.apply())   // the default, 10

        owner.take(2).forEach { a ->
            val r = row(a.id)
            assertNull(r.storedPath, "oldest removed")
            assertNotNull(r.removedAt)
            assertNull(file(a.storedPath), "file gone")
        }
        owner.drop(2).forEach { assertNotNull(file(row(it.id).storedPath)) }
        edith.forEach { assertNotNull(file(row(it.id).storedPath), "Edith's are counted apart (O1)") }
        assertEquals(13, store.storedFiles().size)
    }

    @Test fun aLowerNumberPrunesAtOnce(): Unit = runBlocking {
        val owner = (1..5).map { ownerPhoto() }
        val edith = (1..2).map { edithFile() }
        assertEquals(4 + 1, retention.wouldRemove(1))
        assertEquals(5, retention.prune(1))
        assertNotNull(file(row(owner.last().id).storedPath))
        assertNotNull(file(row(edith.last().id).storedPath))
        assertEquals(2, store.storedFiles().size)
    }

    @Test fun keepingNoneUsesSessionFilesThatGoAtTheNextStart(): Unit = runBlocking {
        settings.state.value = settings.state.value.copy(keepRecentAttachments = 0)
        val session = ownerPhoto(session = true)
        assertTrue(PathAttachmentStore.isSession(session.storedPath))
        assertEquals(0, retention.apply(), "this run's session files stay")
        assertNotNull(file(session.storedPath))

        retention.onStart()   // the next run
        assertNull(file(session.storedPath))
        val r = row(session.id)
        assertNull(r.storedPath)
        assertNotNull(r.removedAt)
    }

    @Test fun orphanedFilesAreDeletedAtStart(): Unit = runBlocking {
        val kept = ownerPhoto()
        val orphan = store.write("lost.md", "nobody points here", session = false)
        File(File(work, "files/attachments"), "half.md.tmp").writeText("x")
        retention.onStart()
        assertNull(file(orphan))
        assertNotNull(file(kept.storedPath))
        assertEquals(1, File(work, "files/attachments").listFiles()!!.size)
    }

    @Test fun deletingAChatOrAMessageDeletesItsFiles(): Unit = runBlocking {
        val file = edithFile()
        val message = repo.observe(cid).first().last().message
        repo.deleteMessage(message.id)   // e.g. Regenerate
        assertNull(file(file.storedPath))

        val other = repo.createConversation()
        val photo = ownerPhoto(chat = other)
        repo.deleteConversation(other)
        assertNull(file(photo.storedPath))
        assertTrue(store.storedFiles().isEmpty())
    }

    @Test fun deleteAllRemovesEveryFileAndUsageCountsWhatsLeft(): Unit = runBlocking {
        repeat(3) { ownerPhoto() }
        edithFile()
        assertEquals(Retention.Usage(4, 12), retention.usage())   // 3 photos × 3 bytes + "- a"
        assertEquals(4, retention.deleteAll())
        assertEquals(Retention.Usage(0, 0), retention.usage())
        assertTrue(db.attachmentDao().allStoredPaths().isEmpty())
    }

    @Test fun retentionCountsAcrossChats(): Unit = runBlocking {
        val other = repo.createConversation()
        val first = ownerPhoto(chat = other)
        repeat(10) { ownerPhoto() }
        assertEquals(1, retention.apply())
        assertNull(file(first.storedPath), "the oldest, in another chat, went")
        assertEquals(AttachmentDirection.FromUser, row(first.id).direction)
    }
}
