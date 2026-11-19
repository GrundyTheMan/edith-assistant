package com.edith.app.printer.ui.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.input.ImeAction
import com.edith.app.printer.feed.ConsoleHistory
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.edith.app.printer.feed.Axis
import com.edith.app.printer.feed.AirMode
import com.edith.app.printer.feed.HomeAxes
import com.edith.app.printer.feed.PrinterCommand
import com.edith.app.printer.feed.PrinterFormat
import com.edith.app.printer.feed.PrinterInput
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.feed.Preset
import com.edith.app.printer.ui.ControlUi
import com.edith.app.printer.ui.PrinterUi
import com.edith.app.printer.ui.controlKey
import com.edith.app.printer.ui.disabledReason

/**
 * What a card needs to draw controls (C39 stage 2): the page's state, Controls, and how to send. A card draws controls only when
 * [mayControl] (Jarvis lets this phone control the printer); otherwise it stays look-only and draws nothing extra.
 */
internal class ControlsCtx(val ui: PrinterUi, val controls: ControlUi, val send: (PrinterCommand) -> Unit) {
    val mayControl: Boolean get() = controls.mayControl
}

/** A button that sends [cmd]: disabled with Jarvis's reason when it cannot be used, a spinner while its change is awaited. [cmd] null means the boxes are not valid yet. */
@Composable
internal fun CmdButton(
    text: String, cmd: PrinterCommand?, ctx: ControlsCtx, quickKey: String? = null, danger: Boolean = false, filled: Boolean = false, tag: String? = null, after: () -> Unit = {},
) {
    val key = cmd?.let { controlKey(it) }
    val pending = key != null && key in ctx.controls.pending
    val enabled = cmd != null && disabledReason(ctx.ui, ctx.controls, quickKey) == null && !pending
    val label: @Composable () -> Unit = {
        if (pending) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
        Text(text)
    }
    val tagged = Modifier.testTag(tag ?: "cmd:$text")
    if (filled) Button(onClick = { cmd?.let { ctx.send(it); after() } }, enabled = enabled, modifier = tagged, colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) { label() }
    else OutlinedButton(
        onClick = { cmd?.let { ctx.send(it); after() } }, enabled = enabled, modifier = tagged,
        colors = if (danger) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.outlinedButtonColors(),
    ) { label() }
}

/** One line saying why this group of controls cannot be used (Controls off, or Jarvis away); nothing when they can. */
@Composable
internal fun WhyOff(ctx: ControlsCtx, quickKey: String? = null) {
    disabledReason(ctx.ui, ctx.controls, quickKey)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("why-off"))
    }
}

/** Jarvis's refusal of a command, shown under the control that sent it ("Home the printer first."). */
@Composable
internal fun Refusal(ctx: ControlsCtx, vararg keys: String) {
    keys.firstNotNullOfOrNull { ctx.controls.refused[it] }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("refusal"))
    }
}

private val numeric = KeyboardOptions(keyboardType = KeyboardType.Decimal)

@Composable
private fun NumberBox(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, keyboardOptions = numeric, modifier = modifier)

/** A slider that sends only when the finger is lifted (never a command for each pixel). */
@Composable
private fun ReleaseSlider(value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, modifier: Modifier = Modifier, onRelease: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onRelease(v) }, valueRange = range, enabled = enabled, modifier = modifier)
}

// --- Temperatures ---------------------------------------------------------------------------------------------------

/** A target for each heater (Set, Off), the presets from Jarvis, and All off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TemperatureControls(
    state: PrinterState, presets: List<Preset>, ctx: ControlsCtx,
    editor: com.edith.app.printer.ui.PresetsUi = com.edith.app.printer.ui.PresetsUi(), onSavePresets: (List<Preset>) -> Unit = {}, onClearError: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(editor.saved) { if (editor.saved > 0) editing = false }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("temperature-controls")) {
        state.heaters.forEach { h ->
            var text by remember(h.id) { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberBox("${h.label} °C", text, { text = it }, Modifier.weight(1f).testTag("target-${h.id}"))
                CmdButton("Set", PrinterInput.setTemp(h.id, text, h.maxTemp), ctx, tag = "set-${h.id}")
                CmdButton("Off", PrinterCommand.SetTemp(h.id, 0.0), ctx, tag = "off-${h.id}")
            }
            Refusal(ctx, "setTemp:${h.id}")
        }
        if (presets.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { p -> CmdButton(p.name, PrinterCommand.Preset(p.name), ctx) }
        }
        Refusal(ctx, *presets.map { "preset:${it.name}" }.toTypedArray())
        CmdButton("All off", PrinterCommand.HeatersOff, ctx, quickKey = "heatersOff")
        OutlinedButton(onClick = { onClearError(); editing = true }, modifier = Modifier.testTag("edit-presets")) { Text("Edit presets") }
        WhyOff(ctx)
    }
    if (editing) PresetsDialog(presets, ctx, editor, onSave = onSavePresets, onClose = { editing = false; onClearError() })
}

// --- Toolhead --------------------------------------------------------------------------------------------------------

/** Home, a jog cross with its step chips, the Z offset, extrude and retract, and motors off. Nothing repeats while held. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolheadControls(state: PrinterState, ctx: ControlsCtx) {
    var step by remember { mutableDoubleStateOf(1.0) }
    var length by remember { mutableStateOf("10") }
    var speed by remember { mutableStateOf("5") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("toolhead-controls")) {
        Text("Home", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CmdButton("All", PrinterCommand.Home(HomeAxes.ALL), ctx, "home:xyz")
            CmdButton("XY", PrinterCommand.Home(HomeAxes.XY), ctx, "home:xy")
            CmdButton("Z", PrinterCommand.Home(HomeAxes.Z), ctx, "home:z")
        }
        Text("Move", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrinterInput.STEPS.forEach { s ->
                FilterChip(selected = step == s, onClick = { step = s }, label = { Text(PrinterInput.stepLabel(s)) }, modifier = Modifier.testTag("step-${PrinterInput.stepLabel(s)}"))
            }
        }
        // The label says Z+ and Z-, never "bed up" or "bed down": which way the bed moves depends on the printer.
        fun d(axis: String): Double = if (axis == Axis.Z && step > 50.0) 50.0 else step
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CmdButton("X−", PrinterCommand.Jog(Axis.X, -d(Axis.X)), ctx)
            CmdButton("X+", PrinterCommand.Jog(Axis.X, d(Axis.X)), ctx)
            CmdButton("Y−", PrinterCommand.Jog(Axis.Y, -d(Axis.Y)), ctx)
            CmdButton("Y+", PrinterCommand.Jog(Axis.Y, d(Axis.Y)), ctx)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CmdButton("Z−", PrinterCommand.Jog(Axis.Z, -d(Axis.Z)), ctx)
            CmdButton("Z+", PrinterCommand.Jog(Axis.Z, d(Axis.Z)), ctx)
        }
        Refusal(ctx, "jog")
        Text("Z offset ${PrinterFormat.position(state.toolhead.zOffset)} mm", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PrinterInput.Z_OFFSET_STEPS.forEach { z -> CmdButton("−${PrinterInput.stepLabel(z)}", PrinterCommand.ZOffset(-z), ctx) }
            PrinterInput.Z_OFFSET_STEPS.forEach { z -> CmdButton("+${PrinterInput.stepLabel(z)}", PrinterCommand.ZOffset(z), ctx) }
        }
        Text("Filament", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberBox("Length mm", length, { length = it }, Modifier.weight(1f).testTag("extrude-length"))
            NumberBox("Speed mm/s", speed, { speed = it }, Modifier.weight(1f).testTag("extrude-speed"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CmdButton("Extrude", PrinterInput.extrude(length, speed, retract = false), ctx)
            CmdButton("Retract", PrinterInput.extrude(length, speed, retract = true), ctx)
        }
        Refusal(ctx, "extrude")
        CmdButton("Motors off", PrinterCommand.MotorsOff, ctx, "motorsOff")
        WhyOff(ctx)
    }
}

// --- Fans, light, air --------------------------------------------------------------------------------------------------

/** Settable fans as sliders (sent on release), the light, and the air circulation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FanControls(state: PrinterState, ctx: ControlsCtx) {
    val off = disabledReason(ctx.ui, ctx.controls) != null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("fan-controls")) {
        state.fans.filter { it.settable }.forEach { f ->
            Text("${f.label} ${PrinterFormat.percent(f.speed)}", style = MaterialTheme.typography.labelLarge)
            ReleaseSlider((f.speed * 100).toFloat(), 0f..100f, !off, Modifier.testTag("fan-${f.id}")) { ctx.send(PrinterCommand.Fan(f.id, PrinterInput.level(it))) }
        }
        if (state.light != null) {
            Text("Light ${PrinterFormat.percent(state.light)}", style = MaterialTheme.typography.labelLarge)
            ReleaseSlider((state.light * 100).toFloat(), 0f..100f, !off, Modifier.testTag("light-slider")) { ctx.send(PrinterCommand.Light(PrinterInput.level(it))) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CmdButton("On", PrinterCommand.Light(1.0), ctx)
                CmdButton("Off", PrinterCommand.Light(0.0), ctx)
            }
        }
        Text("Air circulation", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CmdButton("Internal", PrinterCommand.AirCirculation(AirMode.INTERNAL), ctx, "air:internal")
            CmdButton("External", PrinterCommand.AirCirculation(AirMode.EXTERNAL), ctx, "air:external")
            CmdButton("Stop", PrinterCommand.AirCirculation(AirMode.STOP), ctx, "air:stop")
        }
        WhyOff(ctx)
    }
}

// --- Limits and retraction ---------------------------------------------------------------------------------------------

/** Speed and flow sliders (on release), the speed limits and the retraction with Apply (Jarvis asks before it changes them). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LimitControls(state: PrinterState, ctx: ControlsCtx) {
    val t = state.toolhead
    val off = disabledReason(ctx.ui, ctx.controls) != null
    var velocity by remember { mutableStateOf("") }; var accel by remember { mutableStateOf("") }
    var scv by remember { mutableStateOf("") }; var cruise by remember { mutableStateOf("") }
    var rLength by remember { mutableStateOf("") }; var rSpeed by remember { mutableStateOf("") }
    var rExtra by remember { mutableStateOf("") }; var rUnretract by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("limit-controls")) {
        Text("Speed ${PrinterFormat.factor(t.speedFactor)}", style = MaterialTheme.typography.labelLarge)
        ReleaseSlider((t.speedFactor * 100).toFloat(), 10f..300f, !off, Modifier.testTag("speed-slider")) { ctx.send(PrinterCommand.SpeedFactor(PrinterInput.percent(it))) }
        Text("Flow ${PrinterFormat.factor(t.extrudeFactor)}", style = MaterialTheme.typography.labelLarge)
        ReleaseSlider((t.extrudeFactor * 100).toFloat(), 50f..150f, !off, Modifier.testTag("flow-slider")) { ctx.send(PrinterCommand.FlowFactor(PrinterInput.percent(it))) }
        Text("Speed limits (leave a box empty to leave it as it is)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberBox("Velocity mm/s", velocity, { velocity = it }, Modifier.fillMaxWidth(0.48f).testTag("limit-velocity"))
            NumberBox("Accel mm/s²", accel, { accel = it }, Modifier.fillMaxWidth(0.48f).testTag("limit-accel"))
            NumberBox("Corner velocity", scv, { scv = it }, Modifier.fillMaxWidth(0.48f).testTag("limit-scv"))
            NumberBox("Cruise ratio", cruise, { cruise = it }, Modifier.fillMaxWidth(0.48f).testTag("limit-cruise"))
        }
        CmdButton("Apply limits", PrinterInput.limits(velocity, accel, scv, cruise), ctx)
        Refusal(ctx, "limits")
        Text("Back to the printer's settings after a restart.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.retraction != null) {
            Text("Retraction", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberBox("Length mm", rLength, { rLength = it }, Modifier.fillMaxWidth(0.48f).testTag("retraction-length"))
                NumberBox("Speed mm/s", rSpeed, { rSpeed = it }, Modifier.fillMaxWidth(0.48f).testTag("retraction-speed"))
                NumberBox("Extra length mm", rExtra, { rExtra = it }, Modifier.fillMaxWidth(0.48f).testTag("retraction-extra"))
                NumberBox("Unretract mm/s", rUnretract, { rUnretract = it }, Modifier.fillMaxWidth(0.48f).testTag("retraction-unretract"))
            }
            CmdButton("Apply retraction", PrinterInput.retraction(rLength, rSpeed, rExtra, rUnretract), ctx)
            Refusal(ctx, "retraction")
        }
        WhyOff(ctx)
    }
}

// --- Sensors ---------------------------------------------------------------------------------------------------------

/** The filament sensor's Enabled switch (Jarvis asks first, and calls it danger when turning it off during a print). */
@Composable
internal fun SensorControls(state: PrinterState, ctx: ControlsCtx) {
    if (state.filamentSensors.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("sensor-controls")) {
        state.filamentSensors.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${f.label} enabled", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = f.enabled, onCheckedChange = { ctx.send(PrinterCommand.FilamentSensor(f.id, it)) }, enabled = disabledReason(ctx.ui, ctx.controls) == null, modifier = Modifier.testTag("filament-${f.id}"))
            }
        }
        WhyOff(ctx)
    }
}

// --- Objects, queue, system, camera --------------------------------------------------------------------------------------------

/** Exclude an object from this print (Jarvis asks, and says it cannot be undone). */
@Composable
internal fun ObjectControls(state: PrinterState, ctx: ControlsCtx) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("object-controls")) {
        state.objects.filter { !it.excluded }.forEach { o -> CmdButton("Exclude ${o.name}", PrinterCommand.ExcludeObject(o.name), ctx, danger = true) }
        Refusal(ctx, "excludeObject")
        WhyOff(ctx)
    }
}

/** Remove a waiting job, start the queue, pause the queue. */
@Composable
internal fun QueueControls(state: PrinterState, ctx: ControlsCtx) {
    val q = state.queue ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("queue-controls")) {
        q.jobs.forEach { j -> CmdButton("Remove ${j.title.ifBlank { j.filename }}", PrinterCommand.QueueRemove(j.id), ctx) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CmdButton("Start queue", PrinterCommand.QueueStart, ctx, "queueStart")
            CmdButton("Pause queue", PrinterCommand.QueuePause, ctx, "queuePause")
        }
        WhyOff(ctx)
    }
}

/** Restart Klipper and Firmware restart (both asked first, as dangerous). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SystemControls(ctx: ControlsCtx) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("system-controls")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CmdButton("Restart Klipper", PrinterCommand.Restart, ctx, "restart", danger = true)
            CmdButton("Firmware restart", PrinterCommand.FirmwareRestart, ctx, "firmwareRestart", danger = true)
        }
        WhyOff(ctx)
    }
}

/** The camera's own switch, offered when the picture says it is offline. */
@Composable
internal fun CameraControls(ctx: ControlsCtx) {
    CmdButton("Turn camera on", PrinterCommand.Camera(true), ctx, "camera:on")
}

// --- Console input ---------------------------------------------------------------------------------------------------

/**
 * The console's one-line input (C39 part 8.4): at most 200 characters, never a line break, Enter or Send asks Jarvis (the sheet then shows the
 * exact line and its risk), and the arrows walk through the last 50 lines sent. Jarvis's refusal ("Use the System card or the printer's screen
 * for this") shows under the box.
 */
@Composable
internal fun ConsoleInput(history: List<String>, ctx: ControlsCtx, onRemember: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var index by remember { mutableIntStateOf(-1) }
    val cmd = PrinterInput.consoleLine(text)
    fun sendNow() { cmd?.let { ctx.send(it); onRemember(it.script); text = ""; index = -1 } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("console-input")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { typed -> text = typed.filter { it.code != 10 && it.code != 13 }.take(PrinterInput.MAX_LINE); index = -1 },
                label = { Text("Send a line to the printer") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { sendNow() }),
                modifier = Modifier.weight(1f).testTag("console-line"),
            )
            IconButton(onClick = { index = ConsoleHistory.older(history, index); text = ConsoleHistory.at(history, index) }, enabled = history.isNotEmpty(), modifier = Modifier.testTag("console-older")) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Older line")
            }
            IconButton(onClick = { index = ConsoleHistory.newer(index); text = ConsoleHistory.at(history, index) }, enabled = index >= 0, modifier = Modifier.testTag("console-newer")) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Newer line")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CmdButton("Send", cmd, ctx, tag = "console-send", after = { cmd?.let { onRemember(it.script) }; text = ""; index = -1 })
            Text("${text.length}/${PrinterInput.MAX_LINE}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Refusal(ctx, "gcode")
        WhyOff(ctx)
    }
}

/** Used by the banner when Klipper has stopped: the way out is a firmware restart. */
@Composable
internal fun FirmwareRestartButton(ctx: ControlsCtx) {
    CmdButton("Firmware restart", PrinterCommand.FirmwareRestart, ctx, "firmwareRestart", danger = true)
}
