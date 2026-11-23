package com.edith.app.printer.feed

import com.edith.app.core.time.formatPattern
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import java.util.Locale

/**
 * How the Printer page writes numbers and times (C39 part 8.2): the rules of Jarvis's own page, copied. Pure functions; the clock, the
 * zone and the locale are passed in so they can be tested. File and job titles are never worked out here: use what Jarvis sends.
 */
internal object PrinterFormat {
    const val NONE = "—"
    const val NOT_ENOUGH_PRINTED = "Not enough printed yet to tell."

    /** 3 700 s -> "1h 1m"; 3 600 s -> "1h"; 3 120 s -> "52m"; 45 s -> "45s"; unknown or negative -> "—". Minutes round down. */
    fun duration(seconds: Double?): String {
        if (seconds == null || seconds.isNaN() || seconds < 0) return NONE
        val total = seconds.toLong()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        return when {
            hours >= 1 -> if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
            total >= 60 -> "${minutes}m"
            else -> "${total}s"
        }
    }

    /** "about 1h 14m": always "about", because it is an estimate. Unknown -> "—". */
    fun timeLeft(seconds: Double?): String = if (seconds == null || seconds.isNaN() || seconds < 0) NONE else "about ${duration(seconds)}"

    /** Where the estimate came from, for the little note under the time left. */
    fun timeLeftSource(from: String?): String? = when (from) {
        "slicer" -> "Estimated from the slicer"
        "progress" -> "Estimated from the time printed and the progress"
        else -> null
    }

    /** "about 14:32" in the phone's own zone, 24 hours or 12 as the phone does. Unknown -> "—". */
    fun eta(now: Instant, timeLeftSeconds: Double?, zone: TimeZone, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
        if (timeLeftSeconds == null || timeLeftSeconds.isNaN() || timeLeftSeconds < 0) return NONE
        val at = now + timeLeftSeconds.toLong().seconds
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        return "about ${formatPattern(at, zone, pattern, locale.toLanguageTag())}"
    }

    /** A temperature reading: one decimal, "219.8 °C". */
    fun temperature(value: Double, locale: Locale = Locale.getDefault()): String = "${decimals(value, 1, locale)} °C"

    /** A target: a whole number, "220 °C"; 0 is "off". */
    fun target(value: Double): String = if (value <= 0.0) "off" else "${Math.round(value)} °C"

    /** A position in millimetres, two decimals. */
    fun position(value: Double, locale: Locale = Locale.getDefault()): String = decimals(value, 2, locale)

    /** Filament used, from millimetres to metres with one decimal: 12 400 -> "12.4 m". */
    fun filament(mm: Double, locale: Locale = Locale.getDefault()): String = "${decimals(mm / 1000.0, 1, locale)} m"

    /** 0.4 -> "40 %". The page writes a space before the sign. */
    fun percent(fraction: Double): String = "${Math.round(fraction * 100)} %"

    /** A speed factor, 1.0 -> "100 %". */
    fun factor(factor: Double): String = percent(factor)

    /** "2 min ago", "3 h ago", "yesterday", "4 days ago", then a short date for a week or more. Future times read "just now". */
    fun age(thenMs: Long, nowMs: Long, zone: TimeZone, locale: Locale = Locale.getDefault()): String {
        val seconds = (nowMs - thenMs) / 1000
        if (seconds < 60) return "just now"
        if (seconds < 3600) return "${seconds / 60} min ago"
        val thenAt = Instant.fromEpochMilliseconds(thenMs)
        val days = thenAt.toLocalDateTime(zone).date.daysUntil(Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date)
        return when {
            days <= 0 -> "${seconds / 3600} h ago"
            days == 1 -> "yesterday"
            days < 7 -> "$days days ago"
            else -> formatPattern(thenAt, zone, "MMM d", locale.toLanguageTag())
        }
    }

    /** The time left of the Controls, "1:43", from the server's `until` and `serverTime` offset (the phone's clock may be wrong). */
    fun countdown(untilMs: Long, nowMs: Long, offsetMs: Long): String {
        val left = ((untilMs - (nowMs + offsetMs)) / 1000).coerceAtLeast(0)
        return "${left / 60}:${(left % 60).toString().padStart(2, '0')}"
    }

    private fun decimals(value: Double, places: Int, locale: Locale): String = String.format(locale, "%.${places}f", value)
}
