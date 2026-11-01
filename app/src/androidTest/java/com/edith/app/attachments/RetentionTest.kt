package com.edith.app.attachments

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C16 §2 with real Room and real files: retention per direction (O1), resends not counted, a lower setting, keep = 0
 * (session files), orphans, deleting chats and messages, and Save/Share's file handling.
 */
@RunWith(AndroidJUnit4::class)
class RetentionTest {
    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val work = File(app.cacheDir, "retention-test").apply { deleteRecursively(); mkdirs() }
    private val store = FileAttachmentStore(File(work, "files"), File(work, "cache"))
    private val settings = MemorySettings()
    private val dispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default)
    private lateinit var db: EdithDatabase
    private lateinit var repo: ChatRepository
    private lateinit var retention: Retention
    private var cid = 0L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, EdithDatabase::class.java).build()
        val clock = TickingClock()
        repo = ChatRepository(db, clock, dispatchers, store)
        retention = Retention(db.attachmentDao(), store, settings, clock, dispatchers)
        cid = runBlocking { repo.createConversation() }
    }

    @After fun tearDown() {
        db.close()
        work.deleteRecursively()
    }

    private var n = 0
    private fun pending(): PendingAttachment {
        val f = File(work, "p${n++}.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return PendingAttachment(AttachmentKind.Image, f.name, "image/jpeg", f, 3)
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
            assertNull("oldest removed", r.storedPath)
            assertNotNull(r.removedAt)
            assertNull("file gone", store.file(a.storedPath))
        }
        owner.drop(2).forEach { assertNotNull(store.file(row(it.id).storedPath)) }
        edith.forEach { assertNotNull("Edith's are counted apart (O1)", store.file(row(it.id).storedPath)) }
        assertEquals(13, store.storedFiles().size)
    }

    @Test fun aLowerNumberPrunesAtOnce(): Unit = runBlocking {
        val owner = (1..5).map { ownerPhoto() }
        val edith = (1..2).map { edithFile() }
        assertEquals(4 + 1, retention.wouldRemove(1))
        assertEquals(5, retention.prune(1))
        assertNotNull(store.file(row(owner.last().id).storedPath))
        assertNotNull(store.file(row(edith.last().id).storedPath))
        assertEquals(2, store.storedFiles().size)
    }

    @Test fun keepingNoneUsesSessionFilesThatGoAtTheNextStart(): Unit = runBlocking {
        settings.state.value = settings.state.value.copy(keepRecentAttachments = 0)
        val session = ownerPhoto(session = true)
        assertTrue(FileAttachmentStore.isSession(session.storedPath))
        assertEquals("this run's session files stay", 0, retention.apply())
        assertNotNull(store.file(session.storedPath))

        retention.onStart()   // the next run
        assertNull(store.file(session.storedPath))
        val r = row(session.id)
        assertNull(r.storedPath)
        assertNotNull(r.removedAt)
    }

    @Test fun orphanedFilesAreDeletedAtStart(): Unit = runBlocking {
        val kept = ownerPhoto()
        val orphan = store.write("lost.md", "nobody points here", session = false)
        File(File(work, "files/attachments"), "half.md.tmp").writeText("x")
        retention.onStart()
        assertNull(store.file(orphan))
        assertNotNull(store.file(kept.storedPath))
        assertEquals(1, File(work, "files/attachments").listFiles()!!.size)
    }

    @Test fun deletingAChatOrAMessageDeletesItsFiles(): Unit = runBlocking {
        val file = edithFile()
        val message = repo.observe(cid).first().last().message
        repo.deleteMessage(message.id)   // e.g. Regenerate
        assertNull(store.file(file.storedPath))

        val other = repo.createConversation()
        val photo = ownerPhoto(chat = other)
        repo.deleteConversation(other)
        assertNull(store.file(photo.storedPath))
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

    @Test fun saveWritesTheExactBytesAndShareGivesAReadableUri(): Unit = runBlocking {
        val bytes = "café, ñ, 😀\nline 2\n".toByteArray()
        val source = File(work, "src.md").apply { writeBytes(bytes) }
        val target = File(work, "saved.md")
        assertTrue(FileExport.save(app, source, Uri.fromFile(target)))
        assertTrue(target.readBytes().contentEquals(bytes))

        val uri = FileExport.shareUri(app, source, "beach list.md")
        assertEquals("content", uri.scheme)
        assertEquals(app.packageName + FileExport.PROVIDER_SUFFIX, uri.authority)
        assertTrue(uri.path!!.endsWith("beach-list.md"))
        val shared = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue(shared.contentEquals(bytes))
    }

    @Test fun retentionCountsAcrossChats(): Unit = runBlocking {
        val other = repo.createConversation()
        val first = ownerPhoto(chat = other)
        repeat(10) { ownerPhoto() }
        assertEquals(1, retention.apply())
        assertNull("the oldest, in another chat, went", store.file(first.storedPath))
        assertEquals(AttachmentDirection.FromUser, row(first.id).direction)
    }
}
