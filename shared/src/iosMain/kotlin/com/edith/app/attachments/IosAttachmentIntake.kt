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
import platform.Foundation.NSFileManager
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
 * address the photo picker's copy left in the temporary folder; it is deleted once prepared. Audio files are not offered on the iPhone yet.
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

    override suspend fun audio(uri: String): AttachmentIntake.Result =
        AttachmentIntake.Result.Refused("Audio files can't be attached on the iPhone yet.")

    override fun discard(attachment: PendingAttachment) {
        files.removeItemAtPath(attachment.path, error = null)
    }

    override fun clearLeftovers() {
        files.removeItemAtPath(pendingDir, error = null)
    }
}
