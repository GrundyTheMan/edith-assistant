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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.Accounts
import com.edith.app.accounts.Provider
import com.edith.app.ui.theme.EdithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C22 §5 (was C17 §3, C17b §3): Settings → Accounts lists every account with its state, Add account offers Google
 * (and the browser), an account opens Rename / Grant access / Disconnect (confirmed), and the browser sign-in dialog.
 */
@RunWith(AndroidJUnit4::class)
class AccountsSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private val personal = Account("a1", Provider.Google, "a@gmail.com", "personal", Account.SIGN_IN_PLAY)
    private val work = Account("b2", Provider.Google, "sam@contoso.com", "sam@contoso.com", Account.SIGN_IN_BROWSER)

    @Test fun listAddRenameGrantAndDisconnect() {
        var accounts by mutableStateOf<List<Pair<Account, AccountState>>>(emptyList())
        val calls = mutableListOf<String>()
        compose.setContent {
            EdithTheme {
                AccountsContent(
                    accounts = accounts, message = null, googleNotSetUp = null, offerBrowser = true,
                    onAddGoogle = { calls += "add browser=$it" },
                    onGrant = { calls += "grant $it" },
                    onRename = { id, label, done -> calls += "rename $id $label"; done(if (label == "taken") Accounts.RenameResult.Taken else Accounts.RenameResult.Done) },
                    onDisconnect = { calls += "disconnect $it" },
                )
            }
        }
        compose.onNodeWithTag(ACCOUNTS_EMPTY_TAG).assertExists()

        // Add account → the sheet offers Google, and Google in the browser.
        compose.onNodeWithText("Add account").performClick()
        compose.onNodeWithTag(ADD_GOOGLE_TAG).performClick()
        compose.onNodeWithText("Add account").performClick()
        compose.onNodeWithText("Google, using a browser instead").performClick()

        accounts = listOf(personal to AccountState.Connected, work to AccountState.NeedsSignIn("needs signing in again (about once a week…)", viaBrowser = true))
        compose.onNodeWithText("personal").assertExists()
        compose.onNodeWithText("a@gmail.com").assertExists()
        compose.onNodeWithText("Connected").assertExists()
        compose.onNodeWithText("Needs signing in again (about once a week…)").assertExists()

        // Rename: a taken name is refused, then a good one is saved.
        compose.onNodeWithTag(ACCOUNT_ROW_TAG + "a1").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag(ACCOUNT_LABEL_TAG).performTextClearance()
        compose.onNodeWithTag(ACCOUNT_LABEL_TAG).performTextInput("taken")
        compose.onNodeWithText("Save name").performClick()
        compose.onNodeWithText("Another account already has that name.").assertExists()
        compose.onNodeWithTag(ACCOUNT_LABEL_TAG).performTextClearance()
        compose.onNodeWithTag(ACCOUNT_LABEL_TAG).performTextInput("home")
        compose.onNodeWithText("Save name").performClick()
        compose.onNodeWithText("Rename").assertExists()
        compose.onNodeWithText("Close").performClick()

        // An account that needs signing in again: Sign in again; Disconnect asks first.
        compose.onNodeWithTag(ACCOUNT_ROW_TAG + "b2").performClick()
        compose.onNodeWithTag(ACCOUNT_STATUS_TAG).assert(hasText("Needs signing in again", substring = true))
        compose.onNodeWithText("Sign in again").performClick()
        compose.onNodeWithTag(ACCOUNT_ROW_TAG + "b2").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("Disconnect sam@contoso.com?").assertExists()
        compose.onNodeWithText("Edith won't see its email or calendar.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("Disconnect sam@contoso.com?").assertExists()
        compose.tapDialogDisconnect()

        assertEquals(
            listOf("add browser=false", "add browser=true", "rename a1 taken", "rename a1 home", "grant b2", "disconnect b2"),
            calls,
        )
    }

    @Test fun googleNotSetUpCantBeAdded() {
        var added = 0
        compose.setContent {
            EdithTheme {
                AccountsContent(emptyList(), message = "Google didn't sign you in.", googleNotSetUp = "Google isn't set up in this build", offerBrowser = true,
                    onAddGoogle = { added++ }, onGrant = {}, onRename = { _, _, _ -> }, onDisconnect = {})
            }
        }
        compose.onNodeWithTag(ACCOUNTS_MESSAGE_TAG).assert(hasText("Google didn't sign you in."))
        compose.onNodeWithText("Add account").performClick()
        compose.onNodeWithText("Google isn't set up in this build").assertExists()
        compose.onNodeWithTag(ADD_GOOGLE_TAG).performClick()
        compose.onNodeWithText("Google, using a browser instead").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, added) }
    }

    @Test fun browserSignInDialog() {
        var waiting by mutableStateOf(true)
        val calls = mutableListOf<String>()
        compose.setContent {
            EdithTheme {
                if (waiting) BrowserWaitingDialog(Provider.Google, onPaste = { calls += "paste $it" }, onCancel = { calls += "cancel"; waiting = false })
            }
        }
        compose.onNodeWithText("Finish signing in to Google in your browser").assertExists()
        compose.onNodeWithText("Use this address").assertIsNotEnabled()
        compose.onNodeWithTag(BROWSER_PASTE_TAG).performTextInput("http://127.0.0.1:5555/?state=s&code=c")
        compose.onNodeWithText("Use this address").assertIsEnabled().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Finish signing in to Google in your browser").assertDoesNotExist()
        assertEquals(listOf("paste http://127.0.0.1:5555/?state=s&code=c", "cancel"), calls)
    }

    /** Taps the confirm dialog's Disconnect: the last node with exactly that text (the first is the account dialog's). */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.tapDialogDisconnect() {
        onAllNodes(hasText("Disconnect")).let { nodes -> nodes[nodes.fetchSemanticsNodes().size - 1].performClick() }
    }
}
