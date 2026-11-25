package com.edith.app.accounts.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.di.viewModelFactory

/** The Calendar view model, made from the app's container (the shared class has no factory: the iPhone makes it its own way). */
val CalendarViewModelFactory = viewModelFactory { c, _ -> CalendarViewModel(c.accounts, c.accounts.accounts, c.accounts.loaded, c.clock) }

/** Android's Calendar tab: the shared frame (Agenda | To-do) with each view over its view model. */
@Composable
fun CalendarScreen(onOpenSettings: () -> Unit) {
    CalendarTabFrame(
        agenda = { AgendaScreen(onOpenSettings) },
        todo = { TodoScreen(onOpenSettings) },
    )
}

@Composable
fun AgendaScreen(onOpenSettings: () -> Unit, vm: CalendarViewModel = viewModel(factory = CalendarViewModelFactory)) {
    AgendaScreenFrame(vm, onOpenSettings)
}
