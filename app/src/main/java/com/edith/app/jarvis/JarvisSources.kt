package com.edith.app.jarvis

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
import com.edith.app.accounts.tasks.TaskItem
import com.edith.app.accounts.tasks.TaskList
import com.edith.app.accounts.tasks.TaskPatch
import com.edith.app.accounts.tasks.TaskSource
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The words every read-only account says when asked to change something (C38: Jarvis's feed only reads and saves drafts). */
internal const val READ_ONLY = "Edith can only read your work account through Jarvis (and save email drafts); change it in Outlook on the PC."

private fun <T> readOnly(): Outcome<T> = Outcome.Failure(AppError.ToolFailed("work account", READ_ONLY))

private inline fun <A, B> Outcome<A>.map(f: (A) -> B): Outcome<B> = when (this) {
    is Outcome.Success -> Outcome.Success(f(value))
    is Outcome.Failure -> this
}

/** One more email address in "Name <address>" form, as the mail tools show senders. */
internal fun WireAddress.display(): String = when {
    name.isBlank() -> address
    address.isBlank() -> name
    name.equals(address, ignoreCase = true) -> address
    else -> "$name <$address>"
}

/** The shared work mailboxes as one mailbox (C38 §2): ids are Jarvis's own, so a message keeps its account. */
internal class JarvisMailSource(
    override val account: Account,
    private val api: JarvisApi,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Instant = Instant::now,
) : MailSource {
    override val canSend: Boolean get() = false

    override suspend fun search(q: MailQuery, max: Int): Outcome<MailSearchResult> {
        val words = listOfNotNull(q.text, q.subject, q.from, q.to).joinToString(" ").trim()
        val days = q.after?.let { Duration.between(it, now()).toDays().plus(1).coerceIn(1, 365) } ?: DEFAULT_DAYS
        val query = buildMap {
            if (words.isNotEmpty()) put("text", words)
            put("sinceDays", days.toString())
            if (q.unreadOnly) put("unreadOnly", "1")
            put("limit", max.coerceIn(1, 100).toString())
            // Older Outlook mail is found only by searching, so a text search always reaches into it.
            if (words.isNotEmpty() && days > DEFAULT_DAYS) put("archive", "1")
        }
        return api.get("mail/messages", query, Page.serializer(WireMail.serializer())).map { page ->
            val found = page.items.map { it.toSummary(account.id, zone()) }
                .filter { q.from == null || it.from.contains(q.from, ignoreCase = true) }
                .filter { q.to == null || it.to.orEmpty().contains(q.to, ignoreCase = true) }
                .filter { q.subject == null || it.subject.contains(q.subject, ignoreCase = true) }
                .filter { q.before == null || it.date.toInstant() < q.before }
                .filter { q.after == null || it.date.toInstant() >= q.after }
            MailSearchResult(found, page.more)
        }
    }

    override suspend fun read(id: String): Outcome<MailMessage> =
        api.get("mail/messages", emptyMap(), WireMailBody.serializer(), last = id).map { b ->
            val note = if (b.truncated) "\n\n[The rest of this email was cut off by Jarvis.]" else ""
            MailMessage(b.message.toSummary(account.id, zone()), b.message.cc.joinToString().ifBlank { null }, b.text + note)
        }

    override suspend fun draft(m: OutgoingMail): Outcome<Unit> {
        val request = if (m.replyToId != null) {
            DraftRequest(replyTo = m.replyToId, text = m.body)
        } else {
            val shared = when (val s = api.get("shared", emptyMap(), Shared.serializer())) {
                is Outcome.Success -> s.value
                is Outcome.Failure -> return s
            }
            val from = shared.mail.firstOrNull() ?: return Outcome.Failure(AppError.ToolFailed("Jarvis", "Jarvis isn't sharing a mail account with Edith."))
            DraftRequest(account = from.id, to = m.to, subject = m.subject, body = m.body)
        }
        return api.post("mail/drafts", feedJson.encodeToString(DraftRequest.serializer(), request), DraftSaved.serializer()).map { Unit }
    }

    override suspend fun send(m: OutgoingMail): Outcome<Unit> =
        Outcome.Failure(AppError.ToolFailed("email", "Edith can't send from your work account: she can save a draft there, and you send it from Outlook."))

    companion object {
        const val DEFAULT_DAYS = 30L
    }
}

internal fun WireMail.toSummary(accountId: String, zone: ZoneId) = MailSummary(
    id = id,
    threadId = id,
    from = from.display(),
    to = to.joinToString().ifBlank { null },
    subject = subject,
    date = Instant.ofEpochMilli(date).atZone(zone),
    snippet = preview,
    messageIdHeader = null,
    unread = unread,
    accountId = accountId,
)

/** The shared work calendars as one source (C38 §2), read-only. */
internal class JarvisCalendarSource(
    override val account: Account,
    private val api: JarvisApi,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : CalendarSource {
    override val readOnly: Boolean get() = true

    override suspend fun calendars(): Outcome<List<CalendarInfo>> =
        api.get("shared", emptyMap(), Shared.serializer()).map { s ->
            s.calendars.mapIndexed { i, c -> CalendarInfo(c.id, c.label.ifBlank { "Work calendar" }, isDefault = i == 0, canWrite = false) }
        }

    /** Jarvis answers at most 92 days at a time (spec 7.4), so a long range is asked for in 90-day pieces. */
    override suspend fun events(from: Instant, to: Instant, text: String?): Outcome<List<CalendarEvent>> {
        val names = when (val c = calendars()) {
            is Outcome.Success -> c.value.associate { it.id to it.name }
            is Outcome.Failure -> return c
        }
        val found = LinkedHashMap<String, CalendarEvent>()
        var start = from
        while (start < to) {
            val end = minOf(start.plus(Duration.ofDays(CHUNK_DAYS)), to)
            val page = when (val r = api.get("calendar/events", mapOf("from" to start.toEpochMilli().toString(), "to" to end.toEpochMilli().toString()), Page.serializer(WireEvent.serializer()))) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> return r
            }
            for (e in page.items) if (!e.cancelled) found[e.id] = e.toEvent(account.id, names[e.calendar] ?: "Work calendar", zone())
            start = end
        }
        val all = found.values.sortedWith(compareBy({ it.start }, { it.title }))
        val words = text?.trim()?.takeIf { it.isNotEmpty() }
        return Outcome.Success(if (words == null) all else all.filter { e -> listOfNotNull(e.title, e.location, e.description).any { it.contains(words, ignoreCase = true) } })
    }

    override suspend fun create(calendarId: String?, draft: EventDraft): Outcome<CalendarEvent> = readOnly()
    override suspend fun update(event: CalendarEvent, change: EventChange): Outcome<CalendarEvent> = readOnly()
    override suspend fun delete(event: CalendarEvent): Outcome<Unit> = readOnly()

    companion object {
        const val CHUNK_DAYS = 90L
    }
}

/** An all-day event runs from local midnight to the next local midnight (the contract, spec 7.4); others are instants. */
internal fun WireEvent.toEvent(accountId: String, calendarName: String, zone: ZoneId): CalendarEvent {
    val s: ZonedDateTime = Instant.ofEpochMilli(start).atZone(zone)
    val e: ZonedDateTime = Instant.ofEpochMilli(end).atZone(zone)
    return CalendarEvent(
        id = id, calendarId = calendar, calendarName = calendarName, title = title.ifBlank { "(no title)" },
        start = s, end = if (e.isAfter(s)) e else s.plusMinutes(30),
        allDay = allDay, location = location?.ifBlank { null },
        description = listOfNotNull(description?.ifBlank { null }, joinUrl?.let { "Join: $it" }).joinToString("\n").ifBlank { null },
        accountId = accountId,
    )
}

/** The shared Outlook Tasks (spec part 8) as to-do lists, read-only. */
internal class JarvisTaskSource(override val account: Account, private val api: JarvisApi) : TaskSource {
    override val readOnly: Boolean get() = true

    override suspend fun lists(): Outcome<List<TaskList>> =
        api.get("tasks/lists", emptyMap(), Page.serializer(WireTaskList.serializer())).map { p -> p.items.map { TaskList(it.id, it.title.ifBlank { "Tasks" }) } }

    override suspend fun tasks(listId: String, includeDone: Boolean): Outcome<List<TaskItem>> =
        api.get("tasks", mapOf("list" to listId, "completed" to if (includeDone) "1" else "0"), Page.serializer(WireTask.serializer())).map { p ->
            p.items.mapIndexed { i, t ->
                TaskItem(
                    id = t.id, listId = listId, title = t.title.ifBlank { "(no title)" }, notes = t.notes?.ifBlank { null },
                    due = t.due?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    done = t.done, completedAt = t.completed?.let(Instant::ofEpochMilli),
                    position = "%06d".format(i),   // Jarvis sends them in Outlook's order
                    accountId = account.id,
                )
            }
        }

    override suspend fun insert(listId: String, title: String, notes: String?, due: LocalDate?, parent: String?): Outcome<TaskItem> = readOnly()
    override suspend fun patch(listId: String, taskId: String, patch: TaskPatch): Outcome<TaskItem> = readOnly()
    override suspend fun complete(listId: String, taskId: String, done: Boolean): Outcome<TaskItem> = readOnly()
    override suspend fun move(listId: String, taskId: String, parent: String?, previous: String?): Outcome<TaskItem> = readOnly()
    override suspend fun delete(listId: String, taskId: String): Outcome<Unit> = readOnly()
}

