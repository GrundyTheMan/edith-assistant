package com.edith.app.core.time

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DateFormatTest {
    private val newYork = TimeZone.of("America/New_York")
    private val moment = Instant.parse("2026-10-31T18:06:00Z") // 2:06 PM in New York (daylight time until Nov 1)

    @Test
    fun formatsInTheGivenZone() {
        assertEquals("2:06 PM", formatPattern(moment, newYork, "h:mm a", "en-US"))
        assertEquals("Sat 2:06 PM", formatPattern(moment, newYork, "EEE h:mm a", "en-US"))
        assertEquals("Oct 31, 2:06 PM", formatPattern(moment, newYork, "MMM d, h:mm a", "en-US"))
        assertEquals("18:06", formatPattern(moment, TimeZone.UTC, "HH:mm", "en-US"))
    }

    @Test
    fun isoLocalFormIsKept() {
        assertEquals("2026-10-31T14:06:00", formatInstant(moment, newYork))
    }
}
