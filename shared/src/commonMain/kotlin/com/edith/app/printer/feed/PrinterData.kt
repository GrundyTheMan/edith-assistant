package com.edith.app.printer.feed

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One line of the temperature chart: a value for each second, oldest first, with the target where the line is a heater. */
data class ChartLine(val id: String, val label: String, val values: List<Double>, val targets: List<Double>?)

/**
 * The chart's data (C39 8.4 Temperatures): at most [cap] points a line (20 minutes at a point a second), seeded from Jarvis's
 * `temperatures` route when the card opens and then given one point per `state` answer. Never grows without limit.
 */
class ChartBuffer(private val cap: Int = 1200) {
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
object ChartMath {
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
object FileList {
    /** Newest first; a search matches the title or the path, in any case; [printedOnly] hides files never printed. */
    fun shown(files: List<PrinterFile>, query: String, printedOnly: Boolean): List<PrinterFile> {
        val q = query.trim().lowercase()
        return files
            .filter { !printedOnly || it.printedBefore }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.path.lowercase().contains(q) }
            .sortedByDescending { it.modified }
    }

    /** "2.4 MB", "900 KB". */
    fun size(bytes: Long, languageTag: String? = null): String = when {
        bytes >= 1_000_000 -> "${com.edith.app.core.locale.formatDecimal(bytes / 1_000_000.0, 1, languageTag)} MB"
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }
}

/** The console's lines as the card shows them. */
object ConsoleView {
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
object MeshColors {
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

/** The Macros card's list: Jarvis's groups in Jarvis's order, filtered by what the owner typed. Edith never decides what is hidden: she shows what Jarvis sends. */
object MacroList {
    /** A group with the macros that are shown in it. */
    data class Section(val id: String, val title: String, val macros: List<MacroInfo>)

    private const val OTHER = "other"

    /**
     * The sections to draw. A macro whose group Jarvis did not list goes under "Other". With a [query] only the macros whose name or
     * description contains it (any case) are kept, and a section with none is left out. Macros keep the order Jarvis sent.
     */
    fun sections(answer: MacrosAnswer, query: String): List<Section> {
        val q = query.trim().lowercase()
        val known = answer.groups.map { it.id }.toSet()
        fun groupOf(m: MacroInfo) = if (m.group in known) m.group else OTHER
        val titles = answer.groups.associate { it.id to it.title } + (OTHER to (answer.groups.firstOrNull { it.id == OTHER }?.title ?: "Other"))
        val order = answer.groups.map { it.id }.let { if (OTHER in it) it else it + OTHER }
        val kept = answer.macros.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.description.orEmpty().lowercase().contains(q) }
        return order.mapNotNull { id ->
            val inGroup = kept.filter { groupOf(it) == id }
            if (inGroup.isEmpty()) null else Section(id, titles[id] ?: "Other", inGroup)
        }
    }

    /** The parameters to send: what the owner typed, trimmed; a box left empty is not sent, so the macro uses its own default. */
    fun params(typed: Map<String, String>): Map<String, String> = typed.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
}

/** The lines the owner typed into the console, newest first, at most 50 (kept between visits). */
object ConsoleHistory {
    const val MAX = 50

    /** [line] goes to the front; the same line sent again moves to the front instead of repeating; the list stays at 50. */
    fun push(history: List<String>, line: String): List<String> {
        val t = line.trim()
        if (t.isEmpty()) return history
        return (listOf(t) + history.filter { it != t }).take(MAX)
    }

    /** Where the up arrow goes from [index] (-1 means "nothing chosen yet"): one older, stopping at the oldest. */
    fun older(history: List<String>, index: Int): Int = if (history.isEmpty()) -1 else minOf(index + 1, history.lastIndex)

    /** Where the down arrow goes: one newer; past the newest it is -1, which is an empty box again. */
    fun newer(index: Int): Int = if (index <= 0) -1 else index - 1

    /** The text at [index], or "" for -1. */
    fun at(history: List<String>, index: Int): String = history.getOrNull(index).orEmpty()
}
