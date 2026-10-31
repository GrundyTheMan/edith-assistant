package com.edith.app.core.time

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Provides the current time. Injected for tests to control time deterministically. */
interface Clock {
    fun now(): Instant
    fun zone(): ZoneId
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/** Formats an Instant as a user-friendly string (ISO-8601 local). */
fun formatInstant(instant: Instant, zone: ZoneId = SystemClock.zone()): String {
    return ZonedDateTime.ofInstant(instant, zone).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}
