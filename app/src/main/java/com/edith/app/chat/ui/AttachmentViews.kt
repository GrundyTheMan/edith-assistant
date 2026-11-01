package com.edith.app.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.edith.app.data.db.AttachmentKind
import java.io.File

private val ThumbShape = RoundedCornerShape(12.dp)

/** Chips above the input row for attachments waiting to be sent (C15 §1): thumbnail or audio icon, name, ✕. */
@Composable
fun PendingAttachments(items: List<PendingUi>, onRemove: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag(PENDING_ATTACHMENTS_TAG),
    ) {
        for (item in items) {
            InputChip(
                selected = false,
                onClick = {},
                label = { Text(if (item.ready) item.name else "Preparing…", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.sizeIn(maxWidth = 140.dp)) },
                avatar = {
                    when {
                        !item.ready -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        item.kind == AttachmentKind.Image && item.file != null ->
                            AsyncImage(item.file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)))
                        else -> Icon(Icons.Filled.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                },
                trailingIcon = {
                    IconButton(onClick = { onRemove(item.key) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove ${item.name}", modifier = Modifier.size(16.dp))
                    }
                },
            )
        }
    }
}

/**
 * A message's attachments, above its text (C15 §4): image thumbnails (tap for full screen) and audio rows with
 * ▶/■, name and length. Files that are gone show their name with "no longer available".
 */
@Composable
fun MessageAttachments(
    attachments: List<AttachmentUi>,
    playingId: Long?,
    onTogglePlay: (AttachmentUi) -> Unit,
    onOpenImage: (File) -> Unit,
    contentColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (a in attachments) {
            when {
                a.file == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (a.kind == AttachmentKind.Image) Icons.Filled.Image else Icons.Filled.AudioFile, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                    Text(" ${a.fileName} — no longer available", color = contentColor, style = MaterialTheme.typography.bodySmall)
                }
                a.kind == AttachmentKind.Image -> AsyncImage(
                    model = a.file,
                    contentDescription = "Attachment ${a.number}: ${a.fileName}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.sizeIn(maxWidth = 240.dp, maxHeight = 240.dp).clip(ThumbShape).clickable(onClickLabel = "View full screen") { onOpenImage(a.file) },
                )
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    val playing = playingId == a.id
                    IconButton(onClick = { onTogglePlay(a) }) {
                        Icon(
                            if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Stop ${a.fileName}" else "Play ${a.fileName}",
                            tint = contentColor,
                        )
                    }
                    Column {
                        Text(a.fileName, color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        a.durationMs?.let { Text(formatDuration(it), color = contentColor, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

/** What Edith's file card can do (C16 §4). */
data class FileActions(
    val onView: (AttachmentUi) -> Unit = {},
    val onSave: (AttachmentUi) -> Unit = {},
    val onShare: (AttachmentUi) -> Unit = {},
)

/**
 * Edith's attachments, below her text (C16 §4): a card per file she wrote (name, first lines, View / Save / Share),
 * and the owner's attachments she sent back, shown like the originals. Removed ones get a muted line.
 */
@Composable
fun EdithAttachments(
    attachments: List<AttachmentUi>,
    playingId: Long?,
    onTogglePlay: (AttachmentUi) -> Unit,
    onOpenImage: (File) -> Unit,
    files: FileActions,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (a in attachments) {
            when {
                a.isResend && a.file == null ->
                    Text("Attachment ${a.number} is no longer available.", color = muted, style = MaterialTheme.typography.bodySmall)
                a.isResend -> MessageAttachments(listOf(a), playingId, onTogglePlay, onOpenImage, MaterialTheme.colorScheme.onSurface)
                a.file == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Description, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
                    Text(" ${a.fileName} — no longer kept", color = muted, style = MaterialTheme.typography.bodySmall)
                }
                else -> FileCard(a, files)
            }
        }
    }
}

@Composable
private fun FileCard(a: AttachmentUi, actions: FileActions) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag(FILE_CARD_TAG)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(" ${a.fileName}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            a.preview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row {
                TextButton(onClick = { actions.onView(a) }) { Text("View") }
                TextButton(onClick = { actions.onSave(a) }) { Text("Save") }
                TextButton(onClick = { actions.onShare(a) }) { Text("Share") }
            }
        }
    }
}

/** A file's text, selectable and scrollable (View on a file card). */
@Composable
fun FileViewer(name: String, text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(name) },
        text = {
            SelectionContainer {
                Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()).testTag(FILE_VIEWER_TAG))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** A photo full screen, with pinch-zoom and pan; ✕ or Back closes it. */
@Composable
fun ImageViewer(file: File, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag(IMAGE_VIEWER_TAG)) {
            AsyncImage(
                model = file,
                contentDescription = "Full-screen image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().transformable(transform).graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

/** 65_000 → "1:05". */
fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

const val PENDING_ATTACHMENTS_TAG = "pending-attachments"
const val IMAGE_VIEWER_TAG = "image-viewer"
const val FILE_CARD_TAG = "file-card"
const val FILE_VIEWER_TAG = "file-viewer"
