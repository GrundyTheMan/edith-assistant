package com.edith.app.alerts.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.edith.app.alerts.Alert
import com.edith.app.alerts.AlertKind
import com.edith.app.ui.theme.EdithTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** K4.16: the alert banner and the "While you were away" card, drawn from made-up alerts (desktop and iPhone simulator). */
@OptIn(ExperimentalTestApi::class)
class AlertViewsTest {
    private val ring = Alert("ring", "Front door", AlertKind.Doorbell, 1_800_000_000_000L, "k1")
    private val motion = Alert("ring", "Garage", AlertKind.Motion, 1_800_000_060_000L, "k2")

    @Test fun aBannerNamesTheDeviceAndWhatHappenedAndCanBeDismissed() = runComposeUiTest {
        val dismissed = mutableListOf<Alert>()
        var clips = 0
        setContent { EdithTheme { AlertBanners(listOf(ring), onOpenClips = { clips++ }, onDismiss = { dismissed += it }) } }
        onNodeWithText("Front door — Doorbell rings").assertIsDisplayed()
        onNodeWithText("Clips").performClick()
        onNodeWithText("Dismiss").performClick()
        assertEquals(1, clips)
        assertEquals(listOf(ring), dismissed)
    }

    @Test fun theCatchUpCardListsWhatHappenedAndCountsTheRest() = runComposeUiTest {
        val many = List(7) { i -> motion.copy(key = "m$i", at = motion.at + i) }
        var closed = 0
        setContent { EdithTheme { CatchUpCard(many, onOpenClips = {}, onDismiss = { closed++ }) } }
        onNodeWithText("While you were away").assertIsDisplayed()
        onNodeWithText("and 2 more").assertIsDisplayed()
        onNodeWithText("Dismiss").performClick()
        assertEquals(1, closed)
    }

    @Test fun nothingIsDrawnWithNothingToSay() = runComposeUiTest {
        setContent { EdithTheme { AlertBanners(emptyList(), {}, {}); CatchUpCard(emptyList(), {}, {}) } }
        onNodeWithTag(ALERT_TAG).assertDoesNotExist()
        onNodeWithTag(CATCH_UP_TAG).assertDoesNotExist()
    }
}
