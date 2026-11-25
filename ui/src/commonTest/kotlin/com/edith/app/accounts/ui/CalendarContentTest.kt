package com.edith.app.accounts.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.edith.app.accounts.Account
import com.edith.app.accounts.Provider
import com.edith.app.accounts.sources.CalendarEvent
import com.edith.app.accounts.sources.MergedEvent
import com.edith.app.ui.theme.EdithTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * C18 §3, C22 §6: the Calendar tab with no account, the agenda grouped by day with event details, and with two
 * accounts each event's account and a banner for one that can't be read.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarContentTest {

    private val zone = TimeZone.of("America/Detroit")
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 11, d, h, m).toInstant(zone)
    private val personal = Account("a1", Provider.Google, "a@gmail.com", "personal")
    private val work = Account("b2", Provider.Google, "sam@contoso.com", "work")

    @Test fun notConnectedThenAgendaWithDetails() = runComposeUiTest {
        val today = LocalDate(2026, 11, 2)
        val events = listOf(
            CalendarEvent("1", "p", "Blake", "Standup", at(2, 9), at(2, 9, 15), false),
            CalendarEvent("2", "p", "Blake", "Dentist", at(3, 13), at(3, 14), false, location = "Main St Dental"),
            CalendarEvent("3", "p", "Blake", "Mom's birthday", at(4, 0), at(5, 0), true),
        ).map { MergedEvent(it, personal) }
        var state by mutableStateOf<CalendarUi>(CalendarUi.NotConnected(needsSignIn = false))
        var settings = 0
        setContent { EdithTheme { CalendarContent(state, { settings++ }, {}) } }

        onNodeWithText("Add an account").performClick()
        runOnIdle { assertEquals(1, settings) }
        state = CalendarUi.NotConnected(needsSignIn = true)
        onNodeWithText("Sign in again in Settings").assertExists()

        state = CalendarUi.Agenda(CalendarViewModel.group(events, today, zone), zone = zone)
        onNodeWithText("Today").assertExists()
        onNodeWithText("Tomorrow").assertExists()
        onNodeWithText("Wednesday, Nov 4").assertExists()
        onNodeWithText("All day").assertExists()
        onNodeWithText("personal").assertDoesNotExist()   // one account: no labels
        onNodeWithText("Dentist").performClick()
        onNodeWithText("Tue Nov 3, 1:00–2:00 PM").assertExists()
        onNodeWithText("Close").performClick()
    }

    @Test fun twoAccountsAreLabelledAndAFailingOneHasABanner() = runComposeUiTest {
        val today = LocalDate(2026, 11, 2)
        val events = listOf(
            MergedEvent(CalendarEvent("1", "p", "Personal", "Lunch", at(2, 12), at(2, 13), false), personal),
            MergedEvent(CalendarEvent("2", "w", "Work", "Team sync", at(2, 9), at(2, 10), false), work,
                alsoIn = listOf(personal to CalendarEvent("2b", "p", "Personal", "Team sync", at(2, 9), at(2, 10), false))),
        )
        var settings = 0
        val problem = "Couldn't read other (x@y.z): it needs signing in again — Settings → Accounts."
        setContent {
            EdithTheme { CalendarContent(CalendarUi.Agenda(CalendarViewModel.group(events, today, zone), showAccount = true, problems = listOf(problem), zone = zone), { settings++ }, {}) }
        }
        onNodeWithText("personal").assertExists()
        onNodeWithText("work, personal").assertExists()   // the invite in both accounts, listed once
        onNodeWithText(problem).assertExists()
        onAllNodesWithText("Settings")[0].performClick()
        runOnIdle { assertEquals(1, settings) }
    }

    @Test fun anEmptyWeekSaysSo() = runComposeUiTest {
        setContent { EdithTheme { CalendarContent(CalendarUi.Agenda(emptyList()), {}, {}) } }
        onNodeWithText("Nothing on your calendar for the next week.").assertExists()
    }
}
