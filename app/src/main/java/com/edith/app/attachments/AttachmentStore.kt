package com.edith.app.attachments

import android.media.MediaMetadataRetriever
import com.edith.app.core.log.Log
import com.edith.app.data.chat.StoredFiles
import com.edith.app.core.log.LogTag
import com.edith.app.data.db.AttachmentKind
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A prepared attachment waiting in the input row (C15 §2): its file is in `cacheDir/pending/` until it's sent. */
data class PendingAttachment(
    val kind: AttachmentKind,
    val fileName: String,
    val mimeType: String,
    val file: File,
    val sizeBytes: Long,
    /** Audio only. */
    val durationMs: Long? = null,
)

/**
 * Where attachments' files live (C16 §1). Each is named by a `stored_path` kept on its row:
 * - `attachments/<uuid>.<ext>` under `filesDir`: kept until retention removes it.
 * - `session/<uuid>.<ext>` under `cacheDir`: when the owner keeps 0, files last until the app next starts.
 */
interface AttachmentStore : StoredFiles {
    /** Takes over [pending]'s file. Returns its `stored_path`. */
    fun keep(pending: PendingAttachment, session: Boolean): String
    /** Writes one of Edith's files (C16 §3). Returns its `stored_path`. */
    fun write(fileName: String, content: String, session: Boolean): String
    /** Writes a picture a tool brought back (C25: a camera snapshot). Returns its `stored_path`. */
    fun writeBytes(fileName: String, bytes: ByteArray, session: Boolean): String = throw IOException("This store can't keep pictures")
    /** The file, or null if it's gone (removed, or a session file from an earlier run). */
    fun file(storedPath: String?): File?
    /** An audio file's length, read once and remembered. */
    fun durationMs(storedPath: String?): Long?
    override fun delete(storedPath: String?)
}

/** [AttachmentStore] that keeps attachments as files in the app's private storage (C16). */
class FileAttachmentStore(private val filesDir: File, private val cacheDir: File) : AttachmentStore {
    private val durations = ConcurrentHashMap<String, Long>()

    override fun keep(pending: PendingAttachment, session: Boolean): String {
        val path = newPath(pending.fileName, session)
        val target = resolve(path)
        // Moved, or copied through a .tmp and renamed, so a half-written file never has the real name.
        if (!pending.file.renameTo(target)) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            pending.file.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(target)) throw IOException("Couldn't store ${pending.fileName}")
            pending.file.delete()
        }
        pending.durationMs?.let { durations[path] = it }
        return path
    }

    override fun write(fileName: String, content: String, session: Boolean): String {
        val path = newPath(fileName, session)
        val target = resolve(path)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) throw IOException("Couldn't store $fileName")
        return path
    }

    override fun writeBytes(fileName: String, bytes: ByteArray, session: Boolean): String {
        val path = newPath(fileName, session)
        val target = resolve(path)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) throw IOException("Couldn't store $fileName")
        return path
    }

    override fun file(storedPath: String?): File? = storedPath?.let(::resolve)?.takeIf { it.isFile }

    override fun durationMs(storedPath: String?): Long? {
        val path = storedPath ?: return null
        durations[path]?.let { return it }
        val f = file(path) ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(f.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.also { durations[path] = it }
        } catch (e: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    override fun delete(storedPath: String?) {
        val path = storedPath ?: return
        durations.remove(path)
        if (resolve(path).let { it.exists() && !it.delete() }) Log.w(LogTag.Chat, "Couldn't delete an attachment file")
    }

    /** Every stored file (not session ones), for orphan cleanup and the usage line in Settings. */
    fun storedFiles(): List<Pair<String, File>> =
        File(filesDir, DURABLE).listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }.map { "$DURABLE/${it.name}" to it }

    /** At startup: last run's session files and any half-written ones are gone. */
    fun clearSessionAndTemp() {
        File(cacheDir, SESSION).listFiles()?.forEach { it.delete() }
        File(filesDir, DURABLE).listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun newPath(fileName: String, session: Boolean): String {
        val ext = fileName.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        val dir = if (session) SESSION else DURABLE
        resolve(dir).mkdirs()
        return "$dir/${UUID.randomUUID()}.$ext"
    }

    private fun resolve(path: String): File = if (path.startsWith("$SESSION/") || path == SESSION) File(cacheDir, path) else File(filesDir, path)

    companion object {
        const val DURABLE = "attachments"
        const val SESSION = "session"

        fun isSession(storedPath: String?): Boolean = storedPath?.startsWith("$SESSION/") == true
    }
}
