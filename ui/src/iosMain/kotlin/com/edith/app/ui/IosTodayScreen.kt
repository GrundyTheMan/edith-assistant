package com.edith.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edith.app.accounts.ui.CalendarViewModel
import com.edith.app.accounts.ui.MailViewModel
import com.edith.app.accounts.ui.TodoViewModel
import com.edith.app.alerts.Alert
import com.edith.app.core.time.Clock
import com.edith.app.home.Home
import com.edith.app.today.ui.TodayContent

/**
 * The iPhone's Today tab (K8.1): the shared cards over the view models the other tabs use (so nothing new is loaded), and the camera events found since Edith was last
 * open ([catchUp], K4.16) in the Home card. Keeps clear of the status bar itself, as the other tabs do.
 */
@Composable
fun IosTodayScreen(
    calendarVm: CalendarViewModel,
    todoVm: TodoViewModel,
    mailVm: MailViewModel,
    home: Home,
    catchUp: List<Alert>,
    clock: Clock,
    openCalendar: () -> Unit,
    openMail: () -> Unit,
    openHome: () -> Unit,
) {
    val calendar by calendarVm.state.collectAsStateWithLifecycle()
    val todo by todoVm.state.collectAsStateWithLifecycle()
    val mail by mailVm.state.collectAsStateWithLifecycle()
    val view by home.view.collectAsStateWithLifecycle()
    Box(Modifier.statusBarsPadding()) {
        TodayContent(
            now = clock.now(), zone = clock.zone(), calendar = calendar, todo = todo, mail = mail, devices = view.devices, catchUp = catchUp,
            onOpenCalendar = openCalendar, onOpenMail = openMail, onOpenHome = openHome,
        )
    }
}
