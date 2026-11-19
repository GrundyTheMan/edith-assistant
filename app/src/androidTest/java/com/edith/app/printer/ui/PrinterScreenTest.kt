package com.edith.app.printer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.printer.PrinterCards
import com.edith.app.printer.feed.ChartLine
import com.edith.app.printer.feed.Connections
import com.edith.app.printer.feed.FilamentSlot
import com.edith.app.printer.feed.Heater
import com.edith.app.printer.feed.PrinterCamera
import com.edith.app.printer.feed.Sensor
import com.edith.app.printer.feed.Layer
import com.edith.app.printer.feed.PrintInfo
import com.edith.app.printer.feed.PrinterAlert
import com.edith.app.printer.feed.PrinterScreenState
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.feed.PrinterStates
import com.edith.app.printer.feed.StateAnswer
import com.edith.app.ui.theme.EdithTheme
import org.junit.Assert.assertEquals
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    // --- The read-only cards (E5): every card open ---------------------------------------------------------------------

    private val everything = printing.copy(
        heaters = listOf(Heater("extruder", "Nozzle", 219.8, 220.0, 0.3, 300.0), Heater("heater_bed", "Bed", 60.0, 60.0, 0.2, 120.0)),
        sensors = listOf(Sensor("s1", "Head", 25.5, "°C")),
        slots = listOf(FilamentSlot("1", "PLA", "red", "F72224", true)), slotsLive = false,
        cameras = listOf(PrinterCamera("video", "Camera")),
    )
    /** The cards are in a lazy list: one far down is not drawn until it is scrolled to. */
    private fun scrollTo(tag: String) = compose.onNodeWithTag("printer-list").performScrollToNode(androidx.compose.ui.test.hasTestTag(tag))

    private fun open(state: PrinterState) = PrinterUi(live(state), "FlashForge AD5X", emptySet(), null, now, ZoneId.of("UTC"))

    @Test fun theTemperatureCardShowsEachHeaterAndTheChart() {
        val lines = listOf(ChartLine("extruder", "Nozzle", listOf(20.0, 100.0, 219.8), listOf(220.0, 220.0, 220.0)))
        compose.setContent { EdithTheme { PrinterContent(open(everything), true, PrinterActions(), CardsUi(chart = lines)) } }
        scrollTo("temperature-card")
        compose.onNodeWithTag("temperature-card").assertExists()
        compose.onNodeWithText("219.8 °C").assertExists()
        compose.onNodeWithText("→ 220 °C").assertExists()
        compose.onNodeWithTag("temp-chart").assertExists()
    }

    @Test fun theFilesCardAsksBeforeListingAnything() {
        var loaded = 0
        compose.setContent { EdithTheme { PrinterContent(open(everything.copy(print = PrintInfo())), true, PrinterActions(loadFiles = { loaded++ }), CardsUi()) } }
        scrollTo("files-card")
        compose.onNodeWithText("Listing the files keeps the printer busy for several seconds, and its buttons wait.").assertExists()
        assertEquals(0, loaded)
        compose.onNodeWithTag("load-files").performClick()
        assertEquals(1, loaded)
    }

    @Test fun theFilesCardCannotListWhilePrinting() {
        compose.setContent { EdithTheme { PrinterContent(open(everything), true, PrinterActions(), CardsUi()) } }
        scrollTo("files-card")
        compose.onNodeWithText("Not while printing: the printer would stop answering for several seconds.").assertExists()
    }

    @Test fun theSlotsCardDoesNotCallAnythingEmptyWhenThePrinterIsNotReporting() {
        compose.setContent { EdithTheme { PrinterContent(open(everything), true, PrinterActions(), CardsUi()) } }
        scrollTo("slots-card")
        compose.onNodeWithText("The printer isn't reporting which slots have filament right now. Its screen shows them.").assertExists()
        compose.onNodeWithText("Empty").assertDoesNotExist()
    }

    @Test fun theCameraCardSaysConnectingUntilThereIsAPictureAndOfflineAfterTwoFailures() {
        var cards by androidx.compose.runtime.mutableStateOf(CardsUi())
        compose.setContent { EdithTheme { PrinterContent(open(everything), true, PrinterActions(), cards) } }
        compose.onNodeWithTag("camera-connecting").assertExists()
        cards = CardsUi(camera = CameraUi("video", null, 0, offline = true))
        compose.onNodeWithTag("camera-offline").assertExists()
    }

    @Test fun openingACardTellsTheDataLayer() {
        val told = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { EdithTheme { PrinterContent(open(everything), true, PrinterActions(cardVisible = { id, v -> told += id to v }), CardsUi()) } }
        compose.waitForIdle()
        assertEquals(true, told.contains(PrinterCards.TEMPERATURES to true))
        assertEquals(true, told.contains(PrinterCards.CAMERA to true))
    }

    // --- Stage 2 (E7): Controls, the confirm sheet, the job buttons, the emergency stop ----------------------------

    private fun control(armed: Boolean = false, may: Boolean = true) = ControlUi(mayControl = may, armed = armed, armUntilMs = if (armed) System.currentTimeMillis() + 103_000 else null)
    private val quickOk = mapOf("pause" to com.edith.app.printer.feed.QuickCheck(true), "cancel" to com.edith.app.printer.feed.QuickCheck(true))
    private fun withQuick(state: PrinterState) = PrinterUi(PrinterScreenState.Live(StateAnswer(rev = 1, state = state, quick = quickOk), now, 0), "FlashForge AD5X", emptySet(), null, now, ZoneId.of("UTC"))

    @Test fun theConfirmSheetShowsJarvisWordsAndTwoDifferentButtons() {
        var yes = 0; var no = 0
        val sheet = ConfirmUi("Cancel the print of Benchy? It can’t be resumed.", "Cancel print", danger = true, com.edith.app.printer.feed.PrinterCommand.Cancel, "id")
        compose.setContent { EdithTheme { ConfirmSheet(sheet, onConfirm = { yes++ }, onDismiss = { no++ }) } }
        compose.onNodeWithTag("confirm-question").assertIsDisplayed()
        compose.onNodeWithText("Cancel the print of Benchy? It can’t be resumed.").assertIsDisplayed()
        compose.onNodeWithTag("confirm-yes").assertIsDisplayed()
        compose.onNodeWithText("Keep printing").assertIsDisplayed()
        compose.onNodeWithTag("confirm-no").performClick()
        assertEquals(0, yes); assertEquals(1, no)
        compose.onNodeWithTag("confirm-yes").performClick()
        assertEquals(1, yes)
    }

    @Test fun aLookOnlyPhoneSaysSoAndHasNoControlsSwitch() {
        compose.setContent { EdithTheme { PrinterContent(withQuick(printing), true, PrinterActions(), CardsUi(), control(may = false)) } }
        compose.onNodeWithTag("controls-look-only").assertExists()
        compose.onNodeWithTag("controls-switch").assertDoesNotExist()
        compose.onNodeWithTag("job-buttons").assertDoesNotExist()
    }

    @Test fun theControlsSwitchAsksForControlsAndShowsTheCountdown() {
        val asked = mutableListOf<Boolean>()
        var c by androidx.compose.runtime.mutableStateOf(control(armed = false))
        compose.setContent { EdithTheme { PrinterContent(withQuick(printing), true, PrinterActions(setControls = { asked += it }), CardsUi(), c) } }
        compose.onNodeWithTag("controls-switch").performClick()
        assertEquals(listOf(true), asked)
        c = control(armed = true)
        compose.onNodeWithTag("controls-text").assertExists()
        compose.onNodeWithText("Controls on", substring = true).assertIsDisplayed()
    }

    @Test fun withControlsOffTheJobButtonsAreDisabledAndSayWhy() {
        compose.setContent { EdithTheme { PrinterContent(withQuick(printing), true, PrinterActions(), CardsUi(), control(armed = false)) } }
        compose.onNodeWithTag("job-main").assertIsNotEnabled()
        compose.onNodeWithTag("job-cancel").assertIsNotEnabled()
        compose.onNodeWithText("Turn on Controls to use this").assertIsDisplayed()
    }

    @Test fun withControlsOnPauseAndCancelSendTheirCommands() {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        compose.setContent { EdithTheme { PrinterContent(withQuick(printing), true, PrinterActions(send = { sent += it }), CardsUi(), control(armed = true)) } }
        compose.onNodeWithTag("job-main").assertIsEnabled().performClick()
        compose.onNodeWithTag("job-cancel").assertIsEnabled().performClick()
        assertEquals(listOf(com.edith.app.printer.feed.PrinterCommand.Pause, com.edith.app.printer.feed.PrinterCommand.Cancel), sent)
    }

    @Test fun aPausedPrintOffersResumeAndAPendingButtonIsDisabledWithAProgressIndicator() {
        val pausedState = printing.copy(print = PrintInfo(PrinterStates.PAUSED, paused = true))
        val c = control(armed = true).copy(pending = setOf("resume"))
        compose.setContent { EdithTheme { PrinterContent(withQuick(pausedState), true, PrinterActions(), CardsUi(), c) } }
        compose.onNodeWithText("Resume").assertExists()
        compose.onNodeWithTag("job-main").assertIsNotEnabled()
    }

    @Test fun theServersReasonShowsWhenItWillNotTakeAPause() {
        val ui = PrinterUi(PrinterScreenState.Live(StateAnswer(rev = 1, state = printing, quick = mapOf("pause" to com.edith.app.printer.feed.QuickCheck(false, "The printer is busy."), "cancel" to com.edith.app.printer.feed.QuickCheck(true))), now, 0), "FlashForge AD5X", emptySet(), null, now, ZoneId.of("UTC"))
        compose.setContent { EdithTheme { PrinterContent(ui, true, PrinterActions(), CardsUi(), control(armed = true)) } }
        compose.onNodeWithTag("job-main").assertIsNotEnabled()
        compose.onNodeWithText("The printer is busy.").assertIsDisplayed()
    }

    @Test fun theEmergencyStopIsThereWithControlsOffAndAsksThroughSend() {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        compose.setContent { EdithTheme { PrinterContent(withQuick(printing), true, PrinterActions(send = { sent += it }), CardsUi(), control(armed = false)) } }
        compose.onNodeWithTag("estop").assertIsDisplayed().performClick()
        assertEquals(listOf<com.edith.app.printer.feed.PrinterCommand>(com.edith.app.printer.feed.PrinterCommand.EStop), sent)
    }

    @Test fun theEmergencyStopIsThereOnALookOnlyPhoneButNotWhenThePrinterIsUnreachable() {
        compose.setContent { EdithTheme { PrinterContent(withQuick(printing), true, PrinterActions(), CardsUi(), control(may = false)) } }
        compose.onNodeWithTag("estop").assertExists()
    }

    @Test fun noEmergencyStopWhileJarvisCannotReachThePrinter() {
        compose.setContent { EdithTheme { PrinterContent(open(PrinterState(connection = Connections.UNREACHABLE)), true, PrinterActions(), CardsUi(), control()) } }
        compose.onNodeWithTag("estop").assertDoesNotExist()
    }
}
