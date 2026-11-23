package com.edith.app.attachments

import com.edith.app.data.db.AttachmentKind
import com.edith.app.data.chat.StoredFiles

/** A prepared attachment waiting in the input row (C15 §2): its file (at [path]) is in the cache until it's sent. */
data class PendingAttachment(
    val kind: AttachmentKind,
    val fileName: String,
    val mimeType: String,
    val path: String,
    val sizeBytes: Long,
    /** Audio only. */
    val durationMs: Long? = null,
)

/** What the chat engine asks of wherever attachment files are kept (C15, C16): the rest of the store stays platform code. */
interface AttachmentWriter : StoredFiles {
    /** Takes over [pending]'s file. Returns its `stored_path`. */
    fun keep(pending: PendingAttachment, session: Boolean): String
    /** Removes the prepared file of an attachment that was not kept. */
    fun discard(pending: PendingAttachment)
    /** Writes one of Edith's files (C16 §3). Returns its `stored_path`. */
    fun write(fileName: String, content: String, session: Boolean): String
    /** Writes a picture a tool brought back (C25: a camera snapshot). Returns its `stored_path`. */
    fun writeBytes(fileName: String, bytes: ByteArray, session: Boolean): String
}
