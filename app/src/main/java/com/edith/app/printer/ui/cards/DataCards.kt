package com.edith.app.printer.ui.cards

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.edith.app.printer.feed.ConsoleView
import com.edith.app.printer.feed.FileList
import com.edith.app.printer.feed.PrinterCamera
import com.edith.app.printer.feed.PrinterCommand
import com.edith.app.printer.feed.PrinterFormat
import com.edith.app.printer.ui.UploadUi
import com.edith.app.printer.ui.disabledReason
import androidx.compose.material3.LinearProgressIndicator
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.ui.CameraUi
import com.edith.app.printer.ui.ConsoleUi
import com.edith.app.printer.ui.FilesUi
import com.edith.app.printer.ui.HistoryUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Decodes a picture off the main thread (L39) and keeps only the newest as a bitmap. */
@Composable
private fun rememberDecoded(key: Any?, bytes: () -> ByteArray?): ImageBitmap? {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) {
        val data = bytes() ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }.getOrNull() }
        if (decoded != null) image = decoded
    }
    return image
}

/** Camera: a picture about once a second while this card is open. The rotation, flips and shape are the camera's own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CameraCard(state: PrinterState, camera: CameraUi, onChoose: (String) -> Unit, ctx: ControlsCtx? = null) {
    val cameras = state.cameras
    val chosen: PrinterCamera? = cameras.firstOrNull { it.id == camera.cameraId } ?: cameras.firstOrNull()
    val picture = rememberDecoded(camera.number) { camera.frame }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("camera-card")) {
        if (cameras.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cameras.forEach { c -> FilterChip(selected = c.id == chosen?.id, onClick = { onChoose(c.id) }, label = { Text(c.name) }) }
            }
        }
        when {
            chosen == null -> Text("This printer has no camera.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                val turned = chosen.rotation == 90 || chosen.rotation == 270
                val aspect = (if (turned) 1.0 / chosen.aspect else chosen.aspect).toFloat().coerceIn(0.3f, 3f)
                Box(Modifier.fillMaxWidth().aspectRatio(aspect), contentAlignment = Alignment.Center) {
                    if (picture != null) {
                        Image(
                            picture, contentDescription = chosen.name, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                rotationZ = chosen.rotation.toFloat()
                                scaleX = if (chosen.flipH) -1f else 1f
                                scaleY = if (chosen.flipV) -1f else 1f
                            },
                        )
                    }
                    when {
                        camera.offline -> Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                            Text("Camera offline", Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("camera-offline"), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        picture == null -> Text("Connecting…", Modifier.testTag("camera-connecting"))
                    }
                }
                if (camera.offline && ctx?.mayControl == true) CameraControls(ctx)
            }
        }
    }
}

private const val LISTING_NOTE = "Listing the files keeps the printer busy for several seconds, and its buttons wait."
private const val NOT_WHILE_PRINTING = "Not while printing: the printer would stop answering for several seconds."

/** Files: nothing is asked of Jarvis until Load files is pressed. Then a search, newest first, and a detail sheet for a file. */
@Composable
internal fun FilesCard(
    state: PrinterState, files: FilesUi, nowMs: Long, zone: ZoneId,
    onLoad: (Boolean) -> Unit, onQuery: (String) -> Unit, onPrintedOnly: (Boolean) -> Unit, onOpen: (String) -> Unit, onCloseDetail: () -> Unit,
    ctx: ControlsCtx? = null, onPickUpload: () -> Unit = {}, onSendUpload: () -> Unit = {}, onCancelUpload: () -> Unit = {}, onClearUpload: () -> Unit = {},
) {
    val printing = state.print.state == "printing" || state.print.state == "paused"
    val list = files.files
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("files-card")) {
        // Upload needs no list: it is a command like any other, so it is drawn only for a phone that may control, and Jarvis decides.
        if (ctx != null && ctx.mayControl) UploadRow(files.upload, printing, ctx, onPickUpload, onSendUpload, onCancelUpload, onClearUpload)
        if (list == null) {
            Text(LISTING_NOTE, style = MaterialTheme.typography.bodyMedium)
            if (printing) Text(NOT_WHILE_PRINTING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (files.loading) CircularProgressIndicator() else Button(onClick = { onLoad(false) }, enabled = !printing, modifier = Modifier.testTag("load-files")) { Text("Load files") }
            files.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } else {
            OutlinedTextField(files.query, onQuery, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = files.printedOnly, onCheckedChange = onPrintedOnly)
                Text(if (files.printedOnly) "Printed before" else "All", Modifier.weight(1f))
                Text("Listed ${PrinterFormat.age(files.cachedAt, nowMs, zone)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val shown = FileList.shown(list, files.query, files.printedOnly)
            if (shown.isEmpty()) Text("No files match.", style = MaterialTheme.typography.bodySmall)
            shown.forEach { f ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(f.path) }.padding(vertical = 6.dp)) {
                    Text(f.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(FileList.size(f.size), PrinterFormat.age(f.modified, nowMs, zone), if (f.printedBefore) "printed before" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
            OutlinedButton(onClick = { onLoad(true) }, enabled = !printing && !files.loading) { Text("Refresh") }
            Text(if (printing) NOT_WHILE_PRINTING else LISTING_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            files.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (files.detailOf != null) FileDetailSheet(files, list.orEmpty().firstOrNull { it.path == files.detailOf }?.title ?: files.detailOf, onCloseDetail, ctx)
}

/** The Upload button and what became of the last upload: a progress bar with Cancel while it goes, then "Sent" or Jarvis's words. */
@Composable
private fun UploadRow(upload: UploadUi, printing: Boolean, ctx: ControlsCtx, onPick: () -> Unit, onSend: () -> Unit, onCancel: () -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("upload-row")) {
        if (upload.active) {
            Text("Sending ${upload.name} · ${FileList.size(upload.sent)} of ${FileList.size(upload.total)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("upload-progress-text"))
            LinearProgressIndicator(progress = { if (upload.total > 0) (upload.sent.toFloat() / upload.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().testTag("upload-progress"))
            OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("upload-cancel")) { Text("Cancel upload") }
        } else if (upload.ready) {
            // Picked and checked; nothing has gone yet. Sending needs Controls on now (the picker took the page off screen, which ended them).
            Text("${upload.name} · ${FileList.size(upload.total)} is ready to send.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("upload-ready"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSend, enabled = disabledReason(ctx.ui, ctx.controls, null) == null, modifier = Modifier.testTag("upload-send")) { Text("Send") }
                OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("upload-discard")) { Text("Discard") }
            }
            WhyOff(ctx)
            upload.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("upload-error")) }
        } else {
            // Choosing a file needs no Controls (it sends nothing, and the picker would end them anyway); Send, the next step, does.
            OutlinedButton(onClick = { onClear(); onPick() }, modifier = Modifier.testTag("upload-button")) { Text("Upload a file") }
            upload.done?.let { Text("Sent ${it}.${if (printing) "" else " Refresh the list to see it."}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("upload-done")) }
            upload.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("upload-error")) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileDetailSheet(files: FilesUi, title: String, onClose: () -> Unit, ctx: ControlsCtx? = null) {
    val d = files.detail
    val path = files.detailOf
    val thumb = rememberDecoded(d?.thumbnail) { d?.thumbnail?.substringAfter("base64,", "")?.takeIf { it.isNotEmpty() }?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    files.detailLoading -> CircularProgressIndicator()
                    files.detailError != null -> Text(files.detailError, color = MaterialTheme.colorScheme.error)
                    d != null -> {
                        thumb?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxWidth()) }
                        d.estimatedTime?.let { LabelValue("Estimated time", PrinterFormat.duration(it)) }
                        d.filamentTotal?.let { LabelValue("Filament", PrinterFormat.filament(it)) }
                        d.layerCount?.let { LabelValue("Layers", "$it") }
                        if (d.estimatedTime == null && d.filamentTotal == null && d.layerCount == null && d.thumbnail == null) Text("No details on the printer for this file")
                    }
                }
                // The buttons come with Controls: each is a command Jarvis checks (a .3mf print is refused in Jarvis's words, shown below) and the
                // sheet closes only when Jarvis accepted it. Print and Delete ask for the owner's yes first, as Jarvis says.
                if (ctx != null && ctx.mayControl && path != null) {
                    // A wrapping row: three buttons side by side are too wide for a dialog on a phone (Delete was squeezed into a strip).
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CmdButton("Print", PrinterCommand.Start(path), ctx, filled = true, tag = "file-print")
                        CmdButton("Add to queue", PrinterCommand.QueueAdd(path), ctx, tag = "file-queue")
                        CmdButton("Delete", PrinterCommand.DeleteFile(path), ctx, danger = true, tag = "file-delete")
                    }
                    WhyOff(ctx)
                    Refusal(ctx, "start", "queueAdd", "deleteFile")
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** History: the totals and the last 50 jobs, with More for older ones. Read only. */
@Composable
internal fun HistoryCard(history: HistoryUi, nowMs: Long, zone: ZoneId, onMore: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("history-card")) {
        history.totals?.let { t ->
            LabelValue("Jobs", "${t.jobs}")
            LabelValue("Print time", PrinterFormat.duration(t.printTime))
            LabelValue("Filament", PrinterFormat.filament(t.filamentUsed))
            LabelValue("Longest print", PrinterFormat.duration(t.longestPrint))
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
        history.jobs.forEach { j ->
            Column {
                Text(j.title.ifBlank { j.filename }, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${j.status.replace('_', ' ')} · ${PrinterFormat.age(j.start, nowMs, zone)} · ${PrinterFormat.duration(j.printDuration)} · ${PrinterFormat.filament(j.filamentUsed)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (history.loading) CircularProgressIndicator()
        history.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (history.more && !history.loading) OutlinedButton(onClick = onMore) { Text("More") }
        if (history.jobs.isEmpty() && !history.loading && history.error == null) Text("No jobs yet.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Console: what the printer said, newest at the bottom; its "// value:" chatter hidden unless switched off. Reading only in stage 1. */
@Composable
internal fun ConsoleCard(console: ConsoleUi, onHideValues: (Boolean) -> Unit, onClear: () -> Unit, ctx: ControlsCtx? = null, onRemember: (String) -> Unit = {}) {
    val lines = ConsoleView.visible(console.lines, console.hideValues).takeLast(120)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("console-card")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = console.hideValues, onCheckedChange = onHideValues)
            Text("Hide \"// value\" lines", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClear) { Text("Clear") }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            lines.forEach { l ->
                Text(l.text, style = MaterialTheme.typography.bodySmall,
                    color = if (l.kind == "error") MaterialTheme.colorScheme.error else if (l.kind == "command") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            if (lines.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        console.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (ctx?.mayControl == true) ConsoleInput(console.history, ctx, onRemember)
    }
}
