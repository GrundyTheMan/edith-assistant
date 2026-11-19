package com.edith.app.printer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.printer.PrinterCards
import com.edith.app.printer.feed.PrinterScreenState
import com.edith.app.printer.ui.cards.StatusCard

/** The Printer page (C39 part 8). It asks Jarvis only while it is on screen. */
@Composable
internal fun PrinterScreen(onBack: () -> Unit, onOpenPairing: () -> Unit, vm: PrinterViewModel = viewModel(factory = PrinterViewModel.Factory)) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.attach() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.detach() }
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    PrinterContent(
        ui, is24Hour,
        PrinterActions(back = onBack, tryAgain = vm::tryAgain, openPairing = onOpenPairing, toggleCard = vm::toggleCard, dismissAlert = vm::dismissAlert),
    )
}

/** The page without a ViewModel, so a UI test can draw any state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrinterContent(ui: PrinterUi, is24Hour: Boolean, a: PrinterActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ui.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        val chip = ui.chip
                        if (chip.text.isNotEmpty()) Text(chip.text, style = MaterialTheme.typography.labelMedium, color = chipColor(chip.tone), modifier = Modifier.testTag("connection-chip"))
                    }
                },
                navigationIcon = { IconButton(onClick = a.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val message = ui.message
            when {
                ui.screen is PrinterScreenState.Connecting && ui.state == null -> Connecting()
                message != null -> FullMessage(message, ui, a)
                ui.state != null -> Cards(ui, is24Hour, a)
                else -> Connecting()
            }
        }
    }
}

@Composable
private fun chipColor(tone: Tone) = when (tone) {
    Tone.Good -> MaterialTheme.colorScheme.primary
    Tone.Warn -> MaterialTheme.colorScheme.tertiary
    Tone.Bad -> MaterialTheme.colorScheme.error
    Tone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun Connecting() {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Text("Connecting to Jarvis…", Modifier.padding(top = 16.dp).testTag("connecting"))
    }
}

/** No Jarvis, not shared, no printer, or Jarvis away with nothing to show: one message and, where it helps, one button. */
@Composable
private fun FullMessage(message: String, ui: PrinterUi, a: PrinterActions) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Text(message, textAlign = TextAlign.Center, modifier = Modifier.testTag("printer-message"))
        when (ui.screen) {
            PrinterScreenState.NoJarvis -> Button(onClick = a.openPairing) { Text("Pair with Jarvis") }
            is PrinterScreenState.JarvisAway -> Button(onClick = a.tryAgain) { Text("Try again") }
            else -> Unit
        }
    }
}

@Composable
private fun Cards(ui: PrinterUi, is24Hour: Boolean, a: PrinterActions) {
    val state = checkNotNull(ui.state)
    LazyColumn(
        Modifier.fillMaxSize().alpha(if (ui.greyed) 0.6f else 1f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ui.banner?.let { banner ->
            item(key = "banner") {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag("banner")) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(banner, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        if (ui.screen is PrinterScreenState.JarvisAway) TextButton(onClick = a.tryAgain) { Text("Try again") }
                    }
                }
            }
        }
        items(PrinterCards.ALL.filter { it in available }, key = { it }) { id ->
            CardFrame(title(id), id !in ui.collapsed, onToggle = { a.toggleCard(id) }, id = id) {
                when (id) {
                    PrinterCards.STATUS -> StatusCard(ui, state, is24Hour, a.dismissAlert)
                    else -> Unit
                }
            }
        }
    }
}

/** The cards built so far (the rest arrive with E5): each is drawn in the order of [PrinterCards.ALL]. */
private val available = setOf(PrinterCards.STATUS)

private fun title(id: String) = when (id) {
    PrinterCards.STATUS -> "Status"
    else -> id.replaceFirstChar { it.uppercase() }
}

/** A collapsible card; whether it is open is remembered per card. */
@Composable
private fun CardFrame(title: String, expanded: Boolean, onToggle: () -> Unit, id: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("card-$id")) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggle, modifier = Modifier.testTag("toggle-$id")) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "Fold $title" else "Open $title")
                }
            }
            if (expanded) content()
        }
    }
}
