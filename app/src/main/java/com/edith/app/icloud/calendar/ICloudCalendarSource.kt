package com.edith.app.icloud.calendar

import kotlinx.datetime.TimeZone
import com.edith.app.accounts.Account
import com.edith.app.accounts.sources.CalendarEvent
import com.edith.app.accounts.sources.CalendarInfo
import com.edith.app.accounts.sources.CalendarSource
import com.edith.app.accounts.sources.EventChange
import com.edith.app.accounts.sources.EventDraft
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import java.util.UUID

/**
 * One iCloud account's calendars over CalDAV (C24 §3). Discovery is remembered for the app run. An instance of a
 * repeating event can't be changed or deleted on its own in C24, and Edith never changes the whole series instead.
 * [onUnauthorized] tells the account its password stopped working (401).
 */
class ICloudCalendarSource(
    private val accountOf: () -> Account,
    private val dav: () -> CalDavClient,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val now: () -> Instant = kotlin.time.Clock.System::now,
    private val onUnauthorized: suspend () -> Unit = {},
) : CalendarSource {
    override val account: Account get() = accountOf()

    private val lock = Mutex()
    @Volatile private var home: DavHome? = null

    /** Discovery, once per app run (or after a failure). */
    suspend fun home(): Outcome<DavHome> = lock.withLock {
        home?.let { return@withLock Outcome.Success(it) }
        when (val r = dav().discover()) {
            is DavResult.Ok -> Outcome.Success(r.value.also { home = it })
            else -> failure(r)
        }
    }

    override suspend fun calendars(): Outcome<List<CalendarInfo>> = when (val h = home()) {
        is Outcome.Failure -> h
        is Outcome.Success -> {
            val default = defaultOf(h.value)
            Outcome.Success(
                h.value.calendars
                    .map { CalendarInfo(it.href.toString(), it.name, isDefault = it.href == default?.href, canWrite = it.canWrite) }
                    .sortedByDescending { it.isDefault },
            )
        }
    }

    override suspend fun events(from: Instant, to: Instant, text: String?): Outcome<List<CalendarEvent>> {
        val h = when (val r = home()) { is Outcome.Success -> r.value; is Outcome.Failure -> return r }
        val all = mutableListOf<CalendarEvent>()
        for (cal in h.calendars) {
            val resources = when (val r = dav().query(cal.href, from, to)) {
                is DavResult.Ok -> r.value
                else -> return failure(r)
            }
            for (res in resources) {
                for (e in ICalendar.events(res.ics, zone())) {
                    if (e.cancelled) continue
                    if (e.recurring && e.recurrenceId == null) {
                        // iCloud didn't expand this series (C24 §3): only its first instance is known here.
                        Log.w(LogTag.Accounts, "CalDAV: a repeating event came back unexpanded")
                        if (e.start >= to || e.end <= from) continue
                    }
                    if (e.start >= to || (e.end <= from && e.end != e.start)) continue
                    all += CalendarEvent(
                        id = eventId(res.href.toString(), e.recurrenceId, e.recurring),
                        calendarId = cal.href.toString(),
                        calendarName = cal.name,
                        title = e.summary?.takeIf { it.isNotBlank() } ?: "(No title)",
                        start = e.start,
                        end = e.end,
                        allDay = e.allDay,
                        location = e.location,
                        description = e.description,
                        accountId = accountOf().id,
                        iCalUid = e.uid,
                        inSeries = e.recurrenceId != null || e.recurring,
                    )
                }
            }
        }
        val wanted = text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val matching = if (wanted == null) all else all.filter { e ->
            e.title.lowercase().contains(wanted) || e.location?.lowercase()?.contains(wanted) == true || e.description?.lowercase()?.contains(wanted) == true
        }
        return Outcome.Success(matching.sortedWith(compareBy({ it.start }, { it.title })))
    }

    override suspend fun create(calendarId: String?, draft: EventDraft): Outcome<CalendarEvent> {
        val h = when (val r = home()) { is Outcome.Success -> r.value; is Outcome.Failure -> return r }
        val cal = calendarId?.let { id -> h.calendars.firstOrNull { it.href.toString() == id } } ?: defaultOf(h)
            ?: return Outcome.Failure(AppError.ToolFailed("calendar", "This iCloud account has no calendar Edith can add to."))
        val start = draft.start ?: return Outcome.Failure(AppError.ToolFailed("calendar", "A new event needs a start."))
        val end = draft.end ?: if (draft.allDay) start.plus(1, DateTimeUnit.DAY, zone()) else start + 1.hours
        val uid = UUID.randomUUID().toString().uppercase()
        val href = cal.href.resolve("$uid.ics") ?: return Outcome.Failure(AppError.ToolFailed("calendar", "Edith couldn't name the new event."))
        val ics = ICalendar.newEvent(uid, now(), draft.title ?: "(No title)", start, end, draft.allDay, draft.location, draft.description, zone())
        return when (val r = dav().put(href, ics, etag = null)) {
            is DavResult.Ok -> Outcome.Success(
                CalendarEvent(href.toString(), cal.href.toString(), cal.name, draft.title ?: "(No title)", start, end, draft.allDay, draft.location, draft.description, accountOf().id, uid),
            )
            else -> failure(r)
        }
    }

    override suspend fun update(event: CalendarEvent, change: EventChange): Outcome<CalendarEvent> {
        val href = resourceOf(event) ?: return oneOccurrence()
        repeat(2) { attempt ->
            val current = when (val r = dav().get(href)) { is DavResult.Ok -> r.value; else -> return failure(r) }
            val parsed = ICalendar.events(current.ics, zone())
            if (parsed.size != 1 || parsed[0].recurring) return series()
            val changes = buildMap<String, String?> {
                change.title?.let { put("SUMMARY", "SUMMARY:${ICalendar.escape(it)}") }
                change.location?.let { put("LOCATION", "LOCATION:${ICalendar.escape(it)}") }
                change.description?.let { put("DESCRIPTION", "DESCRIPTION:${ICalendar.escape(it)}") }
                change.start?.let { put("DTSTART", ICalendar.timeLine("DTSTART", it, change.allDay, zone())) }
                change.end?.let { put("DTEND", ICalendar.timeLine("DTEND", it, change.allDay, zone())); put("DURATION", null) }
                put("DTSTAMP", "DTSTAMP:${ICalendar.utc(now())}")
            }
            when (val r = dav().put(href, ICalendar.change(current.ics, changes), current.etag)) {
                is DavResult.Ok -> {
                    val after = ICalendar.events(ICalendar.change(current.ics, changes), zone()).first()
                    return Outcome.Success(event.copy(title = after.summary ?: event.title, start = after.start, end = after.end, allDay = after.allDay, location = after.location, description = after.description))
                }
                DavResult.Changed -> if (attempt == 0) Log.i(LogTag.Accounts, "CalDAV: the event changed elsewhere; reading it again") else return changedElsewhere()
                else -> return failure(r)
            }
        }
        return changedElsewhere()
    }

    override suspend fun delete(event: CalendarEvent): Outcome<Unit> {
        val href = resourceOf(event) ?: return oneOccurrence()
        val current = when (val r = dav().get(href)) { is DavResult.Ok -> r.value; else -> return failure(r) }
        if (ICalendar.events(current.ics, zone()).any { it.recurring }) return series()
        return when (val r = dav().delete(href, current.etag)) {
            is DavResult.Ok -> Outcome.Success(Unit)
            DavResult.Changed -> changedElsewhere()
            else -> failure(r)
        }
    }

    /**
     * Deletes the whole series an occurrence belongs to: its resource holds the series and its exceptions, so one
     * `DELETE` (with `If-Match`) removes every occurrence. Only when the owner asked for the whole series.
     */
    override suspend fun deleteSeries(event: CalendarEvent): Outcome<Unit> {
        val href = event.id.substringBefore('#').toHttpUrlOrNull() ?: return Outcome.Failure(AppError.ToolFailed("calendar", CHANGED))
        val current = when (val r = dav().get(href)) { is DavResult.Ok -> r.value; else -> return failure(r) }
        return when (val r = dav().delete(href, current.etag)) {
            is DavResult.Ok -> Outcome.Success(Unit)
            DavResult.Changed -> changedElsewhere()
            else -> failure(r)
        }
    }

    private fun defaultOf(h: DavHome): DavCalendar? =
        h.calendars.firstOrNull { it.href == h.defaultHref && it.canWrite } ?: h.calendars.firstOrNull { it.canWrite }

    private fun resourceOf(event: CalendarEvent) = if ('#' in event.id) null else event.id.toHttpUrlOrNull()

    private suspend fun <T> failure(r: DavResult<*>): Outcome<T> {
        if (r is DavResult.Failed || r == DavResult.Unauthorized) home = null   // discover again next time
        return Outcome.Failure(
            when (r) {
                DavResult.Unauthorized -> { onUnauthorized(); AppError.GoogleAuth(PASSWORD_STOPPED) }
                DavResult.NotFound -> AppError.ToolFailed("calendar", "That event isn't in your calendar any more.")
                DavResult.Changed -> AppError.ToolFailed("calendar", CHANGED)
                is DavResult.Failed -> AppError.ToolFailed("calendar", r.why)
                is DavResult.Ok -> AppError.ToolFailed("calendar", "iCloud Calendar sent something Edith couldn't read.")
            },
        )
    }

    private fun <T> oneOccurrence(): Outcome<T> = Outcome.Failure(AppError.ToolFailed("calendar", ONE_OCCURRENCE))
    private fun <T> series(): Outcome<T> = Outcome.Failure(AppError.ToolFailed("calendar", ONE_OCCURRENCE))
    private fun <T> changedElsewhere(): Outcome<T> = Outcome.Failure(AppError.ToolFailed("calendar", CHANGED))

    companion object {
        const val PASSWORD_STOPPED = "The app-specific password stopped working: it was revoked, or your Apple Account password changed. Make a new one."
        const val ONE_OCCURRENCE = "That's one occurrence of a repeating iCloud event. Edith can't change or delete one occurrence on its own; she can delete the whole series if the owner asks for that."
        const val CHANGED = "It changed elsewhere; list the events again."

        /** A resource's own event keeps its address; an occurrence of a series gets `#` and its RECURRENCE-ID. */
        fun eventId(href: String, recurrenceId: String?, recurring: Boolean): String = when {
            recurrenceId != null -> "$href#$recurrenceId"
            recurring -> "$href#series"
            else -> href
        }
    }
}
