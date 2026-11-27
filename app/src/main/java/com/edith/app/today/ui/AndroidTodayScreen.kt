package com.edith.app.today.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.accounts.ui.CalendarViewModel
import com.edith.app.accounts.ui.CalendarViewModelFactory
import com.edith.app.accounts.ui.MailViewModel
import com.edith.app.accounts.ui.MailViewModelFactory
import com.edith.app.accounts.ui.TodoViewModel
import com.edith.app.accounts.ui.TodoViewModelFactory
import com.edith.app.core.time.SystemClock
import com.edith.app.home.ui.HomeViewModel
import com.edith.app.home.ui.HomeViewModelFactory

/**
 * Android's Today tab (K8.1): the shared cards over the Calendar, To-do, Mail and Home view models, so nothing new is loaded for it. Camera events while the app was
 * closed arrive as notifications on Android, so that part of the Home card stays empty here.
 */
@Composable
fun TodayScreen(
    onOpenCalendar: () -> Unit,
    onOpenMail: () -> Unit,
    onOpenHome: () -> Unit,
    calendarVm: CalendarViewModel = viewModel(factory = CalendarViewModelFactory),
    todoVm: TodoViewModel = viewModel(factory = TodoViewModelFactory),
    mailVm: MailViewModel = viewModel(factory = MailViewModelFactory),
    homeVm: HomeViewModel = viewModel(factory = HomeViewModelFactory),
) {
    val calendar by calendarVm.state.collectAsStateWithLifecycle()
    val todo by todoVm.state.collectAsStateWithLifecycle()
    val mail by mailVm.state.collectAsStateWithLifecycle()
    val home by homeVm.state.collectAsStateWithLifecycle()
    TodayContent(
        now = SystemClock.now(), zone = SystemClock.zone(),
        calendar = calendar, todo = todo, mail = mail, devices = home.view.devices, catchUp = emptyList(),
        onOpenCalendar = onOpenCalendar, onOpenMail = onOpenMail, onOpenHome = onOpenHome,
    )
}
