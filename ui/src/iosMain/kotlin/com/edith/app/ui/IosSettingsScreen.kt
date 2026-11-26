package com.edith.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edith.app.accounts.IosAccounts
import com.edith.app.core.locale.formatDecimal
import com.edith.app.home.Home
import com.edith.app.settings.SettingsRepository
import com.edith.app.settings.SettingsViewModel
import com.edith.app.ui.settings.AttachmentSettingsSection
import com.edith.app.ui.settings.AttachmentSettingsViewModel
import com.edith.app.ui.settings.SettingsContent
import com.edith.app.ui.settings.WebSearchSection
import com.edith.app.ui.settings.WebSearchSettingsViewModel
import com.edith.app.voice.speech.SpeechOutput
import kotlinx.coroutines.launch

/**
 * The iPhone's Settings page (K3.6): one page where there were two tabs. The shared server-and-model section on top (the Ollama address, Test connection, the
 * model), then Voice, Attachments, Web search, Accounts, the smart home and a button for the engine check, and About at the bottom. Android's Settings is the
 * same shared content with its own extra sections (hands-free, its voices), so a setting that exists on both means the same on both.
 */
@Composable
fun IosSettingsScreen(
    settingsVm: SettingsViewModel,
    webSearchVm: WebSearchSettingsViewModel,
    attachmentsVm: AttachmentSettingsViewModel,
    settings: SettingsRepository,
    speech: SpeechOutput,
    accounts: IosAccounts,
    home: Home,
    alertPrefs: com.edith.app.alerts.AlertPrefs,
    /** Keeps Jarvis told of the push address (alerts while closed); null: no such row. */
    push: com.edith.app.alerts.push.PushRegistration? = null,
    edithVersion: String,
    openEngineCheck: () -> Unit,
    /** The last crash report, if there is one (D58); Settings → About shows "View last crash report". */
    crashReport: String? = null,
) {
    val state by settingsVm.state.collectAsStateWithLifecycle()
    Column(Modifier.statusBarsPadding()) {
        SettingsContent(
            state = state,
            crashReport = crashReport,
            onServerChange = settingsVm::onServerFieldChange,
            onSave = settingsVm::saveServer,
            onTest = settingsVm::testConnection,
            onRetry = settingsVm::loadModels,
            onSelectModel = settingsVm::selectModel,
            onDismissNotice = settingsVm::dismissNotice,
            onRequestPermission = {},   // iOS asks about the local network itself, at the first connection
            onOpenAppSettings = {},
            edithVersion = edithVersion,
            extraSections = {
                IosVoiceSection(settings, speech)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                AttachmentSettingsSection(attachmentsVm)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                WebSearchSection(webSearchVm)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                IosAlertSettings(home, alertPrefs, push)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccountsSections(accounts, home)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = openEngineCheck) { Text("Run the engine check") }
                }
            },
        )
    }
}

/** Settings → Voice on the iPhone: whether replies are read aloud (off until switched on), how fast, and a test. Edith speaks with the voice chosen in the phone's Accessibility settings. */
@Composable
private fun IosVoiceSection(settings: SettingsRepository, speech: SpeechOutput) {
    val scope = rememberCoroutineScope()
    val current by settings.settings.collectAsState(initial = null)
    val s = current ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Voice", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Read replies aloud", style = MaterialTheme.typography.bodyLarge)
                Text("Speaks each new reply with the voice chosen in the phone's Accessibility settings", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = s.speakReplies, onCheckedChange = { on -> scope.launch { settings.update { it.copy(speakReplies = on) }; if (!on) speech.stop() } })
        }
        Text("Speed: ${formatDecimal(s.speechRate.toDouble(), 1, "en-US")}×", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = s.speechRate,
            onValueChange = { rate -> scope.launch { settings.update { it.copy(speechRate = rate.coerceIn(SpeechOutput.MIN_RATE, SpeechOutput.MAX_RATE)) } } },
            valueRange = SpeechOutput.MIN_RATE..SpeechOutput.MAX_RATE,
        )
        OutlinedButton(onClick = { speech.test() }) { Text("Test voice") }
    }
}
