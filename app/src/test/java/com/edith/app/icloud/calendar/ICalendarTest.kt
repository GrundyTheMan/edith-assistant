package com.edith.app.icloud.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** C24 §3: the iCalendar Edith reads and writes (RFC 5545). Made-up events. */
class ICalendarTest {
    private val zone = ZoneId.of("America/Detroit")
    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone)

    private fun cal(vararg event: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Apple Inc.//iCloud//EN\r\n" +
        event.joinToString("") { "BEGIN:VEVENT\r\n$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n"

    @Test fun `timed with TZID, UTC with Z, and floating`() {
        val events = ICalendar.events(
            cal(
                "UID:a\r\nSUMMARY:Dentist\r\nDTSTART;TZID=America/Chicago:20261103T140000\r\nDTEND;TZID=America/Chicago:20261103T150000",
                "UID:b\r\nSUMMARY:Call\r\nDTSTART:20261103T200000Z\r\nDTEND:20261103T210000Z",
                "UID:c\r\nSUMMARY:Floating\r\nDTSTART:20261103T090000\r\nDTEND:20261103T093000",
            ),
            zone,
        )
        assertEquals(listOf(at(3, 15), at(3, 15), at(3, 9)), events.map { it.start })
        assertEquals(listOf(at(3, 16), at(3, 16), at(3, 9, 30)), events.map { it.end })
        assertEquals("a", events[0].uid)
        assertFalse(events.any { it.allDay })
    }

    @Test fun `all-day with an exclusive end, DURATION, and an Outlook-style TZID`() {
        val events = ICalendar.events(
            cal(
                "UID:d\r\nSUMMARY:Holiday\r\nDTSTART;VALUE=DATE:20261104\r\nDTEND;VALUE=DATE:20261106",
                "UID:e\r\nSUMMARY:Run\r\nDTSTART:20261103T120000Z\r\nDURATION:PT1H30M",
                "UID:f\r\nSUMMARY:Board\r\nDTSTART;TZID=\"Eastern Standard Time\":20261103T100000\r\nDURATION:P1W",
                "UID:g\r\nSUMMARY:Odd zone\r\nDTSTART;TZID=Nowhere/Special:20261103T100000\r\nDTEND;TZID=Nowhere/Special:20261103T110000",
            ),
            zone,
        )
        val holiday = events[0]
        assertTrue(holiday.allDay)
        assertEquals(at(4, 0), holiday.start)
        assertEquals("exclusive end", at(6, 0), holiday.end)
        assertEquals(at(3, 7), events[1].start)
        assertEquals(at(3, 8, 30), events[1].end)
        assertEquals("a Windows name is mapped, never read as UTC (L29)", at(3, 10), events[2].start)
        assertEquals(at(10, 10), events[2].end)
        assertEquals("an unknown zone falls back to the calendar's", at(3, 10), events[3].start)
    }

    @Test fun `folding, escapes, cancelled, alarms skipped, instances and series`() {
        val events = ICalendar.events(
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:h\r\nSUMMARY:Lunch\\, with Sam\\; maybe a very long title that the server folds over\r\n  two lines\r\n" +
                "DESCRIPTION:Line one\\nLine two \\\\ done\r\nLOCATION:Café\r\nDTSTART:20261103T170000Z\r\nDTEND:20261103T180000Z\r\n" +
                "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Not this\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\nEND:VEVENT\r\n" +
                "BEGIN:VEVENT\r\nUID:i\r\nSTATUS:CANCELLED\r\nDTSTART:20261103T170000Z\r\nEND:VEVENT\r\n" +
                "BEGIN:VEVENT\r\nUID:j\r\nRECURRENCE-ID:20261104T140000Z\r\nDTSTART:20261104T140000Z\r\nDTEND:20261104T141500Z\r\nSUMMARY:Standup\r\nEND:VEVENT\r\n" +
                "BEGIN:VEVENT\r\nUID:j\r\nRRULE:FREQ=DAILY\r\nDTSTART:20261103T140000Z\r\nDTEND:20261103T141500Z\r\nSUMMARY:Standup\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
            zone,
        )
        val lunch = events[0]
        assertEquals("Lunch, with Sam; maybe a very long title that the server folds over two lines", lunch.summary)
        assertEquals("Line one\nLine two \\ done", lunch.description)
        assertEquals("Café", lunch.location)
        assertTrue(events[1].cancelled)
        assertEquals("20261104T140000Z", events[2].recurrenceId)
        assertTrue(events[3].recurring)
        assertNull(events[3].recurrenceId)
    }

    @Test fun `a new event - UTC times, all-day dates, escaped text, folded at 75 octets`() {
        val ics = ICalendar.newEvent("UID-1", Instant.parse("2026-11-02T18:00:00Z"), "Lunch, Sam; 1\\2", at(3, 15), at(3, 16), false, "Café", "a\nb")
        val lines = ics.split("\r\n")
        assertTrue(ics.endsWith("\r\n"))
        assertTrue("DTSTART:20261103T200000Z" in lines)
        assertTrue("DTEND:20261103T210000Z" in lines)
        assertTrue("DTSTAMP:20261102T180000Z" in lines)
        assertTrue("SUMMARY:Lunch\\, Sam\\; 1\\\\2" in lines)
        assertTrue("DESCRIPTION:a\\nb" in lines)
        assertTrue("PRODID:-//Edith//EN" in lines)
        assertTrue("UID:UID-1" in lines)
        assertFalse("no VTIMEZONE needed", ics.contains("VTIMEZONE"))

        val allDay = ICalendar.newEvent("UID-2", Instant.EPOCH, "Holiday", at(4, 0), at(5, 0), true, null, null)
        assertTrue("DTSTART;VALUE=DATE:20261104" in allDay.split("\r\n"))
        assertTrue("DTEND;VALUE=DATE:20261105" in allDay.split("\r\n"))

        val long = ICalendar.fold("SUMMARY:" + "é".repeat(60))
        long.split("\r\n").forEach { assertTrue("≤ 75 octets: $it", it.toByteArray(Charsets.UTF_8).size <= 75) }
        assertEquals("unfolds back", "SUMMARY:" + "é".repeat(60), ICalendar.lines(long).single().let { "${it.name}:${it.value}" })
        assertEquals("round trip", "Lunch, Sam; 1\\2", ICalendar.events(ics, zone).single().summary)
    }

    @Test fun `a change keeps every other line and replaces only the asked ones`() {
        val original = cal("UID:k\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:Old\r\nX-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC\r\nDTSTART;TZID=America/Detroit:20261103T150000\r\nDURATION:PT1H\r\nBEGIN:VALARM\r\nTRIGGER:-PT5M\r\nEND:VALARM")
        val changed = ICalendar.change(
            original,
            mapOf("SUMMARY" to "SUMMARY:New", "DTSTART" to "DTSTART:20261103T210000Z", "DTEND" to "DTEND:20261103T220000Z", "DURATION" to null, "LOCATION" to "LOCATION:Here"),
        )
        val lines = changed.split("\r\n")
        assertTrue("SUMMARY:New" in lines)
        assertFalse("SUMMARY:Old" in lines)
        assertTrue("DTEND:20261103T220000Z" in lines)
        assertFalse(lines.any { it.startsWith("DURATION") })
        assertTrue("an unknown property kept", "X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC" in lines)
        assertTrue("the alarm kept", "TRIGGER:-PT5M" in lines)
        assertTrue("a new property added", "LOCATION:Here" in lines)
        val e = ICalendar.events(changed, zone).single()
        assertEquals(at(3, 16), e.start)
        assertEquals(at(3, 17), e.end)
    }
}
