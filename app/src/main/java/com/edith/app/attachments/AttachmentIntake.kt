package com.edith.app.attachments

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.data.db.AttachmentKind
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares what the owner picked (C15 §2), off the main thread:
 * - **Images** are decoded upright (EXIF rotation applied), scaled so the longest side is at most [MAX_SIDE] px, and
 *   saved as JPEG q[JPEG_QUALITY].
 * - **Audio** in WAV or MP3 is kept as it is: they're the only formats Ollama accepts (03 §5). Anything else is
 *   decoded to 16-bit mono WAV ([AudioToWav]). At most [MAX_AUDIO_MS].
 *
 * Files go to [pendingDir] (`cacheDir/pending/`), which is emptied at startup.
 */
class AttachmentIntake(
    private val resolver: ContentResolver,
    private val pendingDir: File,
    private val dispatchers: AppDispatchers,
) {
    sealed interface Result {
        data class Ready(val attachment: PendingAttachment) : Result
        /** Why it can't be attached, for the owner. */
        data class Refused(val message: String) : Result
    }

    suspend fun image(uri: Uri): Result = withContext(dispatchers.io) {
        val name = displayName(uri, "image.jpg")
        try {
            val bitmap = decodeUpright(uri) ?: return@withContext Result.Refused("$name isn't an image Edith can open.")
            val file = newFile("jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            Log.d(LogTag.Chat, "Image prepared: ${bitmap.width}×${bitmap.height}, ${file.length() / 1024} KB")
            bitmap.recycle()
            Result.Ready(PendingAttachment(AttachmentKind.Image, name.withExtension("jpg"), "image/jpeg", file, file.length()))
        } catch (e: Exception) {
            Log.w(LogTag.Chat, "Couldn't prepare an image", e)
            Result.Refused("$name couldn't be opened as an image.")
        } catch (e: OutOfMemoryError) {
            Log.w(LogTag.Chat, "Image too large to prepare", e)
            Result.Refused("$name is too large to attach.")
        }
    }

    suspend fun audio(uri: Uri): Result = withContext(dispatchers.io) {
        val name = displayName(uri, "audio.wav")
        try {
            val probe = probeAudio(uri)
                ?: return@withContext Result.Refused("$name isn't an audio file Edith can open.")
            val durationMs = probe.durationMs
            if (durationMs > MAX_AUDIO_MS) {
                return@withContext Result.Refused("$name is ${minutes(durationMs)} long; audio can be up to 5 minutes.")
            }
            // The format from the content itself where possible: a missing name falls back to "audio.wav".
            val type = resolver.getType(uri)?.takeIf { it.startsWith("audio/") } ?: probe.mimeType.orEmpty()
            val passthrough = when {
                type in WAV_TYPES -> "wav"
                type in MP3_TYPES -> "mp3"
                type.isEmpty() && name.endsWith(".wav", ignoreCase = true) -> "wav"
                type.isEmpty() && name.endsWith(".mp3", ignoreCase = true) -> "mp3"
                else -> null
            }
            val file: File
            val outName: String
            val mime: String
            if (passthrough != null) {
                file = newFile(passthrough)
                resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ?: return@withContext Result.Refused("$name couldn't be read.")
                outName = name
                mime = if (passthrough == "wav") "audio/wav" else "audio/mpeg"
            } else {
                file = newFile("wav")
                resolver.openFileDescriptor(uri, "r")?.use { AudioToWav.convert(it.fileDescriptor, file) }
                    ?: return@withContext Result.Refused("$name couldn't be read.")
                outName = name.withExtension("wav")
                mime = "audio/wav"
                Log.d(LogTag.Chat, "Audio converted to WAV: ${type.ifEmpty { "unknown type" }}, ${durationMs} ms, ${file.length() / 1024} KB")
            }
            Result.Ready(PendingAttachment(AttachmentKind.Audio, outName, mime, file, file.length(), durationMs))
        } catch (e: Exception) {
            Log.w(LogTag.Chat, "Couldn't prepare an audio file", e)
            Result.Refused("$name couldn't be converted to audio Edith can send.")
        }
    }

    /** Deletes a prepared file that won't be sent (removed from the input row). */
    fun discard(attachment: PendingAttachment) {
        attachment.file.delete()
    }

    /** Called once at startup: anything here is from a previous run. */
    fun clearLeftovers() {
        pendingDir.listFiles()?.forEach { it.delete() }
    }

    private fun newFile(extension: String): File {
        pendingDir.mkdirs()
        return File(pendingDir, "${UUID.randomUUID()}.$extension")
    }

    private fun displayName(uri: Uri, fallback: String): String =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    private class AudioProbe(val durationMs: Long, val mimeType: String?)

    /** Its length and container type, read before any decoding (C15 §2), or null if it isn't audio. */
    private fun probeAudio(uri: Uri): AudioProbe? {
        val retriever = MediaMetadataRetriever()
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { retriever.setDataSource(it.fileDescriptor) } ?: return null
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") return null
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            AudioProbe(duration, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
        } catch (e: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    /** Decodes [uri] upright and no larger than [MAX_SIDE] on its longest side, or null if it isn't an image. */
    private fun decodeUpright(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF rotation itself.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val (w, h) = scaled(info.size.width, info.size.height)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE   // compress() needs a software bitmap
            }
        }
        // Android 8: sample down while decoding, then scale exactly and rotate by EXIF.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = resolver.openInputStream(uri)?.use { exifRotation(ExifInterface(it)) } ?: 0
        val (w, h) = scaled(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            postScale(w.toFloat() / decoded.width, h.toFloat() / decoded.height)
            postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    companion object {
        const val MAX_SIDE = 1536
        const val JPEG_QUALITY = 85
        const val MAX_AUDIO_MS = 5 * 60 * 1000L
        private val WAV_TYPES = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
        private val MP3_TYPES = setOf("audio/mpeg", "audio/mp3", "audio/mpeg3")

        /** [w]×[h] scaled down so the longest side is at most [MAX_SIDE] (never scaled up). */
        fun scaled(w: Int, h: Int): Pair<Int, Int> {
            val longest = max(w, h)
            if (longest <= MAX_SIDE) return w to h
            val f = MAX_SIDE.toDouble() / longest
            return max(1, (w * f).roundToInt()) to max(1, (h * f).roundToInt())
        }

        fun exifRotation(exif: ExifInterface): Int = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

        /** "receipt.png" → "receipt.jpg". */
        fun String.withExtension(ext: String): String = substringBeforeLast('.', this) + ".$ext"

        private fun minutes(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
