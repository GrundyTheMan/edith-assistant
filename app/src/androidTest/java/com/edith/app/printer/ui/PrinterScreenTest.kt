package com.edith.app.printer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.printer.feed.Connections
import com.edith.app.printer.feed.Layer
import com.edith.app.printer.feed.PrintInfo
import com.edith.app.printer.feed.PrinterAlert
import com.edith.app.printer.feed.PrinterScreenState
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.feed.PrinterStates
import com.edith.app.printer.feed.StateAnswer
import com.edith.app.ui.theme.EdithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/** C39 E4: the six states of the Printer page (part 6.2) and the Status card, drawn without a ViewModel. */
@RunWith(AndroidJUnit4::class)
class PrinterScreenTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private fun ui(screen: PrinterScreenState, dismissed: Long? = null) = PrinterUi(screen, "FlashForge AD5X", emptySet(), dismissed, now, ZoneId.of("UTC"))
    private fun live(state: PrinterState) = PrinterScreenState.Live(StateAnswer(rev = 1, state = state), now, 0)
    private fun show(ui: PrinterUi, actions: PrinterActions = PrinterActions()) = compose.setContent { EdithTheme { PrinterContent(ui, is24Hour = true, a = actions) } }

    private val printing = PrinterState(
        connection = Connections.READY,
        print = PrintInfo(PrinterStates.PRINTING, title = "Chimney and Platforms (plate 1)", progress = 0.4, printDuration = 1800.0, totalDuration = 1900.0,
            timeLeft = 4440.0, timeLeftFrom = "slicer", filamentUsed = 12_400.0, layer = Layer(12, 240)),
    )

    @Test fun noJarvisAsksToPairAndTheButtonOpensSettings() {
        var opened = false
        show(ui(PrinterScreenState.NoJarvis), PrinterActions(openPairing = { opened = true }))
        compose.onNodeWithText("Pair Edith with Jarvis to use the printer away from home.").assertIsDisplayed()
        compose.onNodeWithText("Pair with Jarvis").performClick()
        assertEquals(true, opened)
    }

    @Test fun notSharedSaysWhereToSwitchItOn() {
        show(ui(PrinterScreenState.NotShared))
        compose.onNodeWithTag("printer-message").assertIsDisplayed()
        compose.onNodeWithText("Let paired phones see the printer", substring = true).assertIsDisplayed()
    }

    @Test fun connectingShowsTheProgress() {
        show(ui(PrinterScreenState.Connecting))
        compose.onNodeWithText("Connecting to Jarvis…").assertIsDisplayed()
    }

    @Test fun aPrintingPrinterShowsTheStatusCard() {
        show(ui(live(printing)))
        compose.onNodeWithText("FlashForge AD5X").assertIsDisplayed()
        compose.onNodeWithTag("connection-chip").assertIsDisplayed()
        compose.onNodeWithText("Chimney and Platforms (plate 1)").assertIsDisplayed()
        compose.onNodeWithText("Printing").assertIsDisplayed()
        compose.onNodeWithText("40 %").assertIsDisplayed()
        compose.onNodeWithText("about 1h 14m").assertIsDisplayed()
        compose.onNodeWithText("Estimated from the slicer").assertIsDisplayed()
        compose.onNodeWithText("12 / 240").assertIsDisplayed()
        compose.onNodeWithText("12.4 m").assertIsDisplayed()
    }

    @Test fun aPrinterJarvisCannotReachIsShownGreyedWithTheReason() {
        show(ui(live(PrinterState(connection = Connections.UNREACHABLE, lastSeen = now - 125_000))))
        compose.onNodeWithText("Jarvis can't reach the printer (is it on?). Showing what it last saw 2 min ago.").assertIsDisplayed()
    }

    @Test fun jarvisAwayKeepsTheLastPageAndOffersTryAgain() {
        var tried = false
        show(ui(PrinterScreenState.JarvisAway(StateAnswer(rev = 1, state = printing), "Edith can't reach Jarvis. Is the PC on, Jarvis running, and Tailscale connected (away from home)?")),
            PrinterActions(tryAgain = { tried = true }))
        compose.onNodeWithTag("banner").assertIsDisplayed()
        compose.onNodeWithText("Chimney and Platforms (plate 1)").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(true, tried)
    }

    @Test fun jarvisAwayWithNothingToShowIsAMessageWithTryAgain() {
        show(ui(PrinterScreenState.JarvisAway(null, "Jarvis removed this phone: pair it again.")))
        compose.onNodeWithText("Jarvis removed this phone: pair it again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test fun noPrinterSetUpIsItsOwnMessage() {
        show(ui(live(PrinterState(connection = Connections.NONE))))
        compose.onNodeWithText("No printer is set up in Jarvis.").assertIsDisplayed()
    }

    @Test fun theStatusCardCanBeFoldedAndDismissingTheErrorIsAsked() {
        val dismissed = mutableListOf<Long>()
        val toggled = mutableListOf<String>()
        val state = printing.copy(alert = PrinterAlert("Filament runout detected", time = 77))
        show(ui(live(state)), PrinterActions(dismissAlert = { dismissed += it }, toggleCard = { toggled += it }))
        compose.onNodeWithText("The printer said: Filament runout detected").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithTag("toggle-status").performClick()
        assertEquals(listOf(77L), dismissed)
        assertEquals(listOf("status"), toggled)
    }
}
