package com.edith.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.edith.app.utils.Attachment
import com.edith.app.utils.AttachmentKind
import com.edith.app.utils.ByteArrayMediaDataSource
import com.edith.app.utils.WrittenFile

/** Attachments added to the message being written, each removable before sending. */
@OptIn(ExperimentalMaterial3Api::class) // InputChip
@Composable
fun PendingAttachmentsRow(attachments: List<Attachment>, onRemove: (Attachment) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(attachments, key = { it.number }) { attachment ->
            InputChip(
                selected = false,
                onClick = {},
                label = { Text(attachment.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp)) },
                leadingIcon = {
                    if (attachment.kind == AttachmentKind.IMAGE) {
                        val thumbnail = rememberThumbnail(attachment)
                        Image(thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)))
                    } else {
                        Icon(Icons.Filled.AudioFile, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                },
                trailingIcon = {
                    IconButton(onClick = { onRemove(attachment) }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove ${attachment.fileName}", modifier = Modifier.size(16.dp))
                    }
                }
            )
        }
    }
}

/** An attachment shown in a message: an image thumbnail (tap to enlarge) or an audio player. */
@Composable
fun AttachmentView(attachment: Attachment) {
    when (attachment.kind) {
        AttachmentKind.IMAGE -> ImageAttachment(attachment)
        AttachmentKind.AUDIO -> AudioAttachment(attachment)
    }
}

@Composable
private fun ImageAttachment(attachment: Attachment) {
    var enlarged by remember { mutableStateOf(false) }
    val thumbnail = rememberThumbnail(attachment)
    Image(
        bitmap = thumbnail,
        contentDescription = attachment.fileName,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .widthIn(max = 240.dp)
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { enlarged = true }
    )
    if (enlarged) {
        Dialog(onDismissRequest = { enlarged = false }) {
            val full = remember(attachment) {
                BitmapFactory.decodeByteArray(attachment.bytes, 0, attachment.bytes.size).asImageBitmap()
            }
            Image(
                bitmap = full,
                contentDescription = attachment.fileName,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { enlarged = false }
            )
        }
    }
}

@Composable
private fun AudioAttachment(attachment: Attachment) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    fun stop() {
        player?.release()
        player = null
    }
    DisposableEffect(attachment) { onDispose { stop() } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            if (player != null) {
                stop()
            } else {
                // Played straight from memory: the audio is never written to storage
                player = MediaPlayer().apply {
                    setDataSource(ByteArrayMediaDataSource(attachment.bytes))
                    setOnCompletionListener { stop() }
                    prepare()
                    start()
                }
            }
        }) {
            Icon(
                if (player != null) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (player != null) "Stop ${attachment.fileName}" else "Play ${attachment.fileName}"
            )
        }
        Text(attachment.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A file Edith wrote: view it, save it where you choose, or share it. */
@Composable
fun WrittenFileCard(file: WrittenFile) {
    val context = LocalContext.current
    var viewing by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeTypeFor(file.name))) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> out.write(file.content.toByteArray()) } }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                text = file.content.lines().take(3).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row {
                TextButton(onClick = { viewing = true }) { Text("View") }
                TextButton(onClick = { saveLauncher.launch(file.name) }) { Text("Save") }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, file.name)
                        putExtra(Intent.EXTRA_TEXT, file.content)
                    }
                    context.startActivity(Intent.createChooser(send, file.name))
                }) { Text("Share") }
            }
        }
    }

    if (viewing) {
        AlertDialog(
            onDismissRequest = { viewing = false },
            confirmButton = { TextButton(onClick = { viewing = false }) { Text("Close") } },
            title = { Text(file.name) },
            text = {
                SelectionContainer {
                    Text(
                        file.content,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
        )
    }
}

/** Something that existed only while the app was open and is gone now. */
@Composable
fun NoLongerAvailableNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun rememberThumbnail(attachment: Attachment): ImageBitmap = remember(attachment) {
    // A downsampled copy keeps thumbnails light on memory
    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
    BitmapFactory.decodeByteArray(attachment.bytes, 0, attachment.bytes.size, options).asImageBitmap()
}

private fun mimeTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "md" -> "text/markdown"
    "csv" -> "text/csv"
    "json" -> "application/json"
    "html" -> "text/html"
    else -> "text/plain"
}
