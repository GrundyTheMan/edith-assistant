package com.edith.app.printer.feed

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
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
     * A request body that streams [source] and tells [onProgress] how many bytes have gone, never holding more than one buffer in
     * memory. It stops after [size] bytes (a file that grew while it was sent is not sent past what was promised) and is not
     * one-shot-safe to repeat: the stream is read once.
     */
    fun body(source: InputStream, size: Long, type: MediaType?, onProgress: (Long) -> Unit): RequestBody = object : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = size
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            source.source().use { s ->
                while (sent < size) {
                    val read = s.read(sink.buffer, minOf(CHUNK, size - sent))
                    if (read < 0) break
                    sent += read
                    sink.emit()
                    onProgress(sent)
                }
            }
        }
    }

    private const val CHUNK = 64L * 1024
}
