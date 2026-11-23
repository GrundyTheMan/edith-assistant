package com.edith.app.microsoft

import kotlinx.datetime.TimeZone
import com.edith.app.accounts.Account
import com.edith.app.accounts.sources.CalendarEvent
import com.edith.app.accounts.sources.CalendarInfo
import com.edith.app.accounts.sources.CalendarSource
import com.edith.app.accounts.sources.EventChange
import com.edith.app.accounts.sources.EventDraft
import com.edith.app.accounts.sources.MailMessage
import com.edith.app.accounts.sources.MailQuery
import com.edith.app.accounts.sources.MailSearchResult
import com.edith.app.accounts.sources.MailSource
import com.edith.app.accounts.sources.MailSummary
import com.edith.app.accounts.sources.OutgoingMail
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.google.gmail.MailText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * One Microsoft account's Outlook calendars over Graph (C23 §2). Every calendar is read (Outlook has no "checked"
 * flag, D15); `calendarView` expands recurring events. Times are asked for in the device's zone by its Windows name
 * (Graph takes only some IANA names), else UTC, and each `dateTime` is read in the zone its answer names, never
 * assumed UTC (trap 2, L29). The calendar list is remembered for a few minutes, as Google's is.
 */
class OutlookCalendarSource(
    private val accountOf: () -> Account,
    private val graph: GraphClient,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    /** The Windows name of a zone ("GMT Standard Time"), or null when there's none (Android's ICU on the phone). */
    private val windowsZone: (TimeZone) -> String? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : CalendarSource {
    override val account: Account get() = accountOf()

    private val lock = Mutex()
    @Volatile private var cached: List<CalendarInfo>? = null
    @Volatile private var fetchedAtMs = 0L

    override suspend fun calendars(): Outcome<List<CalendarInfo>> = lock.withLock {
        cached?.takeIf { nowMs() - fetchedAtMs < CACHE_MS }?.let { return@withLock Outcome.Success(it) }
        val r = graph.get(graph.url("me", "calendars") { addQueryParameter("\$select", "id,name,isDefaultCalendar,canEdit"); addQueryParameter("\$top", "50") }) { body ->
            graphJson.decodeFromString<ListDto<CalendarDto>>(body).value
                .map { CalendarInfo(it.id, it.name ?: "Calendar", isDefault = it.isDefaultCalendar == true, canWrite = it.canEdit != false) }
                .sortedByDescending { it.isDefault }
        }
        if (r is Outcome.Success) { cached = r.value; fetchedAtMs = nowMs() }
        r
    }

    override suspend fun events(from: Instant, to: Instant, text: String?): Outcome<List<CalendarEvent>> {
        val calendars = when (val r = calendars()) { is Outcome.Success -> r.value; is Outcome.Failure -> return r }
        val asked = askZone()
        val all = mutableListOf<CalendarEvent>()
        for (cal in calendars) {
            val r = graph.get(
                graph.url("me", "calendars", cal.id, "calendarView") {
                    addQueryParameter("startDateTime", utc(from))
                    addQueryParameter("endDateTime", utc(to))
                    addQueryParameter("\$top", MAX_RESULTS.toString())
                    addQueryParameter("\$orderby", "start/dateTime")
                    addQueryParameter("\$select", "id,subject,start,end,isAllDay,location,bodyPreview,iCalUId,isCancelled")
                },
                mapOf("Prefer" to "outlook.timezone=\"${asked.first}\""),
            ) { body -> graphJson.decodeFromString<ListDto<EventDto>>(body).value.mapNotNull { it.toEvent(cal, asked) } }
            when (r) {
                is Outcome.Success -> all += r.value
                is Outcome.Failure -> return r
            }
        }
        val wanted = text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val matching = if (wanted == null) all else all.filter { e ->   // Graph's $search doesn't apply to events (§2)
            e.title.lowercase().contains(wanted) || e.location?.lowercase()?.contains(wanted) == true
        }
        return Outcome.Success(matching.sortedWith(compareBy({ it.start }, { it.title })))
    }

    override suspend fun create(calendarId: String?, draft: EventDraft): Outcome<CalendarEvent> {
        val calendars = when (val r = calendars()) { is Outcome.Success -> r.value; is Outcome.Failure -> return r }
        val target = calendarId?.let { id -> calendars.firstOrNull { it.id == id } }
            ?: calendars.firstOrNull { it.isDefault }
            ?: calendars.firstOrNull()
            ?: return Outcome.Failure(com.edith.app.core.error.AppError.ToolFailed("calendar", "This Outlook account has no calendar to add to."))
        val asked = askZone()
        return graph.send("POST", graph.url("me", "calendars", target.id, "events"), draft.toJson(asked), prefer(asked)) { body ->
            graphJson.decodeFromString<EventDto>(body).toEvent(target, asked) ?: throw IllegalArgumentException("no event")
        }
    }

    override suspend fun update(event: CalendarEvent, change: EventChange): Outcome<CalendarEvent> {
        val calendar = CalendarInfo(event.calendarId, event.calendarName, isDefault = false)
        val asked = askZone()
        return graph.send("PATCH", graph.url("me", "events", event.id), change.toJson(asked), prefer(asked)) { body ->
            graphJson.decodeFromString<EventDto>(body).toEvent(calendar, asked) ?: throw IllegalArgumentException("no event")
        }
    }

    override suspend fun delete(event: CalendarEvent): Outcome<Unit> =
        graph.send("DELETE", graph.url("me", "events", event.id), null) { }

    /** The zone to ask Graph for, by the name Graph knows, and the zone that name means. */
    private fun askZone(): Pair<String, TimeZone> {
        val device = zone()
        val windows = runCatching { windowsZone(device) }.getOrNull()
        return if (windows.isNullOrBlank()) "UTC" to TimeZone.UTC else windows to device
    }

    private fun prefer(asked: Pair<String, TimeZone>) = mapOf("Prefer" to "outlook.timezone=\"${asked.first}\"")

    private fun EventDto.toEvent(cal: CalendarInfo, asked: Pair<String, TimeZone>): CalendarEvent? {
        if (isCancelled == true) return null
        val device = zone()
        val s = start ?: return null
        val e = end ?: return null
        val allDay = isAllDay == true
        val (startAt, endAt) = if (allDay) {
            // All-day events run midnight to midnight; only their dates count.
            val first = localDateTime(s.dateTime)?.date ?: return null
            val after = localDateTime(e.dateTime)?.date?.takeIf { it > first } ?: first.plus(1, DateTimeUnit.DAY)
            first.atStartOfDayIn(device) to after.atStartOfDayIn(device)
        } else {
            val a = at(s, asked) ?: return null
            val b = at(e, asked) ?: (a + 1.hours)
            a to b
        }
        return CalendarEvent(
            id = id ?: return null,
            calendarId = cal.id,
            calendarName = cal.name,
            title = subject?.takeIf { it.isNotBlank() } ?: "(No title)",
            start = startAt,
            end = endAt,
            allDay = allDay,
            location = location?.displayName?.takeIf { it.isNotBlank() },
            description = bodyPreview?.takeIf { it.isNotBlank() },
            accountId = accountOf().id,
            iCalUid = iCalUId,
        )
    }

    /** A Graph `dateTime` (no offset) in the zone its `timeZone` names, converted to the device's zone. */
    private fun at(t: DateTimeDto, asked: Pair<String, TimeZone>): Instant? {
        val local = localDateTime(t.dateTime) ?: return null
        val zoneOfAnswer = when {
            t.timeZone == null || t.timeZone == asked.first -> asked.second
            t.timeZone.equals("UTC", ignoreCase = true) -> TimeZone.UTC
            else -> try {
                TimeZone.of(t.timeZone)
            } catch (e: IllegalTimeZoneException) {
                Log.w(LogTag.Accounts, "Outlook sent a time in a zone Edith doesn't know; reading it as the zone she asked for")
                asked.second
            }
        }
        return local.toInstant(zoneOfAnswer)
    }

    private fun localDateTime(value: String?): LocalDateTime? = value?.let {
        try { LocalDateTime.parse(it) } catch (e: IllegalArgumentException) { null }
    }

    /** Only the fields that are set; times in the zone asked for (all-day: midnight to midnight). */
    private fun EventChange.toJson(asked: Pair<String, TimeZone>): String {
        val device = zone()
        val time = { t: Instant ->
            val local = if (allDay) t.toLocalDateTime(device).date.atTime(0, 0) else t.toLocalDateTime(asked.second)
            DateTimeDto(LocalDateTime.Formats.ISO.format(local), asked.first)
        }
        val endTime = end ?: if (allDay) start?.plus(1, DateTimeUnit.DAY, device) else null
        return graphJson.encodeToString(
            EventWriteDto.serializer(),
            EventWriteDto(
                subject = title,
                start = start?.let(time),
                end = endTime?.let(time),
                isAllDay = if (start != null || end != null) allDay else null,
                location = location?.let { LocationDto(it) },
                body = description?.let { BodyDto("text", it) },
            ),
        )
    }

    companion object {
        const val CACHE_MS = 5 * 60_000L
        const val MAX_RESULTS = 50

        /** `2026-11-02T14:00:00Z`. */
        fun utc(t: Instant): String = t.toString()
    }
}

/**
 * One Microsoft account's Outlook mail over Graph (C23 §3): the inbox for searches, text bodies for reading. Drafts
 * and sending go through a draft whose text is exactly the card's (D5): a reply's quoted original is replaced, and
 * sending sends that draft. Email content is never logged.
 */
class OutlookMailSource(
    private val accountOf: () -> Account,
    private val graph: GraphClient,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) : MailSource {
    override val account: Account get() = accountOf()

    /**
     * A search's words go in `$search` (KQL); the unread and date conditions in `$filter`. Graph won't take `$search`
     * with `$filter` or `$orderby` on messages, so with words the conditions are checked here on a bigger page.
     */
    override suspend fun search(q: MailQuery, max: Int): Outcome<MailSearchResult> {
        val limit = max.coerceIn(1, MAX_SEARCH)
        val kql = kql(q)
        val clientSide = kql != null && (q.unreadOnly || q.after != null || q.before != null)
        val url = graph.url("me", "mailFolders", "inbox", "messages") {
            addQueryParameter("\$select", SUMMARY_FIELDS)
            if (kql != null) {
                addQueryParameter("\$search", "\"$kql\"")
                addQueryParameter("\$top", (if (clientSide) SEARCH_PAGE else limit).toString())
            } else {
                addQueryParameter("\$filter", filter(q))
                addQueryParameter("\$orderby", "receivedDateTime desc")
                addQueryParameter("\$top", limit.toString())
            }
        }
        return when (val r = graph.get(url) { graphJson.decodeFromString<ListDto<MessageDto>>(it) }) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val all = r.value.value.map { it.summary() }
                val kept = if (!clientSide) all else all.filter { m ->
                    (!q.unreadOnly || m.unread) &&
                        (q.after == null || m.date >= q.after) &&
                        (q.before == null || m.date < q.before)
                }.sortedByDescending { it.date }
                Outcome.Success(MailSearchResult(kept.take(limit), more = kept.size > limit || r.value.nextLink != null))
            }
        }
    }

    override suspend fun read(id: String): Outcome<MailMessage> =
        graph.get(
            graph.url("me", "messages", id) { addQueryParameter("\$select", "$SUMMARY_FIELDS,ccRecipients,body") },
            mapOf("Prefer" to "outlook.body-content-type=\"text\""),
        ) { body ->
            val dto = graphJson.decodeFromString<MessageDto>(body)
            MailMessage(dto.summary(), dto.ccRecipients.joinToString(", ") { it.text() }.ifBlank { null }, MailText.clean(dto.body?.content.orEmpty()))
        }

    override suspend fun draft(m: OutgoingMail): Outcome<Unit> = when (val r = makeDraft(m)) {
        is Outcome.Success -> Outcome.Success(Unit)
        is Outcome.Failure -> r
    }

    /** The draft as on the card, then `/send`; Graph keeps it in Sent Items. Nothing else is sent (§3). */
    override suspend fun send(m: OutgoingMail): Outcome<Unit> {
        val id = when (val r = makeDraft(m)) { is Outcome.Success -> r.value; is Outcome.Failure -> return r }
        return graph.send("POST", graph.url("me", "messages", id, "send"), null) { }
    }

    /**
     * A new email: `POST /me/messages`. A reply: `createReply` (Outlook threads it), then its subject, recipients and
     * body set to exactly the card's, replacing the quoted original. Returns the draft's id.
     */
    private suspend fun makeDraft(m: OutgoingMail): Outcome<String> {
        val content = graphJson.encodeToString(DraftDto.serializer(), DraftDto(m.subject, BodyDto("text", m.body), recipients(m.to)))
        val replyTo = m.replyToId ?: return graph.send("POST", graph.url("me", "messages"), content) { graphJson.decodeFromString<IdDto>(it).id }
        val draftId = when (val r = graph.send("POST", graph.url("me", "messages", replyTo, "createReply"), null) { graphJson.decodeFromString<IdDto>(it).id }) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        return when (val r = graph.send("PATCH", graph.url("me", "messages", draftId), content) { }) {
            is Outcome.Success -> Outcome.Success(draftId)
            is Outcome.Failure -> r
        }
    }

    private fun MessageDto.summary() = MailSummary(
        id = id,
        threadId = conversationId ?: id,
        from = from?.text() ?: "(unknown sender)",
        to = toRecipients.joinToString(", ") { it.text() }.ifBlank { null },
        subject = subject?.takeIf { it.isNotBlank() } ?: "(no subject)",
        date = receivedDateTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: Instant.fromEpochMilliseconds(0),
        snippet = bodyPreview.orEmpty(),
        messageIdHeader = internetMessageId,
        unread = isRead == false,
        accountId = accountOf().id,
    )

    companion object {
        const val MAX_SEARCH = 10
        /** The page searched when the unread or date conditions are checked here (§3). */
        const val SEARCH_PAGE = 50
        private const val SUMMARY_FIELDS = "id,conversationId,subject,from,toRecipients,receivedDateTime,isRead,bodyPreview,internetMessageId"
        private val ADDRESS = Regex("""<([^<>\s]+@[^<>\s]+)>|([^\s<>,;"]+@[^\s<>,;"]+)""")

        /** `$filter` for the unread and date conditions; `receivedDateTime` first, as `$orderby` needs (§3). */
        internal fun filter(q: MailQuery): String = buildList {
            add("receivedDateTime ge ${OutlookCalendarSource.utc(q.after ?: Instant.parse("1900-01-01T00:00:00Z"))}")
            q.before?.let { add("receivedDateTime lt ${OutlookCalendarSource.utc(it)}") }
            if (q.unreadOnly) add("isRead eq false")
        }.joinToString(" and ")

        /** The search words as KQL (`from:… to:… subject:…` and free text); null when there are none. */
        internal fun kql(q: MailQuery): String? = buildList {
            q.from?.takeIf { it.isNotBlank() }?.let { add("from:${term(it)}") }
            q.to?.takeIf { it.isNotBlank() }?.let { add("to:${term(it)}") }
            q.subject?.takeIf { it.isNotBlank() }?.let { add("subject:${term(it)}") }
            q.text?.takeIf { it.isNotBlank() }?.let { add(it.replace("\"", " ").trim()) }
        }.takeIf { it.isNotEmpty() }?.joinToString(" ")

        /** One KQL value: quoted (escaped inside `$search`'s own quotes) when it has spaces. */
        private fun term(value: String): String {
            val v = value.replace("\"", " ").trim()
            return if (v.any { it.isWhitespace() }) "\\\"$v\\\"" else v
        }

        /** "Sam <sam@contoso.com>, kim@x.org" → Graph recipients. */
        internal fun recipients(to: String): List<RecipientDto> =
            ADDRESS.findAll(to).map { RecipientDto(AddressDto(it.groupValues[1].ifEmpty { it.groupValues[2] })) }.toList()

    }
}

/** Graph's JSON: unknown fields ignored; nulls left out, so an update sends only what changed. */
internal val graphJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

@Serializable internal class ListDto<T>(val value: List<T> = emptyList(), @SerialName("@odata.nextLink") val nextLink: String? = null)
@Serializable internal class IdDto(val id: String)
@Serializable internal class CalendarDto(val id: String, val name: String? = null, val isDefaultCalendar: Boolean? = null, val canEdit: Boolean? = null)
@Serializable internal class DateTimeDto(val dateTime: String? = null, val timeZone: String? = null)
@Serializable internal class LocationDto(val displayName: String? = null)
@Serializable internal class BodyDto(val contentType: String? = null, val content: String? = null)
@Serializable internal class EventDto(
    val id: String? = null,
    val subject: String? = null,
    val start: DateTimeDto? = null,
    val end: DateTimeDto? = null,
    val isAllDay: Boolean? = null,
    val isCancelled: Boolean? = null,
    val location: LocationDto? = null,
    val bodyPreview: String? = null,
    val iCalUId: String? = null,
)
/** What's sent on create/update: null fields are left out (`explicitNulls = false`), so an update changes only those. */
@Serializable internal class EventWriteDto(
    val subject: String?,
    val start: DateTimeDto?,
    val end: DateTimeDto?,
    val isAllDay: Boolean?,
    val location: LocationDto?,
    val body: BodyDto?,
)
@Serializable internal class AddressDto(val address: String? = null, val name: String? = null)
@Serializable internal class RecipientDto(val emailAddress: AddressDto? = null) {
    /** "Sam <sam@contoso.com>", or the address alone. */
    fun text(): String {
        val a = emailAddress?.address.orEmpty()
        val n = emailAddress?.name?.takeIf { it.isNotBlank() && !it.equals(a, ignoreCase = true) }
        return if (n != null) "$n <$a>" else a
    }
}
@Serializable internal class MessageDto(
    val id: String,
    val conversationId: String? = null,
    val subject: String? = null,
    val from: RecipientDto? = null,
    val toRecipients: List<RecipientDto> = emptyList(),
    val ccRecipients: List<RecipientDto> = emptyList(),
    val receivedDateTime: String? = null,
    val isRead: Boolean? = null,
    val bodyPreview: String? = null,
    val internetMessageId: String? = null,
    val body: BodyDto? = null,
)
@Serializable internal class DraftDto(val subject: String, val body: BodyDto, val toRecipients: List<RecipientDto>)
