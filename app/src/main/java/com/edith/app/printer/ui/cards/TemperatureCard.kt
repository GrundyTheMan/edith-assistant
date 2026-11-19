package com.edith.app.printer.ui.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edith.app.printer.feed.ChartLine
import com.edith.app.printer.feed.ChartMath
import com.edith.app.printer.feed.PrinterFormat
import com.edith.app.printer.feed.PrinterState

/**
 * Temperatures: a row for each heater (current, target, how hard it is working), then the chart of the last 20 minutes (stage 1: the
 * values; setting targets and presets come in stage 2).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TemperatureCard(state: PrinterState, lines: List<ChartLine>, hidden: Set<String>, onToggleLine: (String) -> Unit) {
    val colors = lineColors()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("temperature-card")) {
        state.heaters.forEach { h ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(h.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text(PrinterFormat.temperature(h.temperature), style = MaterialTheme.typography.titleLarge)
                    Text("→ ${PrinterFormat.target(h.target)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(progress = { h.power.coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp))
            }
        }
        if (lines.any { it.values.size >= 2 }) {
            TempChart(lines, hidden, Modifier.fillMaxWidth().height(180.dp).testTag("temp-chart"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEachIndexed { i, line ->
                    // Dashed means the target, solid the reading, so colour is never the only difference (part 8.4).
                    FilterChip(
                        selected = line.id !in hidden, onClick = { onToggleLine(line.id) }, label = { Text(line.label) },
                        leadingIcon = { androidx.compose.foundation.layout.Box(Modifier.size(12.dp).background(colors[i % colors.size], androidx.compose.foundation.shape.CircleShape)) },
                    )
                }
            }
        } else {
            Text("The chart fills in as the printer reports.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The line colours: theme colours with enough contrast on the card in both light and dark. */
@Composable
private fun lineColors(): List<Color> = with(MaterialTheme.colorScheme) { listOf(primary, tertiary, error, secondary, onSurface, outline) }

/** Time runs left to right; the y axis goes from 0 to the highest value seen plus a tenth, with a line every 50 °C. */
@Composable
internal fun TempChart(lines: List<ChartLine>, hidden: Set<String>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val colors = lineColors()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val shown = lines.filter { it.id !in hidden }
    val yMax = ChartMath.yMax(lines, hidden)
    Canvas(modifier) {
        val left = 36.dp.toPx(); val bottom = size.height - 16.dp.toPx(); val top = 4.dp.toPx()
        val plotW = size.width - left; val plotH = bottom - top
        ChartMath.gridlines(yMax).forEach { g ->
            val y = bottom - (ChartMath.fraction(g, yMax) * plotH).toFloat()
            drawLine(grid, Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
            drawText(measurer, "${g.toInt()}", Offset(0f, y - 7.dp.toPx()), label)
        }
        val points = lines.maxOfOrNull { it.values.size } ?: 0
        if (points >= 2) {
            fun x(i: Int, n: Int) = left + plotW * (i.toFloat() / (n - 1).coerceAtLeast(1)) * 1f
            lines.forEachIndexed { idx, line ->
                if (line.id in hidden || line.values.size < 2) return@forEachIndexed
                val color = colors[idx % colors.size]
                val n = line.values.size
                fun path(values: List<Double>, dashed: Boolean) {
                    for (i in 1 until values.size) {
                        drawLine(
                            color, Offset(x(i - 1, values.size), bottom - (ChartMath.fraction(values[i - 1], yMax) * plotH).toFloat()),
                            Offset(x(i, values.size), bottom - (ChartMath.fraction(values[i], yMax) * plotH).toFloat()),
                            strokeWidth = if (dashed) 2f else 4f,
                            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
                        )
                    }
                }
                path(line.values, dashed = false)
                line.targets?.takeIf { t -> t.size == n && t.any { it > 0 } }?.let { path(it, dashed = true) }
            }
            drawText(measurer, ChartMath.minutesAgo(0, points), Offset(left, bottom + 2.dp.toPx()), label)
            drawText(measurer, "now", Offset(size.width - 22.dp.toPx(), bottom + 2.dp.toPx()), label)
        }
        if (shown.isEmpty()) drawText(measurer, "Every line is hidden", Offset(left + 8.dp.toPx(), top + 8.dp.toPx()), label)
    }
}
