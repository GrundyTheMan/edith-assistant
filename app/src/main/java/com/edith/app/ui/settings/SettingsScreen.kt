package com.edith.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.BuildConfig
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.ollama.ModelInfo
import com.edith.app.settings.ConnectionState
import com.edith.app.settings.ModelsState
import com.edith.app.settings.PermissionState
import com.edith.app.settings.SettingsUiState
import com.edith.app.settings.SettingsViewModel
import com.edith.app.ui.components.CrashReportDialog
import com.edith.app.ui.components.CrashReportViewModel

/** Settings → Server & model, Voice (C13), Hands-free (C14), Attachments (C16), Web search (C10), Accounts (C22), and About (C04 §3). */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
    crash: CrashReportViewModel = viewModel(factory = CrashReportViewModel.Factory),
    webSearch: WebSearchSettingsViewModel = viewModel(factory = WebSearchSettingsViewModel.Factory),
    voice: VoiceSettingsViewModel = viewModel(factory = VoiceSettingsViewModel.Factory),
    handsFree: HandsFreeSettingsViewModel = viewModel(factory = HandsFreeSettingsViewModel.Factory),
    attachments: AttachmentSettingsViewModel = viewModel(factory = AttachmentSettingsViewModel.Factory),
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
        },
    )
}

/** Stateless screen content, so it can be tested and previewed without a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    crashReport: String?,
    onServerChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onRetry: () -> Unit,
    onSelectModel: (String) -> Unit,
    onDismissNotice: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    /** Sections added by later chunks (web search, C10), shown above About. */
    extraSections: @Composable () -> Unit = {},
) {
    var showCrashReport by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Server & model", style = MaterialTheme.typography.titleMedium)

        PermissionCard(state.permission, onRequestPermission, onOpenAppSettings)

        OutlinedTextField(
            value = state.serverField,
            onValueChange = onServerChange,
            label = { Text("Ollama server address") },
            singleLine = true,
            isError = state.serverError != null,
            supportingText = {
                Text(
                    when {
                        state.serverError != null -> state.serverError
                        state.hasUnsavedServer -> "Not saved yet. Tap Save to keep this address."
                        else -> "Emulator: http://10.0.2.2:11434 · Phone: your PC's address, e.g. http://192.168.4.82:11434"
                    },
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTest) { Text("Test connection") }
            Button(onClick = onSave, enabled = state.hasUnsavedServer) { Text("Save") }
        }
        when (val c = state.connection) {
            ConnectionState.Idle -> Unit
            ConnectionState.Testing -> Text("Testing…")
            is ConnectionState.Ok -> Text("Connected — Ollama ${c.version}", color = MaterialTheme.colorScheme.primary)
            is ConnectionState.Failed -> Text(c.msg, color = MaterialTheme.colorScheme.error)
        }

        state.notice?.let { notice ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, Modifier.weight(1f))
                    TextButton(onClick = onDismissNotice) { Text("OK") }
                }
            }
        }

        Text("Model", style = MaterialTheme.typography.titleSmall)
        when (val m = state.models) {
            ModelsState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Loading models…")
            }
            ModelsState.NeedsPermission -> Text("Allow local network access above to load your models.")
            ModelsState.Empty -> ErrorWithRetry("No chat models installed on this server", onRetry)
            is ModelsState.Failed -> ErrorWithRetry(m.msg, onRetry)
            is ModelsState.Loaded -> ModelPicker(m.list, state.selectedModel, onSelectModel)
        }
        if (state.savedModelMissing) {
            Text(
                "Your saved model ${state.selectedModel} isn't installed on this server. Pick another.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        extraSections()
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("About", style = MaterialTheme.typography.titleMedium)
        Text("Edith ${BuildConfig.VERSION_NAME}")
        Text("Ollama ${state.ollamaVersion ?: "— not reachable"}")
        if (crashReport != null) {
            TextButton(onClick = { showCrashReport = true }, contentPadding = ButtonPaddingNone) { Text("View last crash report") }
        } else {
            Text("No crash reports", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showCrashReport && crashReport != null) CrashReportDialog(crashReport, onClose = { showCrashReport = false })
}

private val ButtonPaddingNone = androidx.compose.foundation.layout.PaddingValues(0.dp)

@Composable
private fun PermissionCard(permission: PermissionState, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    if (permission == PermissionState.Granted || permission == PermissionState.Unknown) return
    val denied = permission == PermissionState.Denied
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (denied) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (denied) "Edith can't reach Ollama on your PC without local network access. You can allow it in Android settings, under Permissions."
                else "Edith needs access to your local network to talk to Ollama on your PC.",
            )
            if (denied) Button(onClick = onOpenSettings) { Text("Open Android settings") }
            else Button(onClick = onRequest) { Text("Allow") }
        }
    }
}

@Composable
private fun ErrorWithRetry(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("Retry") }   // a real button (04-lessons L17)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(models: List<ModelInfo>, selected: String?, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected ?: "Choose a model",
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            supportingText = { models.firstOrNull { it.name == selected }?.let { Text(capabilityLine(it)) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.name)
                            capabilityLine(model).takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(model.name)
                    },
                )
            }
        }
    }
}

/** "Sees images · Hears audio · Uses tools", only those the model has (C04 §3). */
fun capabilityLine(model: ModelInfo): String = listOfNotNull(
    "Sees images".takeIf { model.canSee },
    "Hears audio".takeIf { model.canHear },
    "Uses tools".takeIf { model.canUseTools },
).joinToString(" · ")
