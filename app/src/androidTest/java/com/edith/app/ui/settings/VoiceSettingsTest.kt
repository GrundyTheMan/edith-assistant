package com.edith.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.settings.SpeechVoice
import com.edith.app.ui.components.VOICE_BANNER_TAG
import com.edith.app.ui.components.VOICE_BANNER_TEXT
import com.edith.app.ui.components.VoiceBanner
import com.edith.app.ui.theme.EdithTheme
import com.edith.app.voice.own.OwnVoiceState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C13 §2 and C36 §5: the Voice section's switch, speed, voice choice, Edith's own voice and the "no voice" message. */
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
                VoiceContent(
                    state,
                    VoiceActions(
                        onSpeakReplies = { on -> state = state.copy(speakReplies = on) },
                        onTest = { tests++ },
                        onOpenTtsSettings = { opened++ },
                    ),
                )
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

    @Test fun theThreeVoicesCanBeChosen() {
        var state by mutableStateOf(VoiceUiState())
        compose.setContent {
            EdithTheme { VoiceContent(state, VoiceActions(onChoose = { state = state.copy(choice = it) })) }
        }
        compose.onNodeWithTag(VOICE_AUTO_TAG).assertIsSelected()
        compose.onNodeWithText("Phone's voice").performClick()
        compose.onNodeWithTag(VOICE_PHONE_TAG).assertIsSelected()
        compose.onNodeWithText("Edith's own voice").performClick()
        compose.onNodeWithTag(VOICE_OWN_TAG).assertIsSelected()
        assertEquals(SpeechVoice.Own, state.choice)
    }

    @Test fun ownVoiceStatesAndTheirButtons() {
        var state by mutableStateOf(VoiceUiState(ownSize = "82 MB"))
        var downloads = 0
        var cancels = 0
        var deletes = 0
        compose.setContent {
            EdithTheme {
                VoiceContent(state, VoiceActions(onDownload = { downloads++ }, onCancelDownload = { cancels++ }, onDeleteVoice = { deletes++ }))
            }
        }
        compose.onNodeWithText("Not downloaded").assertExists()
        compose.onNodeWithText("Download (about 82 MB)").performClick()

        state = state.copy(own = OwnVoiceState.Downloading(43))
        compose.onNodeWithText("Downloading 43%").assertExists()
        compose.onNodeWithText("Cancel").performClick()

        state = state.copy(own = OwnVoiceState.Downloading(43, waitingForScreen = true))
        compose.onNodeWithText("Waiting for Edith to be on screen (43%)").assertExists()

        state = state.copy(own = OwnVoiceState.Verifying)
        compose.onNodeWithText("Checking the download…").assertExists()

        state = state.copy(own = OwnVoiceState.Ready(98_000_000))
        compose.onNodeWithText("Ready, 98 MB").assertExists()
        compose.onNodeWithText("Delete").performClick()

        state = state.copy(own = OwnVoiceState.Failed("The file didn't match what I expected. Try again."))
        compose.onNodeWithText("The file didn't match what I expected. Try again.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(Triple(2, 1, 1), Triple(downloads, cancels, deletes)) }
    }

    @Test fun withNoPhoneVoiceItOffersEdithsOwnWithADownloadButtonBesideIt() {
        var state by mutableStateOf(
            VoiceUiState(problem = "Your phone has no voice. Download Edith's own voice in Settings → Voice.", phoneAvailable = false),
        )
        var downloads = 0
        compose.setContent { EdithTheme { VoiceContent(state, VoiceActions(onDownload = { downloads++ })) } }
        compose.onNodeWithTag(NO_PHONE_VOICE_TAG).assertExists()
        compose.onNodeWithText("Your phone has no voice. Edith can use her own.").assertExists()
        compose.onNodeWithTag(NO_PHONE_DOWNLOAD_TAG).performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
        compose.onNodeWithText("Android voice settings").assertDoesNotExist()   // nothing to open there

        // Once downloading, the row with its own state takes over.
        state = state.copy(own = OwnVoiceState.Downloading(10))
        compose.onNodeWithTag(NO_PHONE_VOICE_TAG).assertDoesNotExist()
        compose.onNodeWithText("Downloading 10%").assertExists()
    }

    @Test fun theChatBannerSaysItCantSpeakYetAndCanBeDismissed() {
        var opened = 0
        var dismissed = 0
        compose.setContent { EdithTheme { VoiceBanner(onOpenSettings = { opened++ }, onDismiss = { dismissed++ }) } }
        compose.onNodeWithTag(VOICE_BANNER_TAG).assertExists()
        compose.onNodeWithText(VOICE_BANNER_TEXT).assertExists()
        assertEquals("I can't speak yet. Get my voice in Settings → Voice.", VOICE_BANNER_TEXT)
        compose.onNodeWithText("Open Settings").performClick()
        compose.onNodeWithText("Dismiss").performClick()
        compose.runOnIdle { assertEquals(1 to 1, opened to dismissed) }
    }
}
