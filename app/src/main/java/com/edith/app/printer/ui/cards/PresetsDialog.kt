package com.edith.app.printer.ui.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.edith.app.printer.feed.Preset
import com.edith.app.printer.feed.PrinterInput
import com.edith.app.printer.ui.PresetsUi
import com.edith.app.printer.ui.disabledReason

/**
 * The temperature presets (C39 part 16): the names and the nozzle and bed temperatures Jarvis offers this phone, up to 10. Jarvis keeps them,
 * so its own page shows the same ones; it needs Controls on and checks them again. With none, Jarvis uses its own defaults.
 */
@Composable
internal fun PresetsDialog(presets: List<Preset>, ctx: ControlsCtx, editor: PresetsUi, onSave: (List<Preset>) -> Unit, onClose: () -> Unit) {
    val rows = remember {
        mutableStateListOf<PrinterInput.PresetRow>().also { l -> presets.forEach { l += PrinterInput.PresetRow(it.name, it.extruder.toString(), it.bed.toString()) } }
    }
    val problem = PrinterInput.presetsProblem(rows.toList())
    val why = disabledReason(ctx.ui, ctx.controls)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Temperature presets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("presets-dialog")) {
                rows.forEachIndexed { n, r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(r.name, { rows[n] = r.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1.3f).testTag("preset-name-$n"))
                        OutlinedTextField(r.nozzle, { rows[n] = r.copy(nozzle = it) }, label = { Text("Nozzle") }, singleLine = true, modifier = Modifier.weight(1f).testTag("preset-nozzle-$n"))
                        OutlinedTextField(r.bed, { rows[n] = r.copy(bed = it) }, label = { Text("Bed") }, singleLine = true, modifier = Modifier.weight(0.9f).testTag("preset-bed-$n"))
                    }
                    TextButton(onClick = { rows.removeAt(n) }, modifier = Modifier.testTag("preset-remove-$n")) { Text("Remove ${r.name.ifBlank { "this one" }}") }
                }
                OutlinedButton(onClick = { rows += PrinterInput.PresetRow("", "", "") }, enabled = rows.size < PrinterInput.MAX_PRESETS, modifier = Modifier.testTag("preset-add")) { Text("Add a preset") }
                if (rows.isEmpty()) Text("With none, Jarvis uses its own presets.", style = MaterialTheme.typography.bodySmall)
                (problem ?: editor.error ?: why)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("presets-problem")) }
            }
        },
        confirmButton = {
            Button(onClick = { PrinterInput.presets(rows.toList())?.let(onSave) }, enabled = problem == null && why == null && !editor.saving, modifier = Modifier.testTag("presets-save")) {
                if (editor.saving) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("presets-cancel")) { Text("Cancel") } },
    )
}
