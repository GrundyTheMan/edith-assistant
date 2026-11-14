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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import com.edith.app.home.DeviceClip
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The words for a clip's row: "Front Door · A person · 0:23". */
internal fun clipTitle(c: DeviceClip): String =
    listOfNotNull(c.deviceName, c.kind.label, c.seconds?.let { "%d:%02d".format(Locale.US, it / 60, it % 60) }).joinToString(" · ")

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.US)

/** "Today 3:42 PM", "Yesterday 9:05 AM", or "Tue 11 Nov, 6:30 PM". */
internal fun clipWhen(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    return when (time.toLocalDate()) {
        today -> "Today $clock"
        today.minusDays(1) -> "Yesterday $clock"
        else -> time.format(DAY)
    }
}

/** What to say when there are no clips to list. */
internal fun clipsEmptyText(c: ClipsUi): String = when {
    c.noPlan -> "Ring keeps recordings only with a Ring Protect plan, and this account doesn't have one, so there are no clips to show."
    c.problems.isNotEmpty() -> c.problems.joinToString(" ")
    else -> "No clips in the last 60 days."
}

/** The clips of the doorbells and cameras, newest first, older ones as the list is scrolled (C27). */
@Composable
fun ClipsDialog(ui: ClipsUi, now: Long, a: HomeTabActions) {
    Dialog(onDismissRequest = a.closeClips, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Clips", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = a.closeClips) { Text("Close") }
                }
                val state = rememberLazyListState()
                // Near the end of the list: ask for the next older window.
                LaunchedEffect(state, ui.clips.size, ui.more) {
                    snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                        if (ui.more && !ui.loading && last >= ui.clips.size - 3) a.moreClips()
                    }
                }
                LazyColumn(Modifier.fillMaxSize().testTag(CLIPS_LIST_TAG), state = state) {
                    itemsIndexed(ui.clips, key = { _, c -> c.id }) { _, c ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { a.playClip(c) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(clipTitle(c), style = MaterialTheme.typography.bodyLarge)
                                Text(clipWhen(c.at, now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (ui.loading) item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                    } else if (ui.clips.isEmpty()) item(key = "empty") {
                        Text(clipsEmptyText(ui), modifier = Modifier.padding(24.dp))
                    } else if (ui.problems.isNotEmpty()) item(key = "problems") {
                        Text(ui.problems.joinToString(" "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }
    ui.playing?.let { clip -> ClipPlayerDialog(clip, ui, a.closePlayer) }
}

/** Plays one clip with Media3's ExoPlayer, streaming from the address the service just gave (they expire). */
@Composable
private fun ClipPlayerDialog(clip: DeviceClip, ui: ClipsUi, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(clipTitle(clip), style = MaterialTheme.typography.titleMedium) },
        text = {
            when {
                ui.playError != null -> Text(ui.playError)
                ui.playUrl == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> ClipPlayer(ui.playUrl)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/**
 * Decoders with software ones first. Some phones' hardware video decoders start and then fail on a stream
 * (`ERROR_CODE_DECODING_FAILED`); the player then tries once more with these (found on the emulator, whose
 * `c2.goldfish.h264.decoder` failed on a plain H.264 file).
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun softwareFirst() = MediaCodecSelector { mimeType, secure, tunneling ->
    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
}

private val DECODER_ERRORS = setOf(
    PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
)

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun ClipPlayer(url: String) {
    val context = LocalContext.current
    val failure = remember(url) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Set after a decoder error: the player is built again, once, with software decoders first.
    val software = remember(url) { androidx.compose.runtime.mutableStateOf(false) }
    val player = remember(url, software.value) {
        val factory = DefaultRenderersFactory(context).apply { if (software.value) setMediaCodecSelector(softwareFirst()) }
        ExoPlayer.Builder(context, factory).build().apply {
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
        factory = { c -> PlayerView(c).apply { this.player = player; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) } },
        update = { it.player = player },
        modifier = Modifier.fillMaxWidth().height(220.dp),
    )
}
