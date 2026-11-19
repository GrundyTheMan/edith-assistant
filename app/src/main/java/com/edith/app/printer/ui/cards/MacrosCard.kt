package com.edith.app.printer.ui.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.edith.app.printer.feed.MacroInfo
import com.edith.app.printer.feed.MacroList
import com.edith.app.printer.feed.PrinterCommand
import com.edith.app.printer.feed.Risks
import com.edith.app.printer.ui.MacrosUi

/**
 * Macros (C39 part 8.4): the list Jarvis sends, in Jarvis's groups and order, with a filter. A macro with parameters opens a small form
 * (the default is the hint) and a Run button; a macro without them is one button. Every run goes through the confirm sheet, which shows
 * Jarvis's own question. Edith never decides what is hidden or denied: what Jarvis leaves out is not here, and what the owner hid is
 * listed at the bottom with Show.
 */
@Composable
internal fun MacrosCard(macros: MacrosUi, ctx: ControlsCtx, onQuery: (String) -> Unit, onHidden: (List<String>) -> Unit) {
    val answer = macros.answer
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("macros-card")) {
        if (answer == null) {
            if (macros.loading) CircularProgressIndicator()
            macros.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            return@Column
        }
        OutlinedTextField(macros.query, onQuery, label = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("macro-filter"))
        WhyOff(ctx)
        val sections = MacroList.sections(answer, macros.query)
        val folded = remember { mutableStateMapOf<String, Boolean>() }
        if (sections.isEmpty()) Text("No macros match.", style = MaterialTheme.typography.bodySmall)
        for (section in sections) {
            // While the filter has text every group is open, so nothing that matches is hidden behind a fold.
            val open = macros.query.isNotBlank() || folded[section.id] != true
            Row(
                Modifier.fillMaxWidth().clickable { folded[section.id] = open }.padding(vertical = 4.dp).testTag("macro-group-${section.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${section.title} (${section.macros.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (open) "Fold ${section.title}" else "Open ${section.title}")
            }
            if (open) section.macros.forEach { m -> MacroItem(m, ctx) { onHidden((answer.hidden + m.name).distinct()) } }
        }
        macros.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (answer.hidden.isNotEmpty()) {
            Text("Hidden (${answer.hidden.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            answer.hidden.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onHidden(answer.hidden - name) }, modifier = Modifier.testTag("show-$name")) { Text("Show") }
                }
            }
        }
    }
}

@Composable
private fun MacroItem(m: MacroInfo, ctx: ControlsCtx, onHide: () -> Unit) {
    var typed by remember(m.name) { mutableStateOf(emptyMap<String, String>()) }
    var open by remember(m.name) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(start = 8.dp).testTag("macro-${m.name}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (m.params.isEmpty()) {
                CmdButton(m.name, PrinterCommand.Macro(m.name), ctx, danger = m.risk == Risks.DANGER, tag = "run-${m.name}")
            } else {
                androidx.compose.material3.OutlinedButton(
                    onClick = { open = !open }, modifier = Modifier.testTag("open-${m.name}"),
                    colors = if (m.risk == Risks.DANGER) androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error) else androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
                ) { Text(if (open) "Close ${m.name}" else m.name) }
            }
            TextButton(onClick = onHide, modifier = Modifier.testTag("hide-${m.name}")) { Text("Hide") }
        }
        m.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (m.params.isNotEmpty() && open) {
            m.params.forEach { p ->
                OutlinedTextField(
                    typed[p.name].orEmpty(), { typed = typed + (p.name to it) }, label = { Text(p.name) }, singleLine = true,
                    placeholder = { p.default?.let { d -> Text(d) } }, modifier = Modifier.fillMaxWidth().testTag("param-${m.name}-${p.name}"),
                )
            }
            CmdButton("Run ${m.name}", PrinterCommand.Macro(m.name, MacroList.params(typed)), ctx, danger = m.risk == Risks.DANGER, tag = "run-${m.name}")
        }
        Refusal(ctx, "macro:${m.name}")
    }
}
