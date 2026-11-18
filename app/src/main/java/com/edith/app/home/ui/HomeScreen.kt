package com.edith.app.home.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.alerts.AlertTaps
import com.edith.app.home.Capability
import com.edith.app.home.DeviceClip
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceType
import com.edith.app.home.DoorState
import com.edith.app.home.HomeActions
import com.edith.app.home.HomeDevice
import com.edith.app.home.HomeProblem
import com.edith.app.home.PrintState
import com.edith.app.home.LockState
import com.edith.app.home.HomeControlTool

/** What the Home tab can ask its ViewModel (kept apart so the UI test can drive it without one). */
data class HomeTabActions(
    val refresh: () -> Unit = {},
    val chip: (HomeChip) -> Unit = {},
    val toggle: (HomeDevice) -> Unit = {},
    val send: (HomeDevice, DeviceCommand) -> Unit = { _, _ -> },
    val step: (HomeDevice, Double) -> Unit = { _, _ -> },
    val favorite: (HomeDevice, Boolean) -> Unit = { _, _ -> },
    val hide: (HomeDevice) -> Unit = {},
    val snapshot: (HomeDevice) -> Unit = {},
    val startLive: (HomeDevice) -> Unit = {},
    val stopLive: () -> Unit = {},
    val confirm: () -> Unit = {},
    val dismiss: () -> Unit = {},
    val closePicture: () -> Unit = {},
    val messageShown: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val openClips: () -> Unit = {},
    val closeClips: () -> Unit = {},
    val moreClips: () -> Unit = {},
    val playClip: (DeviceClip) -> Unit = {},
    val closePlayer: () -> Unit = {},
)

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)) {
    val ui by vm.state.collectAsStateWithLifecycle()
    // C33: arriving from a tapped camera alert opens the Clips list.
    val fromAlert by AlertTaps.openClips.collectAsStateWithLifecycle()
    LaunchedEffect(fromAlert) {
        if (fromAlert) {
            vm.openClips()
            AlertTaps.consume()
        }
    }
    // A live view stops when the app leaves the screen: a camera left streaming drains its battery.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { vm.stopLive() }
    HomeContent(
        ui,
        HomeTabActions(
            refresh = vm::refresh, chip = vm::selectChip, toggle = vm::toggle, send = vm::send, step = vm::step,
            favorite = vm::setFavorite, hide = vm::hide, snapshot = vm::snapshot, startLive = vm::startLive, stopLive = vm::stopLive, confirm = vm::confirm, dismiss = vm::dismiss,
            closePicture = vm::closePicture, messageShown = vm::messageShown, openSettings = onOpenSettings,
            openClips = vm::openClips, closeClips = vm::closeClips, moreClips = vm::loadMoreClips, playClip = vm::play, closePlayer = vm::closePlayer,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(ui: HomeUi, a: HomeTabActions) {
    if (!ui.connected) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Connect a smart-home service to see your lights, locks, thermostat and cameras here.", textAlign = TextAlign.Center)
                Button(onClick = a.openSettings) { Text("Settings → Home") }
            }
        }
        return
    }
    var open by remember { mutableStateOf<String?>(null) }   // the device whose controls are open, by key
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); a.messageShown() }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LazyRow(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(CHIPS_TAG), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.hasClips) item(key = "clips-chip") {
                    AssistChip(onClick = a.openClips, label = { Text("Clips") }, modifier = Modifier.padding(end = 8.dp).testTag(CLIPS_CHIP_TAG))
                }
                items(HomeChip.entries) { chip ->
                    FilterChip(selected = ui.chip == chip, onClick = { a.chip(chip) }, label = { Text(chip.label) })
                }
            }
            PullToRefreshBox(isRefreshing = ui.view.loading, onRefresh = a.refresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag(LIST_TAG)) {
                    items(ui.view.problems, key = { "problem-${it.backend}" }) { ProblemBanner(it, a.openSettings) }
                    val shown = ui.shown
                    if (shown.isEmpty()) item {
                        Text(
                            when {
                                ui.view.readAt == null -> "Checking the house…"
                                ui.chip == HomeChip.Favorites -> "No favourites yet. Tap a device's star to add it here."
                                else -> "No ${ui.chip.label.lowercase()} here."
                            },
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                    // Locks and doors first, in their own section with explicit buttons (C25 §5).
                    // A 3D printer has a card of its own (C34).
                    val printers = shown.filter { it.type == DeviceType.Printer }
                    if (printers.isNotEmpty()) {
                        item(key = "h-printer") { Heading("3D printer") }
                        items(printers, key = { it.key }) { d -> PrinterTile(d, ui, a) }
                    }
                    val secure = shown.filter { it.type == DeviceType.Lock || it.type == DeviceType.GarageDoor }
                    if (secure.isNotEmpty()) {
                        item(key = "h-secure") { Heading("Locks and doors") }
                        items(secure, key = { it.key }) { d -> SecureTile(d, ui, a) }
                    }
                    for ((room, list) in (shown - secure.toSet() - printers.toSet()).groupBy { it.room ?: "Other" }.toSortedMap()) {
                        item(key = "h-$room") { Heading(room) }
                        items(list, key = { it.key }) { d -> DeviceTile(d, ui, a, onOpen = { open = d.key }) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    open?.let { key -> ui.view.devices.firstOrNull { it.key == key } }?.let { d -> Controls(d, ui, a) { open = null } }
    ui.pending?.let { p -> ConfirmDialog(p, a) }
    ui.picture?.let { (name, jpeg) -> PictureDialog(name, jpeg, a.closePicture) }
    ui.clips?.let { c -> ClipsDialog(c, ui.now, a) }
    ui.live?.let { LiveDialog(it, a.stopLive) }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun ProblemBanner(p: HomeProblem, onOpenSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${p.line} Showing what it last reported.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

/** "On, 40% · 3 min ago", or "Not responding". */
private fun stateLine(d: HomeDevice, ui: HomeUi): String {
    if (!d.online) return if (d.type == DeviceType.Printer) "Not reachable. It works on your home Wi-Fi." else "Not responding"
    val text = HomeActions.stateText(d).ifEmpty { "—" }
    val at = d.stateAt ?: return text
    return if (ui.now > 0) "$text · ${HomeActions.age(at, ui.now)}" else text
}

@Composable
private fun Star(d: HomeDevice, ui: HomeUi, a: HomeTabActions) {
    val on = d.key in ui.favorites
    IconButton(onClick = { a.favorite(d, !on) }, modifier = Modifier.semantics { contentDescription = if (on) "Remove ${d.name} from favourites" else "Add ${d.name} to favourites" }) {
        Icon(if (on) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null)
    }
}

@Composable
private fun DeviceTile(d: HomeDevice, ui: HomeUi, a: HomeTabActions, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 10.dp)) {
                Text(d.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val stepping = ui.stepping[d.key]
                val unit = d.capability<Capability.Thermostat>()?.unit
                Text(if (stepping != null) "Setting ${HomeActions.fmt(stepping)}${unit?.symbol ?: "°"}…" else stateLine(d, ui),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                d.has(Capability.Snapshot) -> {
                    if (d.key in ui.fetching) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp))
                    else TextButton(onClick = { a.snapshot(d) }) { Text("Snapshot") }
                    if (d.has(Capability.Live) && d.online) TextButton(onClick = { a.startLive(d) }) { Text("Live") }
                }
                d.capability<Capability.Thermostat>() != null -> {
                    TextButton(onClick = { a.step(d, -1.0) }, modifier = Modifier.semantics { contentDescription = "Lower ${d.name}" }) { Text("−") }
                    TextButton(onClick = { a.step(d, 1.0) }, modifier = Modifier.semantics { contentDescription = "Raise ${d.name}" }) { Text("+") }
                }
                d.has(Capability.OnOff) -> Switch(checked = d.state?.on == true, onCheckedChange = { a.toggle(d) }, enabled = d.online,
                    modifier = Modifier.semantics { contentDescription = "${d.name} on or off" })
            }
            Star(d, ui, a)
        }
    }
}

/** A 3D printer (C34): its job, a progress bar while it prints, and pause / resume / cancel; Cancel asks first, like the tools do. */
@Composable
private fun PrinterTile(d: HomeDevice, ui: HomeUi, a: HomeTabActions) {
    val job = d.state?.print
    val printing = d.online && job?.state == PrintState.Printing
    val paused = d.online && job?.state == PrintState.Paused
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stateLine(d, ui), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Star(d, ui, a)
            }
            if (printing || paused) {
                LinearProgressIndicator(progress = { (job?.progress ?: 0.0).toFloat() }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (printing) TextButton(onClick = { a.send(d, DeviceCommand.Pause) }) { Text("Pause") }
                if (paused) TextButton(onClick = { a.send(d, DeviceCommand.Resume) }) { Text("Resume") }
                if (printing || paused) TextButton(onClick = { a.send(d, DeviceCommand.CancelPrint) }) { Text("Cancel print") }
                if (d.has(Capability.Snapshot) && d.online) {
                    if (d.key in ui.fetching) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp))
                    else TextButton(onClick = { a.snapshot(d) }) { Text("Camera") }
                    if (d.has(Capability.Live) && d.online) TextButton(onClick = { a.startLive(d) }) { Text("Live") }
                }
            }
        }
    }
}

/** A lock or garage door: its state and explicit buttons; Unlock and Open ask first (C25 §5). */
@Composable
private fun SecureTile(d: HomeDevice, ui: HomeUi, a: HomeTabActions) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(d.name + (d.room?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stateLine(d, ui) + (HomeActions.movedText(d.state, ui.now.takeIf { it > 0 } ?: System.currentTimeMillis())?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (d.type == DeviceType.Lock) {
                val locked = d.state?.lock == LockState.Locked
                OutlinedButton(onClick = { a.send(d, if (locked) DeviceCommand.Unlock else DeviceCommand.Lock) }, enabled = d.online) {
                    Text(if (locked) "Unlock" else "Lock")
                }
            } else {
                val closed = d.state?.door == DoorState.Closed || d.state?.door == DoorState.Closing
                OutlinedButton(onClick = { a.send(d, if (closed) DeviceCommand.Open else DeviceCommand.Close) }, enabled = d.online) {
                    Text(if (closed) "Open" else "Close")
                }
            }
            Star(d, ui, a)
        }
    }
}

/** A device's controls (C25 §5): brightness, fan speed, colour and white, thermostat mode; Hide. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(d: HomeDevice, ui: HomeUi, a: HomeTabActions, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(d.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stateLine(d, ui), style = MaterialTheme.typography.bodyMedium)
                if (d.has(Capability.Brightness)) {
                    var level by remember(d.state?.brightness) { mutableFloatStateOf((d.state?.brightness ?: 100).toFloat()) }
                    Text("Brightness ${level.toInt()}%", style = MaterialTheme.typography.labelLarge)
                    Slider(value = level, onValueChange = { level = it }, valueRange = 1f..100f,
                        onValueChangeFinished = { a.send(d, DeviceCommand.SetBrightness(level.toInt())) })
                }
                if (d.has(Capability.Percentage)) {
                    Text("Speed", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FAN_SPEEDS.forEach { (label, p) ->
                            FilterChip(selected = d.state?.on == true && d.state.percentage == p, onClick = { a.send(d, DeviceCommand.SetPercentage(p)) }, label = { Text(label) })
                        }
                    }
                }
                d.capability<Capability.WhiteTone>()?.let { c ->
                    Text("White", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        c.names.forEach { n -> FilterChip(selected = d.state?.white == n, onClick = { a.send(d, DeviceCommand.SetWhite(n)) }, label = { Text(n) }) }
                    }
                }
                d.capability<Capability.Color>()?.let { c ->
                    Text("Colour", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        c.names.forEach { n -> FilterChip(selected = d.state?.color == n, onClick = { a.send(d, DeviceCommand.SetColor(n)) }, label = { Text(n) }) }
                    }
                }
                d.capability<Capability.Thermostat>()?.let { t ->
                    Text("Mode", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        t.modes.forEach { m -> FilterChip(selected = d.state?.mode == m, onClick = { a.send(d, DeviceCommand.SetMode(m)) }, label = { Text(m) }) }
                    }
                }
                TextButton(onClick = { a.hide(d); onClose() }) { Text("Hide this device") }
                Text("Hidden devices are left out here and from Edith. Show them again in Settings → Home.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun ConfirmDialog(p: PendingCommand, a: HomeTabActions) {
    val card = HomeControlTool.card(p.device, p.command) ?: return
    AlertDialog(
        onDismissRequest = a.dismiss,
        title = { Text(card.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                card.details.filter { it.first != "Note" }.forEach { (k, v) ->
                    Row { Text(k, fontWeight = FontWeight.Medium, modifier = Modifier.width(72.dp)); Text(v) }
                }
            }
        },
        confirmButton = { Button(onClick = a.confirm, modifier = Modifier.testTag(CONFIRM_TAG)) { Text(card.confirmLabel) } },
        dismissButton = { TextButton(onClick = a.dismiss) { Text(card.denyLabel) } },
    )
}

@Composable
private fun PictureDialog(name: String, jpeg: ByteArray, onClose: () -> Unit) {
    val bitmap = remember(jpeg) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(name) },
        text = {
            if (bitmap != null) Image(bitmap, contentDescription = "Picture from $name", modifier = Modifier.fillMaxWidth())
            else Text("The picture couldn't be shown.")
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** Jarvis's fan presets. */
private val FAN_SPEEDS = listOf("Low" to 33, "Medium" to 66, "High" to 100)

const val CHIPS_TAG = "home-chips"
const val CLIPS_CHIP_TAG = "home-clips-chip"
const val CLIPS_LIST_TAG = "home-clips-list"
const val LIST_TAG = "home-list"
const val CONFIRM_TAG = "home-confirm"

