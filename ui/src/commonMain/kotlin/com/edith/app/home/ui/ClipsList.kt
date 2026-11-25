package com.edith.app.home.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.edith.app.home.DeviceClip
import com.edith.app.core.time.formatPattern
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** The words for a clip's row: "Front Door · A person · 0:23". */
fun clipTitle(c: DeviceClip): String =
    listOfNotNull(c.deviceName, c.kind.label, c.seconds?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }).joinToString(" · ")

/** "Today 3:42 PM", "Yesterday 9:05 AM", or "Tue 11 Nov, 6:30 PM". */
fun clipWhen(at: Long, now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val moment = Instant.fromEpochMilliseconds(at)
    val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
    val clock = formatPattern(moment, zone, "h:mm a", "en-US")
    return when (moment.toLocalDateTime(zone).date) {
        today -> "Today $clock"
        today.minus(1, DateTimeUnit.DAY) -> "Yesterday $clock"
        else -> formatPattern(moment, zone, "EEE d MMM, h:mm a", "en-US")
    }
}

/** What to say when there are no clips to list. */
fun clipsEmptyText(c: ClipsUi): String = when {
    c.noPlan -> "Ring keeps recordings only with a Ring Protect plan, and this account doesn't have one, so there are no clips to show."
    c.problems.isNotEmpty() -> c.problems.joinToString(" ")
    else -> "No clips in the last 60 days."
}

/** The clips of the doorbells and cameras, newest first, older ones as the list is scrolled (C27). */
@Composable
fun ClipsDialog(ui: ClipsUi, now: Long, a: HomeTabActions, clipPlayer: @Composable (String, Map<String, String>) -> Unit = { _, _ -> androidx.compose.material3.Text("Clips can't be played on this phone yet.") }) {
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
    ui.playing?.let { clip -> ClipPlayerDialog(clip, ui, a.closePlayer, clipPlayer) }
}

/** The clip's dialog: a spinner until the service gives an address (they expire), then the platform's player. */
@Composable
private fun ClipPlayerDialog(clip: DeviceClip, ui: ClipsUi, onClose: () -> Unit, clipPlayer: @Composable (String, Map<String, String>) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(clipTitle(clip), style = MaterialTheme.typography.titleMedium) },
        text = {
            when {
                ui.playError != null -> Text(ui.playError)
                ui.playUrl == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> clipPlayer(ui.playUrl, ui.playHeaders)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

