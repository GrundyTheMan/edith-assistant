@file:OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class, BetaInteropApi::class)

package com.edith.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitViewController
import com.edith.app.blink.hls.HlsLiveSession
import com.edith.app.blink.hls.LocalHlsServer
import com.edith.app.home.StreamLiveFeed
import com.edith.app.home.ui.LiveUi
import com.edith.app.ring.PeerLiveFeed
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.currentItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL

/**
 * The live view's dialog on the iPhone (K4.17): the camera's picture while it runs, or why there is none. Closing it stops the stream, so the camera and the
 * radio are not left running. Only Blink's stream feeds exist on the iPhone; Ring's live view needs WebRTC, which is not here.
 */
@Composable
fun IosLiveDialog(live: LiveUi, onClose: () -> Unit) {
    val feed = live.feed
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${live.name} — live", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                when {
                    live.error != null -> Text(live.error!!)
                    live.endedBecause != null -> Text(live.endedBecause!!)
                    feed == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    feed is StreamLiveFeed -> IosLivePlayer(feed)
                    feed is PeerLiveFeed -> IosRtcPlayer(feed)
                    else -> Text("Live view for this camera isn't available on this phone yet.")
                }
                Text(
                    "Battery cameras limit how long a live view lasts.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/**
 * Plays a [StreamLiveFeed] with Apple's player, as HLS made from the feed's transport stream and served from this phone's own loopback address (K4.17).
 * Under the picture it shows what the camera sent and what the player asked for and said, because this has not run on a phone before: when the picture
 * does not come, those lines say which part failed. Written without a Mac to try it on: CI compiles it; the owner's phone is the first real run.
 */
@Composable
private fun IosLivePlayer(feed: StreamLiveFeed) {
    val session = remember(feed) { HlsLiveSession(feed) }
    val server = remember(session) { LocalHlsServer(session) }
    val started = remember(server) { server.start() }
    var report by remember { mutableStateOf("") }
    if (!started) {
        Text("Edith couldn't start the player's local connection on this phone.")
        DisposableEffect(session) { onDispose { session.close() } }
        return
    }
    val player = remember(server) {
        val asset = AVURLAsset.URLAssetWithURL(NSURL.URLWithString("http://127.0.0.1:${server.port}/live.m3u8")!!, null)
        AVPlayer(playerItem = AVPlayerItem(asset = asset))
    }
    DisposableEffect(player) {
        val audio = AVAudioSession.sharedInstance()
        audio.setCategory(AVAudioSessionCategoryPlayback, null)
        audio.setActive(true, null)
        player.play()
        onDispose {
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            server.stop()
            session.close()
            audio.setActive(false, null)
        }
    }
    LaunchedEffect(player) {
        while (true) {
            val item = player.currentItem
            val error = item?.error
            val line = when {
                error != null -> "Player: ${error.localizedDescription} (${error.domain} ${error.code})"
                item?.status == AVPlayerItemStatusReadyToPlay -> "Player: ready"
                else -> "Player: waiting"
            }
            report = "${session.status()}\n$player\nAsked for: ${server.requests().ifEmpty { "nothing yet" }}"
            delay(500)
        }
    }
    UIKitViewController(
        factory = { AVPlayerViewController().also { it.player = player } },
        modifier = Modifier.fillMaxWidth().height(220.dp),
    )
    Text(report, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

/**
 * Ring's live picture (K4.18): the WebRTC view Swift made for this call. Only Ring has this kind of feed; ending the dialog ends the call through the feed.
 * Under the picture: the connection's own words (state, candidates, Ring's answer, the picture's size), because this has not run on a phone before: when no picture comes, they say which step stopped.
 */
@Composable
private fun IosRtcPlayer(feed: PeerLiveFeed) {
    val bridged = feed.peer as? BridgedRingPeer
    if (bridged == null) {
        Text("Live view for this camera isn't available on this phone yet.")
        return
    }
    var report by remember { mutableStateOf("") }
    LaunchedEffect(bridged) {
        while (true) {
            report = listOfNotNull(bridged.status().ifEmpty { null }, feed.peer.lost.value?.let { "Lost: $it" }).joinToString("\n")
            delay(500)
        }
    }
    UIKitView(
        factory = { bridged.swift.makeVideoView() },
        modifier = Modifier.fillMaxWidth().height(220.dp),
    )
    if (report.isNotEmpty()) Text(report, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}
