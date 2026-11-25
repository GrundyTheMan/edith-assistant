package com.edith.app.home.ui

import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import com.edith.app.home.DeviceClip
import com.edith.app.core.time.formatPattern
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import java.util.Locale

/**
 * Decoders with software ones first. Some phones' hardware video decoders start and then fail on a stream
 * (`ERROR_CODE_DECODING_FAILED`); the player then tries once more with these (found on the emulator, whose
 * `c2.goldfish.h264.decoder` failed on a plain H.264 file).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun softwareFirst() = MediaCodecSelector { mimeType, secure, tunneling ->
    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
}

internal val DECODER_ERRORS = setOf(
    PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
)

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ClipPlayer(url: String, headers: Map<String, String>) {
    val context = LocalContext.current
    val failure = remember(url) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Set after a decoder error: the player is built again, once, with software decoders first.
    val software = remember(url) { androidx.compose.runtime.mutableStateOf(false) }
    val player = remember(url, software.value) {
        val factory = DefaultRenderersFactory(context).apply { if (software.value) setMediaCodecSelector(softwareFirst()) }
        ExoPlayer.Builder(context, factory).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers))).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (!software.value && error.errorCode in DECODER_ERRORS) {
                        com.edith.app.core.log.Log.w(com.edith.app.core.log.LogTag.Home, "A clip's decoder failed (${error.errorCodeName}); trying a software decoder")
                        software.value = true
                    } else {
                        com.edith.app.core.log.Log.w(com.edith.app.core.log.LogTag.Home, "A clip couldn't be played (${error.errorCodeName})")
                        failure.value = "This phone couldn't play the clip (${error.errorCodeName})."
                    }
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }   // never left playing behind a closed dialog
    failure.value?.let { Text(it); return }
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                this.player = player
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                // Play/pause and the seek bar stay on screen; by default they appear only after a tap and fade after 5 s.
                controllerShowTimeoutMs = 0
                controllerHideOnTouch = false
                showController()
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxWidth().height(220.dp),
    )
}
