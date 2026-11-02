package com.edith.app.google.calendar.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.google.calendar.CalendarEvent
import com.edith.app.ui.theme.EdithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** C18 §3: the Calendar tab when Google isn't connected, and the agenda grouped by day with event details. */
@RunWith(AndroidJUnit4::class)
class CalendarScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("America/Detroit")
    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone)

    @Test fun notConnectedThenAgendaWithDetails() {
        val today = LocalDate.of(2026, 11, 2)
        val events = listOf(
            CalendarEvent("1", "p", "Blake", "Standup", at(2, 9), at(2, 9, 15), false),
            CalendarEvent("2", "p", "Blake", "Dentist", at(3, 13), at(3, 14), false, location = "Main St Dental"),
            CalendarEvent("3", "p", "Blake", "Mom's birthday", at(4, 0), at(5, 0), true),
        )
        var state by mutableStateOf<CalendarUi>(CalendarUi.NotConnected(needsPermission = false))
        var settings = 0
        compose.setContent { EdithTheme { CalendarContent(state, { settings++ }, {}) } }

        compose.onNodeWithText("Connect Google account").performClick()
        compose.runOnIdle { assertEquals(1, settings) }
        state = CalendarUi.NotConnected(needsPermission = true)
        compose.onNodeWithText("Grant access in Settings").assertExists()

        state = CalendarUi.Agenda(CalendarViewModel.group(events, today))
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Tomorrow").assertExists()
        compose.onNodeWithText("Wednesday, Nov 4").assertExists()
        compose.onNodeWithText("All day").assertExists()
        compose.onNodeWithText("Dentist").performClick()
        compose.onNodeWithText("Tue Nov 3, 1:00–2:00 PM").assertExists()
        compose.onNodeWithText("Close").performClick()
    }

    @Test fun anEmptyWeekSaysSo() {
        compose.setContent { EdithTheme { CalendarContent(CalendarUi.Agenda(emptyList()), {}, {}) } }
        compose.onNodeWithText("Nothing on your calendar for the next week.").assertExists()
    }
}
