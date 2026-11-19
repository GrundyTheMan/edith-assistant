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
import com.edith.app.printer.ui.cards.CameraCard
import com.edith.app.printer.ui.cards.ConsoleCard
import com.edith.app.printer.ui.cards.FansCard
import com.edith.app.printer.ui.cards.FilesCard
import com.edith.app.printer.ui.cards.HistoryCard
import com.edith.app.printer.ui.cards.LimitsCard
import com.edith.app.printer.ui.cards.MeshCard
import com.edith.app.printer.ui.cards.ObjectsCard
import com.edith.app.printer.ui.cards.QueueCard
import com.edith.app.printer.ui.cards.SensorsCard
import com.edith.app.printer.ui.cards.SlotsCard
import com.edith.app.printer.ui.cards.StatusCard
import com.edith.app.printer.ui.cards.SystemCard
import com.edith.app.printer.ui.cards.TemperatureCard
import com.edith.app.printer.ui.cards.ToolheadCard

/** The Printer page (C39 part 8). It asks Jarvis only while it is on screen. */
@Composable
internal fun PrinterScreen(onBack: () -> Unit, onOpenPairing: () -> Unit, vm: PrinterViewModel = viewModel(factory = PrinterViewModel.Factory)) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.attach() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.detach() }
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    PrinterContent(
        ui, is24Hour,
        PrinterActions(
            back = onBack, tryAgain = vm::tryAgain, openPairing = onOpenPairing, toggleCard = vm::toggleCard, dismissAlert = vm::dismissAlert,
            cardVisible = vm.data::visible, toggleSeries = vm.data::toggleSeries, moreHistory = vm.data::moreHistory, setHideValues = vm.data::setHideValues,
            clearConsole = vm.data::clearConsole, loadFiles = vm.data::loadFiles, setFileQuery = vm.data::setFileQuery, setPrintedOnly = vm.data::setPrintedOnly,
            openFile = vm.data::openFile, closeFile = vm.data::closeFile, chooseCamera = vm.data::chooseCamera,
        ),
        cards,
    )
}

/** The page without a ViewModel, so a UI test can draw any state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrinterContent(ui: PrinterUi, is24Hour: Boolean, a: PrinterActions, cards: CardsUi = CardsUi()) {
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
                ui.state != null -> Cards(ui, is24Hour, a, cards)
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
private fun Cards(ui: PrinterUi, is24Hour: Boolean, a: PrinterActions, data: CardsUi) {
    val state = checkNotNull(ui.state)
    LazyColumn(
        Modifier.fillMaxSize().alpha(if (ui.greyed) 0.6f else 1f).testTag("printer-list"),
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
        items(PrinterCards.ALL.filter { shows(it, state) }, key = { it }) { id ->
            CardFrame(title(id), id !in ui.collapsed, onToggle = { a.toggleCard(id) }, id = id, onVisible = a.cardVisible) {
                when (id) {
                    PrinterCards.STATUS -> StatusCard(ui, state, is24Hour, a.dismissAlert)
                    PrinterCards.CAMERA -> CameraCard(state, data.camera, a.chooseCamera)
                    PrinterCards.TEMPERATURES -> TemperatureCard(state, data.chart, data.chartHidden, a.toggleSeries)
                    PrinterCards.TOOLHEAD -> ToolheadCard(state)
                    PrinterCards.FANS -> FansCard(state)
                    PrinterCards.LIMITS -> LimitsCard(state)
                    PrinterCards.SENSORS -> SensorsCard(state)
                    PrinterCards.SLOTS -> SlotsCard(state)
                    PrinterCards.FILES -> FilesCard(state, data.files, ui.now, ui.zone, a.loadFiles, a.setFileQuery, a.setPrintedOnly, a.openFile, a.closeFile)
                    PrinterCards.OBJECTS -> ObjectsCard(state)
                    PrinterCards.QUEUE -> QueueCard(state, ui.now, ui.zone)
                    PrinterCards.HISTORY -> HistoryCard(data.history, ui.now, ui.zone, a.moreHistory)
                    PrinterCards.CONSOLE -> ConsoleCard(data.console, a.setHideValues, a.clearConsole)
                    PrinterCards.MESH -> state.mesh?.let { MeshCard(it) }
                    PrinterCards.SYSTEM -> SystemCard(state)
                    else -> Unit
                }
            }
        }
    }
}

/** Which cards this printer has now, in the order of [PrinterCards.ALL]. Macros come in stage 2 and Recordings in stage 4. */
private fun shows(id: String, state: com.edith.app.printer.feed.PrinterState): Boolean = when (id) {
    PrinterCards.MACROS, PrinterCards.RECORDINGS -> false
    PrinterCards.OBJECTS -> (state.print.state == "printing" || state.print.state == "paused") && state.objects.isNotEmpty()
    PrinterCards.MESH -> state.mesh != null
    PrinterCards.QUEUE -> state.queue != null
    else -> true
}

private fun title(id: String) = when (id) {
    PrinterCards.FANS -> "Fans and light"
    PrinterCards.LIMITS -> "Limits and retraction"
    PrinterCards.SLOTS -> "Filament slots"
    PrinterCards.MESH -> "Bed mesh"
    else -> id.replaceFirstChar { it.uppercase() }
}

/** A collapsible card; whether it is open is remembered per card. */
@Composable
private fun CardFrame(title: String, expanded: Boolean, onToggle: () -> Unit, id: String, onVisible: (String, Boolean) -> Unit, content: @Composable () -> Unit) {
    // The data layer loads a card's data only while the card is open and on screen; leaving the screen says so.
    androidx.compose.runtime.DisposableEffect(id, expanded) {
        onVisible(id, expanded)
        onDispose { onVisible(id, false) }
    }
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
