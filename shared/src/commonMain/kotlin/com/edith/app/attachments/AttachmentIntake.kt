package com.edith.app.attachments

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares what the owner picked (C15 §2), off the main thread: images are decoded upright (the orientation applied), scaled so the longest
 * side is at most [AttachmentLimits.MAX_SIDE] pixels and saved as JPEG; audio in WAV or MP3 is kept as it is, anything else is converted to
 * 16-bit mono WAV, and it may be at most [AttachmentLimits.MAX_AUDIO_MS] long. Prepared files wait in a pending folder that is emptied at
 * startup (K2.10: the interface; Android's is `AndroidAttachmentIntake`, the iPhone's is written in K4.3).
 *
 * What was picked is given as an address string: a `content://` or `file://` address on Android, a file address the picker's Swift side
 * copied the item to on the iPhone.
 */
interface AttachmentIntake {
    sealed interface Result {
        data class Ready(val attachment: PendingAttachment) : Result

        /** Why it can't be attached, for the owner. */
        data class Refused(val message: String) : Result
    }

    suspend fun image(uri: String): Result

    suspend fun audio(uri: String): Result

    /** Deletes a prepared file that won't be sent (removed from the input row). */
    fun discard(attachment: PendingAttachment)

    /** Called once at startup: anything left in the pending folder is from a previous run. */
    fun clearLeftovers()
}

/** The rules every platform's intake follows, and the parts of them that are plain arithmetic and text. */
object AttachmentLimits {
    const val MAX_SIDE = 1536
    const val JPEG_QUALITY = 85
    const val MAX_AUDIO_MS = 5 * 60 * 1000L

    private val WAV_TYPES = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
    private val MP3_TYPES = setOf("audio/mpeg", "audio/mp3", "audio/mpeg3")

    /** [w] by [h] scaled down so the longest side is at most [MAX_SIDE] (never scaled up). */
    fun scaled(w: Int, h: Int): Pair<Int, Int> {
        val longest = max(w, h)
        if (longest <= MAX_SIDE) return w to h
        val f = MAX_SIDE.toDouble() / longest
        return max(1, (w * f).roundToInt()) to max(1, (h * f).roundToInt())
    }

    /** "receipt.png" gives "receipt.jpg". */
    fun String.withExtension(ext: String): String = substringBeforeLast('.', this) + ".$ext"

    /**
     * The extension an audio file keeps when it needs no conversion ("wav" or "mp3"), or null when it must be converted. The container type
     * is used when known; a missing type falls back to the file name.
     */
    fun audioPassthrough(mimeType: String, name: String): String? = when {
        mimeType in WAV_TYPES -> "wav"
        mimeType in MP3_TYPES -> "mp3"
        mimeType.isEmpty() && name.endsWith(".wav", ignoreCase = true) -> "wav"
        mimeType.isEmpty() && name.endsWith(".mp3", ignoreCase = true) -> "mp3"
        else -> null
    }

    /** 65_000 ms gives "1:05". */
    fun minutes(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}
