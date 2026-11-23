package com.edith.app.printer.ui.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.edith.app.printer.feed.BedMesh
import com.edith.app.printer.feed.Connections
import com.edith.app.printer.feed.FilamentSlot
import com.edith.app.printer.feed.MeshColors
import com.edith.app.printer.feed.PrintObject
import com.edith.app.printer.feed.PrinterFormat
import com.edith.app.printer.feed.PrinterState
import java.util.Locale

/** A label and its value on one line. */
@Composable
internal fun LabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Toolhead (stage 1: where it is, and which axes are homed; the controls come in stage 2). */
@Composable
internal fun ToolheadCard(state: PrinterState, ctx: ControlsCtx? = null) {
    val t = state.toolhead
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("toolhead-card")) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf("X", "Y", "Z").forEachIndexed { i, axis ->
                Column {
                    val homed = t.homed.contains(axis.lowercase())
                    Text(axis, style = MaterialTheme.typography.labelSmall, color = if (homed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                    Text(PrinterFormat.position(t.position.getOrElse(i) { 0.0 }), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Text(
            if (t.homed.isEmpty()) "Not homed" else "Homed: ${t.homed.uppercase().toList().joinToString(" ")}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!t.motorsOn) Text("Motors are off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ctx?.mayControl == true) ToolheadControls(state, ctx)
    }
}

/** Fans and light (stage 1: values). A fan the printer runs by itself says "automatic". */
@Composable
internal fun FansCard(state: PrinterState, ctx: ControlsCtx? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("fans-card")) {
        state.fans.forEach { f ->
            LabelValue(f.label, PrinterFormat.percent(f.speed) + if (f.settable) "" else " · automatic")
        }
        LabelValue("Light", state.light?.let { if (it <= 0.0) "Off" else PrinterFormat.percent(it) } ?: "No light")
        if (state.fans.isEmpty() && state.light == null) Text("This printer reports no fans.", style = MaterialTheme.typography.bodySmall)
        if (ctx?.mayControl == true) FanControls(state, ctx)
    }
}

/** Limits and retraction (stage 1: values). */
@Composable
internal fun LimitsCard(state: PrinterState, locale: Locale = Locale.getDefault(), ctx: ControlsCtx? = null) {
    val t = state.toolhead
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("limits-card")) {
        LabelValue("Speed", PrinterFormat.factor(t.speedFactor))
        LabelValue("Flow", PrinterFormat.factor(t.extrudeFactor))
        LabelValue("Velocity", "${Math.round(t.maxVelocity)} mm/s")
        LabelValue("Acceleration", "${Math.round(t.maxAccel)} mm/s²")
        LabelValue("Square corner velocity", "${PrinterFormat.position(t.squareCornerVelocity, locale)} mm/s")
        LabelValue("Minimum cruise ratio", PrinterFormat.position(t.minimumCruiseRatio, locale))
        state.retraction?.let { r ->
            Text("Retraction", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            LabelValue("Length", "${PrinterFormat.position(r.length, locale)} mm")
            LabelValue("Speed", "${PrinterFormat.position(r.speed, locale)} mm/s")
            LabelValue("Extra length", "${PrinterFormat.position(r.extraLength, locale)} mm")
            LabelValue("Unretract speed", "${PrinterFormat.position(r.unretractSpeed, locale)} mm/s")
        }
        if (ctx?.mayControl == true) LimitControls(state, ctx)
    }
}

/** Sensors: the printer's temperature sensors, and its filament sensors. A reading that is not a temperature is left out. */
@Composable
internal fun SensorsCard(state: PrinterState, ctx: ControlsCtx? = null) {
    val temps = state.sensors.filter { it.unit.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("sensors-card")) {
        temps.forEach { LabelValue(it.label, PrinterFormat.temperature(it.value)) }
        state.filamentSensors.forEach { f ->
            LabelValue(f.label, (if (f.detected) "Filament detected" else "No filament") + " · " + (if (f.enabled) "enabled" else "disabled"))
        }
        if (temps.isEmpty() && state.filamentSensors.isEmpty()) Text("This printer reports no sensors.", style = MaterialTheme.typography.bodySmall)
        if (ctx?.mayControl == true) SensorControls(state, ctx)
    }
}

/** Filament slots: a swatch, the material and the colour's name. When the printer is not reporting them, no slot is called empty. */
@Composable
internal fun SlotsCard(state: PrinterState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("slots-card")) {
        state.slots.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(22.dp).background(swatch(s), CircleShape))
                Text("${s.material} · ${s.color}".trim(' ', '·'), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (state.slotsLive) Text(if (s.loaded) "Loaded" else "Empty", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!state.slotsLive) Text("The printer isn't reporting which slots have filament right now. Its screen shows them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.slots.isEmpty()) Text("This printer reports no filament slots.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun swatch(s: FilamentSlot): Color = runCatching { Color(("#" + s.hex.trimStart('#')).toColorInt()) }.getOrDefault(Color.Gray)

/** System: what Jarvis says about the connection, the software versions and the printer's own computer. */
@Composable
internal fun SystemCard(state: PrinterState, ctx: ControlsCtx? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("system-card")) {
        state.message?.takeIf { it.isNotBlank() && state.connection != Connections.READY }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        state.hostname?.let { LabelValue("Host name", it) }
        state.versions?.moonraker?.let { LabelValue("Moonraker", it) }
        state.versions?.mcu?.let { LabelValue("MCU", it) }
        state.host?.let { LabelValue("Load", String.format(androidx.compose.ui.platform.LocalConfiguration.current.locales[0], "%.2f", it.load)); LabelValue("Memory free", "${(it.memAvailableKb / 1024).toLong()} MB") }
        if (ctx?.mayControl == true) SystemControls(ctx)
    }
}

/** Queue: the printer's list of waiting jobs. */
@Composable
internal fun QueueCard(state: PrinterState, nowMs: Long, zone: kotlinx.datetime.TimeZone, ctx: ControlsCtx? = null) {
    val q = state.queue ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("queue-card")) {
        Text(
            when (q.state) {
                "ready" -> "The next job starts as soon as the printer is free"
                "paused" -> "The queue is paused"
                else -> "Starting the next job…"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (q.jobs.isEmpty()) Text("No jobs are waiting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        q.jobs.forEach { j -> LabelValue(j.title.ifBlank { j.filename }, PrinterFormat.age(j.added, nowMs, zone)) }
        Text("Starting prints from here needs the printer in Local Only mode.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ctx?.mayControl == true) QueueControls(state, ctx)
    }
}

/** Objects: the parts of this print on a drawing of the bed (the one being printed in the accent colour, excluded ones crossed out). */
@Composable
internal fun ObjectsCard(state: PrinterState, ctx: ControlsCtx? = null) {
    val maxX = state.toolhead.max.getOrElse(0) { 0.0 }.takeIf { it > 0 } ?: 220.0
    val maxY = state.toolhead.max.getOrElse(1) { 0.0 }.takeIf { it > 0 } ?: 220.0
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("objects-card")) {
        Canvas(Modifier.fillMaxWidth().aspectRatio((maxX / maxY).toFloat()).background(grid)) {
            fun px(x: Double) = (x / maxX * size.width).toFloat()
            fun py(y: Double) = (size.height - (y / maxY * size.height)).toFloat()   // the back of the bed is the top
            state.objects.forEach { o ->
                val poly = o.polygon?.takeIf { it.size >= 3 } ?: return@forEach
                val path = Path().apply {
                    moveTo(px(poly[0][0]), py(poly[0][1]))
                    for (p in poly.drop(1)) lineTo(px(p[0]), py(p[1]))
                    close()
                }
                val color = if (o.excluded) muted else if (o.current) accent else muted
                drawPath(path, color.copy(alpha = if (o.excluded) 0.15f else 0.35f))
                drawPath(path, color, style = Stroke(width = if (o.current) 4f else 2f))
                if (o.excluded) {
                    drawLine(muted, Offset(px(poly.minOf { it[0] }), py(poly.minOf { it[1] })), Offset(px(poly.maxOf { it[0] }), py(poly.maxOf { it[1] })), strokeWidth = 3f)
                }
            }
        }
        state.objects.forEach { o ->
            LabelValue(o.name, objectNote(o))
        }
        if (ctx?.mayControl == true) ObjectControls(state, ctx)
    }
}

private fun objectNote(o: PrintObject) = when {
    o.excluded -> "excluded"
    o.current -> "printing now"
    else -> ""
}

/** Bed mesh: a grid of heights, the back of the bed at the top; blue where the bed is low, red where it is high. View only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MeshCard(mesh: BedMesh, locale: Locale = Locale.getDefault()) {
    val all = mesh.points.flatten()
    val lo = all.minOrNull() ?: 0.0
    val hi = all.maxOrNull() ?: 0.0
    val extremes = MeshColors.extremes(mesh.points)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("mesh-card")) {
        if (!mesh.active) Text("Saved profile (not in use now)", style = MaterialTheme.typography.labelLarge)
        Text("Profile: ${mesh.profile}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Rows run from the front (min Y) to the back: draw the back first so it is at the top.
            mesh.points.asReversed().forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { v ->
                        val (r, g, b) = MeshColors.rgb(v, lo, hi)
                        Box(Modifier.weight(1f).height(28.dp).background(Color(r, g, b)), contentAlignment = Alignment.Center) {
                            Text(String.format(locale, "%.2f", v), style = MaterialTheme.typography.labelSmall, color = Color.Black)
                        }
                    }
                }
            }
        }
        extremes?.let { e ->
            LabelValue("Spread", "${String.format(locale, "%.3f", e.spread)} mm")
            LabelValue("Lowest", "${String.format(locale, "%.3f", e.lowest.first)} mm")
            LabelValue("Highest", "${String.format(locale, "%.3f", e.highest.first)} mm")
        }
    }
}
