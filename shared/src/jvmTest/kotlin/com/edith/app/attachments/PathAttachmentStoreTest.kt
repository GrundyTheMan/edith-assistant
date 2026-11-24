package com.edith.app.attachments

import com.edith.app.data.db.AttachmentKind
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K2.3: the attachment store's file handling, on the desktop with the same code the iPhone runs. */
class PathAttachmentStoreTest {
    private val root = Files.createTempDirectory("edith-store").toFile()
    private val files = root.resolve("files").path
    private val cache = root.resolve("cache").path
    private var lengths = 0
    private val store = PathAttachmentStore(files, cache) { lengths++; 4_000L }

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    private fun pending(name: String, bytes: ByteArray): PendingAttachment {
        val f = root.resolve("pending-$name").apply { writeBytes(bytes) }
        return PendingAttachment(AttachmentKind.Image, name, "image/jpeg", f.path, bytes.size.toLong())
    }

    @Test fun aKeptFileMovesIntoTheStoreAndReadsBack() {
        val p = pending("list.jpg", byteArrayOf(1, 2, 3))
        val path = store.keep(p, session = false)
        assertTrue(path.startsWith("attachments/") && path.endsWith(".jpg"), path)
        assertFalse(root.resolve("pending-list.jpg").exists(), "the prepared file was taken")
        assertTrue(store.exists(path))
        assertEquals(listOf<Byte>(1, 2, 3), store.readBytes(path)?.toList())
        assertEquals(listOf(StoredFile(path, 3)), store.storedFiles())
    }

    @Test fun writtenTextAndPicturesAreStoredAndSessionFilesGoAtStartup() {
        val note = store.write("note.md", "- towel", session = false)
        val shot = store.writeBytes("door.jpg", byteArrayOf(9), session = true)
        assertEquals("- towel", store.readText(note))
        assertTrue(PathAttachmentStore.isSession(shot) && store.exists(shot))
        assertEquals(listOf(note), store.storedFiles().map { it.path }, "session files are not counted")
        store.clearSessionAndTemp()
        assertFalse(store.exists(shot))
        assertTrue(store.exists(note))
    }

    @Test fun deletingARemovedOrUnknownFileIsQuiet() {
        val path = store.write("a.txt", "x", session = false)
        store.delete(path)
        store.delete(path)
        store.delete(null)
        assertFalse(store.exists(path))
        assertNull(store.readBytes(path))
    }

    @Test fun aHalfWrittenFileIsNotCountedAndIsCleared() {
        store.write("a.txt", "x", session = false)
        root.resolve("files/attachments/half.tmp").writeText("partial")
        assertEquals(1, store.storedFiles().size)
        store.clearSessionAndTemp()
        assertFalse(root.resolve("files/attachments/half.tmp").exists())
    }

    @Test fun anAudioLengthIsReadOnceAndRemembered() {
        val path = store.write("memo.wav", "x", session = false)
        assertEquals(4_000L, store.durationMs(path))
        assertEquals(4_000L, store.durationMs(path))
        assertEquals(1, lengths)
        assertNull(store.durationMs("attachments/gone.wav"))
    }
}
