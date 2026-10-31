package com.edith.app.ui.components

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.ui.theme.EdithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrashBannerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun viewShowsTheReportAndDismissIsReported() {
        var dismissed = 0
        compose.setContent {
            EdithTheme { CrashBanner(report = "java.lang.IllegalStateException: boom", onDismiss = { dismissed++ }) }
        }

        compose.onNodeWithText("Edith closed unexpectedly last time.").assertExists()
        compose.onNodeWithText("View").performClick()
        compose.onNodeWithText("java.lang.IllegalStateException: boom").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("java.lang.IllegalStateException: boom").assertDoesNotExist()

        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissed)
    }
}
