package com.edith.app.accounts.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.di.viewModelFactory

/** The Mail view model, made from the app's container (the shared class has no factory: the iPhone makes it its own way). */
val MailViewModelFactory = viewModelFactory { c, _ -> MailViewModel(c.accounts, c.accounts.accounts, c.accounts.loaded, c.clock, c.settingsRepository, c.mailRanker) }

/** Android's Mail tab (K7.1): the shared ranked list over its view model. */
@Composable
fun MailScreen(onOpenSettings: () -> Unit, vm: MailViewModel = viewModel(factory = MailViewModelFactory)) {
    MailScreenFrame(vm, onOpenSettings)
}
