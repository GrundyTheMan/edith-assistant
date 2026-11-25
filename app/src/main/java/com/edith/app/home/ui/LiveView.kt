package com.edith.app.home.ui

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
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
internal class FeedDataSource(private val feed: StreamLiveFeed, private val received: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong()) : BaseDataSource(/* isNetwork = */ true) {
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
        received.addAndGet(n.toLong())
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null; chunk = null
        if (opened) { opened = false; transferEnded() }
    }
}

private val DIALOG_PICTURE_HEIGHT = 220.dp

/** How long a picture may take to appear before the player is retried or the viewer is told. */
private const val PICTURE_WAIT_MS = 6_000L

/** How long Blink may take to send the first video (a sleeping camera wakes first) before the viewer is told it is waiting. */
private const val CAMERA_WAIT_MS = 10_000L

/** Set once a view needed the software decoder, so later views in this run start on it instead of failing first. */
@Volatile private var preferSoftware = false

/** Live video from one camera. Closing the dialog stops the stream, so the camera and the phone's radio aren't left running. */
@Composable
fun LiveDialog(live: LiveUi, onClose: () -> Unit) {
    // Saved, so a rotation (which recreates the screen) keeps full screen. It ends if the view ends: the small dialog says why.
    var full by rememberSaveable { mutableStateOf(false) }
    val error = live.error
    val ended = live.endedBecause
    val feed = live.feed
    val playing = feed != null && error == null && ended == null
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
                    error != null -> Text(error)
                    ended != null -> Text(ended)
                    feed == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    feed is StreamLiveFeed -> LivePlayer(feed, Modifier.fillMaxWidth().height(DIALOG_PICTURE_HEIGHT))
                    feed is TrackLiveFeed -> RtcPlayer(feed, Modifier.fillMaxWidth().height(DIALOG_PICTURE_HEIGHT))
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
    val software = remember(feed) { mutableStateOf(preferSoftware) }
    val opened = remember(feed) { System.nanoTime() }
    val received = remember(feed) { java.util.concurrent.atomic.AtomicLong() }
    val rendered = remember(feed) { mutableStateOf(false) }
    val note = remember(feed) { mutableStateOf<String?>(null) }
    val player = remember(feed, software.value) {
        val factory = DefaultRenderersFactory(context).apply { if (software.value) setMediaCodecSelector(softwareFirst()) }
        // A live picture should start quickly and stay close to now: small buffers.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 5_000, 500, 1_000).build()
        // Blink's video has no access-unit delimiters; without this flag the player never finishes a frame and the picture stays black.
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
        )
        val source = ProgressiveMediaSource.Factory(DataSource.Factory { FeedDataSource(feed, received) }, extractors)
        ExoPlayer.Builder(context, factory).setLoadControl(load).setMediaSourceFactory(source).build().apply {
            setMediaItem(MediaItem.fromUri("feed://live"))
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (!rendered.value) Log.i(LogTag.Home, "Live view picture appeared ${(System.nanoTime() - opened) / 1_000_000_000} s after the player opened (${received.get() / 1024} KB received, ${if (software.value) "software" else "hardware"} decoder)")
                    rendered.value = true; note.value = null
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (!software.value && error.errorCode in DECODER_ERRORS) {
                        Log.w(LogTag.Home, "A live view's decoder failed (${error.errorCodeName}); trying a software decoder")
                        preferSoftware = true
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
    // A decoder can swallow its own failure and leave a black picture with no error: try the software decoder once, then say what is happening.
    LaunchedEffect(player) {
        // The picture clock starts when video starts to arrive: a sleeping camera can take 10 s to wake, and that is not the decoder's fault.
        var waited = 0L
        while (received.get() == 0L) {
            kotlinx.coroutines.delay(200)
            waited += 200
            if (waited >= CAMERA_WAIT_MS && !rendered.value) note.value = "Waiting for the camera to send video…"
        }
        if (!rendered.value && note.value?.startsWith("Waiting") == true) note.value = null
        kotlinx.coroutines.delay(PICTURE_WAIT_MS)
        if (rendered.value) return@LaunchedEffect
        val kb = received.get() / 1024
        if (!software.value) {
            Log.w(LogTag.Home, "A live view has no picture ${PICTURE_WAIT_MS / 1000} s after video began ($kb KB received); trying a software decoder")
            preferSoftware = true
            software.value = true
        } else {
            Log.w(LogTag.Home, "A live view still has no picture ($kb KB received, software decoder, player state ${player.playbackState})")
            note.value = "No picture yet. Video is arriving (${kb / 1024} MB), but this phone has not shown it. It may need a few more seconds; if not, Close and try again."
        }
    }
    failure.value?.let { Text(it); return }
    Box(modifier) {
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                this.player = player
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                useController = false   // nothing to seek in a live picture
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
    note.value?.let { Text(it, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.BottomStart).background(androidx.compose.ui.graphics.Color(0x99000000)).padding(8.dp)) }
    }
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
