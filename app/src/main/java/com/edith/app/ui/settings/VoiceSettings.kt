package com.edith.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edith.app.di.viewModelFactory
import com.edith.app.settings.SettingsRepository
import com.edith.app.settings.SpeechVoice
import com.edith.app.voice.own.OwnVoiceState
import com.edith.app.voice.own.OwnVoices
import com.edith.app.voice.own.VoiceDownloader
import com.edith.app.voice.speech.ChoosingTts
import com.edith.app.voice.speech.SpeechOutput
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/** What Settings → Voice shows. */
data class VoiceUiState(
    val speakReplies: Boolean = true,
    val speechRate: Float = 1.0f,
    /** Why nothing can speak right now, or null. */
    val problem: String? = null,
    /** C36: which voice the owner chose. */
    val choice: SpeechVoice = SpeechVoice.Auto,
    /** C36: the phone has a voice of its own that works offline. */
    val phoneAvailable: Boolean = true,
    val own: OwnVoiceState = OwnVoiceState.NotDownloaded,
    /** "82 MB" */
    val ownSize: String = OwnVoices.default.sizeLabel,
)

/** What the Voice section can do (so [VoiceContent] can be tested without a ViewModel). */
data class VoiceActions(
    val onSpeakReplies: (Boolean) -> Unit = {},
    val onSpeechRate: (Float) -> Unit = {},
    val onTest: () -> Unit = {},
    val onOpenTtsSettings: () -> Unit = {},
    val onChoose: (SpeechVoice) -> Unit = {},
    val onDownload: () -> Unit = {},
    val onCancelDownload: () -> Unit = {},
    val onDeleteVoice: () -> Unit = {},
)

/** Settings → Voice (C13 §2, C36 §5): auto-speech, which voice, Edith's own voice, speed, Test voice. */
class VoiceSettingsViewModel(
    private val settings: SettingsRepository,
    private val speech: SpeechOutput,
    private val choosing: ChoosingTts,
    private val downloader: VoiceDownloader,
) : ViewModel() {
    val state: StateFlow<VoiceUiState> = combine(settings.settings, speech.problem, choosing.phoneAvailable, downloader.state) { s, problem, phone, own ->
        VoiceUiState(s.speakReplies, s.speechRate, problem, s.speechVoice, phone, own)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceUiState())

    fun setSpeakReplies(on: Boolean) {
        viewModelScope.launch { settings.update { it.copy(speakReplies = on) } }
        if (!on) speech.stop()
    }

    fun setSpeechRate(rate: Float) {
        viewModelScope.launch { settings.update { it.copy(speechRate = rate.coerceIn(SpeechOutput.MIN_RATE, SpeechOutput.MAX_RATE)) } }
    }

    fun choose(voice: SpeechVoice) {
        viewModelScope.launch { settings.update { it.copy(speechVoice = voice) } }
    }

    fun download() = downloader.start()
    fun cancelDownload() = downloader.cancel()
    fun deleteVoice() = downloader.delete()

    fun testVoice() = speech.test()

    override fun onCleared() {
        if (speech.speakingMessageId.value == SpeechOutput.TEST_VOICE) speech.stop()
    }

    companion object {
        val Factory = viewModelFactory { container, _ ->
            VoiceSettingsViewModel(container.settingsRepository, container.speechOutput, container.choosingTts, container.voiceDownloader)
        }
    }
}

@Composable
fun VoiceSection(vm: VoiceSettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    VoiceContent(
        state = state,
        actions = VoiceActions(
            onSpeakReplies = vm::setSpeakReplies,
            onSpeechRate = vm::setSpeechRate,
            onTest = vm::testVoice,
            onOpenTtsSettings = {
                try {
                    context.startActivity(Intent(TTS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            onChoose = vm::choose,
            onDownload = vm::download,
            onCancelDownload = vm::cancelDownload,
            onDeleteVoice = vm::deleteVoice,
        ),
    )
}

@Composable
fun VoiceContent(state: VoiceUiState, actions: VoiceActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Voice", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Speak Edith's replies", style = MaterialTheme.typography.bodyLarge)
                Text("Reads each new reply aloud", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = state.speakReplies,
                onCheckedChange = actions.onSpeakReplies,
                modifier = Modifier.testTag(SPEAK_REPLIES_TAG).semantics { contentDescription = "Speak Edith's replies" },
            )
        }
        VoiceChoice(SpeechVoice.Auto, "Automatic (recommended)", VOICE_AUTO_TAG, state, actions)
        VoiceChoice(SpeechVoice.Phone, "Phone's voice", VOICE_PHONE_TAG, state, actions)
        VoiceChoice(SpeechVoice.Own, "Edith's own voice", VOICE_OWN_TAG, state, actions)
        OwnVoiceRow(state, actions)
        // The slider moves freely; the speed is saved when the owner lets go.
        var dragging by remember { mutableStateOf<Float?>(null) }
        val rate = dragging ?: state.speechRate
        Text("Speed: ${String.format(Locale.US, "%.1f", rate)}×", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = rate,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let(actions.onSpeechRate); dragging = null },
            valueRange = SpeechOutput.MIN_RATE..SpeechOutput.MAX_RATE,
            steps = 9,
            modifier = Modifier.testTag(SPEECH_RATE_TAG).semantics { contentDescription = "Speech speed" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = actions.onTest, enabled = state.problem == null) { Text("Test voice") }
            if (state.phoneAvailable) TextButton(onClick = actions.onOpenTtsSettings) { Text("Android voice settings") }
        }
        if (!state.phoneAvailable && state.own is OwnVoiceState.NotDownloaded) {
            // C36 §5: the phone has no voice, so say what to do about it, with the button right here.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Your phone has no voice. Edith can use her own.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f).testTag(NO_PHONE_VOICE_TAG),
                )
                Button(onClick = actions.onDownload, modifier = Modifier.testTag(NO_PHONE_DOWNLOAD_TAG)) { Text("Download") }
            }
        } else {
            state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun VoiceChoice(voice: SpeechVoice, label: String, tag: String, state: VoiceUiState, actions: VoiceActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = state.choice == voice, role = Role.RadioButton) { actions.onChoose(voice) }
            .testTag(tag),
    ) {
        RadioButton(selected = state.choice == voice, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

/** The state of Edith's own voice, with its button (C36 §5). */
@Composable
private fun OwnVoiceRow(state: VoiceUiState, actions: VoiceActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 36.dp).testTag(OWN_STATE_TAG),
    ) {
        when (val own = state.own) {
            OwnVoiceState.NotDownloaded -> {
                Text("Not downloaded", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = actions.onDownload, modifier = Modifier.testTag(DOWNLOAD_TAG)) { Text("Download (about ${state.ownSize})") }
            }
            is OwnVoiceState.Downloading -> {
                val text = if (own.waitingForScreen) "Waiting for Edith to be on screen (${own.percent}%)" else "Downloading ${own.percent}%"
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = actions.onCancelDownload, modifier = Modifier.testTag(CANCEL_TAG)) { Text("Cancel") }
            }
            is OwnVoiceState.Verifying -> Text("Checking the download ${own.percent}%", style = MaterialTheme.typography.bodyMedium)
            is OwnVoiceState.Unpacking -> Text("Unpacking ${own.percent}% (can take a few minutes)", style = MaterialTheme.typography.bodyMedium)
            is OwnVoiceState.Ready -> {
                Text("Ready, ${(own.sizeBytes + 500_000) / 1_000_000} MB", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = actions.onDeleteVoice, modifier = Modifier.testTag(DELETE_TAG)) { Text("Delete") }
            }
            is OwnVoiceState.Failed -> {
                Text(own.message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = actions.onDownload, modifier = Modifier.testTag(DOWNLOAD_TAG)) { Text("Try again") }
            }
        }
    }
}

private const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
const val SPEAK_REPLIES_TAG = "speak_replies"
const val SPEECH_RATE_TAG = "speech_rate"
const val VOICE_AUTO_TAG = "voice_auto"
const val VOICE_PHONE_TAG = "voice_phone"
const val VOICE_OWN_TAG = "voice_own"
const val OWN_STATE_TAG = "own_voice_state"
const val DOWNLOAD_TAG = "own_voice_download"
const val NO_PHONE_DOWNLOAD_TAG = "no_phone_voice_download"
const val CANCEL_TAG = "own_voice_cancel"
const val DELETE_TAG = "own_voice_delete"
const val NO_PHONE_VOICE_TAG = "no_phone_voice"
