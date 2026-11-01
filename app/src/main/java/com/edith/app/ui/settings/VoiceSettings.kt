package com.edith.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edith.app.di.viewModelFactory
import com.edith.app.settings.SettingsRepository
import com.edith.app.voice.speech.SpeechOutput
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

data class VoiceUiState(
    val speakReplies: Boolean = true,
    val speechRate: Float = 1.0f,
    /** Why the phone can't speak, or null. */
    val problem: String? = null,
)

/** Settings → Voice (C13 §2): auto-speech, speed, Test voice, and Android's text-to-speech settings. */
class VoiceSettingsViewModel(private val settings: SettingsRepository, private val speech: SpeechOutput) : ViewModel() {
    val state: StateFlow<VoiceUiState> = combine(settings.settings, speech.problem) { s, problem ->
        VoiceUiState(s.speakReplies, s.speechRate, problem)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceUiState())

    fun setSpeakReplies(on: Boolean) {
        viewModelScope.launch { settings.update { it.copy(speakReplies = on) } }
        if (!on) speech.stop()
    }

    fun setSpeechRate(rate: Float) {
        viewModelScope.launch { settings.update { it.copy(speechRate = rate.coerceIn(SpeechOutput.MIN_RATE, SpeechOutput.MAX_RATE)) } }
    }

    fun testVoice() = speech.test()

    override fun onCleared() {
        if (speech.speakingMessageId.value == SpeechOutput.TEST_VOICE) speech.stop()
    }

    companion object {
        val Factory = viewModelFactory { container, _ -> VoiceSettingsViewModel(container.settingsRepository, container.speechOutput) }
    }
}

@Composable
fun VoiceSection(vm: VoiceSettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    VoiceContent(
        state = state,
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
    )
}

@Composable
fun VoiceContent(
    state: VoiceUiState,
    onSpeakReplies: (Boolean) -> Unit,
    onSpeechRate: (Float) -> Unit,
    onTest: () -> Unit,
    onOpenTtsSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Voice", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Speak Edith's replies", style = MaterialTheme.typography.bodyLarge)
                Text("Reads each new reply aloud with the phone's voice", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = state.speakReplies,
                onCheckedChange = onSpeakReplies,
                modifier = Modifier.testTag(SPEAK_REPLIES_TAG).semantics { contentDescription = "Speak Edith's replies" },
            )
        }
        // The slider moves freely; the speed is saved when the owner lets go.
        var dragging by remember { mutableStateOf<Float?>(null) }
        val rate = dragging ?: state.speechRate
        Text("Speed: ${String.format(Locale.US, "%.1f", rate)}×", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = rate,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let(onSpeechRate); dragging = null },
            valueRange = SpeechOutput.MIN_RATE..SpeechOutput.MAX_RATE,
            steps = 9,
            modifier = Modifier.testTag(SPEECH_RATE_TAG).semantics { contentDescription = "Speech speed" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onTest, enabled = state.problem == null) { Text("Test voice") }
            TextButton(onClick = onOpenTtsSettings) { Text("Android voice settings") }
        }
        state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
const val SPEAK_REPLIES_TAG = "speak_replies"
const val SPEECH_RATE_TAG = "speech_rate"
