package com.edith.app.attachments

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.data.db.AttachmentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
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

/** Where sent attachments' files live, by attachment row id. C16 makes this durable with retention. */
interface AttachmentStore {
    /** Takes over [pending]'s file for attachment [id]. */
    fun save(id: Long, pending: PendingAttachment)
    /** The file, or null if it's no longer available. */
    fun file(id: Long): File?
    fun durationMs(id: Long): Long?
    /** Bumped on every [save], so screens showing attachments look again. */
    val changes: StateFlow<Int>
}

/**
 * Until C16 (C15 §3): attachments are kept for this run of the app only, the same as `keep_recent_attachments = 0`.
 * Files move from `pending/` to [dir] when sent, so large audio isn't held in memory; [dir] is emptied when the app
 * starts, so after a restart older attachments show as "no longer available".
 */
class SessionAttachmentStore(private val dir: File) : AttachmentStore {
    private data class Entry(val file: File, val durationMs: Long?)
    private val entries = ConcurrentHashMap<Long, Entry>()
    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Called once at startup, off the main thread. */
    fun clearLeftovers() {
        dir.listFiles()?.forEach { it.delete() }
    }

    override fun save(id: Long, pending: PendingAttachment) {
        dir.mkdirs()
        val target = File(dir, "$id-${pending.file.name}")
        val moved = pending.file.renameTo(target) || runCatching { pending.file.copyTo(target, overwrite = true); pending.file.delete() }.isSuccess
        if (moved) {
            entries[id] = Entry(target, pending.durationMs)
            _changes.update { it + 1 }
        } else {
            Log.w(LogTag.Chat, "Couldn't keep attachment $id; it will show as no longer available")
        }
    }

    override fun file(id: Long): File? = entries[id]?.file?.takeIf { it.isFile }

    override fun durationMs(id: Long): Long? = entries[id]?.durationMs
}
