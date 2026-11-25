@file:OptIn(ExperimentalForeignApi::class)

package com.edith.app.chat.ui

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_group_create
import platform.darwin.dispatch_group_enter
import platform.darwin.dispatch_group_leave
import platform.darwin.dispatch_group_notify

/**
 * The iPhone's photo picker (K4.3): `PHPickerViewController`, which runs outside the app and needs no permission. The system deletes the file it
 * hands over as soon as the loading callback returns, so each is copied into a folder of its own in the temporary directory first; the intake
 * reads that copy and deletes it. Written without a Mac to try it on: the macOS jobs compile it, the owner's phone is the first real run.
 */
class IosPhotoPicker : PhotoPicker {
    /** The picker keeps its delegate weakly, so it is held here until the picker has finished. */
    private var delegate: PickerDelegate? = null

    override fun pick(limit: Int, onPicked: (List<String>) -> Unit) {
        val configuration = PHPickerConfiguration().apply {
            selectionLimit = limit.toLong()
            filter = PHPickerFilter.imagesFilter
        }
        val picker = PHPickerViewController(configuration = configuration)
        val d = PickerDelegate { paths -> delegate = null; onPicked(paths) }
        delegate = d
        picker.delegate = d
        val top = topViewController()
        if (top == null) { delegate = null; onPicked(emptyList()); return }
        top.presentViewController(picker, animated = true, completion = null)
    }

    private fun topViewController(): UIViewController? {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}

private class PickerDelegate(private val done: (List<String>) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val results = didFinishPicking.filterIsInstance<PHPickerResult>()
        if (results.isEmpty()) { done(emptyList()); return }
        val copies = arrayOfNulls<String>(results.size)
        val group = dispatch_group_create()
        results.forEachIndexed { index, result ->
            dispatch_group_enter(group)
            result.itemProvider.loadFileRepresentationForTypeIdentifier("public.image") { url, _ ->
                copies[index] = url?.let(::copyAway)
                dispatch_group_leave(group)
            }
        }
        dispatch_group_notify(group, dispatch_get_main_queue()) { done(copies.filterNotNull()) }
    }

    /** A copy of the system's temporary file, in a folder of its own, as a file address (the original is gone when the callback returns). */
    private fun copyAway(url: NSURL): String? {
        val folder = NSTemporaryDirectory() + "picked-" + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        if (!files.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = null, error = null)) return null
        val target = NSURL.fileURLWithPath(folder + "/" + (url.lastPathComponent ?: "Photo.jpg"))
        return if (files.copyItemAtURL(url, toURL = target, error = null)) target.absoluteString else null
    }
}
