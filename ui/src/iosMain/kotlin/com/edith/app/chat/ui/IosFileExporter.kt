@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.edith.app.chat.ui

import com.edith.app.attachments.ReplyFormat
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController

/**
 * Edith's file cards on the iPhone (C16 §4): View reads the stored file as text, Save opens the Files app's picker to keep a copy, Share opens the share sheet.
 * Both give a **copy under the file's own name** (the stored file has a random one): the copies live in a temporary `share` folder that is emptied each time, as Android's `FileExport` does.
 * Written without a Mac to try it on: the macOS jobs compile it, the owner's phone is the first real run.
 */
class IosFileExporter : FileExporter {
    override fun readText(path: String): String? =
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) else null

    override fun save(path: String, name: String) {
        val url = copyForExport(path, name) ?: return
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    override fun share(path: String, name: String) {
        val url = copyForExport(path, name) ?: return
        val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
        topViewController()?.presentViewController(sheet, animated = true, completion = null)
    }

    /** A copy of [path] named [name] in a fresh temporary folder (the folder is emptied first); null when it can't be made. */
    private fun copyForExport(path: String, name: String): NSURL? {
        val files = NSFileManager.defaultManager
        if (!files.fileExistsAtPath(path)) return null
        val dir = NSTemporaryDirectory() + "share"
        files.removeItemAtPath(dir, error = null)
        files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val target = "$dir/${ReplyFormat.sanitize(name)}"
        if (!files.copyItemAtPath(path, toPath = target, error = null)) return null
        return NSURL.fileURLWithPath(target)
    }

    private fun topViewController(): UIViewController? {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}
