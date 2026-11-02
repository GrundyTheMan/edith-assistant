package com.edith.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.google.GoogleState
import com.edith.app.ui.theme.EdithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C17 §3: Settings → Google account in each state, and Disconnect asks first. */
@RunWith(AndroidJUnit4::class)
class GoogleSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun eachStateShowsItsButton() {
        var state by mutableStateOf<GoogleState>(GoogleState.NotSetUp)
        val calls = mutableListOf<String>()
        compose.setContent { EdithTheme { GoogleContent(state, { calls += "sign in" }, { calls += "grant" }, { calls += "disconnect" }) } }

        compose.onNodeWithTag(GOOGLE_STATUS_TAG).assert(hasText("Web client ID is missing", substring = true))
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

    /** Taps the dialog's Disconnect: the last node with exactly that text (the first is the section's button). */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.tapDialogDisconnect() {
        onAllNodes(hasText("Disconnect")).let { nodes -> nodes[nodes.fetchSemanticsNodes().size - 1].performClick() }
    }
}
