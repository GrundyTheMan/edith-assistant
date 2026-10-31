package com.edith.app.ui.components

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Shown on the launch after a crash (C02 §5): "Edith closed unexpectedly last time." with View (the report,
 * selectable), Copy (to the clipboard) and Dismiss (deletes the report via [onDismiss]).
 */
@Composable
fun CrashBanner(report: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var showReport by rememberSaveable { mutableStateOf(false) }
    val copy = rememberCopyReport(report)

    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
            Text(
                "Edith closed unexpectedly last time.",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showReport = true }) { Text("View") }
                TextButton(onClick = copy) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }

    if (showReport) CrashReportDialog(report, onClose = { showReport = false })
}

/** The crash report, selectable and scrollable, with Copy and Close. Also opened from Settings → About (C04). */
@Composable
fun CrashReportDialog(report: String, onClose: () -> Unit) {
    val copy = rememberCopyReport(report)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Crash report") },
        text = {
            SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = { TextButton(onClick = copy) { Text("Copy") } },
    )
}

@Composable
private fun rememberCopyReport(report: String): () -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Edith crash report", report))) } }
}
