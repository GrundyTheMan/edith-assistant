package com.edith.app.attachments

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.data.chat.StoredFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [AttachmentFiles] that keeps attachments as files under two folders: [filesDir] (durable) and [cacheDir] (session), on every
 * platform. [readDuration] says how long an audio file is (a platform question), given its full path.
 */
@OptIn(ExperimentalUuidApi::class)
class PathAttachmentStore(
    private val filesDir: String,
    private val cacheDir: String,
    private val readDuration: (String) -> Long? = { null },
) : AttachmentFiles {
    private val fs = SystemFileSystem
    private val durations = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** The full path of a stored file. */
    fun resolve(storedPath: String): String =
        if (storedPath.startsWith("$SESSION/") || storedPath == SESSION) Path(cacheDir, storedPath).toString() else Path(filesDir, storedPath).toString()

    override fun keep(pending: PendingAttachment, session: Boolean): String {
        val path = newPath(pending.fileName, session)
        val target = Path(resolve(path))
        val from = Path(pending.path)
        // Moved, or copied through a .tmp and renamed, so a half-written file never has the real name.
        try {
            fs.atomicMove(from, target)
        } catch (e: IOException) {
            val tmp = Path(target.parent!!, target.name + ".tmp")
            fs.source(from).buffered().use { source -> fs.sink(tmp).buffered().use { it.write(source.readByteArray()) } }
            try { fs.atomicMove(tmp, target) } catch (e2: IOException) { throw IOException("Couldn't store ${pending.fileName}") }
            fs.delete(from, mustExist = false)
        }
        pending.durationMs?.let { ms -> durations.update { it + (path to ms) } }
        return path
    }

    override fun discard(pending: PendingAttachment) = fs.delete(Path(pending.path), mustExist = false)

    override fun write(fileName: String, content: String, session: Boolean): String =
        store(fileName, session) { sink -> sink.writeString(content) }

    override fun writeBytes(fileName: String, bytes: ByteArray, session: Boolean): String =
        store(fileName, session) { sink -> sink.write(bytes) }

    private fun store(fileName: String, session: Boolean, write: (kotlinx.io.Sink) -> Unit): String {
        val path = newPath(fileName, session)
        val target = Path(resolve(path))
        val tmp = Path(target.parent!!, target.name + ".tmp")
        fs.sink(tmp).buffered().use(write)
        try { fs.atomicMove(tmp, target) } catch (e: IOException) { throw IOException("Couldn't store $fileName") }
        return path
    }

    override fun exists(storedPath: String?): Boolean = storedPath?.let { isFile(Path(resolve(it))) } == true

    override fun readBytes(storedPath: String?): ByteArray? {
        val path = Path(resolve(storedPath ?: return null))
        if (!isFile(path)) return null
        return try { fs.source(path).buffered().use { it.readByteArray() } } catch (e: IOException) { null }
    }

    override fun durationMs(storedPath: String?): Long? {
        val path = storedPath ?: return null
        durations.value[path]?.let { return it }
        if (!exists(path)) return null
        return readDuration(resolve(path))?.also { ms -> durations.update { it + (path to ms) } }
    }

    override fun delete(storedPath: String?) {
        val path = storedPath ?: return
        durations.update { it - path }
        val full = Path(resolve(path))
        try {
            fs.delete(full, mustExist = false)
        } catch (e: IOException) {
            Log.w(LogTag.Chat, "Couldn't delete an attachment file")
        }
    }

    override fun storedFiles(): List<StoredFile> = listFiles(Path(filesDir, DURABLE))
        .filter { !it.name.endsWith(".tmp") }
        .mapNotNull { f -> fs.metadataOrNull(f)?.takeIf { it.isRegularFile }?.let { StoredFile("$DURABLE/${f.name}", it.size) } }

    override fun clearSessionAndTemp() {
        listFiles(Path(cacheDir, SESSION)).forEach { fs.delete(it, mustExist = false) }
        listFiles(Path(filesDir, DURABLE)).filter { it.name.endsWith(".tmp") }.forEach { fs.delete(it, mustExist = false) }
    }

    private fun listFiles(dir: Path): List<Path> = if (fs.exists(dir)) fs.list(dir).toList() else emptyList()

    private fun isFile(path: Path): Boolean = fs.metadataOrNull(path)?.isRegularFile == true

    private fun newPath(fileName: String, session: Boolean): String {
        val ext = fileName.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        val dir = if (session) SESSION else DURABLE
        fs.createDirectories(Path(resolve(dir)))
        return "$dir/${Uuid.random()}.$ext"
    }

    companion object {
        const val DURABLE = "attachments"
        const val SESSION = "session"

        fun isSession(storedPath: String?): Boolean = storedPath?.startsWith("$SESSION/") == true
    }
}
