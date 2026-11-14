package com.edith.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInField
import com.edith.app.home.SignInForm
import com.edith.app.home.SignInResult
import com.edith.app.ui.theme.EdithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C26 §1, C25 §7: Settings → Home service rows and the shared sign-in form (the code box only when the service asks). */
@RunWith(AndroidJUnit4::class)
class HomeSettingsTest {
    @get:Rule val compose = createComposeRule()

    private val form = SignInForm(
        "Meross", "Sign in with your Meross account.",
        listOf(SignInField("email", "Meross email", email = true), SignInField("password", "Meross password", secret = true), SignInField("code", "Code from Meross", onlyWhenAsked = true)),
    )

    private fun row(state: ServiceState) = HomeServiceRow("meross", "Meross", state, form, removeHint = "Also see the Meross app.")

    @Test fun signInAsksForTheCodeOnlyWhenTheServiceDoes() {
        val tried = mutableListOf<Map<String, String>>()
        var answers = ArrayDeque(listOf<SignInResult>(SignInResult.Failed("Meross says the email or password isn't right."), SignInResult.NeedsCode("Meross sent a code."), SignInResult.Done))
        compose.setContent {
            EdithTheme { HomeSettingsContent(HomeSettingsUi(services = listOf(row(ServiceState.NotConnected))), {}, { _, _ -> }, { _, values, done -> tried += values; done(answers.removeFirst()) }) }
        }
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithTag(SIGN_IN_TAG + "meross").performClick()
        compose.onNodeWithTag(SUBMIT_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(FIELD_TAG + "code").assertDoesNotExist()
        compose.onNodeWithTag(FIELD_TAG + "email").performTextInput("owner@example.com")
        compose.onNodeWithTag(FIELD_TAG + "password").performTextInput("hunter2")
        compose.onNodeWithTag(SUBMIT_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(MESSAGE_TAG).assertIsDisplayed()          // the refusal, in words
        compose.onNodeWithTag(SUBMIT_TAG).performClick()
        compose.onNodeWithTag(FIELD_TAG + "code").assertIsDisplayed()    // now it asks
        compose.onNodeWithTag(FIELD_TAG + "code").performTextInput("123456")
        compose.onNodeWithTag(SUBMIT_TAG).performClick()
        compose.onNodeWithTag(FIELD_TAG + "email").assertDoesNotExist()  // done: the dialog is gone
        assertEquals(listOf(setOf("email", "password"), setOf("email", "password"), setOf("email", "password", "code")), tried.map { it.keys })
        assertEquals("123456", tried.last()["code"])
    }

    @Test fun connectedAndNeedsSignInRows() {
        var state by mutableStateOf(ServiceState.Connected)
        val disconnected = mutableListOf<String>()
        compose.setContent {
            EdithTheme { HomeSettingsContent(HomeSettingsUi(services = listOf(row(state))), {}, { _, _ -> }, { _, _, _ -> }, { disconnected += it }) }
        }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithTag(DISCONNECT_TAG + "meross").performClick()
        compose.onNodeWithText("Also see the Meross app.", substring = true).assertIsDisplayed()   // where else to remove it
        compose.onNodeWithTag(DISCONNECT_CONFIRM_TAG).performClick()
        assertEquals(listOf("meross"), disconnected)
        state = ServiceState.NeedsSignIn
        compose.onNodeWithText("Needs signing in again").assertIsDisplayed()
        compose.onNodeWithText("Sign in again").assertIsDisplayed()
    }
}
