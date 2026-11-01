package com.edith.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edith.app.di.viewModelFactory
import com.edith.app.settings.SettingsRepository
import com.edith.app.voice.handsfree.HandsFree
import com.edith.app.voice.handsfree.OpenWakeWord
import com.edith.app.voice.handsfree.WakeListener
import com.edith.app.voice.handsfree.WakeWordModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class WakeTest { Idle, Listening, Heard, NotHeard, MicUnavailable }

data class HandsFreeSettingsUi(
    val phrase: String = "",
    val isStandIn: Boolean = false,
    val sensitivity: Float = 0.5f,
    val followUpSeconds: Int = 8,
    val test: WakeTest = WakeTest.Idle,
    /** Testing needs the microphone, which hands-free holds while it's on. */
    val handsFreeOn: Boolean = false,
)

/** Settings → Hands-free (C14 §3): the wake word, its sensitivity, the follow-up window, and a test. */
class HandsFreeSettingsViewModel(
    private val settings: SettingsRepository,
    private val model: WakeWordModel,
    private val wake: WakeListener,
    handsFree: HandsFree,
) : ViewModel() {
    private val test = MutableStateFlow(WakeTest.Idle)
    private var testJob: Job? = null

    val state: StateFlow<HandsFreeSettingsUi> = combine(settings.settings, test, handsFree.state) { s, t, hf ->
        HandsFreeSettingsUi(model.phrase, model.isStandIn, s.wakeWordSensitivity, s.followUpSeconds, t, hf != HandsFree.State.Off)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HandsFreeSettingsUi(model.phrase, model.isStandIn))

    fun setSensitivity(value: Float) {
        viewModelScope.launch { settings.update { it.copy(wakeWordSensitivity = value.coerceIn(0f, 1f)) } }
    }

    fun setFollowUpSeconds(value: Int) {
        viewModelScope.launch { settings.update { it.copy(followUpSeconds = value.coerceIn(0, MAX_FOLLOW_UP)) } }
    }

    /** Listens for up to [TEST_MS] and says whether the wake word was heard. */
    fun testWakeWord() {
        if (state.value.handsFreeOn || test.value == WakeTest.Listening) return
        testJob = viewModelScope.launch {
            test.value = WakeTest.Listening
            val threshold = OpenWakeWord.threshold(settings.current().wakeWordSensitivity)
            val started = wake.start(threshold, { test.value = WakeTest.Heard }, { test.value = WakeTest.MicUnavailable })
            if (!started) {
                test.value = WakeTest.MicUnavailable
                return@launch
            }
            var waited = 0L
            while (test.value == WakeTest.Listening && waited < TEST_MS) { delay(100); waited += 100 }
            wake.stop()
            if (test.value == WakeTest.Listening) test.value = WakeTest.NotHeard
        }
    }

    override fun onCleared() {
        if (testJob?.isActive == true) wake.stop()
    }

    companion object {
        const val TEST_MS = 10_000L
        const val MAX_FOLLOW_UP = 15

        val Factory = viewModelFactory { c, _ ->
            HandsFreeSettingsViewModel(c.settingsRepository, c.wakeWordModel, c.wakeListener, c.handsFree)
        }
    }
}

@Composable
fun HandsFreeSection(vm: HandsFreeSettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.testWakeWord()
    }
    HandsFreeContent(
        state = state,
        onSensitivity = vm::setSensitivity,
        onFollowUp = vm::setFollowUpSeconds,
        onTest = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                vm.testWakeWord()
            } else {
                requestMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    )
}

@Composable
fun HandsFreeContent(
    state: HandsFreeSettingsUi,
    onSensitivity: (Float) -> Unit,
    onFollowUp: (Int) -> Unit,
    onTest: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hands-free", style = MaterialTheme.typography.titleMedium)
        Text(
            "Turn it on with the ear button in Chat. Say “${state.phrase}”, then your question; you can follow up " +
                "without the wake word. Listening happens on this phone; only what you ask is sent to Ollama.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.isStandIn) {
            Text(
                "“${state.phrase}” is a stand-in until the “Hey Edith” model is added (docs/wake-word-training.md).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        var sensitivity by remember { mutableStateOf<Float?>(null) }
        Text("Wake-word sensitivity: ${((sensitivity ?: state.sensitivity) * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
        Text("Higher hears you more easily, but wakes by mistake more often.", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = sensitivity ?: state.sensitivity,
            onValueChange = { sensitivity = it },
            onValueChangeFinished = { sensitivity?.let(onSensitivity); sensitivity = null },
            valueRange = 0f..1f,
            steps = 9,
            modifier = Modifier.testTag(WAKE_SENSITIVITY_TAG).semantics { contentDescription = "Wake-word sensitivity" },
        )

        var followUp by remember { mutableStateOf<Float?>(null) }
        val seconds = (followUp ?: state.followUpSeconds.toFloat()).roundToInt()
        Text(
            if (seconds == 0) "Follow-ups: off" else "Follow-ups: listen for $seconds s after Edith answers",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = followUp ?: state.followUpSeconds.toFloat(),
            onValueChange = { followUp = it },
            onValueChangeFinished = { followUp?.let { onFollowUp(it.roundToInt()) }; followUp = null },
            valueRange = 0f..HandsFreeSettingsViewModel.MAX_FOLLOW_UP.toFloat(),
            steps = HandsFreeSettingsViewModel.MAX_FOLLOW_UP - 1,
            modifier = Modifier.testTag(FOLLOW_UP_TAG).semantics { contentDescription = "Follow-up window" },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onTest, enabled = !state.handsFreeOn && state.test != WakeTest.Listening) {
                Text("Test wake word")
            }
            Text(
                when (state.test) {
                    WakeTest.Idle -> if (state.handsFreeOn) "Turn hands-free off to test." else ""
                    WakeTest.Listening -> "Say “${state.phrase}”…"
                    WakeTest.Heard -> "✓ Heard it"
                    WakeTest.NotHeard -> "Didn't hear it. Try again, or raise the sensitivity."
                    WakeTest.MicUnavailable -> "The microphone isn't available."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.test == WakeTest.Heard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false).testTag(WAKE_TEST_TAG),
            )
        }
    }
}

const val WAKE_SENSITIVITY_TAG = "wake_sensitivity"
const val FOLLOW_UP_TAG = "follow_up"
const val WAKE_TEST_TAG = "wake_test"
