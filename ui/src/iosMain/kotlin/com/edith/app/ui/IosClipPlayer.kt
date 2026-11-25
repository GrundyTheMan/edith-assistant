@file:OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)

package com.edith.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL

/**
 * Plays one clip on the iPhone (K3.3, K4.15) with Apple's own player and its controls, streaming from the address the service just gave (they expire).
 * [headers] go with every request (Blink's clips need its sign-in). The sound plays with the silent switch on, as a recording should, and the player is
 * let go when the dialog closes, so nothing keeps playing behind it. Written without a Mac to try it on: CI compiles it; the owner's phone is the first real run.
 */
@Composable
fun IosClipPlayer(url: String, headers: Map<String, String>) {
    val address = remember(url) { NSURL.URLWithString(url) }
    if (address == null) {
        Text("This clip's address couldn't be used.")
        return
    }
    val player = remember(url) {
        val options: Map<Any?, *> = if (headers.isEmpty()) emptyMap<Any?, Any?>() else mapOf("AVURLAssetHTTPHeaderFieldsKey" to headers)
        AVPlayer(playerItem = AVPlayerItem(asset = AVURLAsset.URLAssetWithURL(address, options)))
    }
    DisposableEffect(player) {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)
        player.play()
        onDispose {
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            session.setActive(false, null)
        }
    }
    UIKitViewController(
        factory = { AVPlayerViewController().also { it.player = player } },
        modifier = Modifier.fillMaxWidth().height(220.dp),
    )
}
