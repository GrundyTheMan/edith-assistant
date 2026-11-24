package com.edith.app.testing

import com.edith.app.core.time.Clock
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

/** A clock that only moves when the test says so (C02 §8), for tests that run on every platform. The app's own `FakeClock` also takes java.time values. */
class FakeClock(
    var instant: Instant = Instant.parse("2026-10-29T18:00:00Z"),
    private val timeZone: TimeZone = TimeZone.of("America/New_York"),
) : Clock {
    override fun now(): Instant = instant
    override fun zone(): TimeZone = timeZone
    fun advance(by: Duration) { instant += by }
}
