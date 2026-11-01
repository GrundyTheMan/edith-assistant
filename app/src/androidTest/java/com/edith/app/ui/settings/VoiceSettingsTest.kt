package com.edith.app.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.ui.theme.EdithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C13 §2: the Voice section's switch, speed, Test voice and the "no voice" message. */
@RunWith(AndroidJUnit4::class)
class VoiceSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun switchTestVoiceAndAndroidSettings() {
        var state by mutableStateOf(VoiceUiState(speakReplies = true, speechRate = 1.2f))
        var tests = 0
        var opened = 0
        compose.setContent {
            EdithTheme {
                VoiceContent(state, { on -> state = state.copy(speakReplies = on) }, {}, { tests++ }, { opened++ })
            }
        }
        compose.onNodeWithText("Speed: 1.2×").assertExists()
        compose.onNodeWithTag(SPEAK_REPLIES_TAG).assertIsOn().performClick()
        compose.onNodeWithTag(SPEAK_REPLIES_TAG).assertIsOff()
        assertEquals(false, state.speakReplies)

        compose.onNodeWithText("Test voice").assertIsEnabled().performClick()
        compose.onNodeWithText("Android voice settings").performClick()
        compose.runOnIdle {
            assertEquals(1, tests)
            assertEquals(1, opened)
        }

        state = state.copy(problem = "Speech isn't available — install a voice in Android settings (Text-to-speech output).")
        compose.onNodeWithText("Test voice").assertIsNotEnabled()
        compose.onNodeWithText(state.problem!!).assertExists()
    }
}
