@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.edith.app.attachments

import com.edith.app.attachments.AttachmentLimits.withExtension
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.data.db.AttachmentKind
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.AVFAudio.AVAudioFile
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/**
 * Prepares what the owner picked on the iPhone (K4.3; the rules are Android's, in [AttachmentLimits]): an image is decoded upright (drawing a
 * `UIImage` applies its orientation), scaled so the longest side is at most [AttachmentLimits.MAX_SIDE] and saved as JPEG. The picked item is a file
 * address the photo picker's copy left in the temporary folder; it is deleted once prepared. Audio files come from the Files app (K4.3).
 * Files go to [pendingDir], which is emptied at startup.
 *
 * Written without a Mac to try it on: the macOS jobs compile it, and the owner's phone is the first real run. A very large photo is decoded in
 * full before it is scaled (about 190 MB for 48 megapixels); an ImageIO thumbnail would avoid that and is the next step if it proves a problem.
 */
class IosAttachmentIntake(private val pendingDir: String, private val dispatchers: AppDispatchers) : AttachmentIntake {
    private val files = NSFileManager.defaultManager

    override suspend fun image(uri: String): AttachmentIntake.Result = withContext(dispatchers.io) {
        val source = NSURL.URLWithString(uri)?.path ?: uri.removePrefix("file://")
        val name = NSURL.fileURLWithPath(source).lastPathComponent?.takeIf { it.isNotBlank() } ?: "Photo.jpg"
        try {
            val original = UIImage.imageWithContentsOfFile(source)
                ?: return@withContext AttachmentIntake.Result.Refused("$name isn't an image Edith can open.")
            val (width, height) = original.size.useContents { width to height }
            if (width < 1 || height < 1) return@withContext AttachmentIntake.Result.Refused("$name isn't an image Edith can open.")
            val (w, h) = AttachmentLimits.scaled(width.toInt(), height.toInt())
            val format = UIGraphicsImageRendererFormat.defaultFormat().apply { scale = 1.0 }   // pixels, not points
            val renderer = UIGraphicsImageRenderer(size = CGSizeMake(w.toDouble(), h.toDouble()), format = format)
            val upright = renderer.imageWithActions { _ -> original.drawInRect(CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble())) }
            val jpeg = UIImageJPEGRepresentation(upright, AttachmentLimits.JPEG_QUALITY / 100.0)
                ?: return@withContext AttachmentIntake.Result.Refused("$name couldn't be opened as an image.")
            files.createDirectoryAtPath(pendingDir, withIntermediateDirectories = true, attributes = null, error = null)
            val path = "$pendingDir/${NSUUID().UUIDString}.jpg"
            if (!jpeg.writeToFile(path, atomically = true)) return@withContext AttachmentIntake.Result.Refused("$name couldn't be saved to attach.")
            Log.d(LogTag.Chat, "Image prepared: ${w}×$h, ${jpeg.length.toLong() / 1024} KB")
            AttachmentIntake.Result.Ready(PendingAttachment(AttachmentKind.Image, name.withExtension("jpg"), "image/jpeg", path, jpeg.length.toLong()))
        } catch (e: Exception) {
            Log.w(LogTag.Chat, "Couldn't prepare an image", e)
            AttachmentIntake.Result.Refused("$name couldn't be opened as an image.")
        } finally {
            files.removeItemAtPath(source, error = null)   // the picker's copy is no longer needed
        }
    }

    /**
     * An audio file from the Files app (the document picker's copy, deleted once prepared). WAV and MP3 are kept as they are, the only formats Ollama
     * takes; anything else the system can decode (an iPhone voice memo's .m4a, say) becomes 16 kHz mono WAV. At most 5 minutes (as on Android).
     */
    override suspend fun audio(uri: String): AttachmentIntake.Result = withContext(dispatchers.io) {
        val source = NSURL.URLWithString(uri)?.path ?: uri.removePrefix("file://")
        val name = NSURL.fileURLWithPath(source).lastPathComponent?.takeIf { it.isNotBlank() } ?: "audio.wav"
        try {
            val length = runCatching { AVAudioFile(forReading = NSURL.fileURLWithPath(source), error = null) }.getOrNull()
                ?: return@withContext AttachmentIntake.Result.Refused("$name isn't an audio file Edith can open.")
            val durationMs = (length.length * 1000.0 / length.processingFormat.sampleRate).toLong()
            if (durationMs > AttachmentLimits.MAX_AUDIO_MS) {
                return@withContext AttachmentIntake.Result.Refused("$name is ${AttachmentLimits.minutes(durationMs)} long; audio can be up to 5 minutes.")
            }
            files.createDirectoryAtPath(pendingDir, withIntermediateDirectories = true, attributes = null, error = null)
            val passthrough = AttachmentLimits.audioPassthrough("", name)
            val extension = passthrough ?: "wav"
            val path = "$pendingDir/${NSUUID().UUIDString}.$extension"
            val (outName, mime) = if (passthrough != null) {
                if (!files.copyItemAtPath(source, toPath = path, error = null)) return@withContext AttachmentIntake.Result.Refused("$name couldn't be read.")
                name to if (passthrough == "wav") "audio/wav" else "audio/mpeg"
            } else {
                AudioToWav.convert(source, path) ?: return@withContext AttachmentIntake.Result.Refused("$name couldn't be converted to audio Edith can send.")
                name.withExtension("wav") to "audio/wav"
            }
            val size = (files.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
            AttachmentIntake.Result.Ready(PendingAttachment(AttachmentKind.Audio, outName, mime, path, size, durationMs))
        } catch (e: Exception) {
            Log.w(LogTag.Chat, "Couldn't prepare an audio file", e)
            AttachmentIntake.Result.Refused("$name couldn't be converted to audio Edith can send.")
        } finally {
            files.removeItemAtPath(source, error = null)   // the picker's copy is no longer needed
        }
    }

    override fun discard(attachment: PendingAttachment) {
        files.removeItemAtPath(attachment.path, error = null)
    }

    override fun clearLeftovers() {
        files.removeItemAtPath(pendingDir, error = null)
    }
}
