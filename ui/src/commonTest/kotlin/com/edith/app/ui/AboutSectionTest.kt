package com.edith.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.edith.app.ui.about.AboutSection
import com.edith.app.ui.theme.EdithTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** K3.0: the first UI tests of a shared screen part. They run on the desktop and on the iPhone simulator (plan §14 U8). */
@OptIn(ExperimentalTestApi::class)
class AboutSectionTest {
    @Test fun showsTheVersionsAndSaysThereAreNoCrashReports() = runComposeUiTest {
        setContent { EdithTheme { Column { AboutSection("2.0.0-dev", "0.40.13", hasCrashReport = false, onViewCrashReport = {}) } } }
        onNodeWithText("About").assertIsDisplayed()
        onNodeWithText("Edith 2.0.0-dev").assertIsDisplayed()
        onNodeWithText("Ollama 0.40.13").assertIsDisplayed()
        onNodeWithText("No crash reports").assertIsDisplayed()
    }

    @Test fun saysWhenOllamaCannotBeReached() = runComposeUiTest {
        setContent { EdithTheme { Column { AboutSection("2.0.0-dev", null, hasCrashReport = false, onViewCrashReport = {}) } } }
        onNodeWithText("Ollama — not reachable").assertIsDisplayed()
    }

    @Test fun aCrashReportOffersAButtonThatOpensIt() = runComposeUiTest {
        var opened = 0
        setContent { EdithTheme { Column { AboutSection("2.0.0-dev", "0.40.13", hasCrashReport = true, onViewCrashReport = { opened++ }) } } }
        onNodeWithText("View last crash report").performClick()
        assertEquals(1, opened)
    }
}
