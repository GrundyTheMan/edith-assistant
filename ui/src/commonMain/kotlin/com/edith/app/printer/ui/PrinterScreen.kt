package com.edith.app.printer.ui

import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edith.app.printer.PrinterCards
import com.edith.app.printer.feed.PrinterScreenState
import com.edith.app.printer.feed.Connections
import com.edith.app.printer.ui.cards.CameraCard
import com.edith.app.printer.ui.cards.ControlsCtx
import com.edith.app.printer.ui.cards.FirmwareRestartButton
import com.edith.app.printer.ui.cards.ConsoleCard
import com.edith.app.printer.ui.cards.FansCard
import com.edith.app.printer.ui.cards.FilesCard
import com.edith.app.printer.ui.cards.HistoryCard
import com.edith.app.printer.ui.cards.LimitsCard
import com.edith.app.printer.ui.cards.MacrosCard
import com.edith.app.printer.ui.cards.MeshCard
import com.edith.app.printer.ui.cards.ObjectsCard
import com.edith.app.printer.ui.cards.QueueCard
import com.edith.app.printer.ui.cards.SensorsCard
import com.edith.app.printer.ui.cards.SlotsCard
import com.edith.app.printer.ui.cards.StatusCard
import com.edith.app.printer.ui.cards.SystemCard
import com.edith.app.printer.ui.cards.TemperatureCard
import com.edith.app.printer.ui.cards.ToolheadCard

/** The page without a ViewModel, so a UI test can draw any state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterContent(ui: PrinterUi, is24Hour: Boolean, a: PrinterActions, cards: CardsUi = CardsUi(), controls: ControlUi = ControlUi()) {
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(controls.message) { controls.message?.let { snackbar.showSnackbar(it); a.messageShown() } }
    controls.sheet?.let { ConfirmSheet(it, onConfirm = a.confirm, onDismiss = a.dismissSheet) }
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            Column {
            TopAppBar(
                title = {
                    Column {
                        Text(ui.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        val chip = ui.chip
                        if (chip.text.isNotEmpty()) Text(chip.text, style = MaterialTheme.typography.labelMedium, color = chipColor(chip.tone), modifier = Modifier.testTag("connection-chip"))
                    }
                },
                navigationIcon = { IconButton(onClick = a.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    // The emergency stop works with Controls off, from a look-only phone too; Jarvis decides and the owner confirms first.
                    if (ui.state?.isReady == true && !ui.greyed) {
                        Button(
                            onClick = { a.send(com.edith.app.printer.feed.PrinterCommand.EStop) },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                            modifier = Modifier.padding(end = 8.dp).testTag("estop"),
                        ) { Text("Emergency stop") }
                    }
                },
            )
            ControlsBar(ui, controls, a.setControls)
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val message = ui.message
            when {
                ui.screen is PrinterScreenState.Connecting && ui.state == null -> Connecting()
                message != null -> FullMessage(message, ui, a)
                ui.state != null -> Cards(ui, is24Hour, a, cards, controls)
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
private fun Cards(ui: PrinterUi, is24Hour: Boolean, a: PrinterActions, data: CardsUi, controls: ControlUi) {
    val state = checkNotNull(ui.state)
    val ctx = ControlsCtx(ui, controls, a.send)
    LazyColumn(
        Modifier.fillMaxSize().alpha(if (ui.greyed) 0.6f else 1f).then(if (controls.armed) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier).testTag("printer-list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ui.banner?.let { banner ->
            item(key = "banner") {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag("banner")) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(banner, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        if (ui.screen is PrinterScreenState.JarvisAway) TextButton(onClick = a.tryAgain) { Text("Try again") }
                        // When Klipper has stopped, the way out is a firmware restart (Controls may be switched on in that state).
                        if (ctx.mayControl && (state.connection == Connections.KLIPPY_SHUTDOWN || state.connection == Connections.KLIPPY_ERROR)) FirmwareRestartButton(ctx)
                    }
                }
            }
        }
        items(PrinterCards.ALL.filter { shows(it, state, controls.mayControl) }, key = { it }) { id ->
            CardFrame(title(id), id !in ui.collapsed, onToggle = { a.toggleCard(id) }, id = id, onVisible = a.cardVisible) {
                when (id) {
                    PrinterCards.STATUS -> StatusCard(ui, state, is24Hour, a.dismissAlert, controls, a.send)
                    PrinterCards.CAMERA -> CameraCard(state, data.camera, a.chooseCamera, ctx)
                    PrinterCards.TEMPERATURES -> TemperatureCard(state, data.chart, data.chartHidden, a.toggleSeries, ctx, data.presets, a.savePresets, a.clearPresetsError)
                    PrinterCards.TOOLHEAD -> ToolheadCard(state, ctx)
                    PrinterCards.FANS -> FansCard(state, ctx)
                    PrinterCards.LIMITS -> LimitsCard(state, ctx = ctx)
                    PrinterCards.SENSORS -> SensorsCard(state, ctx)
                    PrinterCards.SLOTS -> SlotsCard(state)
                    PrinterCards.FILES -> FilesCard(state, data.files, ui.now, ui.zone, a.loadFiles, a.setFileQuery, a.setPrintedOnly, a.openFile, a.closeFile, ctx, a.pickUpload, a.sendUpload, a.cancelUpload, a.clearUpload)
                    PrinterCards.OBJECTS -> ObjectsCard(state, ctx)
                    PrinterCards.QUEUE -> QueueCard(state, ui.now, ui.zone, ctx)
                    PrinterCards.HISTORY -> HistoryCard(data.history, ui.now, ui.zone, a.moreHistory)
                    PrinterCards.CONSOLE -> ConsoleCard(data.console, a.setHideValues, a.clearConsole, ctx, a.rememberConsoleLine)
                    PrinterCards.MACROS -> MacrosCard(data.macros, ctx, a.setMacroQuery, a.setHiddenMacros)
                    PrinterCards.MESH -> state.mesh?.let { MeshCard(it) }
                    PrinterCards.SYSTEM -> SystemCard(state, ctx)
                    else -> Unit
                }
            }
        }
    }
}

/** Which cards this printer has now, in the order of [PrinterCards.ALL]. Macros come in stage 2 and Recordings in stage 4. */
private fun shows(id: String, state: com.edith.app.printer.feed.PrinterState, mayControl: Boolean): Boolean = when (id) {
    PrinterCards.RECORDINGS -> false
    // Every macro button is a control: a look-only phone has no Macros card at all.
    PrinterCards.MACROS -> mayControl
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
    PrinterCards.MACROS -> "Macros"
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

/** The Printer page's actions, wired to [this] view model: the same on every platform, which adds only the back button, the pairing page and the file picker. */
fun PrinterViewModel.pageActions(back: () -> Unit, openPairing: () -> Unit, pickUpload: () -> Unit = {}) = PrinterActions(
    pickUpload = pickUpload, sendUpload = data::sendUpload, cancelUpload = data::cancelUpload, clearUpload = data::clearUpload,
    back = back, tryAgain = ::tryAgain, openPairing = openPairing, toggleCard = ::toggleCard, dismissAlert = ::dismissAlert,
    cardVisible = data::visible, toggleSeries = data::toggleSeries, moreHistory = data::moreHistory, setHideValues = data::setHideValues,
    clearConsole = data::clearConsole, loadFiles = data::loadFiles, setFileQuery = data::setFileQuery, setPrintedOnly = data::setPrintedOnly,
    openFile = data::openFile, closeFile = data::closeFile, chooseCamera = data::chooseCamera,
    setMacroQuery = data::setMacroQuery, setHiddenMacros = data::setHiddenMacros, rememberConsoleLine = data::rememberConsoleLine, savePresets = data::savePresets, clearPresetsError = data::clearPresetsError,
    setControls = control::setControls, send = control::send, confirm = control::confirm, dismissSheet = control::dismissSheet, messageShown = control::messageShown,
)
