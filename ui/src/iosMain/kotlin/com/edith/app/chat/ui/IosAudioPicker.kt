@file:OptIn(ExperimentalForeignApi::class)

package com.edith.app.chat.ui

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.darwin.NSObject

/**
 * The iPhone's audio file picker (K4.3): the Files app's document picker, set to hand over a **copy** (so the app needs no access to the original and
 * the intake may delete what it gets). Written without a Mac to try it on: the macOS jobs compile it, the owner's phone is the first real run.
 */
class IosAudioPicker : AudioPicker {
    /** The picker keeps its delegate weakly, so it is held here until the picker has finished. */
    private var delegate: Delegate? = null

    override fun pick(onPicked: (String?) -> Unit) {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeAudio), asCopy = true)
        picker.allowsMultipleSelection = false
        val d = Delegate { address -> delegate = null; onPicked(address) }
        delegate = d
        picker.delegate = d
        val top = topViewController()
        if (top == null) { delegate = null; onPicked(null); return }
        top.presentViewController(picker, animated = true, completion = null)
    }

    private fun topViewController(): UIViewController? {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }

    private class Delegate(private val done: (String?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            done((didPickDocumentsAtURLs.firstOrNull() as? NSURL)?.absoluteString)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = done(null)
    }
}
