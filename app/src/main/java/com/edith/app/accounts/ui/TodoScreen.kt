package com.edith.app.accounts.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.di.viewModelFactory

/** The To-do view model, made from the app's container (the shared class has no factory: the iPhone makes it its own way). */
val TodoViewModelFactory = viewModelFactory { c, _ ->
    TodoViewModel(
        c.accounts, c.accounts.accounts, c.accounts.tasksAccess, c.accounts.loaded, c.clock,
        grant = { id, a, show -> c.accounts.grantTasks(id, a, show) },
        consentResult = { c.accounts.onConsentResult(it) },
        appScope = c.appScope,
    )
}

/** Android's To-do view: the shared one, with Google's consent screen for **To-dos: Grant access**. */
@Composable
fun TodoScreen(onOpenSettings: () -> Unit, vm: TodoViewModel = viewModel(factory = TodoViewModelFactory)) {
    val activity = LocalActivity.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> vm.onConsentResult(result.data) }
    TodoScreenFrame(vm, onOpenSettings, onGrant = { id -> activity?.let { a -> vm.grantTasks(id, a) { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) } } })
}
