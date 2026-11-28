package com.edith.app.today.ui

import com.edith.app.accounts.Account
import com.edith.app.accounts.Provider
import com.edith.app.accounts.mail.MailRating
import com.edith.app.accounts.sources.CalendarEvent
import com.edith.app.accounts.sources.MailSummary
import com.edith.app.accounts.sources.MergedEvent
import com.edith.app.accounts.tasks.TaskItem
import com.edith.app.accounts.tasks.TaskList
import com.edith.app.accounts.ui.AgendaDay
import com.edith.app.accounts.ui.CalendarUi
import com.edith.app.accounts.ui.MailRow
import com.edith.app.accounts.ui.MailUi
import com.edith.app.accounts.ui.TodoSection
import com.edith.app.accounts.ui.TodoUi
import com.edith.app.alerts.Alert
import com.edith.app.alerts.AlertKind
import com.edith.app.home.DeviceState
import com.edith.app.home.DeviceType
import com.edith.app.home.DoorState
import com.edith.app.home.HomeDevice
import com.edith.app.home.LockState
import com.edith.app.home.PrintJob
import com.edith.app.home.PrintState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K8.1: what the Today tab's four cards say, from the states the other tabs already hold. */
class TodayModelTest {
    private val zone = TimeZone.of("America/Detroit")
    private val today = LocalDate(2026, 11, 27)
    private val account = Account("a1", Provider.Google, "a@gmail.com", "personal")
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 11, d, h, m).toInstant(zone)

    private fun event(title: String, d: Int, h: Int, allDay: Boolean = false, location: String? = null) =
        MergedEvent(CalendarEvent("e$title", "c", "Calendar", title, at(d, h), at(d, h + 1), allDay, location = location), account)

    @Test fun `the calendar card shows today and tomorrow and leaves later days out`() {
        val agenda = CalendarUi.Agenda(listOf(
            AgendaDay(LocalDate(2026, 11, 27), "Today", listOf(event("Dentist", 27, 9, location = "Main St"), event("Birthday", 27, 0, allDay = true))),
            AgendaDay(LocalDate(2026, 11, 28), "Tomorrow", listOf(event("Hike", 28, 8))),
            AgendaDay(LocalDate(2026, 11, 30), "Monday", listOf(event("Later", 30, 8))),
        ), zone = zone)
        val card = TodayModel.calendar(agenda, today)
        assertEquals(listOf("Dentist", "Birthday", "Hike"), card.lines.map { it.text })
        assertTrue(card.lines[0].lead.startsWith("9:00"))
        assertEquals("All day", card.lines[1].lead)
        assertEquals("Tomorrow", card.lines[2].lead)
        assertEquals("Main St", card.lines[0].detail)
        assertNull(card.empty)
    }

    @Test fun `an empty calendar says so and a missing account says how to add one`() {
        assertEquals("Nothing on your calendar today or tomorrow.", TodayModel.calendar(CalendarUi.Agenda(emptyList(), zone = zone), today).empty)
        assertTrue("Add an account" in TodayModel.calendar(CalendarUi.NotConnected(needsSignIn = false), today).empty.orEmpty())
        assertTrue("signing in again" in TodayModel.calendar(CalendarUi.NotConnected(needsSignIn = true), today).empty.orEmpty())
    }

    private fun task(id: String, title: String, due: LocalDate?, done: Boolean = false, parent: String? = null) =
        TaskItem(id, "L", title, due = due, done = done, parent = parent)

    private fun lists(vararg tasks: TaskItem) = TodoUi.Lists(listOf(TodoSection(account, TaskList("L", "My Tasks"), "My Tasks", tasks.toList())), today)

    @Test fun `to-dos due soon are overdue first then by date and done or far or undated ones are left out`() {
        val card = TodayModel.todos(lists(
            task("1", "Later", LocalDate(2026, 12, 20)),
            task("2", "Tomorrow", LocalDate(2026, 11, 28)),
            task("3", "Late", LocalDate(2026, 11, 20)),
            task("4", "Done", today, done = true),
            task("5", "No date", null),
            task("6", "Sub", today, parent = "2"),
            task("7", "Today", today),
        ))
        assertEquals(listOf("Late", "Today", "Tomorrow"), card.lines.map { it.text })
        assertEquals(listOf("Overdue", "Today", "Tomorrow"), card.lines.map { it.lead })
        assertEquals("My Tasks", card.lines[0].detail)
    }

    @Test fun `no to-do accounts means no card and nothing due says so`() {
        assertTrue(TodayModel.todos(TodoUi.NotConnected).let { it.lines.isEmpty() && it.empty == null })
        assertEquals("Nothing due in the next 7 days.", TodayModel.todos(lists(task("1", "Far", LocalDate(2026, 12, 25)))).empty)
    }

    private fun row(id: String, from: String, subject: String, score: Int, unread: Boolean = true, reason: String = "why") =
        MailRow(MailSummary(id, "t$id", from, null, subject, at(27, 8), "", null, unread, "a1"), account, MailRating(score, reason, "m"))

    @Test fun `the mail card shows the top unread rows in the list's order with score and reason`() {
        val inbox = MailUi.Inbox(
            listOf(row("1", "Pat <pat@x.org>", "Need a decision", 9), row("2", "Lee <lee@x.org>", "Read already", 8, unread = false), row("3", "Shop <no-reply@s.com>", "Sale", 2, reason = "")),
            listOf(account), zone = zone,
        )
        val card = TodayModel.mail(inbox)
        assertEquals(listOf("9/10", "2/10"), card.lines.map { it.lead })
        assertEquals("Pat: Need a decision", card.lines[0].text)
        assertEquals("why", card.lines[0].detail)
        assertNull(card.lines[1].detail)
    }

    @Test fun `no unread mail says so`() {
        val card = TodayModel.mail(MailUi.Inbox(listOf(row("1", "Pat <p@x.org>", "Old", 5, unread = false)), listOf(account), zone = zone))
        assertEquals("No unread mail in the last 14 days.", card.empty)
    }

    private fun device(name: String, type: DeviceType, state: DeviceState?) = HomeDevice("d$name", name, null, type, emptySet(), "meross", state = state)

    @Test fun `the home card lists camera events then doors locks and the printer`() {
        val card = TodayModel.home(
            listOf(
                device("Garage", DeviceType.GarageDoor, DeviceState(door = DoorState.Open)),
                device("Front", DeviceType.Lock, DeviceState(lock = LockState.Locked)),
                device("Voron", DeviceType.Printer, DeviceState(print = PrintJob(PrintState.Printing, "Benchy", 0.42))),
                device("Lamp", DeviceType.Light, DeviceState(on = true)),
            ),
            listOf(Alert("ring", "Front Door", AlertKind.Doorbell, at(27, 7).toEpochMilliseconds(), "k1")),
            zone, { it.kind.label },
        )
        assertEquals(listOf("Doorbell rings: Front Door", "Garage", "Front", "Voron"), card.lines.map { if (it.lead.contains(":")) it.text else it.text })
        assertEquals("Open", card.lines[1].detail)
        assertEquals("Locked", card.lines[2].detail)
        assertEquals("Printing Benchy 42%", card.lines[3].detail)
        assertTrue(card.lines[0].lead.startsWith("7:00"))
    }

    @Test fun `a home with nothing to report says how to connect`() {
        val card = TodayModel.home(emptyList(), emptyList(), zone) { it.kind.label }
        assertTrue(card.lines.isEmpty() && "Connect" in card.empty.orEmpty())
    }

    @Test fun `the heading is the weekday and date`() = assertEquals("Friday, Nov 27", TodayModel.heading(today))
}
