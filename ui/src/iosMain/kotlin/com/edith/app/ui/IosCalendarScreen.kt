package com.edith.app.ui

import androidx.compose.runtime.Composable
import com.edith.app.accounts.PlatformActivity
import com.edith.app.accounts.ui.AgendaScreenFrame
import com.edith.app.accounts.ui.CalendarTabFrame
import com.edith.app.accounts.ui.CalendarViewModel
import com.edith.app.accounts.ui.TodoScreenFrame
import com.edith.app.accounts.ui.TodoViewModel

/**
 * The iPhone's Calendar tab (K3.5, K4.16): the shared Agenda | To-do frame over its view models. The To-do view model is made only when that view is picked
 * ([todoVm] is called then). **To-dos: Grant access** signs in again in the system sheet, which asks for every scope. Settings is the More tab.
 */
@Composable
fun IosCalendarScreen(calendarVm: CalendarViewModel, todoVm: @Composable () -> TodoViewModel, openMore: () -> Unit) {
    CalendarTabFrame(
        agenda = { AgendaScreenFrame(calendarVm, openMore) },
        todo = {
            val vm = todoVm()
            TodoScreenFrame(vm, openMore, onGrant = { id -> vm.grantTasks(id, PlatformActivity()) {} })
        },
    )
}
