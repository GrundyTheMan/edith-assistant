package com.edith.app.accounts.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.accounts.Account
import com.edith.app.accounts.Provider
import com.edith.app.accounts.sources.CalendarEvent
import com.edith.app.accounts.sources.MergedEvent
import com.edith.app.ui.theme.EdithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * C18 §3, C22 §6: the Calendar tab with no account, the agenda grouped by day with event details, and with two
 * accounts each event's account and a banner for one that can't be read.
 */
@RunWith(AndroidJUnit4::class)
class CalendarScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = TimeZone.of("America/Detroit")
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 11, d, h, m).toInstant(zone)
    private val personal = Account("a1", Provider.Google, "a@gmail.com", "personal")
    private val work = Account("b2", Provider.Google, "sam@contoso.com", "work")

    @Test fun notConnectedThenAgendaWithDetails() {
        val today = LocalDate(2026, 11, 2)
        val events = listOf(
            CalendarEvent("1", "p", "Blake", "Standup", at(2, 9), at(2, 9, 15), false),
            CalendarEvent("2", "p", "Blake", "Dentist", at(3, 13), at(3, 14), false, location = "Main St Dental"),
            CalendarEvent("3", "p", "Blake", "Mom's birthday", at(4, 0), at(5, 0), true),
        ).map { MergedEvent(it, personal) }
        var state by mutableStateOf<CalendarUi>(CalendarUi.NotConnected(needsSignIn = false))
        var settings = 0
        compose.setContent { EdithTheme { CalendarContent(state, { settings++ }, {}) } }

        compose.onNodeWithText("Add an account").performClick()
        compose.runOnIdle { assertEquals(1, settings) }
        state = CalendarUi.NotConnected(needsSignIn = true)
        compose.onNodeWithText("Sign in again in Settings").assertExists()

        state = CalendarUi.Agenda(CalendarViewModel.group(events, today, zone), zone = zone)
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Tomorrow").assertExists()
        compose.onNodeWithText("Wednesday, Nov 4").assertExists()
        compose.onNodeWithText("All day").assertExists()
        compose.onNodeWithText("personal").assertDoesNotExist()   // one account: no labels
        compose.onNodeWithText("Dentist").performClick()
        compose.onNodeWithText("Tue Nov 3, 1:00–2:00 PM").assertExists()
        compose.onNodeWithText("Close").performClick()
    }

    @Test fun twoAccountsAreLabelledAndAFailingOneHasABanner() {
        val today = LocalDate(2026, 11, 2)
        val events = listOf(
            MergedEvent(CalendarEvent("1", "p", "Personal", "Lunch", at(2, 12), at(2, 13), false), personal),
            MergedEvent(CalendarEvent("2", "w", "Work", "Team sync", at(2, 9), at(2, 10), false), work,
                alsoIn = listOf(personal to CalendarEvent("2b", "p", "Personal", "Team sync", at(2, 9), at(2, 10), false))),
        )
        var settings = 0
        val problem = "Couldn't read other (x@y.z): it needs signing in again — Settings → Accounts."
        compose.setContent {
            EdithTheme { CalendarContent(CalendarUi.Agenda(CalendarViewModel.group(events, today, zone), showAccount = true, problems = listOf(problem), zone = zone), { settings++ }, {}) }
        }
        compose.onNodeWithText("personal").assertExists()
        compose.onNodeWithText("work, personal").assertExists()   // the invite in both accounts, listed once
        compose.onNodeWithText(problem).assertExists()
        compose.onAllNodesWithText("Settings")[0].performClick()
        compose.runOnIdle { assertEquals(1, settings) }
    }

    @Test fun anEmptyWeekSaysSo() {
        compose.setContent { EdithTheme { CalendarContent(CalendarUi.Agenda(emptyList()), {}, {}) } }
        compose.onNodeWithText("Nothing on your calendar for the next week.").assertExists()
    }
}
