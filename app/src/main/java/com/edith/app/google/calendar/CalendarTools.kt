package com.edith.app.google.calendar

import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.google.GoogleAuth
import com.edith.app.tools.framework.ArgumentError
import com.edith.app.tools.framework.ParamType
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolParam
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.framework.ToolSpec
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The calendars Edith reads (C18), remembered for a few minutes so a turn with several tool calls asks Google once.
 * Events she lists get short handles (`e1`, `e2`, …) the model passes back to change or delete them: Google's ids are
 * long, and the handle keeps what the event looked like, for the confirmation card's "before → after".
 */
class CalendarSession(private val client: CalendarClient, private val nowMs: () -> Long = System::currentTimeMillis) {
    private var calendars: List<CalendarInfo>? = null
    private var fetchedAtMs = 0L
    private val handles = LinkedHashMap<String, CalendarEvent>()
    private var next = 1

    suspend fun calendars(): Outcome<List<CalendarInfo>> {
        calendars?.takeIf { nowMs() - fetchedAtMs < CACHE_MS }?.let { return Outcome.Success(it) }
        return client.calendars().also { if (it is Outcome.Success) { calendars = it.value; fetchedAtMs = nowMs() } }
    }

    @Synchronized fun handleFor(event: CalendarEvent): String {
        handles.entries.firstOrNull { it.value.id == event.id && it.value.calendarId == event.calendarId }?.let {
            it.setValue(event)
            return it.key
        }
        val h = "e${next++}"
        handles[h] = event
        if (handles.size > MAX_HANDLES) handles.remove(handles.keys.first())
        return h
    }

    @Synchronized fun event(handle: String?): CalendarEvent? = handle?.trim()?.let { handles[it] }

    @Synchronized fun update(handle: String, event: CalendarEvent) { handles[handle] = event }

    @Synchronized fun forget(handle: String) { handles.remove(handle) }

    companion object {
        const val CACHE_MS = 5 * 60_000L
        const val MAX_HANDLES = 300
    }
}

/** "Fri Oct 30, 12:00–1:00 PM", "Sat Oct 31, all day", "Oct 31–Nov 2, all day" (device zone, English month names). */
object CalendarFormat {
    private val day = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val timeNoAmPm = DateTimeFormatter.ofPattern("h:mm", Locale.US)

    fun whenText(start: ZonedDateTime, end: ZonedDateTime, allDay: Boolean): String {
        if (allDay) {
            val last = end.minusDays(1)
            return if (!last.toLocalDate().isAfter(start.toLocalDate())) "${day.format(start)}, all day"
            else "${day.format(start)}–${day.format(last)}, all day"
        }
        if (start.toLocalDate() != end.toLocalDate()) return "${day.format(start)} ${time.format(start)} – ${day.format(end)} ${time.format(end)}"
        val sameHalf = (start.hour < 12) == (end.hour < 12)
        return "${day.format(start)}, ${if (sameHalf) timeNoAmPm.format(start) else time.format(start)}–${time.format(end)}"
    }

    fun line(e: CalendarEvent, handle: String, showCalendar: Boolean): String = buildString {
        append(whenText(e.start, e.end, e.allDay)).append(" — ").append(e.title)
        e.location?.takeIf { it.isNotBlank() }?.let { append(" (").append(it.take(60)).append(')') }
        if (showCalendar) append(" [").append(e.calendarName).append(']')
        append(" {event_id: ").append(handle).append('}')
    }
}

private val START = ToolParam("start", ParamType.DateTime, "Start of the period, in the user's local time", required = true, aliases = listOf("from", "time_min"))
private val END = ToolParam("end", ParamType.DateTime, "End of the period, in the user's local time", required = true, aliases = listOf("to", "until", "time_max"))

/**
 * A time from the model, in [zone]. Calendar questions are about the owner's wall-clock time, and live gemma4 got the
 * offset wrong twice:
 * - It wrote "tomorrow" as `2026-11-03T00:00:00Z`…`23:59:59Z`, which in Detroit is 7 PM the evening before to 7 PM.
 * - It wrote "tomorrow at 3pm" as `15:00:00-04:00`, the day after daylight saving ended (it should be -05:00):
 *   the card said 2:00 PM.
 *
 * So `Z`/`+00:00`, and any offset the owner's own zone uses at some time of year (both of Detroit's), are read as local
 * wall-clock time. A genuinely different zone ("3pm Pacific" → `-08:00`) is kept.
 */
internal fun ToolArgs.localTime(name: String, zone: ZoneId): ZonedDateTime? {
    val text = string(name)?.trim() ?: return dateTime(name, zone)
    val offset = Regex("(Z|[+-]\\d\\d:?\\d\\d)$").find(text) ?: return dateTime(name, zone)
    val local = try {
        java.time.LocalDateTime.parse(text.removeRange(offset.range))
    } catch (_: java.time.format.DateTimeParseException) {
        return dateTime(name, zone)
    }
    val given = if (offset.value == "Z") java.time.ZoneOffset.UTC else java.time.ZoneOffset.of(offset.value.let { if (it.length == 5) it.substring(0, 3) + ":" + it.substring(3) else it })
    val year = local.year
    val ownOffsets = setOf(java.time.ZoneOffset.UTC) + listOf(1, 7).map { zone.rules.getOffset(java.time.LocalDateTime.of(year, it, 1, 12, 0)) }
    return if (given in ownOffsets) local.atZone(zone) else dateTime(name, zone)
}

private const val LOCAL_TIMES = "Give times in the user's local time without an offset or Z, e.g. 2026-11-03T15:00:00."
private const val NEEDS_LIST = "I don't have that event. List the events first (list_calendar_events) and use the event_id it gives."

private fun calendarError(e: AppError): ToolResult = ToolResult.Error(
    when (e) {
        AppError.GoogleNotConnected -> "Google isn't connected. Tell the user to connect it in Settings → Google account."
        is AppError.GoogleAuth -> if (e.detail == GoogleAuth.NEEDS_PERMISSION) "I need permission to see the calendar. Tell the user to tap Grant access in Settings → Google account." else "Google: ${e.detail}"
        is AppError.ToolFailed -> e.detail
        else -> e.userMessage
    },
)

/** `list_calendar_events` (C18 §2): what's on the owner's calendars between two times. */
class ListCalendarEventsTool(private val session: CalendarSession, private val client: CalendarClient) : Tool {
    override val spec = ToolSpec(
        "list_calendar_events",
        "List events on the user's Google calendars between two times, optionally matching words",
        listOf(START, END, ToolParam("query", ParamType.String, "Words to look for in events (optional)", aliases = listOf("q", "search"))),
    )
    override val statusLabel = "Checking your calendar…"
    override val displayName = "calendar"
    override val integrationId = GoogleAuth.ID
    override val guidance = "For the user's schedule, call list_calendar_events with ISO start and end covering the days asked about " +
        "(a whole day: 00:00 to 23:59). Results carry an event_id for changing or deleting an event. $LOCAL_TIMES"

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val zone = ctx.clock.zone()
        val from = args.localTime("start", zone)!!
        val to = args.localTime("end", zone)!!.let { if (it == from) it.plusDays(1) else it }
        if (!to.isAfter(from)) throw ArgumentError("end", "end must be after start")
        val calendars = when (val r = session.calendars()) { is Outcome.Success -> r.value; is Outcome.Failure -> return calendarError(r.error) }
        val events = when (val r = client.events(calendars, from, to, args.string("query"))) { is Outcome.Success -> r.value; is Outcome.Failure -> return calendarError(r.error) }
        if (events.isEmpty()) return ToolResult.Ok("No events.")
        val shown = events.take(MAX_EVENTS)
        val lines = shown.map { CalendarFormat.line(it, session.handleFor(it), calendars.size > 1) }
        val more = if (events.size > shown.size) "\n…and ${events.size - shown.size} more; ask for a shorter period." else ""
        return ToolResult.Ok(lines.joinToString("\n") + more)
    }

    companion object { const val MAX_EVENTS = 20 }
}

/** `find_free_time` (C18 §2): open slots of at least a given length, 8 AM–8 PM each day. All-day events don't block. */
class FindFreeTimeTool(private val session: CalendarSession, private val client: CalendarClient) : Tool {
    override val spec = ToolSpec(
        "find_free_time",
        "Find free time on the user's calendars between two times, between 8 AM and 8 PM",
        listOf(START, END, ToolParam("duration_minutes", ParamType.Integer, "How long the free time must be, in minutes (default 30)", aliases = listOf("duration", "minutes"))),
    )
    override val statusLabel = "Checking your calendar…"
    override val displayName = "calendar"
    override val integrationId = GoogleAuth.ID
    override val guidance = "For \"am I free…\" or \"when could I…\", call find_free_time."

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val zone = ctx.clock.zone()
        val from = args.localTime("start", zone)!!
        val to = args.localTime("end", zone)!!
        if (!to.isAfter(from)) throw ArgumentError("end", "end must be after start")
        val minutes = (args.int("duration_minutes") ?: 30).coerceIn(5, 24 * 60)
        val calendars = when (val r = session.calendars()) { is Outcome.Success -> r.value; is Outcome.Failure -> return calendarError(r.error) }
        val events = when (val r = client.events(calendars, from, to)) { is Outcome.Success -> r.value; is Outcome.Failure -> return calendarError(r.error) }
        val slots = freeSlots(events, from, to, Duration.ofMinutes(minutes.toLong()), zone)
        if (slots.isEmpty()) return ToolResult.Ok("No free time of $minutes minutes or more between 8 AM and 8 PM in that period.")
        return ToolResult.Ok(slots.take(MAX_SLOTS).joinToString("\n") { (s, e) -> "Free: " + CalendarFormat.whenText(s, e, allDay = false) })
    }

    companion object {
        const val MAX_SLOTS = 12
        val DAY_START: LocalTime = LocalTime.of(8, 0)
        val DAY_END: LocalTime = LocalTime.of(20, 0)

        /** Gaps of at least [min] between timed events, inside 8 AM–8 PM of each day in [from, to). */
        fun freeSlots(events: List<CalendarEvent>, from: ZonedDateTime, to: ZonedDateTime, min: Duration, zone: ZoneId): List<Pair<ZonedDateTime, ZonedDateTime>> {
            val busy = events.filter { !it.allDay }.map { it.start to it.end }.sortedBy { it.first }
            val out = mutableListOf<Pair<ZonedDateTime, ZonedDateTime>>()
            var day = from.withZoneSameInstant(zone).toLocalDate()
            val lastDay = to.withZoneSameInstant(zone).toLocalDate()
            while (!day.isAfter(lastDay)) {
                var cursor = maxOf(day.atTime(DAY_START).atZone(zone), from)
                val dayEnd = minOf(day.atTime(DAY_END).atZone(zone), to)
                for ((s, e) in busy) {
                    if (!e.isAfter(cursor) || !s.isBefore(dayEnd)) continue
                    if (Duration.between(cursor, s) >= min) out += cursor to s
                    if (e.isAfter(cursor)) cursor = e
                }
                if (dayEnd.isAfter(cursor) && Duration.between(cursor, dayEnd) >= min) out += cursor to dayEnd
                day = day.plusDays(1)
            }
            return out
        }
    }
}

/** `create_calendar_event` (C18 §2): adds an event to the primary calendar, after the owner confirms (D11). */
class CreateCalendarEventTool(private val session: CalendarSession, private val client: CalendarClient, private val zone: () -> ZoneId = ZoneId::systemDefault) : Tool {
    override val spec = ToolSpec(
        "create_calendar_event",
        "Add an event to the user's primary Google calendar (the user confirms first)",
        listOf(
            ToolParam("title", ParamType.String, "What the event is", required = true, aliases = listOf("summary", "name")),
            ToolParam("start", ParamType.DateTime, "When it starts", required = true),
            ToolParam("end", ParamType.DateTime, "When it ends (default: an hour after start)"),
            ToolParam("all_day", ParamType.Boolean, "True for an all-day event"),
            ToolParam("location", ParamType.String, "Where (optional)", aliases = listOf("where", "place")),
            ToolParam("description", ParamType.String, "Notes (optional)", aliases = listOf("notes")),
        ),
    )
    override val statusLabel = "Adding to your calendar…"
    override val displayName = "calendar"
    override val integrationId = GoogleAuth.ID
    override val guidance = "To add, change or delete an event, call the tool straight away: the app asks the user to confirm on screen, so don't ask in chat first."

    private fun change(args: ToolArgs): EventChange {
        val z = zone()
        val allDay = args.bool("all_day") == true
        val start = args.localTime("start", z)!!.let { if (allDay) it.toLocalDate().atStartOfDay(z) else it }
        val end = args.localTime("end", z)?.let { if (allDay) it.toLocalDate().atStartOfDay(z).plusDays(1) else it }
            ?: if (allDay) start.plusDays(1) else start.plusHours(1)
        if (!end.isAfter(start)) throw ArgumentError("end", "end must be after start")
        return EventChange(args.string("title"), start, end, allDay, args.string("location"), args.string("description"))
    }

    override fun confirmation(args: ToolArgs): ConfirmationRequest {
        val c = change(args)
        return ConfirmationRequest(
            "Add to your calendar?",
            listOfNotNull("What" to c.title!!, "When" to CalendarFormat.whenText(c.start!!, c.end!!, c.allDay), c.location?.let { "Where" to it }),
            confirmLabel = "Add",
        )
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val c = change(args)
        val calendars = when (val r = session.calendars()) { is Outcome.Success -> r.value; is Outcome.Failure -> return calendarError(r.error) }
        val primary = calendars.firstOrNull { it.primary } ?: CalendarInfo("primary", "Calendar", primary = true)
        return when (val r = client.insert(primary, c)) {
            is Outcome.Success -> {
                session.handleFor(r.value)
                ToolResult.Ok("Added \"${r.value.title}\" on ${CalendarFormat.whenText(r.value.start, r.value.end, r.value.allDay)}.")
            }
            is Outcome.Failure -> calendarError(r.error)
        }
    }
}

/** `update_calendar_event` (C18 §2): changes an event Edith listed, after the owner sees before → after. */
class UpdateCalendarEventTool(private val session: CalendarSession, private val client: CalendarClient, private val zone: () -> ZoneId = ZoneId::systemDefault) : Tool {
    override val spec = ToolSpec(
        "update_calendar_event",
        "Change an event from list_calendar_events: its title, time or place (the user confirms first)",
        listOf(
            ToolParam("event_id", ParamType.String, "The event_id from list_calendar_events, e.g. e3", required = true, aliases = listOf("id", "event")),
            ToolParam("title", ParamType.String, "New title", aliases = listOf("summary")),
            ToolParam("start", ParamType.DateTime, "New start (the length stays the same unless end is given)"),
            ToolParam("end", ParamType.DateTime, "New end"),
            ToolParam("location", ParamType.String, "New place", aliases = listOf("where")),
        ),
    )
    override val statusLabel = "Changing your calendar…"
    override val displayName = "calendar"
    override val integrationId = GoogleAuth.ID

    private fun change(args: ToolArgs, e: CalendarEvent): EventChange {
        val z = zone()
        val start = args.localTime("start", z)
        val end = args.localTime("end", z) ?: start?.plus(Duration.between(e.start, e.end))
        if (start != null && end != null && !end.isAfter(start)) throw ArgumentError("end", "end must be after start")
        return EventChange(args.string("title"), start ?: end?.let { e.start }, end, e.allDay && start == null, args.string("location"))
    }

    override fun confirmation(args: ToolArgs): ConfirmationRequest? {
        val e = session.event(args.string("event_id")) ?: return null   // unknown: execute says so, nothing changes
        val c = change(args, e)
        val details = buildList {
            add("Event" to e.title)
            c.title?.let { add("Title" to "${e.title} → $it") }
            if (c.start != null || c.end != null) {
                add("When" to "${CalendarFormat.whenText(e.start, e.end, e.allDay)} → ${CalendarFormat.whenText(c.start ?: e.start, c.end ?: e.end, false)}")
            }
            c.location?.let { add("Where" to "${e.location ?: "—"} → $it") }
        }
        return ConfirmationRequest("Change this event?", details, confirmLabel = "Change")
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val handle = args.string("event_id")!!
        val e = session.event(handle) ?: return ToolResult.Error(NEEDS_LIST)
        val c = change(args, e)
        if (c.title == null && c.start == null && c.end == null && c.location == null) return ToolResult.Error("Nothing to change: give a new title, start, end or location.")
        return when (val r = client.patch(e, c)) {
            is Outcome.Success -> {
                session.update(handle, r.value)
                ToolResult.Ok("Changed \"${r.value.title}\": now ${CalendarFormat.whenText(r.value.start, r.value.end, r.value.allDay)}" +
                    (r.value.location?.let { " at $it" } ?: "") + ".")
            }
            is Outcome.Failure -> calendarError(r.error)
        }
    }
}

/** `delete_calendar_event` (C18 §2): removes an event Edith listed, after the owner confirms. */
class DeleteCalendarEventTool(private val session: CalendarSession, private val client: CalendarClient) : Tool {
    override val spec = ToolSpec(
        "delete_calendar_event",
        "Delete an event from list_calendar_events (the user confirms first)",
        listOf(ToolParam("event_id", ParamType.String, "The event_id from list_calendar_events, e.g. e3", required = true, aliases = listOf("id", "event"))),
    )
    override val statusLabel = "Changing your calendar…"
    override val displayName = "calendar"
    override val integrationId = GoogleAuth.ID

    override fun confirmation(args: ToolArgs): ConfirmationRequest? {
        val e = session.event(args.string("event_id")) ?: return null
        return ConfirmationRequest(
            "Delete from your calendar?",
            listOfNotNull("Event" to e.title, "When" to CalendarFormat.whenText(e.start, e.end, e.allDay), e.location?.let { "Where" to it }),
            confirmLabel = "Delete",
        )
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val handle = args.string("event_id")!!
        val e = session.event(handle) ?: return ToolResult.Error(NEEDS_LIST)
        return when (val r = client.delete(e)) {
            is Outcome.Success -> { session.forget(handle); ToolResult.Ok("Deleted \"${e.title}\" (${CalendarFormat.whenText(e.start, e.end, e.allDay)}).") }
            is Outcome.Failure -> calendarError(r.error)
        }
    }
}
