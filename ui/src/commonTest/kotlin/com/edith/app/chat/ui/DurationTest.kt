package com.edith.app.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DurationTest {
    @Test fun sixtyFiveSecondsIsOneOhFive() = assertEquals("1:05", formatDuration(65_000))
    @Test fun underAMinuteKeepsTheLeadingZero() = assertEquals("0:07", formatDuration(7_400))
    @Test fun anExactMinute() = assertEquals("2:00", minutesAndSeconds(120))
}
