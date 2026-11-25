package com.edith.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.BuildConfig
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.di.viewModelFactory
import com.edith.app.settings.PermissionState
import com.edith.app.settings.SettingsViewModel
import com.edith.app.ui.components.CrashReportViewModel

/** The Settings view model, made from the app's container (the shared class has no factory: the iPhone makes it its own way). */
val SettingsViewModelFactory = viewModelFactory { c, _ ->
    SettingsViewModel(c.settingsRepository, c.modelCatalog, c.ollama, c.modelSelection, c.localNetworkAccess)
}

val WebSearchSettingsViewModelFactory = viewModelFactory { container, _ -> WebSearchSettingsViewModel(container.webSearchSetup, container.ollamaWeb) }

val AttachmentSettingsViewModelFactory = viewModelFactory { c, _ -> AttachmentSettingsViewModel(c.settingsRepository, c.retention) }

/** Settings → Server & model, Voice (C13), Hands-free (C14), Attachments (C16), Web search (C10), Accounts (C22), and About (C04 §3). Android's: the shared [SettingsContent] with the local-network permission and Android's own sections. */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(factory = SettingsViewModelFactory),
    crash: CrashReportViewModel = viewModel(factory = CrashReportViewModel.Factory),
    webSearch: WebSearchSettingsViewModel = viewModel(factory = WebSearchSettingsViewModelFactory),
    voice: VoiceSettingsViewModel = viewModel(factory = VoiceSettingsViewModel.Factory),
    handsFree: HandsFreeSettingsViewModel = viewModel(factory = HandsFreeSettingsViewModel.Factory),
    attachments: AttachmentSettingsViewModel = viewModel(factory = AttachmentSettingsViewModelFactory),
    accounts: AccountsViewModel = viewModel(factory = AccountsViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val crashReport by crash.report.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onPermissionResult(it)
    }

    // Re-check on every return to the screen (the owner may have changed it in Android settings).
    LifecycleResumeEffect(Unit) {
        vm.refreshPermission()
        onPauseOrDispose { }
    }
    // Ask once, automatically, the first time the screen needs it (04-lessons L23).
    var askedAutomatically by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.permission) {
        if (state.permission == PermissionState.NeedsRequest && !askedAutomatically) {
            askedAutomatically = true
            requestPermission.launch(LocalNetworkAccess.PERMISSION)
        }
    }

    SettingsContent(
        state = state,
        crashReport = crashReport,
        onServerChange = vm::onServerFieldChange,
        onSave = vm::saveServer,
        onTest = vm::testConnection,
        onRetry = vm::loadModels,
        onSelectModel = vm::selectModel,
        onDismissNotice = vm::dismissNotice,
        onRequestPermission = { requestPermission.launch(LocalNetworkAccess.PERMISSION) },
        onOpenAppSettings = {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        edithVersion = BuildConfig.VERSION_NAME,
        extraSections = {
            VoiceSection(voice)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            HandsFreeSection(handsFree)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            AttachmentSettingsSection(attachments)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            WebSearchSection(webSearch)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            AccountsSection(accounts)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            HomeSection()
        },
    )
}
