package com.edith.app.accounts.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.edith.app.accounts.Account
import com.edith.app.accounts.Provider
import com.edith.app.accounts.tasks.TaskItem
import com.edith.app.accounts.tasks.TaskList
import com.edith.app.ui.theme.EdithTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** C35 §5: the Agenda | To-do switch, sections, a tick box, Show done and the Grant access banner. */
@OptIn(ExperimentalTestApi::class)
class TodoContentTest {

    private val today = LocalDate(2026, 11, 13)
    private val personal = Account("a1", Provider.Google, "a@gmail.com", "personal")
    private val work = Account("b2", Provider.Google, "sam@contoso.com", "work")
    private val list = TaskList("L1", "My Tasks")
    private fun t(id: String, title: String, due: LocalDate? = null, parent: String? = null, done: Boolean = false) =
        TaskItem(id, "L1", title, null, due, done, parent = parent, position = id, accountId = "a1")

    @Test fun switchShowsEachView() = runComposeUiTest {
        var view by mutableStateOf(CalendarView.Agenda)
        setContent { EdithTheme { CalendarViewSwitch(view) { view = it } } }
        onNodeWithText("Agenda").assertExists()
        onNodeWithText("To-do").performClick()
        runOnIdle { assertEquals(CalendarView.Todo, view) }
    }

    @Test fun sectionsTickShowDoneAndGrantBanner() = runComposeUiTest {
        val tasks = listOf(t("a", "Call the dentist", due = today.minus(1, DateTimeUnit.DAY)), t("b", "Pack", due = today), t("b1", "Socks", parent = "b"))
        val state = TodoUi.Lists(
            sections = listOf(TodoSection(personal, list, "My Tasks · personal", tasks)),
            today = today,
            needsPermission = listOf(work),
        )
        var ticked: String? = null
        var shownDone: Boolean? = null
        var granted: String? = null
        setContent { EdithTheme { TodoContent(state, {}, {}, { _, task -> ticked = task.id }, { shownDone = it }, { granted = it }) } }

        onNodeWithText("My Tasks · personal").assertExists()
        onNodeWithText("Call the dentist").assertExists()
        onNodeWithText("Due Thu Nov 12 · overdue").assertExists()
        onNodeWithText("Due today").assertExists()
        onNodeWithText("Socks").assertExists()
        onNodeWithTag(TASK_CHECK_TAG + "a").performClick()
        runOnIdle { assertEquals("a", ticked) }
        onNodeWithTag(SHOW_DONE_TAG).performClick()
        runOnIdle { assertEquals(true, shownDone) }
        onNodeWithText("work: to-dos need your permission.").assertExists()
        onNodeWithText("Grant access").performClick()
        runOnIdle { assertEquals("b2", granted) }
    }
}
