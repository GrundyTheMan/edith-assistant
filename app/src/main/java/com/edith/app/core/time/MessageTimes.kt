package com.edith.app.core.time

import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import java.util.Locale
import kotlin.time.Instant

/**
 * How the chat shows times (C07 §3), decided by calendar day in the owner's time zone, not elapsed time:
 * today "2:06 PM", the previous six days "Mon 2:06 PM", older "Oct 12, 2:06 PM".
 */
fun formatMessageTime(at: Instant, clock: Clock, locale: Locale = Locale.getDefault()): String {
    val zone = clock.zone()
    val days = at.toLocalDateTime(zone).date.daysUntil(clock.now().toLocalDateTime(zone).date)
    val pattern = when {
        days <= 0 -> "h:mm a"          // today (or a clock that's slightly behind)
        days < 7 -> "EEE h:mm a"
        else -> "MMM d, h:mm a"
    }
    return formatPattern(at, zone, pattern, locale.toLanguageTag())
}

/** The conversation list's shorter form (C07 §8): today "2:06 PM", this week "Mon", older "Oct 12". */
fun formatListTime(at: Instant, clock: Clock, locale: Locale = Locale.getDefault()): String {
    val zone = clock.zone()
    val days = at.toLocalDateTime(zone).date.daysUntil(clock.now().toLocalDateTime(zone).date)
    val pattern = when {
        days <= 0 -> "h:mm a"
        days < 7 -> "EEE"
        else -> "MMM d"
    }
    return formatPattern(at, zone, pattern, locale.toLanguageTag())
}
