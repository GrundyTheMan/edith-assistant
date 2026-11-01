package com.edith.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.ui.theme.EdithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C14 §3: Settings → Hands-free shows the wake word (and that it's a stand-in), the sliders, and the test's result. */
@RunWith(AndroidJUnit4::class)
class HandsFreeSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun standInNoticeSlidersAndWakeWordTest() {
        var state by mutableStateOf(HandsFreeSettingsUi(phrase = "Hey Jarvis", isStandIn = true, sensitivity = 0.5f, followUpSeconds = 8))
        var tests = 0
        compose.setContent { EdithTheme { HandsFreeContent(state, {}, {}, { tests++ }) } }

        compose.onNodeWithText("“Hey Jarvis” is a stand-in", substring = true).assertExists()
        compose.onNodeWithText("Wake-word sensitivity: 50%").assertExists()
        compose.onNodeWithText("Follow-ups: listen for 8 s after Edith answers").assertExists()

        compose.onNodeWithText("Test wake word").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, tests) }

        state = state.copy(test = WakeTest.Listening)
        compose.onNodeWithTag(WAKE_TEST_TAG).assert(hasText("Say “Hey Jarvis”…"))
        compose.onNodeWithText("Test wake word").assertIsNotEnabled()
        state = state.copy(test = WakeTest.Heard)
        compose.onNodeWithTag(WAKE_TEST_TAG).assert(hasText("✓ Heard it"))

        state = state.copy(test = WakeTest.Idle, handsFreeOn = true, followUpSeconds = 0, isStandIn = false, phrase = "Hey Edith")
        compose.onNodeWithText("Test wake word").assertIsNotEnabled()
        compose.onNodeWithText("Turn hands-free off to test.").assertExists()
        compose.onNodeWithText("Follow-ups: off").assertExists()
        compose.onNodeWithText("stand-in", substring = true).assertDoesNotExist()
    }
}
