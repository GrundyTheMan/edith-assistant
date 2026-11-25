package com.edith.app.google

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
import com.edith.app.accounts.sources.OutgoingMail
import com.edith.app.core.error.Outcome
import com.edith.app.google.calendar.CalendarClient
import com.edith.app.google.gmail.GmailClient
import com.edith.app.google.gmail.GmailQuery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * One Google account's calendars (C22 §3) over C18's [CalendarClient]: checked calendars only (D15), recurring events
 * as instances. The calendar list is remembered for a few minutes, so a turn with several tool calls asks once.
 */
class GoogleCalendarSource(
    private val accountOf: () -> Account,
    private val client: CalendarClient,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : CalendarSource {
    override val account: Account get() = accountOf()

    private val lock = Mutex()
    @kotlin.concurrent.Volatile private var cached: List<CalendarInfo>? = null
    @kotlin.concurrent.Volatile private var fetchedAtMs = 0L

    override suspend fun calendars(): Outcome<List<CalendarInfo>> = lock.withLock {
        cached?.takeIf { nowMs() - fetchedAtMs < CACHE_MS }?.let { return@withLock Outcome.Success(it) }
        client.calendars().also { if (it is Outcome.Success) { cached = it.value; fetchedAtMs = nowMs() } }
    }

    override suspend fun events(from: Instant, to: Instant, text: String?): Outcome<List<CalendarEvent>> {
        val calendars = when (val r = calendars()) { is Outcome.Success -> r.value; is Outcome.Failure -> return r }
        return client.events(calendars, from, to, text)
    }

    override suspend fun create(calendarId: String?, draft: EventDraft): Outcome<CalendarEvent> {
        val calendars = when (val r = calendars()) { is Outcome.Success -> r.value; is Outcome.Failure -> return r }
        val target = calendarId?.let { id -> calendars.firstOrNull { it.id == id } }
            ?: calendars.firstOrNull { it.isDefault }
            ?: CalendarInfo("primary", "Calendar", isDefault = true)
        return client.insert(target, draft)
    }

    override suspend fun update(event: CalendarEvent, change: EventChange): Outcome<CalendarEvent> = client.patch(event, change)

    override suspend fun delete(event: CalendarEvent): Outcome<Unit> = client.delete(event)

    companion object {
        const val CACHE_MS = 5 * 60_000L
    }
}

/** One Google account's Gmail (C22 §3) over C19's [GmailClient]: a [MailQuery] becomes Gmail's search syntax. */
class GmailSource(
    private val accountOf: () -> Account,
    private val client: GmailClient,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val now: () -> Instant = kotlin.time.Clock.System::now,
) : MailSource {
    override val account: Account get() = accountOf()

    override suspend fun search(q: MailQuery, max: Int): Outcome<MailSearchResult> =
        client.search(GmailQuery.build(q, now().toLocalDateTime(zone()).date, zone()), max)

    override suspend fun read(id: String): Outcome<MailMessage> = client.read(id)

    override suspend fun draft(m: OutgoingMail): Outcome<Unit> = when (val r = client.createDraft(m)) {
        is Outcome.Success -> Outcome.Success(Unit)
        is Outcome.Failure -> r
    }

    override suspend fun send(m: OutgoingMail): Outcome<Unit> = when (val r = client.send(m)) {
        is Outcome.Success -> Outcome.Success(Unit)
        is Outcome.Failure -> r
    }
}
