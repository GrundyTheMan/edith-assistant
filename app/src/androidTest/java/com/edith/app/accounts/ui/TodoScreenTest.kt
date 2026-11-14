package com.edith.app.accounts.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.accounts.Account
import com.edith.app.accounts.Provider
import com.edith.app.accounts.tasks.TaskItem
import com.edith.app.accounts.tasks.TaskList
import com.edith.app.ui.theme.EdithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** C35 §5: the Agenda | To-do switch, sections, a tick box, Show done and the Grant access banner. */
@RunWith(AndroidJUnit4::class)
class TodoScreenTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 13)
    private val personal = Account("a1", Provider.Google, "a@gmail.com", "personal")
    private val work = Account("b2", Provider.Google, "sam@contoso.com", "work")
    private val list = TaskList("L1", "My Tasks")
    private fun t(id: String, title: String, due: LocalDate? = null, parent: String? = null, done: Boolean = false) =
        TaskItem(id, "L1", title, null, due, done, parent = parent, position = id, accountId = "a1")

    @Test fun switchShowsEachView() {
        var view by mutableStateOf(CalendarView.Agenda)
        compose.setContent { EdithTheme { CalendarViewSwitch(view) { view = it } } }
        compose.onNodeWithText("Agenda").assertExists()
        compose.onNodeWithText("To-do").performClick()
        compose.runOnIdle { assertEquals(CalendarView.Todo, view) }
    }

    @Test fun sectionsTickShowDoneAndGrantBanner() {
        val tasks = listOf(t("a", "Call the dentist", due = today.minusDays(1)), t("b", "Pack", due = today), t("b1", "Socks", parent = "b"))
        val state = TodoUi.Lists(
            sections = listOf(TodoSection(personal, list, "My Tasks · personal", tasks)),
            today = today,
            needsPermission = listOf(work),
        )
        var ticked: String? = null
        var shownDone: Boolean? = null
        var granted: String? = null
        compose.setContent { EdithTheme { TodoContent(state, {}, {}, { _, task -> ticked = task.id }, { shownDone = it }, { granted = it }) } }

        compose.onNodeWithText("My Tasks · personal").assertExists()
        compose.onNodeWithText("Call the dentist").assertExists()
        compose.onNodeWithText("Due Thu Nov 12 · overdue").assertExists()
        compose.onNodeWithText("Due today").assertExists()
        compose.onNodeWithText("Socks").assertExists()
        compose.onNodeWithTag(TASK_CHECK_TAG + "a").performClick()
        compose.runOnIdle { assertEquals("a", ticked) }
        compose.onNodeWithTag(SHOW_DONE_TAG).performClick()
        compose.runOnIdle { assertEquals(true, shownDone) }
        compose.onNodeWithText("work: to-dos need your permission.").assertExists()
        compose.onNodeWithText("Grant access").performClick()
        compose.runOnIdle { assertEquals("b2", granted) }
    }
}
