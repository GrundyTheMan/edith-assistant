package com.edith.app.today.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edith.app.accounts.mail.MailRanking
import com.edith.app.accounts.tasks.TaskItem
import com.edith.app.accounts.ui.CalendarUi
import com.edith.app.accounts.ui.MailUi
import com.edith.app.accounts.ui.TodoUi
import com.edith.app.alerts.Alert
import com.edith.app.core.time.formatPattern
import com.edith.app.home.DeviceType
import com.edith.app.home.HomeDevice
import com.edith.app.home.PrintState
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * What one line of a card says and what it opens: pure data so the rules are tested without a screen. Built by [TodayModel] from the states the Calendar, To-do, Mail and Home
 * tabs already hold, so the Today tab adds no loading of its own.
 */
data class TodayLine(val lead: String, val text: String, val detail: String? = null)

/** One card: its heading, its lines, and a sentence when there is nothing to show (or it cannot). */
data class TodayCard(val title: String, val lines: List<TodayLine>, val empty: String?)

/** The rules for what the Today tab shows (K8.1). */
object TodayModel {
    /** The most lines a card shows before "and N more" is left to its own tab. */
    const val MAX_LINES = 5

    /** How far ahead a to-do counts as "due soon". */
    const val DUE_DAYS = 7

    /** Events today and tomorrow, in time order: "9:30 AM  Dentist", all-day ones first with "All day", tomorrow's with "Tomorrow". */
    fun calendar(state: CalendarUi, today: LocalDate): TodayCard = when (state) {
        is CalendarUi.NotConnected -> TodayCard("Calendar", emptyList(), if (state.needsSignIn) "Your accounts need signing in again." else "Add an account to see your calendar here.")
        CalendarUi.Loading -> TodayCard("Calendar", emptyList(), "Loading…")
        is CalendarUi.Failed -> TodayCard("Calendar", emptyList(), state.message)
        is CalendarUi.Agenda -> {
            val tomorrow = today.plus(1, DateTimeUnit.DAY)
            val lines = state.days.filter { it.date == today || it.date == tomorrow }.flatMap { day ->
                day.events.map { m ->
                    val e = m.event
                    val time = if (e.allDay) "All day" else formatPattern(e.start, state.zone, "h:mm a", "en-US")
                    TodayLine(if (day.date == today) time else "Tomorrow", e.title, e.location?.takeIf { it.isNotBlank() })
                }
            }
            TodayCard("Calendar", lines.take(MAX_LINES), if (lines.isEmpty()) "Nothing on your calendar today or tomorrow." else null)
        }
    }

    /** Overdue and due-within-a-week to-dos, soonest first: "Overdue", "Today", "Tomorrow", or the date. */
    fun todos(state: TodoUi): TodayCard = when (state) {
        TodoUi.NotConnected -> TodayCard("To-dos", emptyList(), null)
        TodoUi.Loading -> TodayCard("To-dos", emptyList(), "Loading…")
        is TodoUi.Failed -> TodayCard("To-dos", emptyList(), state.message)
        is TodoUi.Lists -> {
            val limit = state.today.plus(DUE_DAYS, DateTimeUnit.DAY)
            val due = state.sections.flatMap { s -> s.tasks.mapNotNull { t -> t.due?.takeIf { !t.done && t.parent == null && it <= limit }?.let { d -> Triple(d, t, s) } } }
                .sortedBy { it.first }
            val lines = due.map { (_, t, s) -> TodayLine(dueWords(t, state.today), t.title, s.title) }
            TodayCard("To-dos due soon", lines.take(MAX_LINES), if (lines.isEmpty()) "Nothing due in the next $DUE_DAYS days." else null)
        }
    }

    fun dueWords(t: TaskItem, today: LocalDate): String {
        val due = t.due ?: return ""
        val days = due.toEpochDays() - today.toEpochDays()
        return when {
            days < 0 -> "Overdue"
            days == 0L -> "Today"
            days == 1L -> "Tomorrow"
            else -> formatPattern(due, "EEE, MMM d", "en-US")
        }
    }

    /** The highest-ranked unread emails: the score first, then who and what. */
    fun mail(state: MailUi): TodayCard = when (state) {
        is MailUi.NotConnected -> TodayCard("Top mail", emptyList(), if (state.needsSignIn) "Your accounts need signing in again." else "Add an account to see your mail here.")
        MailUi.Loading -> TodayCard("Top mail", emptyList(), "Loading…")
        is MailUi.Failed -> TodayCard("Top mail", emptyList(), state.message)
        is MailUi.Inbox -> {
            val lines = state.rows.filter { it.summary.unread }.take(MAX_LINES).map { r ->
                TodayLine("${r.rating.score}/10", "${MailRanking.nameOf(r.summary.from)}: ${r.summary.subject.ifBlank { "(no subject)" }}", r.rating.reason.takeIf { it.isNotBlank() })
            }
            TodayCard("Top mail", lines, if (lines.isEmpty()) "No unread mail in the last 14 days." else null)
        }
    }

    /** Camera events since Edith was last open, then the doors, locks and printer that matter now. */
    fun home(devices: List<HomeDevice>, catchUp: List<Alert>, zone: TimeZone, kindLabel: (Alert) -> String): TodayCard {
        val lines = mutableListOf<TodayLine>()
        catchUp.take(3).forEach { a -> lines += TodayLine(formatPattern(Instant.fromEpochMilliseconds(a.at), zone, "h:mm a", "en-US"), "${kindLabel(a)}: ${a.device}") }
        devices.filter { it.type == DeviceType.GarageDoor }.forEach { d -> lines += TodayLine("Garage", d.name, d.state?.door?.label ?: "Unknown") }
        devices.filter { it.type == DeviceType.Lock }.forEach { d -> lines += TodayLine("Lock", d.name, d.state?.lock?.label ?: "Unknown") }
        devices.filter { it.type == DeviceType.Printer }.forEach { d ->
            val job = d.state?.print
            val text = when {
                job == null -> "No report"
                job.state == PrintState.Printing -> "Printing ${job.title ?: ""}".trim() + " " + (job.progress * 100).toInt() + "%"
                else -> job.state.label
            }
            lines += TodayLine("Printer", d.name, text)
        }
        return TodayCard("Home", lines.take(MAX_LINES + 3), if (lines.isEmpty()) "Nothing to report. Connect Ring, Blink or Meross in Settings to see cameras and doors here." else null)
    }

    /** "Friday, Nov 27". */
    fun heading(today: LocalDate): String = formatPattern(today, "EEEE, MMM d", "en-US")

    fun today(now: Instant, zone: TimeZone): LocalDate = now.toLocalDateTime(zone).date
}

/** The Today tab (K8.1): the day at a glance as four cards, each opening its own tab. */
@Composable
fun TodayContent(
    now: Instant,
    zone: TimeZone,
    calendar: CalendarUi,
    todo: TodoUi,
    mail: MailUi,
    devices: List<HomeDevice>,
    catchUp: List<Alert>,
    onOpenCalendar: () -> Unit,
    onOpenMail: () -> Unit,
    onOpenHome: () -> Unit,
) {
    val today = TodayModel.today(now, zone)
    val cards = listOf(
        Triple(TodayModel.calendar(calendar, today), onOpenCalendar, "today-calendar"),
        Triple(TodayModel.todos(todo), onOpenCalendar, "today-todos"),
        Triple(TodayModel.mail(mail), onOpenMail, "today-mail"),
        Triple(TodayModel.home(devices, catchUp, zone) { it.kind.label }, onOpenHome, "today-home"),
    ).filter { (card, _, _) -> card.lines.isNotEmpty() || card.empty != null }
    LazyColumn(Modifier.fillMaxSize().testTag(TODAY_TAG), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(TodayModel.heading(today), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        cards.forEach { (card, open, tag) -> item(key = tag) { CardView(card, open, tag) } }
    }
}

@Composable
private fun CardView(card: TodayCard, onOpen: () -> Unit, tag: String) {
    Card(Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onOpen), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            card.lines.forEach { l ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(l.lead, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp), maxLines = 1)
                    Column(Modifier.weight(1f)) {
                        Text(l.text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        l.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            card.empty?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

const val TODAY_TAG = "today"
