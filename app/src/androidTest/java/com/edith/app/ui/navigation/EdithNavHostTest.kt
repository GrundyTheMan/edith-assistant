package com.edith.app.ui.navigation

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.ui.theme.EdithTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EdithNavHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bottomBarSwitchesBetweenPlaceholders() {
        compose.setContent { EdithTheme { EdithNavHost() } }

        compose.onNodeWithText("Chat").assertExists()
        compose.onNodeWithText("Chat — coming in C07").assertExists()

        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithText("Calendar — coming in C18").assertExists()

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Settings — coming in C04").assertExists()

        compose.onNodeWithText("Chat").performClick()
        compose.onNodeWithText("Chat — coming in C07").assertExists()
    }
}
