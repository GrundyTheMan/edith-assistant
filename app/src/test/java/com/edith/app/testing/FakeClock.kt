package com.edith.app.testing

import com.edith.app.core.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaZoneId
import kotlinx.datetime.toKotlinTimeZone
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * A clock that only moves when the test says so (C02 §8). It is set and moved with java.time values (TEMPORARY, K1.3: the tests of the
 * packages still on `java.time` build theirs that way) and reads as the new [Clock] types.
 */
class FakeClock(
    var instant: Instant = Instant.parse("2026-10-29T18:00:00Z"),
    private val javaZone: ZoneId = ZoneId.of("America/New_York"),
) : Clock {
    constructor(instant: Instant, zone: TimeZone) : this(instant, zone.toJavaZoneId())
    constructor(instant: kotlin.time.Instant, zone: TimeZone = TimeZone.of("America/New_York")) : this(instant.toJavaInstant(), zone.toJavaZoneId())

    override fun now(): kotlin.time.Instant = instant.toKotlinInstant()
    override fun zone(): TimeZone = javaZone.toKotlinTimeZone()
    fun advance(by: Duration) { instant += by }
}
