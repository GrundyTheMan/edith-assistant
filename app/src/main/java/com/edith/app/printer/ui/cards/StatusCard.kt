package com.edith.app.printer.ui.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.edith.app.printer.feed.PrintInfo
import com.edith.app.printer.feed.PrinterFormat
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.feed.PrinterStates
import com.edith.app.printer.feed.PrinterCommand
import com.edith.app.printer.ui.ControlUi
import com.edith.app.printer.ui.PrinterUi
import com.edith.app.printer.ui.controlKey
import com.edith.app.printer.ui.disabledReason
import java.time.Instant

/** What the print state is called on the chip. A state from a newer Jarvis is shown as it came. */
internal fun printStateLabel(state: String): String = when (state) {
    PrinterStates.PRINTING -> "Printing"
    PrinterStates.PAUSED -> "Paused"
    PrinterStates.COMPLETE -> "Complete"
    PrinterStates.CANCELLED -> "Cancelled"
    PrinterStates.ERROR -> "Error"
    PrinterStates.STANDBY -> "Standby"
    else -> state.replaceFirstChar { it.uppercase() }
}

/** The title of the job: what Jarvis calls it ("Ready" when nothing is loaded). Never worked out from the file name here. */
internal fun statusTitle(print: PrintInfo): String = when {
    !print.title.isNullOrBlank() -> print.title
    !print.filename.isNullOrBlank() -> print.filename
    print.state == PrinterStates.STANDBY -> "Ready"
    else -> printStateLabel(print.state)
}

/** The Status card (C39 8.4): the job, its progress, and the numbers that matter while it prints. Controls come in stage 2. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusCard(ui: PrinterUi, state: PrinterState, is24Hour: Boolean, onDismissAlert: (Long) -> Unit, controls: ControlUi = ControlUi(), onSend: (PrinterCommand) -> Unit = {}) {
    val p = state.print
    val now = Instant.ofEpochMilli(ui.now)
    Column(Modifier.testTag("status-card"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(statusTitle(p), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StateChip(printStateLabel(p.state), p.state)
        }
        if (p.state == PrinterStates.PRINTING || p.state == PrinterStates.PAUSED || p.progress > 0.0) {
            LinearProgressIndicator(progress = { p.progress.coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth().testTag("status-progress"))
            Text(PrinterFormat.percent(p.progress), style = MaterialTheme.typography.labelLarge)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Fact("Time left", PrinterFormat.timeLeft(p.timeLeft), if (p.timeLeft != null) PrinterFormat.timeLeftSource(p.timeLeftFrom) else if (p.state == PrinterStates.PRINTING) PrinterFormat.NOT_ENOUGH_PRINTED else null)
            Fact("Elapsed", PrinterFormat.duration(p.printDuration))
            Fact("Total", PrinterFormat.duration(p.totalDuration))
            Fact("ETA", PrinterFormat.eta(now, p.timeLeft, ui.zone, is24Hour))
            Fact("Filament", PrinterFormat.filament(p.filamentUsed))
            p.layer?.let { Fact("Layer", "${it.current} / ${it.total}") }
            Fact("Speed", "${Math.round(state.toolhead.liveVelocity)} mm/s · ${PrinterFormat.factor(state.toolhead.speedFactor)}")
            Fact("Flow", PrinterFormat.factor(state.toolhead.extrudeFactor))
        }
        p.message?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (controls.mayControl && (p.state == PrinterStates.PRINTING || p.state == PrinterStates.PAUSED)) JobButtons(ui, state, controls, onSend)
        ui.alert?.let { alert ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag("status-alert")) {
                Column(Modifier.padding(12.dp)) {
                    Text("The printer said: ${alert.text}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(PrinterFormat.age(alert.time, ui.now, ui.zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onDismissAlert(alert.time) }) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, note: String? = null) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
        if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A coloured chip for the print state: accent while printing, amber paused, green complete, red cancelled or error, muted standby. */
@Composable
internal fun StateChip(text: String, state: String) {
    val scheme = MaterialTheme.colorScheme
    val (background, content) = when (state) {
        PrinterStates.PRINTING -> scheme.primaryContainer to scheme.onPrimaryContainer
        PrinterStates.PAUSED -> Color(0xFFFFE0A3) to Color(0xFF3D2B00)
        PrinterStates.COMPLETE -> Color(0xFFBFE8C4) to Color(0xFF0A3315)
        PrinterStates.CANCELLED, PrinterStates.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        else -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Surface(color = background, contentColor = content, shape = MaterialTheme.shapes.small, modifier = Modifier.testTag("state-chip")) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** Pause or Resume (whichever the server allows) and Cancel print, with the reason under them when they cannot be used. Cancel always asks first. */
@Composable
private fun JobButtons(ui: PrinterUi, state: PrinterState, controls: ControlUi, onSend: (PrinterCommand) -> Unit) {
    val printing = state.print.state == PrinterStates.PRINTING
    val main: PrinterCommand = if (printing) PrinterCommand.Pause else PrinterCommand.Resume
    val mainKey = if (printing) "pause" else "resume"
    val mainWhy = disabledReason(ui, controls, mainKey)
    val cancelWhy = disabledReason(ui, controls, "cancel")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("job-buttons")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedButton(onClick = { onSend(main) }, enabled = mainWhy == null && controlKey(main) !in controls.pending, modifier = Modifier.testTag("job-main")) {
                if (controlKey(main) in controls.pending) androidx.compose.material3.CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                Text(if (printing) "Pause" else "Resume")
            }
            androidx.compose.material3.OutlinedButton(
                onClick = { onSend(PrinterCommand.Cancel) }, enabled = cancelWhy == null && "cancel" !in controls.pending,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("job-cancel"),
            ) {
                if ("cancel" in controls.pending) androidx.compose.material3.CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                Text("Cancel print")
            }
        }
        (mainWhy ?: cancelWhy)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("job-why")) }
    }
}
