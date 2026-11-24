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
import androidx.core.net.toUri
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.data.db.AttachmentKind
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.edith.app.attachments.AttachmentLimits.withExtension
import kotlin.math.max

/**
 * Prepares what the owner picked (C15 §2), off the main thread:
 * - **Images** are decoded upright (EXIF rotation applied), scaled so the longest side is at most [MAX_SIDE] px, and
 *   saved as JPEG q[JPEG_QUALITY].
 * - **Audio** in WAV or MP3 is kept as it is: they're the only formats Ollama accepts (03 §5). Anything else is
 *   decoded to 16-bit mono WAV ([AudioToWav]). At most [MAX_AUDIO_MS].
 *
 * Files go to [pendingDir] (`cacheDir/pending/`), which is emptied at startup.
 */
class AndroidAttachmentIntake(
    private val resolver: ContentResolver,
    private val pendingDir: File,
    private val dispatchers: AppDispatchers,
) : AttachmentIntake {
    override suspend fun image(uri: String): AttachmentIntake.Result = withContext(dispatchers.io) {
        val uri = uri.toUri()
        val name = displayName(uri, "image.jpg")
        try {
            val bitmap = decodeUpright(uri) ?: return@withContext AttachmentIntake.Result.Refused("$name isn't an image Edith can open.")
            val file = newFile("jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            Log.d(LogTag.Chat, "Image prepared: ${bitmap.width}×${bitmap.height}, ${file.length() / 1024} KB")
            bitmap.recycle()
            AttachmentIntake.Result.Ready(PendingAttachment(AttachmentKind.Image, name.withExtension("jpg"), "image/jpeg", file.path, file.length()))
        } catch (e: Exception) {
            Log.w(LogTag.Chat, "Couldn't prepare an image", e)
            AttachmentIntake.Result.Refused("$name couldn't be opened as an image.")
        } catch (e: OutOfMemoryError) {
            Log.w(LogTag.Chat, "Image too large to prepare", e)
            AttachmentIntake.Result.Refused("$name is too large to attach.")
        }
    }

    override suspend fun audio(uri: String): AttachmentIntake.Result = withContext(dispatchers.io) {
        val uri = uri.toUri()
        val name = displayName(uri, "audio.wav")
        try {
            val probe = probeAudio(uri)
                ?: return@withContext AttachmentIntake.Result.Refused("$name isn't an audio file Edith can open.")
            val durationMs = probe.durationMs
            if (durationMs > MAX_AUDIO_MS) {
                return@withContext AttachmentIntake.Result.Refused("$name is ${AttachmentLimits.minutes(durationMs)} long; audio can be up to 5 minutes.")
            }
            // The format from the content itself where possible: a missing name falls back to "audio.wav".
            val type = resolver.getType(uri)?.takeIf { it.startsWith("audio/") } ?: probe.mimeType.orEmpty()
            val passthrough = AttachmentLimits.audioPassthrough(type, name)
            val file: File
            val outName: String
            val mime: String
            if (passthrough != null) {
                file = newFile(passthrough)
                resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ?: return@withContext AttachmentIntake.Result.Refused("$name couldn't be read.")
                outName = name
                mime = if (passthrough == "wav") "audio/wav" else "audio/mpeg"
            } else {
                file = newFile("wav")
                resolver.openFileDescriptor(uri, "r")?.use { AudioToWav.convert(it.fileDescriptor, file) }
                    ?: return@withContext AttachmentIntake.Result.Refused("$name couldn't be read.")
                outName = name.withExtension("wav")
                mime = "audio/wav"
                Log.d(LogTag.Chat, "Audio converted to WAV: ${type.ifEmpty { "unknown type" }}, ${durationMs} ms, ${file.length() / 1024} KB")
            }
            AttachmentIntake.Result.Ready(PendingAttachment(AttachmentKind.Audio, outName, mime, file.path, file.length(), durationMs))
        } catch (e: Exception) {
            Log.w(LogTag.Chat, "Couldn't prepare an audio file", e)
            AttachmentIntake.Result.Refused("$name couldn't be converted to audio Edith can send.")
        }
    }

    /** Deletes a prepared file that won't be sent (removed from the input row). */
    override fun discard(attachment: PendingAttachment) {
        attachment.file.delete()
    }

    /** Called once at startup: anything here is from a previous run. */
    override fun clearLeftovers() {
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
        private const val MAX_SIDE = AttachmentLimits.MAX_SIDE
        private const val JPEG_QUALITY = AttachmentLimits.JPEG_QUALITY
        private const val MAX_AUDIO_MS = AttachmentLimits.MAX_AUDIO_MS

        private fun scaled(w: Int, h: Int) = AttachmentLimits.scaled(w, h)

        fun exifRotation(exif: ExifInterface): Int = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }
}
