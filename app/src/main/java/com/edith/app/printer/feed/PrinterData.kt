package com.edith.app.printer.feed

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One line of the temperature chart: a value for each second, oldest first, with the target where the line is a heater. */
internal data class ChartLine(val id: String, val label: String, val values: List<Double>, val targets: List<Double>?)

/**
 * The chart's data (C39 8.4 Temperatures): at most [cap] points a line (20 minutes at a point a second), seeded from Jarvis's
 * `temperatures` route when the card opens and then given one point per `state` answer. Never grows without limit.
 */
internal class ChartBuffer(private val cap: Int = 1200) {
    private class Mutable(val label: String, val values: ArrayDeque<Double> = ArrayDeque(), var targets: ArrayDeque<Double>? = null)
    private val lines = LinkedHashMap<String, Mutable>()

    /** Replaces everything with what Jarvis has kept (about the last 20 minutes). */
    fun seed(series: List<TempSeries>) {
        lines.clear()
        for (s in series) {
            val m = Mutable(s.label)
            m.values.addAll(s.temperatures.takeLast(cap))
            s.targets?.let { m.targets = ArrayDeque(it.takeLast(cap)) }
            lines[s.id] = m
        }
    }

    /** One more point for each heater and for each temperature sensor (a reading that is not a temperature has an empty unit: not charted). */
    fun append(state: PrinterState) {
        for (h in state.heaters) add(h.id, h.label, h.temperature, h.target)
        for (s in state.sensors) if (s.unit.isNotEmpty()) add(s.id, s.label, s.value, null)
    }

    private fun add(id: String, label: String, value: Double, target: Double?) {
        val m = lines.getOrPut(id) { Mutable(label) }
        m.values.addLast(value)
        if (m.values.size > cap) m.values.removeFirst()
        if (target != null) {
            val t = m.targets ?: ArrayDeque<Double>().also { m.targets = it }
            t.addLast(target)
            if (t.size > cap) t.removeFirst()
        }
    }

    fun lines(): List<ChartLine> = lines.map { (id, m) -> ChartLine(id, m.label, m.values.toList(), m.targets?.toList()) }
}

/** The chart's axes. */
internal object ChartMath {
    const val GRID_STEP = 50.0

    /** The top of the y axis: the highest value or target seen plus 10 %, never below 50 so an idle printer still has a scale. */
    fun yMax(lines: List<ChartLine>, hidden: Set<String> = emptySet()): Double {
        val top = lines.filter { it.id !in hidden }.flatMap { it.values + (it.targets ?: emptyList()) }.maxOrNull() ?: 0.0
        return max(GRID_STEP, top * 1.1)
    }

    /** The horizontal lines: every 50 °C from 0 up to [yMax]. */
    fun gridlines(yMax: Double): List<Double> = generateSequence(0.0) { it + GRID_STEP }.takeWhile { it <= yMax }.toList()

    /** "20 min ago" style label for the point [index] of [count] points a second apart (the last point is "now"). */
    fun minutesAgo(index: Int, count: Int): String {
        val seconds = count - 1 - index
        return if (seconds < 30) "now" else "${ceil(seconds / 60.0).toInt()} min ago"
    }

    /** Where a value sits from the bottom (0) to the top (1) of the plot. */
    fun fraction(value: Double, yMax: Double): Double = if (yMax <= 0) 0.0 else min(1.0, max(0.0, value / yMax))
}

/** Which files the Files card lists, in what order. */
internal object FileList {
    /** Newest first; a search matches the title or the path, in any case; [printedOnly] hides files never printed. */
    fun shown(files: List<PrinterFile>, query: String, printedOnly: Boolean): List<PrinterFile> {
        val q = query.trim().lowercase()
        return files
            .filter { !printedOnly || it.printedBefore }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.path.lowercase().contains(q) }
            .sortedByDescending { it.modified }
    }

    /** "2.4 MB", "900 KB". */
    fun size(bytes: Long, locale: java.util.Locale = java.util.Locale.getDefault()): String = when {
        bytes >= 1_000_000 -> String.format(locale, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }
}

/** The console's lines as the card shows them. */
internal object ConsoleView {
    private const val KEEP = 500
    private val VALUE_LINE = Regex("^// value:")

    /** The lines to draw: the printer's "// value:" chatter is hidden unless the owner switched that off. */
    fun visible(lines: List<ConsoleLine>, hideValues: Boolean): List<ConsoleLine> = if (hideValues) lines.filterNot { VALUE_LINE.containsMatchIn(it.text) } else lines

    /** Adds the lines after the last one we have; never keeps more than 500, and never repeats a line (by its number). */
    fun append(old: List<ConsoleLine>, new: List<ConsoleLine>): List<ConsoleLine> {
        val last = old.lastOrNull()?.seq ?: Long.MIN_VALUE
        return (old + new.filter { it.seq > last }).takeLast(KEEP)
    }
}

/** The bed mesh's colours: blue below the middle of the range, red above, white at the middle (readable on a light and a dark page). */
internal object MeshColors {
    /** (red, green, blue), 0-255. [value] outside the range is clamped; a flat mesh (min == max) is the middle colour. */
    fun rgb(value: Double, min: Double, max: Double): Triple<Int, Int, Int> {
        if (max <= min) return Triple(235, 235, 235)
        val t = ((value - min) / (max - min)).coerceIn(0.0, 1.0)   // 0 lowest .. 1 highest
        return if (t < 0.5) {
            val k = t / 0.5    // 0 = deepest blue, 1 = middle
            Triple((60 + 175 * k).toInt(), (110 + 125 * k).toInt(), (220 + 15 * k).toInt())
        } else {
            val k = (t - 0.5) / 0.5   // 0 = middle, 1 = deepest red
            Triple((235 - 15 * k).toInt(), (235 - 150 * k).toInt(), (235 - 160 * k).toInt())
        }
    }

    /** The spread (highest minus lowest) in mm, and where they are as (row, column) from the front-left. */
    data class Extremes(val spread: Double, val lowest: Triple<Double, Int, Int>, val highest: Triple<Double, Int, Int>)

    fun extremes(points: List<List<Double>>): Extremes? {
        var lo: Triple<Double, Int, Int>? = null
        var hi: Triple<Double, Int, Int>? = null
        points.forEachIndexed { r, row -> row.forEachIndexed { c, v ->
            if (lo == null || v < lo!!.first) lo = Triple(v, r, c)
            if (hi == null || v > hi!!.first) hi = Triple(v, r, c)
        } }
        return if (lo == null || hi == null) null else Extremes(hi!!.first - lo!!.first, lo!!, hi!!)
    }
}
