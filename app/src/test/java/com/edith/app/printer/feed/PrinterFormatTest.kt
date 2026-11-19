package com.edith.app.printer.feed

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class PrinterFormatTest {
    private val us = Locale.US
    private val zone = ZoneId.of("America/Detroit")

    @Test fun `durations round minutes down and never show 0m for an hour or more`() {
        assertEquals("1h 1m", PrinterFormat.duration(3_700.0))
        assertEquals("1h", PrinterFormat.duration(3_600.0))
        assertEquals("2h", PrinterFormat.duration(7_259.0))
        assertEquals("52m", PrinterFormat.duration(3_120.0))
        assertEquals("1m", PrinterFormat.duration(60.0))
        assertEquals("59s", PrinterFormat.duration(59.9))
        assertEquals("0s", PrinterFormat.duration(0.0))
    }

    @Test fun `an unknown or negative duration is a dash`() {
        assertEquals("—", PrinterFormat.duration(null))
        assertEquals("—", PrinterFormat.duration(-1.0))
        assertEquals("—", PrinterFormat.duration(Double.NaN))
    }

    @Test fun `time left always says about, and unknown is a dash`() {
        assertEquals("about 1h 14m", PrinterFormat.timeLeft(4_440.0))
        assertEquals("about 45s", PrinterFormat.timeLeft(45.0))
        assertEquals("—", PrinterFormat.timeLeft(null))
    }

    @Test fun `the note under the time left says where the estimate came from`() {
        assertEquals("Estimated from the slicer", PrinterFormat.timeLeftSource("slicer"))
        assertEquals("Estimated from the time printed and the progress", PrinterFormat.timeLeftSource("progress"))
        assertEquals(null, PrinterFormat.timeLeftSource(null))
    }

    @Test fun `the finish time is in the phone's zone, 24 hours or 12`() {
        val now = Instant.parse("2026-11-18T18:00:00Z")   // 13:00 in Detroit
        assertEquals("about 14:32", PrinterFormat.eta(now, 5_520.0, zone, is24Hour = true, locale = us))
        assertEquals("about 2:32 PM", PrinterFormat.eta(now, 5_520.0, zone, is24Hour = false, locale = us))
        assertEquals("—", PrinterFormat.eta(now, null, zone, true, us))
    }

    @Test fun `temperatures have one decimal, targets are whole, positions two`() {
        assertEquals("219.8 °C", PrinterFormat.temperature(219.8, us))
        assertEquals("24.0 °C", PrinterFormat.temperature(23.96, us))
        assertEquals("220 °C", PrinterFormat.target(219.6))
        assertEquals("off", PrinterFormat.target(0.0))
        assertEquals("100.50", PrinterFormat.position(100.5, us))
    }

    @Test fun `filament is metres with one decimal and percentages are whole with a space`() {
        assertEquals("12.4 m", PrinterFormat.filament(12_400.0, us))
        assertEquals("0.0 m", PrinterFormat.filament(0.0, us))
        assertEquals("40 %", PrinterFormat.percent(0.4))
        assertEquals("85 %", PrinterFormat.factor(0.85))
    }

    @Test fun `a number follows the phone's locale`() {
        assertEquals("219,8 °C", PrinterFormat.temperature(219.8, Locale.GERMANY))
    }

    @Test fun `ages are relative under a week and a date after`() {
        val now = Instant.parse("2026-11-18T18:00:00Z").toEpochMilli()
        fun ago(ms: Long) = PrinterFormat.age(now - ms, now, zone, us)
        assertEquals("just now", ago(20_000))
        assertEquals("2 min ago", ago(125_000))
        assertEquals("3 h ago", ago(3 * 3_600_000L + 5_000))
        assertEquals("yesterday", PrinterFormat.age(Instant.parse("2026-11-17T20:00:00Z").toEpochMilli(), now, zone, us))
        assertEquals("4 days ago", PrinterFormat.age(Instant.parse("2026-11-14T15:00:00Z").toEpochMilli(), now, zone, us))
        assertEquals("Nov 3", PrinterFormat.age(Instant.parse("2026-11-03T15:00:00Z").toEpochMilli(), now, zone, us))
        assertEquals("just now", PrinterFormat.age(now + 5_000, now, zone, us))
    }

    @Test fun `the Controls countdown uses the server's clock, not the phone's`() {
        // The phone's clock is 5 minutes slow: serverTime - phone = +300 000 ms.
        assertEquals("1:43", PrinterFormat.countdown(untilMs = 1_203_000, nowMs = 800_000, offsetMs = 300_000))
        assertEquals("0:00", PrinterFormat.countdown(untilMs = 1_000, nowMs = 5_000, offsetMs = 0))
        assertEquals("2:00", PrinterFormat.countdown(untilMs = 1_120_000, nowMs = 1_000_000, offsetMs = 0))
    }
}
