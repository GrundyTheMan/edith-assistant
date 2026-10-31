package com.edith.app.testing

import com.edith.app.core.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** A clock that only moves when the test says so (C02 §8). */
class FakeClock(
    var instant: Instant = Instant.parse("2026-10-29T18:00:00Z"),
    private val zone: ZoneId = ZoneId.of("America/New_York"),
) : Clock {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zone
    fun advance(by: Duration) { instant += by }
}
