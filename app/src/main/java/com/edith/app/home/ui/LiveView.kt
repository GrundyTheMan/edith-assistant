package com.edith.app.home.ui

import android.net.Uri
import android.view.ViewGroup
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.home.LiveFeed
import com.edith.app.home.StreamLiveFeed
import com.edith.app.home.TrackLiveFeed
import kotlinx.coroutines.runBlocking
import java.io.InterruptedIOException

/**
 * A Media3 data source that reads a [LiveFeed]'s video as it arrives (C32). The length is unknown, and it ends when the
 * feed's channel closes (the view ended or was stopped). It blocks the player's loader thread, never the main thread.
 */
@UnstableApi
internal class FeedDataSource(private val feed: StreamLiveFeed) : BaseDataSource(/* isNetwork = */ true) {
    private var chunk: ByteArray? = null
    private var at = 0
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var current = chunk
        while (current == null || at >= current.size) {
            val next = try {
                runBlocking { feed.video.receiveCatching() }
            } catch (e: InterruptedException) {
                throw InterruptedIOException()   // the player cancelled its load
            }
            if (next.isClosed) return C.RESULT_END_OF_INPUT
            current = next.getOrNull() ?: return C.RESULT_END_OF_INPUT
            chunk = current; at = 0
        }
        val n = minOf(length, current.size - at)
        System.arraycopy(current, at, buffer, offset, n)
        at += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null; chunk = null
        if (opened) { opened = false; transferEnded() }
    }
}

/** What the Live dialog shows: the camera's name, and the feed once the service has one (or why it couldn't). */
data class LiveUi(
    val name: String,
    val feed: LiveFeed? = null,
    val error: String? = null,
    /** Why the view ended on its own (Blink's limit, the camera going quiet). */
    val endedBecause: String? = null,
)

private val DIALOG_PICTURE_HEIGHT = 220.dp

/** Live video from one camera. Closing the dialog stops the stream, so the camera and the phone's radio aren't left running. */
@Composable
fun LiveDialog(live: LiveUi, onClose: () -> Unit) {
    // Saved, so a rotation (which recreates the screen) keeps full screen. It ends if the view ends: the small dialog says why.
    var full by rememberSaveable { mutableStateOf(false) }
    val playing = live.feed != null && live.error == null && live.endedBecause == null
    if (full && playing) {
        LiveFullScreen(live, onExit = { full = false }, onClose = onClose)
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${live.name} — live", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                when {
                    live.error != null -> Text(live.error)
                    live.endedBecause != null -> Text(live.endedBecause)
                    live.feed == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    live.feed is StreamLiveFeed -> LivePlayer(live.feed, Modifier.fillMaxWidth().height(DIALOG_PICTURE_HEIGHT))
                    live.feed is TrackLiveFeed -> RtcPlayer(live.feed, Modifier.fillMaxWidth().height(DIALOG_PICTURE_HEIGHT))
                    else -> Text("This camera has no picture to show.")
                }
                Text(
                    "Battery cameras limit how long a live view lasts.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = if (playing) ({ TextButton(onClick = { full = true }) { Text("Full screen") } }) else null,
    )
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
internal fun LivePlayer(feed: StreamLiveFeed, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val failure = remember(feed) { mutableStateOf<String?>(null) }
    val software = remember(feed) { mutableStateOf(false) }
    val player = remember(feed, software.value) {
        val factory = DefaultRenderersFactory(context).apply { if (software.value) setMediaCodecSelector(softwareFirst()) }
        // A live picture should start quickly and stay close to now: small buffers.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 5_000, 500, 1_000).build()
        val source = ProgressiveMediaSource.Factory(DataSource.Factory { FeedDataSource(feed) }, DefaultExtractorsFactory())
        ExoPlayer.Builder(context, factory).setLoadControl(load).setMediaSourceFactory(source).build().apply {
            setMediaItem(MediaItem.fromUri("feed://live"))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (!software.value && error.errorCode in DECODER_ERRORS) {
                        Log.w(LogTag.Home, "A live view's decoder failed (${error.errorCodeName}); trying a software decoder")
                        software.value = true
                    } else {
                        Log.w(LogTag.Home, "A live view couldn't be played (${error.errorCodeName})")
                        failure.value = "This phone couldn't play the live view (${error.errorCodeName})."
                    }
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    failure.value?.let { Text(it); return }
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                this.player = player
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                useController = false   // nothing to seek in a live picture
            }
        },
        update = { it.player = player },
        modifier = modifier,
    )
}

/** Ring's live view: the WebRTC track drawn on a renderer that shares the call's EGL context. */
@Composable
internal fun RtcPlayer(feed: TrackLiveFeed, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val renderer = remember(feed) {
        org.webrtc.SurfaceViewRenderer(context).apply {
            init(feed.eglContext, null)
            setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }
    DisposableEffect(feed) {
        feed.track.addSink(renderer)
        onDispose {
            // The call may already be closed (stopping the feed closes it, and its track with it).
            runCatching { feed.track.removeSink(renderer) }
            renderer.release()
        }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}
