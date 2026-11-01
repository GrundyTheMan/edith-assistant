package com.edith.app.core.time

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * How the chat shows times (C07 §3), decided by calendar day in the owner's time zone, not elapsed time:
 * today "2:06 PM", the previous six days "Mon 2:06 PM", older "Oct 12, 2:06 PM".
 */
fun formatMessageTime(at: Instant, clock: Clock, locale: Locale = Locale.getDefault()): String {
    val zone = clock.zone()
    val time = at.atZone(zone)
    val days = ChronoUnit.DAYS.between(time.toLocalDate(), clock.now().atZone(zone).toLocalDate())
    val pattern = when {
        days <= 0 -> "h:mm a"          // today (or a clock that's slightly behind)
        days < 7 -> "EEE h:mm a"
        else -> "MMM d, h:mm a"
    }
    return time.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** The conversation list's shorter form (C07 §8): today "2:06 PM", this week "Mon", older "Oct 12". */
fun formatListTime(at: Instant, clock: Clock, locale: Locale = Locale.getDefault()): String {
    val zone = clock.zone()
    val time = at.atZone(zone)
    val days = ChronoUnit.DAYS.between(time.toLocalDate(), clock.now().atZone(zone).toLocalDate())
    val pattern = when {
        days <= 0 -> "h:mm a"
        days < 7 -> "EEE"
        else -> "MMM d"
    }
    return time.format(DateTimeFormatter.ofPattern(pattern, locale))
}
