package com.edith.app.ui.navigation

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.ui.theme.EdithTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EdithNavHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun grantLocalNetwork() {
        // The real Settings screen (C04) asks for this on entry; grant it so no system dialog appears mid-test.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission("com.edith.app", LocalNetworkAccess.PERMISSION)
    }

    @Test
    fun bottomBarSwitchesBetweenScreens() {
        compose.setContent { EdithTheme { EdithNavHost() } }

        compose.onNodeWithText("Chat").assertExists()
        compose.onNodeWithText("Message Edith").assertExists()   // the chat screen (C07); not its contents, which are the owner's

        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithText("Calendar — coming in C18").assertExists()

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Server & model").assertExists()

        compose.onNodeWithText("Chat").performClick()
        compose.onNodeWithText("Message Edith").assertExists()   // the chat screen (C07); not its contents, which are the owner's
    }
}
