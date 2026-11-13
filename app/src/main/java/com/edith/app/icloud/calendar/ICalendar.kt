package com.edith.app.icloud.calendar

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One `VEVENT` as Edith needs it (C24 §3). Times are in the device's zone; an all-day [end] is exclusive. */
data class ICalEvent(
    val uid: String?,
    val summary: String?,
    val location: String?,
    val description: String?,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean,
    val cancelled: Boolean,
    /** Set on an instance of a recurring event (an expanded occurrence or an exception). */
    val recurrenceId: String?,
    /** The event repeats (an `RRULE` on it: a series iCloud didn't expand). */
    val recurring: Boolean,
)

/** One content line: `NAME;PARAM=VALUE:value`, unfolded. */
data class ICalLine(val name: String, val params: Map<String, String>, val value: String)

/**
 * The little of iCalendar (RFC 5545) Edith reads and writes: unfolding, parameters, escapes, `DTSTART`/`DTEND` with
 * `TZID`, `Z` or floating, all-day `VALUE=DATE`, `DURATION`. Pure Kotlin, so it runs on the JVM in tests.
 */
object ICalendar {
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** A few Windows zone names Outlook writes as `TZID` (L29: never read them as UTC). Others fall back to [zone]. */
    private val WINDOWS = mapOf(
        "Eastern Standard Time" to "America/New_York",
        "Central Standard Time" to "America/Chicago",
        "Mountain Standard Time" to "America/Denver",
        "US Mountain Standard Time" to "America/Phoenix",
        "Pacific Standard Time" to "America/Los_Angeles",
        "Alaskan Standard Time" to "America/Anchorage",
        "Hawaiian Standard Time" to "Pacific/Honolulu",
        "GMT Standard Time" to "Europe/London",
        "W. Europe Standard Time" to "Europe/Berlin",
        "Romance Standard Time" to "Europe/Paris",
        "Central European Standard Time" to "Europe/Warsaw",
        "UTC" to "UTC",
    )

    /** Lines with folding undone (a CRLF followed by a space or tab continues the line). */
    fun lines(text: String): List<ICalLine> =
        text.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").lines().filter { it.isNotBlank() }.mapNotNull(::line)

    private fun line(raw: String): ICalLine? {
        // The value starts at the first ':' outside a quoted parameter value.
        var quoted = false
        var colon = -1
        for ((i, c) in raw.withIndex()) {
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) { colon = i; break }
        }
        if (colon < 0) return null
        val head = raw.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull { p ->
            val eq = p.indexOf('=')
            if (eq < 0) null else p.substring(0, eq).uppercase() to p.substring(eq + 1).trim('"')
        }.toMap()
        return ICalLine(head[0].uppercase(), params, raw.substring(colon + 1))
    }

    /** `\,` `\;` `\\` `\n` back to text. */
    fun unescape(v: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (val n = v[i + 1]) { 'n', 'N' -> out.append('\n'); else -> out.append(n) }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    /** Text into a value: `\` `;` `,` escaped, newlines as `\n`. */
    fun escape(v: String): String =
        v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\n").replace("\n", "\\n")

    /** Every `VEVENT` in [text], read in the device's [zone]. Unreadable events are left out. */
    fun events(text: String, zone: ZoneId): List<ICalEvent> {
        val out = mutableListOf<ICalEvent>()
        var current: MutableList<ICalLine>? = null
        var depth = 0   // inside a VEVENT, nested components (VALARM) are skipped
        for (l in lines(text)) {
            when {
                l.name == "BEGIN" && l.value.equals("VEVENT", true) -> { current = mutableListOf(); depth = 0 }
                l.name == "BEGIN" && current != null -> depth++
                l.name == "END" && l.value.equals("VEVENT", true) && current != null -> {
                    event(current, zone)?.let(out::add)
                    current = null
                }
                l.name == "END" && current != null -> depth--
                current != null && depth == 0 -> current += l
            }
        }
        return out
    }

    private fun event(props: List<ICalLine>, zone: ZoneId): ICalEvent? {
        fun get(n: String) = props.firstOrNull { it.name == n }
        val startLine = get("DTSTART") ?: return null
        val allDay = isDate(startLine)
        val start = time(startLine, zone) ?: return null
        val end = get("DTEND")?.let { time(it, zone) }
            ?: get("DURATION")?.value?.let { d -> duration(d)?.let { start.plus(it) } }
            ?: if (allDay) start.plusDays(1) else start
        return ICalEvent(
            uid = get("UID")?.value,
            summary = get("SUMMARY")?.value?.let(::unescape),
            location = get("LOCATION")?.value?.let(::unescape)?.takeIf { it.isNotBlank() },
            description = get("DESCRIPTION")?.value?.let(::unescape)?.takeIf { it.isNotBlank() },
            start = start,
            end = end,
            allDay = allDay,
            cancelled = get("STATUS")?.value.equals("CANCELLED", ignoreCase = true),
            recurrenceId = get("RECURRENCE-ID")?.value,
            recurring = get("RRULE") != null,
        )
    }

    private fun isDate(l: ICalLine) = l.params["VALUE"].equals("DATE", true) || (l.value.length == 8 && l.value.all { it.isDigit() })

    /** A `DTSTART`/`DTEND` in [zone]: all-day at midnight, `Z` as UTC, `TZID` in its zone, floating in [zone]. */
    fun time(l: ICalLine, zone: ZoneId): ZonedDateTime? = try {
        val v = l.value.trim()
        when {
            isDate(l) -> LocalDate.parse(v.take(8), DATE).atStartOfDay(zone)
            v.endsWith("Z") -> LocalDateTime.parse(v.dropLast(1), DATE_TIME).atZone(ZoneOffset.UTC).withZoneSameInstant(zone)
            else -> LocalDateTime.parse(v, DATE_TIME).atZone(l.params["TZID"]?.let { zoneOf(it, zone) } ?: zone).withZoneSameInstant(zone)
        }
    } catch (e: DateTimeException) {
        null
    }

    /** An IANA id, a known Windows name, else [fallback] (the calendar's zone), never UTC by default. */
    fun zoneOf(tzid: String, fallback: ZoneId): ZoneId {
        val id = tzid.trim().removePrefix("/")
        return try {
            ZoneId.of(id)
        } catch (e: DateTimeException) {
            WINDOWS[id]?.let(ZoneId::of) ?: fallback
        }
    }

    /** `PT1H30M`, `P1D`, `P2W`, `-PT15M`. */
    fun duration(v: String): Duration? {
        val s = v.trim().uppercase()
        val weeks = Regex("^([+-]?)P(\\d+)W$").find(s)
        if (weeks != null) return Duration.ofDays(7L * weeks.groupValues[2].toLong()).let { if (weeks.groupValues[1] == "-") it.negated() else it }
        return try { Duration.parse(s) } catch (e: DateTimeException) { null } catch (e: java.time.format.DateTimeParseException) { null }
    }

    // --- Writing ------------------------------------------------------------------------------------------------

    /** `20261103T200000Z`: timed events are written in UTC, so no VTIMEZONE is needed (C24 §3). */
    fun utc(t: ZonedDateTime): String = t.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().format(DATE_TIME) + "Z"

    fun date(d: LocalDate): String = d.format(DATE)

    /** The `DTSTART`/`DTEND` line for a time: `;VALUE=DATE:20261104` for all-day, else UTC. */
    fun timeLine(name: String, t: ZonedDateTime, allDay: Boolean): String =
        if (allDay) "$name;VALUE=DATE:${date(t.toLocalDate())}" else "$name:${utc(t)}"

    /** Folds a line at 75 octets (RFC 5545 §3.1), never inside a UTF-8 character. */
    fun fold(line: String): String {
        val out = StringBuilder()
        var octets = 0
        for (ch in line) {
            val n = ch.toString().toByteArray(Charsets.UTF_8).size
            if (octets + n > 75) { out.append("\r\n "); octets = 1 }
            out.append(ch)
            octets += n
        }
        return out.toString()
    }

    /** A new event: a minimal VCALENDAR with one VEVENT (C24 §3). */
    fun newEvent(
        uid: String,
        stamp: Instant,
        summary: String,
        start: ZonedDateTime,
        end: ZonedDateTime,
        allDay: Boolean,
        location: String?,
        description: String?,
    ): String = buildList {
        add("BEGIN:VCALENDAR")
        add("VERSION:2.0")
        add("PRODID:-//Edith//EN")
        add("CALSCALE:GREGORIAN")
        add("BEGIN:VEVENT")
        add("UID:$uid")
        add("DTSTAMP:${utc(stamp.atZone(ZoneOffset.UTC))}")
        add(timeLine("DTSTART", start, allDay))
        add(timeLine("DTEND", end, allDay))
        add("SUMMARY:${escape(summary)}")
        location?.let { add("LOCATION:${escape(it)}") }
        description?.let { add("DESCRIPTION:${escape(it)}") }
        add("END:VEVENT")
        add("END:VCALENDAR")
    }.joinToString("\r\n", postfix = "\r\n") { fold(it) }

    /**
     * [ics] with its VEVENT's [changes] applied (name → the whole new line, or null to remove the property); every other
     * line is kept as the server had it. Only for a resource with one VEVENT (not a recurring series).
     */
    fun change(ics: String, changes: Map<String, String?>): String {
        val unfolded = ics.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").lines().filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var inEvent = false
        var depth = 0
        val done = mutableSetOf<String>()
        for (raw in unfolded) {
            val name = raw.substringBefore(':').substringBefore(';').uppercase()
            when {
                raw.equals("BEGIN:VEVENT", true) -> { inEvent = true; depth = 0; out += raw }
                inEvent && raw.uppercase().startsWith("BEGIN:") -> { depth++; out += raw }
                inEvent && depth > 0 && raw.uppercase().startsWith("END:") -> { depth--; out += raw }
                inEvent && raw.equals("END:VEVENT", true) -> {
                    changes.filterKeys { it !in done }.values.filterNotNull().forEach { out += it }
                    inEvent = false
                    out += raw
                }
                inEvent && depth == 0 && name in changes -> {
                    if (done.add(name)) changes[name]?.let { out += it }
                }
                else -> out += raw
            }
        }
        return out.joinToString("\r\n", postfix = "\r\n") { fold(it) }
    }
}
