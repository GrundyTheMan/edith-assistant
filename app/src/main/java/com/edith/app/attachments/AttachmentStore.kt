package com.edith.app.attachments

import android.media.MediaMetadataRetriever
import java.io.File

/** The prepared attachment's file. */
val PendingAttachment.file: File get() = File(path)

/** [AttachmentFiles] with the files themselves, for what plays, shows or shares them (C16). */
interface AttachmentStore : AttachmentFiles {
    /** The file, or null if it's gone (removed, or a session file from an earlier run). */
    fun file(storedPath: String?): File?
}

/** [AttachmentStore] that keeps attachments as files in the app's private storage (C16): the shared store plus Android's audio length. */
class FileAttachmentStore private constructor(private val inner: PathAttachmentStore) : AttachmentStore, AttachmentFiles by inner {
    constructor(filesDir: File, cacheDir: File) : this(PathAttachmentStore(filesDir.path, cacheDir.path, ::audioLengthMs))

    override fun file(storedPath: String?): File? = storedPath?.let { File(inner.resolve(it)) }?.takeIf { it.isFile }

    companion object {
        const val DURABLE = PathAttachmentStore.DURABLE
        const val SESSION = PathAttachmentStore.SESSION

        fun isSession(storedPath: String?): Boolean = PathAttachmentStore.isSession(storedPath)
    }
}

private fun audioLengthMs(path: String): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (e: RuntimeException) {
        null
    } finally {
        retriever.release()
    }
}
