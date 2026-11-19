package com.edith.app.printer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.printer.PrinterCards
import com.edith.app.printer.feed.ChartLine
import com.edith.app.printer.feed.Connections
import com.edith.app.printer.feed.PrinterCommand
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
        compose.onNodeWithTag("job-why").assertIsDisplayed()
        compose.onAllNodesWithText("Turn on Controls to use this")[0].assertExists()
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

    // --- Stage 2 (E8): the controls in the cards -------------------------------------------------------------------------

    private val rich = printing.copy(
        heaters = listOf(Heater("extruder", "Nozzle", 219.8, 220.0, 0.3, 300.0), Heater("heater_bed", "Bed", 60.0, 60.0, 0.2, 120.0)),
        fans = listOf(com.edith.app.printer.feed.Fan("fan_generic fanM106", "Part cooling", 0.5, true)),
        light = 0.5,
        filamentSensors = listOf(com.edith.app.printer.feed.FilamentSensor("filament_switch_sensor head", "Head switch sensor", true, true)),
        objects = listOf(com.edith.app.printer.feed.PrintObject("Chimney", excluded = false, current = true)),
        queue = com.edith.app.printer.feed.JobQueue("ready", listOf(com.edith.app.printer.feed.QueueJob("0001", "a.gcode", "Benchy", 1))),
        retraction = com.edith.app.printer.feed.Retraction(0.8, 35.0, 0.0, 35.0),
        cameras = listOf(PrinterCamera("video", "Camera")),
    )
    private fun richUi(state: PrinterState = rich) = PrinterUi(
        PrinterScreenState.Live(StateAnswer(rev = 1, state = state), now, 0), "FlashForge AD5X", emptySet(), null, now, ZoneId.of("UTC"),
        listOf(com.edith.app.printer.feed.Preset("PLA", 220, 60)),
    )
    private fun controlsContent(c: ControlUi, sent: MutableList<com.edith.app.printer.feed.PrinterCommand>, state: PrinterState = rich, cards: CardsUi = CardsUi()) =
        compose.setContent { EdithTheme { PrinterContent(richUi(state), true, PrinterActions(send = { sent += it }), cards, c) } }
    private fun click(tag: String, card: String) { scrollTo(card); compose.onNodeWithTag(tag).performScrollTo().performClick() }

    @Test fun theTemperatureControlsSendTargetsPresetsAndAllOff() {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent)
        scrollTo("temperature-controls")
        compose.onNodeWithTag("target-extruder").performScrollTo().performTextInput("220")
        compose.onNodeWithTag("set-extruder").performScrollTo().performClick()
        compose.onNodeWithTag("off-heater_bed").performScrollTo().performClick()
        compose.onNodeWithTag("cmd:PLA").performScrollTo().performClick()
        compose.onNodeWithTag("cmd:All off").performScrollTo().performClick()
        assertEquals(
            listOf(com.edith.app.printer.feed.PrinterCommand.SetTemp("extruder", 220.0), com.edith.app.printer.feed.PrinterCommand.SetTemp("heater_bed", 0.0),
                com.edith.app.printer.feed.PrinterCommand.Preset("PLA"), com.edith.app.printer.feed.PrinterCommand.HeatersOff), sent,
        )
    }

    @Test fun aTargetBoxThatIsEmptyOrTooHotSendsNothing() {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent)
        scrollTo("temperature-controls")
        compose.onNodeWithTag("set-heater_bed").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("target-heater_bed").performScrollTo().performTextInput("130")
        compose.onNodeWithTag("set-heater_bed").performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList<com.edith.app.printer.feed.PrinterCommand>(), sent)
    }

    @Test fun theToolheadControlsSendHomeJogZOffsetAndExtrude() {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent)
        scrollTo("toolhead-controls")
        compose.onNodeWithTag("cmd:All").performScrollTo().performClick()
        compose.onNodeWithTag("step-10").performScrollTo().performClick()
        compose.onNodeWithTag("cmd:X+").performScrollTo().performClick()
        compose.onNodeWithTag("cmd:Y−").performScrollTo().performClick()
        compose.onNodeWithTag("step-100").performScrollTo().performClick()
        compose.onNodeWithTag("cmd:Z+").performScrollTo().performClick()
        compose.onNodeWithTag("cmd:+0.05").performScrollTo().performClick()
        compose.onNodeWithTag("cmd:Extrude").performScrollTo().performClick()
        compose.onNodeWithTag("cmd:Retract").performScrollTo().performClick()
        compose.onNodeWithTag("cmd:Motors off").performScrollTo().performClick()
        assertEquals(
            listOf(
                PrinterCommand.Home(com.edith.app.printer.feed.HomeAxes.ALL), PrinterCommand.Jog("x", 10.0), PrinterCommand.Jog("y", -10.0), PrinterCommand.Jog("z", 50.0), PrinterCommand.ZOffset(0.05),
                PrinterCommand.Extrude(10.0, 5.0), PrinterCommand.Extrude(-10.0, 5.0), PrinterCommand.MotorsOff,
            ), sent,
        )
    }

    @Test fun theFanLightAndAirControlsSend() {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent)
        scrollTo("fan-controls")
        compose.onNodeWithTag("cmd:On").performScrollTo().performClick()
        compose.onNodeWithTag("cmd:Internal").performScrollTo().performClick()
        compose.onNodeWithTag("cmd:Stop").performScrollTo().performClick()
        assertEquals(listOf(PrinterCommand.Light(1.0), PrinterCommand.AirCirculation("internal"), PrinterCommand.AirCirculation("stop")), sent)
    }

    @Test fun theLimitsAreAppliedOnlyWhenSomethingIsTyped() {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent)
        scrollTo("limit-controls")
        compose.onNodeWithTag("cmd:Apply limits").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("limit-velocity").performScrollTo().performTextInput("200")
        compose.onNodeWithTag("cmd:Apply limits").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf<com.edith.app.printer.feed.PrinterCommand>(com.edith.app.printer.feed.PrinterCommand.Limits(velocity = 200.0)), sent)
    }

    @Test fun theSensorObjectQueueAndSystemControlsSend() {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent)
        scrollTo("sensor-controls"); compose.onNodeWithTag("filament-filament_switch_sensor head").performScrollTo().performClick()
        scrollTo("object-controls"); compose.onNodeWithTag("cmd:Exclude Chimney").performScrollTo().performClick()
        scrollTo("queue-controls"); compose.onNodeWithTag("cmd:Start queue").performScrollTo().performClick()
        compose.onNodeWithTag("cmd:Remove Benchy").performScrollTo().performClick()
        scrollTo("system-controls"); compose.onNodeWithTag("cmd:Restart Klipper").performScrollTo().performClick()
        assertEquals(listOf(PrinterCommand.FilamentSensor("filament_switch_sensor head", false), PrinterCommand.ExcludeObject("Chimney"), PrinterCommand.QueueStart, PrinterCommand.QueueRemove("0001"), PrinterCommand.Restart), sent)
    }

    @Test fun withControlsOffEveryCardControlIsDisabledAndSaysWhy() {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = false), sent)
        scrollTo("toolhead-controls")
        compose.onNodeWithTag("cmd:All").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("cmd:X+").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("cmd:Extrude").performScrollTo().assertIsNotEnabled()
        scrollTo("temperature-controls")
        compose.onNodeWithTag("cmd:All off").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("cmd:PLA").performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList<com.edith.app.printer.feed.PrinterCommand>(), sent)
    }

    @Test fun aLookOnlyPhoneGetsNoControlsInAnyCard() {
        compose.setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), CardsUi(), control(may = false)) } }
        val cardOf = mapOf(
            "toolhead-controls" to "toolhead-card", "temperature-controls" to "temperature-card", "fan-controls" to "fans-card", "limit-controls" to "limits-card",
            "sensor-controls" to "sensors-card", "object-controls" to "objects-card", "queue-controls" to "queue-card", "system-controls" to "system-card",
        )
        for ((controls, card) in cardOf) {
            scrollTo(card)
            compose.onNodeWithTag(controls).assertDoesNotExist()
        }
    }

    @Test fun aRefusalFromJarvisIsShownUnderTheJogControls() {
        val c = control(armed = true).copy(refused = mapOf("jog" to "Home the printer first."))
        compose.setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), CardsUi(), c) } }
        scrollTo("toolhead-controls")
        compose.onNodeWithText("Home the printer first.").performScrollTo().assertIsDisplayed()
    }

    @Test fun anOfflineCameraOffersToTurnItOn() {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent, cards = CardsUi(camera = CameraUi("video", null, 0, offline = true)))
        compose.onNodeWithTag("cmd:Turn camera on").performScrollTo().performClick()
        assertEquals(listOf<com.edith.app.printer.feed.PrinterCommand>(com.edith.app.printer.feed.PrinterCommand.Camera(true)), sent)
    }

    @Test fun whenKlipperHasStoppedTheBannerOffersAFirmwareRestart() {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        val stopped = PrinterState(connection = Connections.KLIPPY_SHUTDOWN, message = "Heater not heating")
        controlsContent(control(armed = true), sent, state = stopped)
        compose.onNodeWithTag("cmd:Firmware restart").assertIsDisplayed().performClick()
        assertEquals(listOf<com.edith.app.printer.feed.PrinterCommand>(com.edith.app.printer.feed.PrinterCommand.FirmwareRestart), sent)
    }

    @Test fun noEmergencyStopWhileJarvisCannotReachThePrinter() {
        compose.setContent { EdithTheme { PrinterContent(open(PrinterState(connection = Connections.UNREACHABLE)), true, PrinterActions(), CardsUi(), control()) } }
        compose.onNodeWithTag("estop").assertDoesNotExist()
    }
}
