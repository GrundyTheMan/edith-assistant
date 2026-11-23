package com.edith.app.core.time

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.TimeZone.Companion.currentSystemDefault
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Provides the current time. Injected for tests to control time deterministically. */
interface Clock {
    fun now(): Instant
    fun zone(): TimeZone
}

/** The real clock. */
object SystemClock : Clock {
    override fun now(): Instant = kotlin.time.Clock.System.now()
    override fun zone(): TimeZone = currentSystemDefault()
}

/** Formats an Instant as a user-friendly string (ISO-8601 local, like `2026-10-31T14:06:05`). */
fun formatInstant(instant: Instant, zone: TimeZone = SystemClock.zone()): String =
    LocalDateTime.Formats.ISO.format(instant.toLocalDateTime(zone))
