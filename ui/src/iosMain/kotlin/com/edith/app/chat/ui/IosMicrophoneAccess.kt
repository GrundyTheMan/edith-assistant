@file:OptIn(ExperimentalForeignApi::class)

package com.edith.app.chat.ui

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The iPhone's microphone permission (K4.3): the system's own question the first time, then its remembered answer. The answer comes on a background
 * thread, so it is passed to the main one. `NSMicrophoneUsageDescription` in `iosApp/project.yml` is the text the system shows; without it iOS
 * ends the app the moment it asks. Written without a Mac to try it on: the macOS jobs compile it, the owner's phone is the first real run.
 */
class IosMicrophoneAccess : MicrophoneAccess {
    override fun request(onResult: (granted: Boolean) -> Unit) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted ->
            dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
        }
    }

    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}
