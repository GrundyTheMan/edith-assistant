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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import com.edith.app.blink.hls.HlsLiveSession
import com.edith.app.home.StreamLiveFeed
import com.edith.app.home.ui.LiveUi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.resourceLoader
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.QOS_CLASS_USER_INITIATED
import platform.darwin.dispatch_get_global_queue
import platform.posix.usleep

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

private const val SCHEME = "edithlive"

/**
 * Serves one [HlsLiveSession] to Apple's player from memory: the player asks for `edithlive://camera/live.m3u8` and the segments it names, and each request
 * is answered from the session (the playlist waits up to 20 s for the first segment). Answers run on a background queue so waiting never blocks the screen.
 */
private class LiveLoader(private val session: HlsLiveSession) : NSObject(), AVAssetResourceLoaderDelegateProtocol {
    override fun resourceLoader(resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest): Boolean {
        val request = shouldWaitForLoadingOfRequestedResource
        val path = request.request.URL?.path ?: return false
        if (request.request.URL?.scheme != SCHEME) return false
        val (bytes, type) = when {
            path.endsWith(".m3u8") -> {
                var text = session.playlist()
                var waited = 0
                while (text == null && waited < 20_000) { usleep(100_000u); waited += 100; text = session.playlist() }
                (text?.encodeToByteArray()) to "public.m3u-playlist"
            }
            path.endsWith(".ts") -> session.segment(path.substringAfterLast("seg").removeSuffix(".ts").toLongOrNull() ?: -1) to "public.mpeg-2-transport-stream"
            else -> null to ""
        }
        if (bytes == null) {
            request.finishLoadingWithError(NSError.errorWithDomain("EdithLive", 404, null))
            return true
        }
        request.contentInformationRequest?.let {
            it.contentType = type
            it.contentLength = bytes.size.toLong()
            it.byteRangeAccessSupported = false
        }
        request.dataRequest?.let { data ->
            val from = data.requestedOffset.toInt().coerceIn(0, bytes.size)
            val to = if (data.requestedLength > 0) minOf(bytes.size.toLong(), from + data.requestedLength).toInt() else bytes.size
            val slice = bytes.copyOfRange(from, to)
            if (slice.isNotEmpty()) data.respondWithData(slice.usePinned { NSData.create(bytes = it.addressOf(0), length = slice.size.convert()) })
        }
        request.finishLoading()
        return true
    }
}

/** Plays a [StreamLiveFeed] with Apple's player, as HLS made from the feed's transport stream. Written without a Mac to try it on: CI compiles it; the owner's phone is the first real run. */
@Composable
private fun IosLivePlayer(feed: StreamLiveFeed) {
    val session = remember(feed) { HlsLiveSession(feed) }
    val loader = remember(session) { LiveLoader(session) }
    val player = remember(session) {
        val asset = AVURLAsset.URLAssetWithURL(NSURL.URLWithString("$SCHEME://camera/live.m3u8")!!, null)
        asset.resourceLoader.setDelegate(loader, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED.convert(), 0u))
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
            session.close()
            audio.setActive(false, null)
        }
    }
    UIKitViewController(
        factory = { AVPlayerViewController().also { it.player = player } },
        modifier = Modifier.fillMaxWidth().height(220.dp),
    )
}
