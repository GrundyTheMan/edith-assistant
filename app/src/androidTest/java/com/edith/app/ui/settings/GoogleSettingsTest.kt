package com.edith.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.google.GoogleState
import com.edith.app.ui.theme.EdithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C17 §3, C17b §3: Settings → Google account in each state, Disconnect asks first, and the browser sign-in. */
@RunWith(AndroidJUnit4::class)
class GoogleSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun eachStateShowsItsButton() {
        var state by mutableStateOf<GoogleState>(GoogleState.NotSetUp)
        val calls = mutableListOf<String>()
        compose.setContent { EdithTheme { GoogleContent(state, { calls += "sign in" }, { calls += "grant" }, { calls += "disconnect" }) } }

        compose.onNodeWithTag(GOOGLE_STATUS_TAG).assert(hasText("Web and Desktop client IDs are missing", substring = true))
        compose.onNodeWithText("Sign in with Google").assertDoesNotExist()

        state = GoogleState.SignedOut
        compose.onNodeWithText("Not connected").assertExists()
        compose.onNodeWithText("Sign in with Google").performClick()

        state = GoogleState.NeedsConsent("owner@gmail.com")
        compose.onNodeWithText("owner@gmail.com needs your permission").assertExists()
        compose.onNodeWithText("Grant access").performClick()

        state = GoogleState.Connected("owner@gmail.com")
        compose.onNodeWithText("Connected as owner@gmail.com").assertExists()
        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("Disconnect Google?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        compose.tapDialogDisconnect()

        state = GoogleState.Error(null, "No Google account is set up on this phone.")
        compose.onNodeWithText("No Google account is set up on this phone.").assertExists()
        assertEquals(listOf("sign in", "grant", "disconnect"), calls)
    }

    @Test fun browserSignIn() {
        var state by mutableStateOf<GoogleState>(GoogleState.SignedOut)
        var waiting by mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent {
            EdithTheme {
                GoogleContent(
                    state, { calls += "sign in" }, { calls += "grant" }, { calls += "disconnect" },
                    offerBrowser = true,
                    onUseBrowser = { calls += "browser"; waiting = true },
                )
                if (waiting) BrowserWaitingDialog(onPaste = { calls += "paste $it" }, onCancel = { calls += "cancel"; waiting = false })
            }
        }
        compose.onNodeWithText("Use a browser instead").performClick()
        compose.onNodeWithText("Finish signing in in your browser").assertExists()
        compose.onNodeWithText("Use this address").assertIsNotEnabled()
        compose.onNodeWithTag(GOOGLE_PASTE_TAG).performTextInput("http://127.0.0.1:5555/?state=s&code=c")
        compose.onNodeWithText("Use this address").assertIsEnabled().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Finish signing in in your browser").assertDoesNotExist()
        assertEquals(listOf("browser", "paste http://127.0.0.1:5555/?state=s&code=c", "cancel"), calls)

        state = GoogleState.NeedsConsent("owner@gmail.com", viaBrowser = true)
        compose.onNodeWithText("Google needs you to sign in again", substring = true).assertExists()
        compose.onNodeWithText("Use a browser instead").assertDoesNotExist()
        state = GoogleState.Connected("owner@gmail.com")
        compose.onNodeWithText("Use a browser instead").assertDoesNotExist()
        state = GoogleState.Error(null, "Signing in took too long")
        compose.onNodeWithText("Use a browser instead").assertExists()
    }

    /** Taps the dialog's Disconnect: the last node with exactly that text (the first is the section's button). */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.tapDialogDisconnect() {
        onAllNodes(hasText("Disconnect")).let { nodes -> nodes[nodes.fetchSemanticsNodes().size - 1].performClick() }
    }
}
