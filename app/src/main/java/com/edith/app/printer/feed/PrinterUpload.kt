package com.edith.app.printer.feed

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.InputStream

/**
 * What may be sent to the printer from the phone (C39 E11, Jarvis spec 14.7). Checked **before** anything is sent, with the same rules
 * Jarvis checks, so the owner gets a plain sentence at once instead of a refusal after a long upload.
 */
internal object PrinterUpload {
    /** The cap of 64 MB (the owner's answer 1, spec part 13): Jarvis answers `413 TOO_LARGE` for more. */
    const val MAX_BYTES = 64L * 1024 * 1024

    /**
     * Jarvis's rule for a name, copied from `UPLOAD_NAME` in its `printerRoutes.ts`: 1 to 100 characters of letters, digits, space and
     * `_ . ( ) + -`, **then** `.gcode` or `.3mf`, in lower case (Jarvis's pattern has no case flag: `.GCODE` is refused there, so here too).
     */
    private val NAME = Regex("^[A-Za-z0-9 _.()+\\-]{1,100}\\.(gcode|3mf)$")

    const val NAME_WORDS = "Only .gcode and .3mf files (the ending in lower case) can be sent, with a name of letters, digits, spaces and _ . ( ) + - up to 100 characters long before the ending."
    const val EMPTY_WORDS = "That file is empty."
    /** Jarvis's `UPLOAD_MAX_BYTES` is 64 × 1024 × 1024 (checked in `printerRoutes.ts`), so the cap is exact, not 64,000,000. */
    const val TOO_BIG_WORDS = "That file is over 64 MB (67,108,864 bytes): too big to send from the phone."
    const val UNREADABLE_WORDS = "Edith couldn't read that file."

    const val SIZE_UNKNOWN_WORDS = "Edith couldn't tell how big that file is, so it can't be sent."

    /** Why [name] of [size] bytes cannot be sent, in plain words; null when it can. A negative size means the file did not say how big it is. */
    fun problem(name: String, size: Long): String? = when {
        !NAME.matches(name) -> NAME_WORDS
        size < 0 -> SIZE_UNKNOWN_WORDS
        size == 0L -> EMPTY_WORDS
        size > MAX_BYTES -> TOO_BIG_WORDS
        else -> null
    }

    /**
     * Copies up to [size] bytes of [source] to [write], one buffer at a time (so never more than one buffer is held), and tells
     * [onProgress] how many have gone. It stops after [size] bytes: a file that grew while it was sent is not sent past what was promised.
     */
    suspend fun copy(source: InputStream, size: Long, onProgress: (Long) -> Unit, write: suspend (ByteArray, Int) -> Unit) {
        var sent = 0L
        val buffer = ByteArray(CHUNK.toInt())
        source.use { s ->
            while (sent < size) {
                val read = s.read(buffer, 0, minOf(CHUNK, size - sent).toInt())
                if (read < 0) break
                write(buffer, read)
                sent += read
                onProgress(sent)
            }
        }
    }

    /** A request body that streams [source] through [copy], with its length declared up front. The stream is read once. */
    fun body(source: InputStream, size: Long, type: ContentType?, onProgress: (Long) -> Unit): OutgoingContent = object : OutgoingContent.WriteChannelContent() {
        override val contentType = type
        override val contentLength = size
        override suspend fun writeTo(channel: ByteWriteChannel) {
            copy(source, size, onProgress) { bytes, count ->
                channel.writeFully(bytes, 0, count)
                channel.flush()
            }
        }
    }

    private const val CHUNK = 64L * 1024
}
