package com.edith.app.attachments

/** One stored file: where it is (its `stored_path`) and how big. */
data class StoredFile(val path: String, val sizeBytes: Long)

/**
 * Where attachments' files live and how they are read (C16 §1): everything the engine, the context builder and retention ask of
 * the store, with no `File` in it. Each file is named by a `stored_path` kept on its row:
 * - `attachments/<uuid>.<ext>`: kept until retention removes it.
 * - `session/<uuid>.<ext>`: when the owner keeps 0, files last until the app next starts.
 */
interface AttachmentFiles : AttachmentWriter {
    /** Whether the file is there (it is gone when removed, or when it is a session file from an earlier run). */
    fun exists(storedPath: String?): Boolean
    /** The file's content, or null if it's gone. */
    fun readBytes(storedPath: String?): ByteArray?
    /** The file as text, or null if it's gone. */
    fun readText(storedPath: String?): String? = readBytes(storedPath)?.decodeToString()
    /** An audio file's length, read once and remembered. */
    fun durationMs(storedPath: String?): Long?
    /** Every stored file (not session ones), for orphan cleanup and the usage line in Settings. */
    fun storedFiles(): List<StoredFile>
    /** At startup: last run's session files and any half-written ones are gone. */
    fun clearSessionAndTemp()
}
