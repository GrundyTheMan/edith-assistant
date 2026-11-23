package com.edith.app.accounts.tools

import com.edith.app.accounts.Account
import kotlinx.datetime.toKotlinTimeZone
import kotlin.time.toJavaInstant
import com.edith.app.core.time.javaNow
import com.edith.app.core.time.javaZone
import com.edith.app.accounts.AccountDirectory
import com.edith.app.accounts.AccountEntry
import com.edith.app.accounts.Accounts
import com.edith.app.accounts.sources.CalendarEvent
import com.edith.app.accounts.sources.CalendarMerge
import com.edith.app.accounts.sources.CalendarSource
import com.edith.app.accounts.sources.EventChange
import com.edith.app.accounts.sources.MergedEvent
import com.edith.app.accounts.sources.ReadAll
import com.edith.app.accounts.sources.Readable
import com.edith.app.accounts.sources.Reads
import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.core.error.Outcome
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
 * Events Edith lists get short handles (`e1`, `e2`, …) the model passes back to change or delete them (C18). One
 * sequence for the whole app run, across accounts (C22 trap 4), so a handle is never ambiguous; each keeps the event
 * as listed, with its account, for the confirmation card's "before → after".
 */
class CalendarHandles {
    private val handles = LinkedHashMap<String, CalendarEvent>()
    private var next = 1

    @Synchronized fun handleFor(event: CalendarEvent): String {
        handles.entries.firstOrNull { it.value.accountId == event.accountId && it.value.id == event.id && it.value.calendarId == event.calendarId }?.let {
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

    /**
     * `Mon Nov 2, 1:00–2:00 PM — Dentist (Main St Dental) [Blake · work] {event_id: e2}`. The bracket names the
     * calendar when [showCalendar], and the account when [showAccount] (more than one account); an invite in two
     * accounts names both.
     */
    fun line(m: MergedEvent, handle: String, showCalendar: Boolean, showAccount: Boolean): String = buildString {
        val e = m.event
        append(whenText(e.start, e.end, e.allDay)).append(" — ").append(e.title)
        e.location?.takeIf { it.isNotBlank() }?.let { append(" (").append(it.take(60)).append(')') }
        val where = (listOf(m.account to e) + m.alsoIn).map { (a, ev) ->
            listOfNotNull(ev.calendarName.takeIf { showCalendar }, a.label.takeIf { showAccount }).joinToString(" · ")
        }.filter { it.isNotEmpty() }
        if (where.isNotEmpty()) append(" [").append(where.joinToString("; ")).append(']')
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
/** [ToolArgs.dateTime] as a java `ZonedDateTime` (TEMPORARY, K1.3: this package is still on `java.time`). */
private fun ToolArgs.zonedDateTime(name: String, zone: ZoneId): ZonedDateTime? =
    dateTime(name, zone.toKotlinTimeZone())?.toJavaInstant()?.atZone(zone)

internal fun ToolArgs.localTime(name: String, zone: ZoneId): ZonedDateTime? {
    val text = string(name)?.trim() ?: return zonedDateTime(name, zone)
    val offset = Regex("(Z|[+-]\\d\\d:?\\d\\d)$").find(text) ?: return zonedDateTime(name, zone)
    val local = try {
        java.time.LocalDateTime.parse(text.removeRange(offset.range))
    } catch (_: java.time.format.DateTimeParseException) {
        return zonedDateTime(name, zone)
    }
    val given = if (offset.value == "Z") java.time.ZoneOffset.UTC else java.time.ZoneOffset.of(offset.value.let { if (it.length == 5) it.substring(0, 3) + ":" + it.substring(3) else it })
    val year = local.year
    val ownOffsets = setOf(java.time.ZoneOffset.UTC) + listOf(1, 7).map { zone.rules.getOffset(java.time.LocalDateTime.of(year, it, 1, 12, 0)) }
    return if (given in ownOffsets) local.atZone(zone) else zonedDateTime(name, zone)
}

private const val LOCAL_TIMES = "Give times in the user's local time without an offset or Z, e.g. 2026-11-03T15:00:00."
private const val NEEDS_LIST = "I don't have that event. List the events first (list_calendar_events) and use the event_id it gives."

/** The accounts to read: all with a calendar, or the one [named]. Null with a [ToolResult] when the name matches none. */
private fun calendarTargets(dir: AccountDirectory, named: String?): Pair<List<Readable<CalendarSource>>, ToolResult?> {
    val all = dir.entries().filter { it.calendar != null }
    val chosen = if (named == null) all else {
        val ids = dir.byName(named).map { it.id }.toSet()
        val hit = all.filter { it.account.id in ids }
        if (hit.size != 1) {
            return emptyList<Readable<CalendarSource>>() to ToolResult.Error("There's no account called '$named'. The accounts are: ${all.joinToString { it.account.display }}.")
        }
        hit
    }
    return chosen.map { Readable(it.account, it.state, requireNotNull(it.calendar)) } to null
}

/** The account named on a card: "work (sam@contoso.com) — Calendar". */
private fun accountRow(account: Account, calendarName: String?): Pair<String, String> =
    "Account" to (account.display + (calendarName?.let { " — $it" } ?: ""))

/** `list_calendar_events` (C18, C22): what's on the owner's calendars between two times, across every account. */
class ListCalendarEventsTool(private val dir: AccountDirectory, private val handles: CalendarHandles) : Tool {
    override val timeoutMs: Long? = Reads.PATIENT_TOOL_MS   // a Patient source (Jarvis, through Outlook) may take longer than 20 s
    override val spec = ToolSpec(
        "list_calendar_events",
        "List events on the user's calendars (every connected account) between two times, optionally matching words",
        listOf(START, END, ToolParam("query", ParamType.String, "Words to look for in events (optional)", aliases = listOf("q", "search")), ACCOUNT_PARAM),
    )
    override val statusLabel = "Checking your calendar…"
    override val displayName = "calendar"
    override val integrationId = Accounts.ID
    override val guidance = "For the user's schedule, call list_calendar_events with ISO start and end covering the days asked about " +
        "(a whole day: 00:00 to 23:59). Results carry an event_id for changing or deleting an event. $LOCAL_TIMES"

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val zone = ctx.clock.javaZone()
        val from = requireNotNull(args.localTime("start", zone))
        val to = requireNotNull(args.localTime("end", zone)).let { if (it == from) it.plusDays(1) else it }
        if (!to.isAfter(from)) throw ArgumentError("end", "end must be after start")
        val (targets, refused) = calendarTargets(dir, args.string("account"))
        refused?.let { return it }
        val read = Reads.readAll(targets) { it.events(from.toInstant(), to.toInstant(), args.string("query")) }
        return listResult(read, showAccount = dir.entries().count { it.calendar != null } > 1)
    }

    private fun listResult(read: ReadAll<List<CalendarEvent>>, showAccount: Boolean): ToolResult {
        if (read.results.isEmpty() && read.failures.isNotEmpty()) return ToolResult.Error(read.failureText())
        val events = CalendarMerge.merge(read.results)
        val showCalendar = showAccount || read.results.any { (_, list) -> list.map { it.calendarId }.distinct().size > 1 }
        val text = if (events.isEmpty()) "No events." else {
            val shown = events.take(MAX_EVENTS)
            shown.joinToString("\n") { CalendarFormat.line(it, handles.handleFor(it.event), showCalendar, showAccount) } +
                if (events.size > shown.size) "\n…and ${events.size - shown.size} more; ask for a shorter period." else ""
        }
        return ToolResult.Ok(listOf(text, read.failureText()).filter { it.isNotEmpty() }.joinToString("\n"))
    }

    companion object { const val MAX_EVENTS = 20 }
}

/** `find_free_time` (C18, C22): open slots of at least a given length, 8 AM–8 PM; busy in any account = busy. */
class FindFreeTimeTool(private val dir: AccountDirectory) : Tool {
    override val timeoutMs: Long? = Reads.PATIENT_TOOL_MS   // a Patient source (Jarvis, through Outlook) may take longer than 20 s
    override val spec = ToolSpec(
        "find_free_time",
        "Find free time on the user's calendars (every connected account) between two times, between 8 AM and 8 PM",
        listOf(START, END, ToolParam("duration_minutes", ParamType.Integer, "How long the free time must be, in minutes (default 30)", aliases = listOf("duration", "minutes"))),
    )
    override val statusLabel = "Checking your calendar…"
    override val displayName = "calendar"
    override val integrationId = Accounts.ID
    override val guidance = "For \"am I free…\" or \"when could I…\", call find_free_time."

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val zone = ctx.clock.javaZone()
        val from = requireNotNull(args.localTime("start", zone))
        val to = requireNotNull(args.localTime("end", zone))
        if (!to.isAfter(from)) throw ArgumentError("end", "end must be after start")
        val minutes = (args.int("duration_minutes") ?: 30).coerceIn(5, 24 * 60)
        val (targets, _) = calendarTargets(dir, null)
        val read = Reads.readAll(targets) { it.events(from.toInstant(), to.toInstant()) }
        if (read.results.isEmpty() && read.failures.isNotEmpty()) return ToolResult.Error(read.failureText())
        val slots = freeSlots(read.results.flatMap { it.second }, from, to, Duration.ofMinutes(minutes.toLong()), zone)
        val text = if (slots.isEmpty()) "No free time of $minutes minutes or more between 8 AM and 8 PM in that period."
        else slots.take(MAX_SLOTS).joinToString("\n") { (s, e) -> "Free: " + CalendarFormat.whenText(s, e, allDay = false) }
        val missing = read.failures.joinToString("\n") { it.line.removeSuffix(".") + "; its events aren't counted." }
        return ToolResult.Ok(listOf(text, missing).filter { it.isNotEmpty() }.joinToString("\n"))
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

/**
 * `create_calendar_event` (C18, C22): adds an event, after the owner confirms (D11). Which account it goes to is
 * [WhichAccount]'s decision; when Edith must ask, there's no card and no request.
 */
class CreateCalendarEventTool(private val dir: AccountDirectory, private val handles: CalendarHandles, private val zone: () -> ZoneId = ZoneId::systemDefault) : Tool {
    override val spec = ToolSpec(
        "create_calendar_event",
        "Add an event to the user's calendar (the user confirms first)",
        listOf(
            ToolParam("title", ParamType.String, "What the event is", required = true, aliases = listOf("summary", "name")),
            ToolParam("start", ParamType.DateTime, "When it starts", required = true),
            ToolParam("end", ParamType.DateTime, "When it ends (default: an hour after start)"),
            ToolParam("all_day", ParamType.Boolean, "True for an all-day event"),
            ToolParam("location", ParamType.String, "Where (optional)", aliases = listOf("where", "place")),
            ToolParam("description", ParamType.String, "Notes (optional)", aliases = listOf("notes")),
            ToolParam("calendar", ParamType.String, "A calendar's name, from list_calendar_events, only when the user named one", aliases = listOf("calendar_name")),
            ACCOUNT_PARAM,
        ),
    )
    override val statusLabel = "Adding to your calendar…"
    override val displayName = "calendar"
    override val integrationId = Accounts.ID
    override val guidance = "To add, change or delete an event, call the tool straight away: the app asks the user to confirm on screen, so don't ask in chat first."

    private fun change(args: ToolArgs): EventChange {
        val z = zone()
        val allDay = args.bool("all_day") == true
        val start = requireNotNull(args.localTime("start", z)).let { if (allDay) it.toLocalDate().atStartOfDay(z) else it }
        val end = args.localTime("end", z)?.let { if (allDay) it.toLocalDate().atStartOfDay(z).plusDays(1) else it }
            ?: if (allDay) start.plusDays(1) else start.plusHours(1)
        if (!end.isAfter(start)) throw ArgumentError("end", "end must be after start")
        return EventChange(args.string("title"), start, end, allDay, args.string("location"), args.string("description"))
    }

    private suspend fun target(args: ToolArgs) = WhichAccount.forCalendar(dir, args.string("account"), args.string("calendar"))

    override suspend fun confirmation(args: ToolArgs): ConfirmationRequest? {
        val c = change(args)
        val use = target(args) as? WhichAccount.Choice.Use ?: return null   // execute explains: no card, no request
        val calendarName = use.calendar?.name ?: defaultCalendarName(use.entry)
        return ConfirmationRequest(
            "Add to your calendar?",
            listOfNotNull(
                "What" to requireNotNull(c.title), "When" to CalendarFormat.whenText(requireNotNull(c.start), requireNotNull(c.end), c.allDay),
                c.location?.let { "Where" to it }, accountRow(use.entry.account, calendarName),
            ),
            confirmLabel = "Add",
        )
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val c = change(args)
        val use = when (val t = target(args)) { is WhichAccount.Choice.Use -> t; is WhichAccount.Choice.Stop -> return t.result }
        val source = requireNotNull(use.entry.calendar)
        return when (val r = source.create(use.calendar?.id, c)) {
            is Outcome.Success -> {
                handles.handleFor(r.value)
                ToolResult.Ok("Added \"${r.value.title}\" on ${CalendarFormat.whenText(r.value.start, r.value.end, r.value.allDay)} to ${use.entry.account.display}.")
            }
            is Outcome.Failure -> accountError(use.entry.account, r.error)
        }
    }

    private suspend fun defaultCalendarName(e: AccountEntry): String? =
        (e.calendar?.calendars() as? Outcome.Success)?.value?.let { cals -> (cals.firstOrNull { it.isDefault } ?: cals.firstOrNull())?.name }
}

/** `update_calendar_event` (C18): changes an event Edith listed, in its own account, after the owner sees before → after. */
class UpdateCalendarEventTool(private val dir: AccountDirectory, private val handles: CalendarHandles, private val zone: () -> ZoneId = ZoneId::systemDefault) : Tool {
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
    override val integrationId = Accounts.ID

    private fun change(args: ToolArgs, e: CalendarEvent): EventChange {
        val z = zone()
        val start = args.localTime("start", z)
        val end = args.localTime("end", z) ?: start?.plus(Duration.between(e.start, e.end))
        if (start != null && end != null && !end.isAfter(start)) throw ArgumentError("end", "end must be after start")
        return EventChange(args.string("title"), start ?: end?.let { e.start }, end, e.allDay && start == null, args.string("location"))
    }

    override suspend fun confirmation(args: ToolArgs): ConfirmationRequest? {
        val e = handles.event(args.string("event_id")) ?: return null   // unknown: execute says so, nothing changes
        val entry = ownAccount(dir, e.accountId).first ?: return null
        val c = change(args, e)
        val details = buildList {
            add("Event" to e.title)
            c.title?.let { add("Title" to "${e.title} → $it") }
            if (c.start != null || c.end != null) {
                add("When" to "${CalendarFormat.whenText(e.start, e.end, e.allDay)} → ${CalendarFormat.whenText(c.start ?: e.start, c.end ?: e.end, false)}")
            }
            c.location?.let { add("Where" to "${e.location ?: "—"} → $it") }
            add(accountRow(entry.account, e.calendarName))
        }
        return ConfirmationRequest("Change this event?", details, confirmLabel = "Change")
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val handle = requireNotNull(args.string("event_id"))
        val e = handles.event(handle) ?: return ToolResult.Error(NEEDS_LIST)
        val (entry, refused) = ownAccount(dir, e.accountId)
        if (entry == null) return requireNotNull(refused)
        val c = change(args, e)
        if (c.title == null && c.start == null && c.end == null && c.location == null) return ToolResult.Error("Nothing to change: give a new title, start, end or location.")
        return when (val r = requireNotNull(entry.calendar).update(e, c)) {
            is Outcome.Success -> {
                handles.update(handle, r.value)
                ToolResult.Ok("Changed \"${r.value.title}\": now ${CalendarFormat.whenText(r.value.start, r.value.end, r.value.allDay)}" +
                    (r.value.location?.let { " at $it" } ?: "") + ".")
            }
            is Outcome.Failure -> accountError(entry.account, r.error)
        }
    }
}

/** `delete_calendar_event` (C18): removes an event Edith listed, from its own account, after the owner confirms. */
class DeleteCalendarEventTool(private val dir: AccountDirectory, private val handles: CalendarHandles) : Tool {
    override val spec = ToolSpec(
        "delete_calendar_event",
        "Delete an event from list_calendar_events (the user confirms first)",
        listOf(
            ToolParam("event_id", ParamType.String, "The event_id from list_calendar_events, e.g. e3", required = true, aliases = listOf("id", "event")),
            ToolParam(
                "whole_series", ParamType.Boolean,
                "True to delete every occurrence of a repeating event. Only when the user asked to delete the whole series.",
                aliases = listOf("series", "all_occurrences"),
            ),
        ),
    )
    override val statusLabel = "Changing your calendar…"
    override val displayName = "calendar"
    override val integrationId = Accounts.ID

    /**
     * C24: an occurrence that can only go with its whole series (iCloud) needs `whole_series`, which the model may send
     * only when the owner asked for the series. Without it there's no card: the result tells Edith to ask.
     */
    private fun wholeSeries(args: ToolArgs) = args.bool("whole_series") == true

    override suspend fun confirmation(args: ToolArgs): ConfirmationRequest? {
        val e = handles.event(args.string("event_id")) ?: return null
        val entry = ownAccount(dir, e.accountId).first ?: return null
        if (e.inSeries && !wholeSeries(args)) return null
        if (e.inSeries) {
            return ConfirmationRequest(
                "Delete the whole repeating series?",
                listOfNotNull(
                    "Event" to e.title,
                    "Deletes" to "Every occurrence, past and future (one is ${CalendarFormat.whenText(e.start, e.end, e.allDay)})",
                    e.location?.let { "Where" to it },
                    accountRow(entry.account, e.calendarName),
                ),
                confirmLabel = "Delete series",
            )
        }
        return ConfirmationRequest(
            "Delete from your calendar?",
            listOfNotNull("Event" to e.title, "When" to CalendarFormat.whenText(e.start, e.end, e.allDay), e.location?.let { "Where" to it }, accountRow(entry.account, e.calendarName)),
            confirmLabel = "Delete",
        )
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val handle = requireNotNull(args.string("event_id"))
        val e = handles.event(handle) ?: return ToolResult.Error(NEEDS_LIST)
        val (entry, refused) = ownAccount(dir, e.accountId)
        if (entry == null) return requireNotNull(refused)
        val source = requireNotNull(entry.calendar)
        if (e.inSeries && !wholeSeries(args)) {
            return ToolResult.Ok(
                "\"${e.title}\" repeats, and in ${entry.account.display} one occurrence can't be deleted on its own. Ask the owner " +
                    "whether to delete the whole series (every occurrence). Only if they say yes, call this tool again with " +
                    "whole_series true.",
            )
        }
        val r = if (e.inSeries) source.deleteSeries(e) else source.delete(e)
        return when (r) {
            is Outcome.Success -> {
                handles.forget(handle)
                if (e.inSeries) ToolResult.Ok("Deleted the whole repeating series \"${e.title}\" from ${entry.account.display}.")
                else ToolResult.Ok("Deleted \"${e.title}\" (${CalendarFormat.whenText(e.start, e.end, e.allDay)}) from ${entry.account.display}.")
            }
            is Outcome.Failure -> accountError(entry.account, r.error)
        }
    }
}

/** The calendar tools (C18, C22), sharing one set of handles. */
fun calendarTools(dir: AccountDirectory, handles: CalendarHandles): List<Tool> = listOf(
    ListCalendarEventsTool(dir, handles), FindFreeTimeTool(dir), CreateCalendarEventTool(dir, handles),
    UpdateCalendarEventTool(dir, handles), DeleteCalendarEventTool(dir, handles),
)
