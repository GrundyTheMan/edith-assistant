package com.edith.app.accounts.sources

import com.edith.app.accounts.Account
import com.edith.app.core.error.Outcome
import java.time.Instant
import java.time.ZonedDateTime

/** A calendar in one account (C22 §3). [isDefault]: where a new event goes when none is named (D15, D17). */
data class CalendarInfo(val id: String, val name: String, val isDefault: Boolean, val canWrite: Boolean = true)

/**
 * One event, in the device's zone. All-day events have [allDay] and run from [start] (midnight) to [end] (exclusive).
 * [accountId] and the provider's own [id]/[calendarId] say where it lives; [iCalUid] finds the same invite in two
 * accounts.
 */
data class CalendarEvent(
    val id: String,
    val calendarId: String,
    val calendarName: String,
    val title: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean,
    val location: String? = null,
    val description: String? = null,
    val accountId: String = "",
    val iCalUid: String? = null,
)

/** A new event, or the changes to one: only the fields that are set are sent. */
data class EventChange(
    val title: String? = null,
    val start: ZonedDateTime? = null,
    val end: ZonedDateTime? = null,
    val allDay: Boolean = false,
    val location: String? = null,
    val description: String? = null,
)

/** A new event: the same fields as a change, with the title and times set. */
typealias EventDraft = EventChange

/** One connected account's calendars (C22 §3): a Google account now, Microsoft and iCloud later. */
interface CalendarSource {
    val account: Account
    /** The calendars Edith reads (for Google, those checked in Google Calendar: D15), the default first. */
    suspend fun calendars(): Outcome<List<CalendarInfo>>
    /** Events in [from, to) across [calendars], in start order (recurring events as their instances). */
    suspend fun events(from: Instant, to: Instant, text: String? = null): Outcome<List<CalendarEvent>>
    /** Adds [draft] to [calendarId], or the default calendar when it's null. */
    suspend fun create(calendarId: String?, draft: EventDraft): Outcome<CalendarEvent>
    suspend fun update(event: CalendarEvent, change: EventChange): Outcome<CalendarEvent>
    suspend fun delete(event: CalendarEvent): Outcome<Unit>
}

/** One email in a search result: who, what, when, and the ids a reply needs. */
data class MailSummary(
    val id: String,
    val threadId: String,
    val from: String,
    val to: String?,
    val subject: String,
    val date: ZonedDateTime,
    val snippet: String,
    /** The `Message-ID` header, for a reply's `In-Reply-To`. */
    val messageIdHeader: String?,
    val unread: Boolean,
    val accountId: String = "",
)

/** An email to read: its summary and the body as plain text (quoted history removed, capped). */
data class MailMessage(val summary: MailSummary, val cc: String?, val body: String)

/** A search's messages, newest first, and whether more matched than were returned. */
data class MailSearchResult(val messages: List<MailSummary>, val more: Boolean)

/** An email to save as a draft or send. For a reply, [inReplyTo] and [threadId] come from the original. */
data class OutgoingMail(
    val to: String,
    val subject: String,
    val body: String,
    val inReplyTo: String? = null,
    val threadId: String? = null,
    /** The provider's own id of the email being replied to (Outlook's `createReply`, C23 §3). */
    val replyToId: String? = null,
)

/**
 * A provider-neutral search (C22 §4): Gmail's search syntax doesn't work for Microsoft or iCloud. [after] and
 * [before] are exact instants (the tool turns "today" into local midnight, L31).
 */
data class MailQuery(
    val from: String? = null,
    val to: String? = null,
    val subject: String? = null,
    val text: String? = null,
    val unreadOnly: Boolean = false,
    val after: Instant? = null,
    val before: Instant? = null,
)

/** One connected account's mailbox (C22 §3). */
interface MailSource {
    val account: Account
    suspend fun search(q: MailQuery, max: Int): Outcome<MailSearchResult>
    suspend fun read(id: String): Outcome<MailMessage>
    suspend fun draft(m: OutgoingMail): Outcome<Unit>
    suspend fun send(m: OutgoingMail): Outcome<Unit>
}
