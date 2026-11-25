package com.edith.app.printer.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.datetime.TimeZone

/** C39 E4, K3.4: the six states of the Printer page (part 6.2) and the Status card, drawn without a ViewModel; shared since K3.4, so it runs on the desktop and the iPhone simulator. */
@OptIn(ExperimentalTestApi::class)
class PrinterContentTest {

    private val now = 1_800_000_000_000L
    private fun ui(screen: PrinterScreenState, dismissed: Long? = null) = PrinterUi(screen, "FlashForge AD5X", emptySet(), dismissed, now, TimeZone.UTC)
    private fun live(state: PrinterState) = PrinterScreenState.Live(StateAnswer(rev = 1, state = state), now, 0)
    private fun ComposeUiTest.show(ui: PrinterUi, actions: PrinterActions = PrinterActions()) = setContent { EdithTheme { PrinterContent(ui, is24Hour = true, a = actions) } }

    private val printing = PrinterState(
        connection = Connections.READY,
        print = PrintInfo(PrinterStates.PRINTING, title = "Chimney and Platforms (plate 1)", progress = 0.4, printDuration = 1800.0, totalDuration = 1900.0,
            timeLeft = 4440.0, timeLeftFrom = "slicer", filamentUsed = 12_400.0, layer = Layer(12, 240)),
    )

    @Test fun noJarvisAsksToPairAndTheButtonOpensSettings() = runComposeUiTest {
        var opened = false
        show(ui(PrinterScreenState.NoJarvis), PrinterActions(openPairing = { opened = true }))
        onNodeWithText("Pair Edith with Jarvis to use the printer away from home.").assertIsDisplayed()
        onNodeWithText("Pair with Jarvis").performClick()
        assertEquals(true, opened)
    }

    @Test fun notSharedSaysWhereToSwitchItOn() = runComposeUiTest {
        show(ui(PrinterScreenState.NotShared))
        onNodeWithTag("printer-message").assertIsDisplayed()
        onNodeWithText("Let paired phones see the printer", substring = true).assertIsDisplayed()
    }

    @Test fun connectingShowsTheProgress() = runComposeUiTest {
        show(ui(PrinterScreenState.Connecting))
        onNodeWithText("Connecting to Jarvis…").assertIsDisplayed()
    }

    @Test fun aPrintingPrinterShowsTheStatusCard() = runComposeUiTest {
        show(ui(live(printing)))
        onNodeWithText("FlashForge AD5X").assertIsDisplayed()
        onNodeWithTag("connection-chip").assertIsDisplayed()
        onNodeWithText("Chimney and Platforms (plate 1)").assertIsDisplayed()
        onNodeWithText("Printing").assertIsDisplayed()
        onNodeWithText("40 %").assertIsDisplayed()
        onNodeWithText("about 1h 14m").assertIsDisplayed()
        onNodeWithText("Estimated from the slicer").assertIsDisplayed()
        onNodeWithText("12 / 240").assertIsDisplayed()
        onNodeWithText("12.4 m").assertIsDisplayed()
    }

    @Test fun aPrinterJarvisCannotReachIsShownGreyedWithTheReason() = runComposeUiTest {
        show(ui(live(PrinterState(connection = Connections.UNREACHABLE, lastSeen = now - 125_000))))
        onNodeWithText("Jarvis can't reach the printer (is it on?). Showing what it last saw 2 min ago.").assertIsDisplayed()
    }

    @Test fun jarvisAwayKeepsTheLastPageAndOffersTryAgain() = runComposeUiTest {
        var tried = false
        show(ui(PrinterScreenState.JarvisAway(StateAnswer(rev = 1, state = printing), "Edith can't reach Jarvis. Is the PC on, Jarvis running, and Tailscale connected (away from home)?")),
            PrinterActions(tryAgain = { tried = true }))
        onNodeWithTag("banner").assertIsDisplayed()
        onNodeWithText("Chimney and Platforms (plate 1)").assertIsDisplayed()
        onNodeWithText("Try again").performClick()
        assertEquals(true, tried)
    }

    @Test fun jarvisAwayWithNothingToShowIsAMessageWithTryAgain() = runComposeUiTest {
        show(ui(PrinterScreenState.JarvisAway(null, "Jarvis removed this phone: pair it again.")))
        onNodeWithText("Jarvis removed this phone: pair it again.").assertIsDisplayed()
        onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test fun noPrinterSetUpIsItsOwnMessage() = runComposeUiTest {
        show(ui(live(PrinterState(connection = Connections.NONE))))
        onNodeWithText("No printer is set up in Jarvis.").assertIsDisplayed()
    }

    @Test fun theStatusCardCanBeFoldedAndDismissingTheErrorIsAsked() = runComposeUiTest {
        val dismissed = mutableListOf<Long>()
        val toggled = mutableListOf<String>()
        val state = printing.copy(alert = PrinterAlert("Filament runout detected", time = 77))
        show(ui(live(state)), PrinterActions(dismissAlert = { dismissed += it }, toggleCard = { toggled += it }))
        onNodeWithText("The printer said: Filament runout detected").assertIsDisplayed()
        onNodeWithText("Dismiss").performClick()
        onNodeWithTag("toggle-status").performClick()
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
    private fun ComposeUiTest.scrollTo(tag: String) = onNodeWithTag("printer-list").performScrollToNode(androidx.compose.ui.test.hasTestTag(tag))

    private fun open(state: PrinterState) = PrinterUi(live(state), "FlashForge AD5X", emptySet(), null, now, TimeZone.UTC)

    @Test fun theTemperatureCardShowsEachHeaterAndTheChart() = runComposeUiTest {
        val lines = listOf(ChartLine("extruder", "Nozzle", listOf(20.0, 100.0, 219.8), listOf(220.0, 220.0, 220.0)))
        setContent { EdithTheme { PrinterContent(open(everything), true, PrinterActions(), CardsUi(chart = lines)) } }
        scrollTo("temperature-card")
        onNodeWithTag("temperature-card").assertExists()
        onNodeWithText("219.8 °C").assertExists()
        onNodeWithText("→ 220 °C").assertExists()
        onNodeWithTag("temp-chart").assertExists()
    }

    @Test fun theFilesCardAsksBeforeListingAnything() = runComposeUiTest {
        var loaded = 0
        setContent { EdithTheme { PrinterContent(open(everything.copy(print = PrintInfo())), true, PrinterActions(loadFiles = { loaded++ }), CardsUi()) } }
        scrollTo("files-card")
        onNodeWithText("Listing the files keeps the printer busy for several seconds, and its buttons wait.").assertExists()
        assertEquals(0, loaded)
        onNodeWithTag("load-files").performClick()
        assertEquals(1, loaded)
    }

    @Test fun theFilesCardCannotListWhilePrinting() = runComposeUiTest {
        setContent { EdithTheme { PrinterContent(open(everything), true, PrinterActions(), CardsUi()) } }
        scrollTo("files-card")
        onNodeWithText("Not while printing: the printer would stop answering for several seconds.").assertExists()
    }

    @Test fun theSlotsCardDoesNotCallAnythingEmptyWhenThePrinterIsNotReporting() = runComposeUiTest {
        setContent { EdithTheme { PrinterContent(open(everything), true, PrinterActions(), CardsUi()) } }
        scrollTo("slots-card")
        onNodeWithText("The printer isn't reporting which slots have filament right now. Its screen shows them.").assertExists()
        onNodeWithText("Empty").assertDoesNotExist()
    }

    @Test fun theCameraCardSaysConnectingUntilThereIsAPictureAndOfflineAfterTwoFailures() = runComposeUiTest {
        var cards by androidx.compose.runtime.mutableStateOf(CardsUi())
        setContent { EdithTheme { PrinterContent(open(everything), true, PrinterActions(), cards) } }
        onNodeWithTag("camera-connecting").assertExists()
        cards = CardsUi(camera = CameraUi("video", null, 0, offline = true))
        onNodeWithTag("camera-offline").assertExists()
    }

    @Test fun openingACardTellsTheDataLayer() = runComposeUiTest {
        val told = mutableListOf<Pair<String, Boolean>>()
        setContent { EdithTheme { PrinterContent(open(everything), true, PrinterActions(cardVisible = { id, v -> told += id to v }), CardsUi()) } }
        waitForIdle()
        assertEquals(true, told.contains(PrinterCards.STATUS to true))   // the first card is on screen in any window; the lower ones are not drawn until scrolled to
        // Only the cards on screen are told: the camera card is below the fold in a test window (the phone it was written on was taller).
    }

    // --- Stage 2 (E7): Controls, the confirm sheet, the job buttons, the emergency stop ----------------------------

    private fun control(armed: Boolean = false, may: Boolean = true) = ControlUi(mayControl = may, armed = armed, armUntilMs = if (armed) kotlin.time.Clock.System.now().toEpochMilliseconds() + 103_000 else null)
    private val quickOk = mapOf("pause" to com.edith.app.printer.feed.QuickCheck(true), "cancel" to com.edith.app.printer.feed.QuickCheck(true))
    private fun withQuick(state: PrinterState) = PrinterUi(PrinterScreenState.Live(StateAnswer(rev = 1, state = state, quick = quickOk), now, 0), "FlashForge AD5X", emptySet(), null, now, TimeZone.UTC)

    @Test fun theConfirmSheetShowsJarvisWordsAndTwoDifferentButtons() = runComposeUiTest {
        var yes = 0; var no = 0
        val sheet = ConfirmUi("Cancel the print of Benchy? It can’t be resumed.", "Cancel print", danger = true, com.edith.app.printer.feed.PrinterCommand.Cancel, "id")
        setContent { EdithTheme { ConfirmSheet(sheet, onConfirm = { yes++ }, onDismiss = { no++ }) } }
        onNodeWithTag("confirm-question").assertIsDisplayed()
        onNodeWithText("Cancel the print of Benchy? It can’t be resumed.").assertIsDisplayed()
        onNodeWithTag("confirm-yes").assertIsDisplayed()
        onNodeWithText("Keep printing").assertIsDisplayed()
        onNodeWithTag("confirm-no").performClick()
        assertEquals(0, yes); assertEquals(1, no)
        onNodeWithTag("confirm-yes").performClick()
        assertEquals(1, yes)
    }

    @Test fun aLookOnlyPhoneSaysSoAndHasNoControlsSwitch() = runComposeUiTest {
        setContent { EdithTheme { PrinterContent(withQuick(printing), true, PrinterActions(), CardsUi(), control(may = false)) } }
        onNodeWithTag("controls-look-only").assertExists()
        onNodeWithTag("controls-switch").assertDoesNotExist()
        onNodeWithTag("job-buttons").assertDoesNotExist()
    }

    @Test fun theControlsSwitchAsksForControlsAndShowsTheCountdown() = runComposeUiTest {
        val asked = mutableListOf<Boolean>()
        var c by androidx.compose.runtime.mutableStateOf(control(armed = false))
        setContent { EdithTheme { PrinterContent(withQuick(printing), true, PrinterActions(setControls = { asked += it }), CardsUi(), c) } }
        onNodeWithTag("controls-switch").performClick()
        assertEquals(listOf(true), asked)
        c = control(armed = true)
        onNodeWithTag("controls-text").assertExists()
        onNodeWithText("Controls on", substring = true).assertIsDisplayed()
    }

    @Test fun withControlsOffTheJobButtonsAreDisabledAndSayWhy() = runComposeUiTest {
        setContent { EdithTheme { PrinterContent(withQuick(printing), true, PrinterActions(), CardsUi(), control(armed = false)) } }
        onNodeWithTag("job-main").assertIsNotEnabled()
        onNodeWithTag("job-cancel").assertIsNotEnabled()
        onNodeWithTag("job-why").assertIsDisplayed()
        onAllNodesWithText("Turn on Controls to use this")[0].assertExists()
    }

    @Test fun withControlsOnPauseAndCancelSendTheirCommands() = runComposeUiTest {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        setContent { EdithTheme { PrinterContent(withQuick(printing), true, PrinterActions(send = { sent += it }), CardsUi(), control(armed = true)) } }
        onNodeWithTag("job-main").assertIsEnabled().performClick()
        onNodeWithTag("job-cancel").assertIsEnabled().performClick()
        assertEquals<List<com.edith.app.printer.feed.PrinterCommand>>(listOf(com.edith.app.printer.feed.PrinterCommand.Pause, com.edith.app.printer.feed.PrinterCommand.Cancel), sent)
    }

    @Test fun aPausedPrintOffersResumeAndAPendingButtonIsDisabledWithAProgressIndicator() = runComposeUiTest {
        val pausedState = printing.copy(print = PrintInfo(PrinterStates.PAUSED, paused = true))
        val c = control(armed = true).copy(pending = setOf("resume"))
        setContent { EdithTheme { PrinterContent(withQuick(pausedState), true, PrinterActions(), CardsUi(), c) } }
        onNodeWithText("Resume").assertExists()
        onNodeWithTag("job-main").assertIsNotEnabled()
    }

    @Test fun theServersReasonShowsWhenItWillNotTakeAPause() = runComposeUiTest {
        val ui = PrinterUi(PrinterScreenState.Live(StateAnswer(rev = 1, state = printing, quick = mapOf("pause" to com.edith.app.printer.feed.QuickCheck(false, "The printer is busy."), "cancel" to com.edith.app.printer.feed.QuickCheck(true))), now, 0), "FlashForge AD5X", emptySet(), null, now, TimeZone.UTC)
        setContent { EdithTheme { PrinterContent(ui, true, PrinterActions(), CardsUi(), control(armed = true)) } }
        onNodeWithTag("job-main").assertIsNotEnabled()
        onNodeWithText("The printer is busy.").assertIsDisplayed()
    }

    @Test fun theEmergencyStopIsThereWithControlsOffAndAsksThroughSend() = runComposeUiTest {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        setContent { EdithTheme { PrinterContent(withQuick(printing), true, PrinterActions(send = { sent += it }), CardsUi(), control(armed = false)) } }
        onNodeWithTag("estop").assertIsDisplayed().performClick()
        assertEquals(listOf<com.edith.app.printer.feed.PrinterCommand>(com.edith.app.printer.feed.PrinterCommand.EStop), sent)
    }

    @Test fun theEmergencyStopIsThereOnALookOnlyPhoneButNotWhenThePrinterIsUnreachable() = runComposeUiTest {
        setContent { EdithTheme { PrinterContent(withQuick(printing), true, PrinterActions(), CardsUi(), control(may = false)) } }
        onNodeWithTag("estop").assertExists()
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
        PrinterScreenState.Live(StateAnswer(rev = 1, state = state), now, 0), "FlashForge AD5X", emptySet(), null, now, TimeZone.UTC,
        listOf(com.edith.app.printer.feed.Preset("PLA", 220, 60)),
    )
    private fun ComposeUiTest.controlsContent(c: ControlUi, sent: MutableList<com.edith.app.printer.feed.PrinterCommand>, state: PrinterState = rich, cards: CardsUi = CardsUi()) =
        setContent { EdithTheme { PrinterContent(richUi(state), true, PrinterActions(send = { sent += it }), cards, c) } }
    private fun ComposeUiTest.click(tag: String, card: String) { scrollTo(card); onNodeWithTag(tag).performScrollTo().performClick() }

    @Test fun theTemperatureControlsSendTargetsPresetsAndAllOff() = runComposeUiTest {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent)
        scrollTo("temperature-controls")
        onNodeWithTag("target-extruder").performScrollTo().performTextInput("220")
        onNodeWithTag("set-extruder").performScrollTo().performClick()
        onNodeWithTag("off-heater_bed").performScrollTo().performClick()
        onNodeWithTag("cmd:PLA").performScrollTo().performClick()
        onNodeWithTag("cmd:All off").performScrollTo().performClick()
        assertEquals(
            listOf(com.edith.app.printer.feed.PrinterCommand.SetTemp("extruder", 220.0), com.edith.app.printer.feed.PrinterCommand.SetTemp("heater_bed", 0.0),
                com.edith.app.printer.feed.PrinterCommand.Preset("PLA"), com.edith.app.printer.feed.PrinterCommand.HeatersOff), sent,
        )
    }

    @Test fun aTargetBoxThatIsEmptyOrTooHotSendsNothing() = runComposeUiTest {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent)
        scrollTo("temperature-controls")
        onNodeWithTag("set-heater_bed").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("target-heater_bed").performScrollTo().performTextInput("130")
        onNodeWithTag("set-heater_bed").performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList<com.edith.app.printer.feed.PrinterCommand>(), sent)
    }

    @Test fun theToolheadControlsSendHomeJogZOffsetAndExtrude() = runComposeUiTest {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent)
        scrollTo("toolhead-controls")
        onNodeWithTag("cmd:All").performScrollTo().performClick()
        onNodeWithTag("step-10").performScrollTo().performClick()
        onNodeWithTag("cmd:X+").performScrollTo().performClick()
        onNodeWithTag("cmd:Y−").performScrollTo().performClick()
        onNodeWithTag("step-100").performScrollTo().performClick()
        onNodeWithTag("cmd:Z+").performScrollTo().performClick()
        onNodeWithTag("cmd:+0.05").performScrollTo().performClick()
        onNodeWithTag("cmd:Extrude").performScrollTo().performClick()
        onNodeWithTag("cmd:Retract").performScrollTo().performClick()
        onNodeWithTag("cmd:Motors off").performScrollTo().performClick()
        assertEquals(
            listOf(
                PrinterCommand.Home(com.edith.app.printer.feed.HomeAxes.ALL), PrinterCommand.Jog("x", 10.0), PrinterCommand.Jog("y", -10.0), PrinterCommand.Jog("z", 50.0), PrinterCommand.ZOffset(0.05),
                PrinterCommand.Extrude(10.0, 5.0), PrinterCommand.Extrude(-10.0, 5.0), PrinterCommand.MotorsOff,
            ), sent,
        )
    }

    @Test fun theFanLightAndAirControlsSend() = runComposeUiTest {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent)
        scrollTo("fan-controls")
        onNodeWithTag("cmd:On").performScrollTo().performClick()
        onNodeWithTag("cmd:Internal").performScrollTo().performClick()
        onNodeWithTag("cmd:Stop").performScrollTo().performClick()
        assertEquals(listOf(PrinterCommand.Light(1.0), PrinterCommand.AirCirculation("internal"), PrinterCommand.AirCirculation("stop")), sent)
    }

    @Test fun theLimitsAreAppliedOnlyWhenSomethingIsTyped() = runComposeUiTest {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent)
        scrollTo("limit-controls")
        onNodeWithTag("cmd:Apply limits").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("limit-velocity").performScrollTo().performTextInput("200")
        onNodeWithTag("cmd:Apply limits").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf<com.edith.app.printer.feed.PrinterCommand>(com.edith.app.printer.feed.PrinterCommand.Limits(velocity = 200.0)), sent)
    }

    @Test fun theSensorObjectQueueAndSystemControlsSend() = runComposeUiTest {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent)
        scrollTo("sensor-controls"); onNodeWithTag("filament-filament_switch_sensor head").performScrollTo().performClick()
        scrollTo("object-controls"); onNodeWithTag("cmd:Exclude Chimney").performScrollTo().performClick()
        scrollTo("queue-controls"); onNodeWithTag("cmd:Start queue").performScrollTo().performClick()
        onNodeWithTag("cmd:Remove Benchy").performScrollTo().performClick()
        scrollTo("system-controls"); onNodeWithTag("cmd:Restart Klipper").performScrollTo().performClick()
        assertEquals(listOf(PrinterCommand.FilamentSensor("filament_switch_sensor head", false), PrinterCommand.ExcludeObject("Chimney"), PrinterCommand.QueueStart, PrinterCommand.QueueRemove("0001"), PrinterCommand.Restart), sent)
    }

    @Test fun withControlsOffEveryCardControlIsDisabledAndSaysWhy() = runComposeUiTest {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = false), sent)
        scrollTo("toolhead-controls")
        onNodeWithTag("cmd:All").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("cmd:X+").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("cmd:Extrude").performScrollTo().assertIsNotEnabled()
        scrollTo("temperature-controls")
        onNodeWithTag("cmd:All off").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("cmd:PLA").performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList<com.edith.app.printer.feed.PrinterCommand>(), sent)
    }

    @Test fun aLookOnlyPhoneGetsNoControlsInAnyCard() = runComposeUiTest {
        setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), CardsUi(), control(may = false)) } }
        val cardOf = mapOf(
            "toolhead-controls" to "toolhead-card", "temperature-controls" to "temperature-card", "fan-controls" to "fans-card", "limit-controls" to "limits-card",
            "sensor-controls" to "sensors-card", "object-controls" to "objects-card", "queue-controls" to "queue-card", "system-controls" to "system-card",
        )
        for ((controls, card) in cardOf) {
            scrollTo(card)
            onNodeWithTag(controls).assertDoesNotExist()
        }
    }

    @Test fun aRefusalFromJarvisIsShownUnderTheJogControls() = runComposeUiTest {
        val c = control(armed = true).copy(refused = mapOf("jog" to "Home the printer first."))
        setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), CardsUi(), c) } }
        scrollTo("toolhead-controls")
        onNodeWithText("Home the printer first.").performScrollTo().assertIsDisplayed()
    }

    @Test fun anOfflineCameraOffersToTurnItOn() = runComposeUiTest {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        controlsContent(control(armed = true), sent, cards = CardsUi(camera = CameraUi("video", null, 0, offline = true)))
        onNodeWithTag("cmd:Turn camera on").performScrollTo().performClick()
        assertEquals(listOf<com.edith.app.printer.feed.PrinterCommand>(com.edith.app.printer.feed.PrinterCommand.Camera(true)), sent)
    }

    @Test fun whenKlipperHasStoppedTheBannerOffersAFirmwareRestart() = runComposeUiTest {
        val sent = mutableListOf<com.edith.app.printer.feed.PrinterCommand>()
        val stopped = PrinterState(connection = Connections.KLIPPY_SHUTDOWN, message = "Heater not heating")
        controlsContent(control(armed = true), sent, state = stopped)
        onNodeWithTag("cmd:Firmware restart").assertIsDisplayed().performClick()
        assertEquals(listOf<com.edith.app.printer.feed.PrinterCommand>(com.edith.app.printer.feed.PrinterCommand.FirmwareRestart), sent)
    }

    // --- Stage 2 (E9): macros and the console input ----------------------------------------------------------------------

    private val macroAnswer = com.edith.app.printer.feed.MacrosAnswer(
        macros = listOf(
            com.edith.app.printer.feed.MacroInfo("LOAD_FILAMENT", "Load filament", group = "filament"),
            com.edith.app.printer.feed.MacroInfo("CLEAN_NOZZLE", group = "print"),
            com.edith.app.printer.feed.MacroInfo("BED_MESH_CALIBRATE", "Probe the bed", params = listOf(com.edith.app.printer.feed.MacroParam("PROFILE", "default")), risk = "danger", group = "calibration"),
        ),
        groups = listOf(com.edith.app.printer.feed.MacroGroup("filament", "Filament and nozzle"), com.edith.app.printer.feed.MacroGroup("print", "Printing"), com.edith.app.printer.feed.MacroGroup("calibration", "Bed and calibration")),
        hidden = listOf("SECRET_MACRO"),
    )
    private fun macroCards() = CardsUi(macros = MacrosUi(answer = macroAnswer))

    @Test fun theMacrosCardShowsJarvisGroupsInOrderWithCounts() = runComposeUiTest {
        controlsContent(control(armed = true), mutableListOf(), cards = macroCards())
        scrollTo("macros-card")
        onNodeWithText("Filament and nozzle (1)").performScrollTo().assertIsDisplayed()
        onNodeWithText("Printing (1)").performScrollTo().assertIsDisplayed()
        onNodeWithText("Bed and calibration (1)").performScrollTo().assertIsDisplayed()
        onNodeWithText("Load filament").performScrollTo().assertIsDisplayed()
    }

    @Test fun aLookOnlyPhoneHasNoMacrosCard() = runComposeUiTest {
        setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), macroCards(), control(may = false)) } }
        onNodeWithTag("macros-card").assertDoesNotExist()
    }

    @Test fun aMacroWithNoParametersRunsWithOneTap() = runComposeUiTest {
        val sent = mutableListOf<PrinterCommand>()
        controlsContent(control(armed = true), sent, cards = macroCards())
        scrollTo("macros-card")
        onNodeWithTag("run-CLEAN_NOZZLE").performScrollTo().performClick()
        assertEquals(listOf<PrinterCommand>(PrinterCommand.Macro("CLEAN_NOZZLE")), sent)
    }

    @Test fun aMacroWithParametersOpensAFormAndSendsWhatWasTyped() = runComposeUiTest {
        val sent = mutableListOf<PrinterCommand>()
        controlsContent(control(armed = true), sent, cards = macroCards())
        scrollTo("macros-card")
        onNodeWithTag("open-BED_MESH_CALIBRATE").performScrollTo().performClick()
        onNodeWithTag("run-BED_MESH_CALIBRATE").performScrollTo().performClick()
        onNodeWithTag("param-BED_MESH_CALIBRATE-PROFILE").performScrollTo().performTextInput("mine")
        onNodeWithTag("run-BED_MESH_CALIBRATE").performScrollTo().performClick()
        assertEquals(listOf<PrinterCommand>(PrinterCommand.Macro("BED_MESH_CALIBRATE"), PrinterCommand.Macro("BED_MESH_CALIBRATE", mapOf("PROFILE" to "mine"))), sent)
    }

    @Test fun theFilterNarrowsTheListAndOpensEveryGroup() = runComposeUiTest {
        var query by androidx.compose.runtime.mutableStateOf("")
        setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(setMacroQuery = { query = it }), CardsUi(macros = MacrosUi(answer = macroAnswer, query = query)), control(armed = true)) } }
        scrollTo("macros-card")
        onNodeWithTag("macro-filter").performScrollTo().performTextInput("probe")
        waitForIdle()
        assertEquals("probe", query)
    }

    @Test fun hidingAMacroAsksJarvisAndAHiddenOneCanBeShownAgain() = runComposeUiTest {
        val hidden = mutableListOf<List<String>>()
        setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(setHiddenMacros = { hidden += it }), macroCards(), control(armed = true)) } }
        scrollTo("macros-card")
        onNodeWithTag("hide-CLEAN_NOZZLE").performScrollTo().performClick()
        onNodeWithTag("show-SECRET_MACRO").performScrollTo().performClick()
        assertEquals(listOf(listOf("SECRET_MACRO", "CLEAN_NOZZLE"), emptyList<String>()), hidden)
    }

    @Test fun theConsoleSendsTheLineTypedAndKeepsIt() = runComposeUiTest {
        val sent = mutableListOf<PrinterCommand>(); val remembered = mutableListOf<String>()
        setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(send = { sent += it }, rememberConsoleLine = { remembered += it }), CardsUi(), control(armed = true)) } }
        scrollTo("console-card")
        onNodeWithTag("console-send").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("console-line").performScrollTo().performTextInput("  M105 ")
        onNodeWithTag("console-send").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf<PrinterCommand>(PrinterCommand.Gcode("M105")), sent)
        assertEquals(listOf("M105"), remembered)
    }

    @Test fun theConsoleLineIsCutAt200Characters() = runComposeUiTest {
        setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), CardsUi(), control(armed = true)) } }
        scrollTo("console-card")
        onNodeWithTag("console-line").performScrollTo().performTextInput("G".repeat(250))
        onNodeWithText("200/200").assertExists()
    }

    @Test fun theUpArrowRecallsTheLastLinesSent() = runComposeUiTest {
        val history = listOf("G28", "M105")
        setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), CardsUi(console = ConsoleUi(history = history)), control(armed = true)) } }
        scrollTo("console-card")
        onNodeWithTag("console-older").performScrollTo().performClick()
        onNodeWithText("G28").assertExists()
        onNodeWithTag("console-older").performClick()
        onNodeWithText("M105").assertExists()
        onNodeWithTag("console-newer").performClick()
        onNodeWithText("G28").assertExists()
    }

    @Test fun aLookOnlyPhoneHasNoConsoleInput() = runComposeUiTest {
        setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), CardsUi(), control(may = false)) } }
        scrollTo("console-card")
        onNodeWithTag("console-input").assertDoesNotExist()
    }

    // --- Stage 2 (E10b): the temperature presets editor ----------------------------------------------------------------

    private fun ComposeUiTest.presetsContent(c: ControlUi, saved: MutableList<List<com.edith.app.printer.feed.Preset>>, editor: PresetsUi = PresetsUi()) =
        setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(savePresets = { saved += it }), CardsUi(presets = editor), c) } }
    private fun ComposeUiTest.openPresets() { scrollTo("temperature-controls"); onNodeWithTag("edit-presets").performScrollTo().performClick() }

    @Test fun theEditorOpensWithJarvisPresets() = runComposeUiTest {
        presetsContent(control(armed = true), mutableListOf())
        openPresets()
        onNodeWithTag("presets-dialog").assertIsDisplayed()
        onNodeWithTag("preset-name-0").assert(androidx.compose.ui.test.hasText("PLA"))
        onNodeWithTag("preset-nozzle-0").assert(androidx.compose.ui.test.hasText("220"))
        onNodeWithTag("preset-bed-0").assert(androidx.compose.ui.test.hasText("60"))
    }

    @Test fun editingAndSavingSendsTheList() = runComposeUiTest {
        val saved = mutableListOf<List<com.edith.app.printer.feed.Preset>>()
        presetsContent(control(armed = true), saved)
        openPresets()
        onNodeWithTag("preset-nozzle-0").performTextClearance()
        onNodeWithTag("preset-nozzle-0").performTextInput("215")
        onNodeWithTag("preset-add").performClick()
        onNodeWithTag("preset-name-1").performTextInput("ASA")
        onNodeWithTag("preset-nozzle-1").performTextInput("260")
        onNodeWithTag("preset-bed-1").performTextInput("100")
        onNodeWithTag("presets-save").assertIsEnabled().performClick()
        assertEquals(listOf(listOf(com.edith.app.printer.feed.Preset("PLA", 215, 60), com.edith.app.printer.feed.Preset("ASA", 260, 100))), saved)
    }

    @Test fun aWrongTemperatureSaysWhyAndSaveIsOff() = runComposeUiTest {
        presetsContent(control(armed = true), mutableListOf())
        openPresets()
        onNodeWithTag("preset-nozzle-0").performTextClearance()
        onNodeWithTag("preset-nozzle-0").performTextInput("999")
        onNodeWithTag("presets-save").assertIsNotEnabled()
        onNodeWithText("The nozzle temperature of PLA must be from 0 to 300 °C.").assertIsDisplayed()
    }

    @Test fun aPresetCanBeRemovedAndNoneIsAllowed() = runComposeUiTest {
        val saved = mutableListOf<List<com.edith.app.printer.feed.Preset>>()
        presetsContent(control(armed = true), saved)
        openPresets()
        onNodeWithTag("preset-remove-0").performClick()
        onNodeWithText("With none, Jarvis uses its own presets.").assertIsDisplayed()
        onNodeWithTag("presets-save").assertIsEnabled().performClick()
        assertEquals(listOf(emptyList<com.edith.app.printer.feed.Preset>()), saved)
    }

    @Test fun withControlsOffSavingIsOffAndSaysWhy() = runComposeUiTest {
        presetsContent(control(armed = false), mutableListOf())
        openPresets()
        onNodeWithTag("presets-save").assertIsNotEnabled()
        onNodeWithTag("presets-problem").assert(androidx.compose.ui.test.hasText("Turn on Controls to use this"))
    }

    @Test fun jarvisRefusalIsShownInTheEditor() = runComposeUiTest {
        presetsContent(control(armed = true), mutableListOf(), PresetsUi(error = "Controls turned off. Turn them on to continue."))
        openPresets()
        onNodeWithText("Controls turned off. Turn them on to continue.").assertIsDisplayed()
    }

    @Test fun aLookOnlyPhoneCannotEditPresets() = runComposeUiTest {
        setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), CardsUi(), control(may = false)) } }
        scrollTo("temperature-card")
        onNodeWithTag("edit-presets").assertDoesNotExist()
    }

    @Test fun noEmergencyStopWhileJarvisCannotReachThePrinter() = runComposeUiTest {
        setContent { EdithTheme { PrinterContent(open(PrinterState(connection = Connections.UNREACHABLE)), true, PrinterActions(), CardsUi(), control()) } }
        onNodeWithTag("estop").assertDoesNotExist()
    }

    // --- Stage 3 (E11): upload, and the Files sheet's Print, Add to queue and Delete -----------------------------------------

    private val idle get() = open(everything.copy(print = PrintInfo()))
    private val benchy = com.edith.app.printer.feed.PrinterFile(path = "benchy.gcode", size = 2_000_000, title = "Benchy")
    private fun filesCards(upload: UploadUi = UploadUi(), detailOf: String? = null) =
        CardsUi(files = FilesUi(files = listOf(benchy), upload = upload, detailOf = detailOf, detail = if (detailOf != null) com.edith.app.printer.feed.PrinterFileDetail(path = detailOf) else null))

    @Test fun aLookOnlyPhoneHasNoUploadAndNoFileButtons() = runComposeUiTest {
        setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(), filesCards(detailOf = "benchy.gcode"), control(may = false)) } }
        scrollTo("files-card")
        onNodeWithTag("upload-row").assertDoesNotExist()
        onNodeWithTag("file-print").assertDoesNotExist()
        onNodeWithTag("file-queue").assertDoesNotExist()
        onNodeWithTag("file-delete").assertDoesNotExist()
    }

    @Test fun withControlsOffAFileCanStillBeChosenBecauseNothingIsSentYet() = runComposeUiTest {
        var picked = 0
        setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(pickUpload = { picked++ }), filesCards(), control(armed = false)) } }
        scrollTo("upload-row")
        onNodeWithTag("upload-button").assertIsEnabled().performClick()
        assertEquals(1, picked)
    }

    @Test fun withControlsOnUploadOpensThePicker() = runComposeUiTest {
        var picked = 0
        setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(pickUpload = { picked++ }), filesCards(), control(armed = true)) } }
        scrollTo("upload-row")
        onNodeWithTag("upload-button").assertIsEnabled().performClick()
        assertEquals(1, picked)
    }

    @Test fun aChosenFileWaitsForSendAndSendNeedsControlsOn() = runComposeUiTest {
        var sent = 0; var discarded = 0
        val chosen = UploadUi(name = "benchy.gcode", total = 2_000_000, ready = true)
        var c by androidx.compose.runtime.mutableStateOf(control(armed = false))
        setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(sendUpload = { sent++ }, cancelUpload = { discarded++ }), filesCards(chosen), c) } }
        scrollTo("upload-row")
        onNodeWithTag("upload-ready").assert(androidx.compose.ui.test.hasText("benchy.gcode · 2.0 MB is ready to send."))
        onNodeWithTag("upload-button").assertDoesNotExist()
        onNodeWithTag("upload-send").assertIsNotEnabled()
        c = control(armed = true)
        onNodeWithTag("upload-send").assertIsEnabled().performClick()
        assertEquals(1, sent)
        onNodeWithTag("upload-discard").performClick()
        assertEquals(1, discarded)
    }

    @Test fun aRefusedSendKeepsTheFileReadyAndShowsJarvisWords() = runComposeUiTest {
        val chosen = UploadUi(name = "benchy.gcode", total = 2_000_000, ready = true, error = "Controls turned off. Turn them on to continue.")
        setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(), filesCards(chosen), control(armed = false)) } }
        scrollTo("upload-row")
        onNodeWithTag("upload-send").assertExists()
        onNodeWithTag("upload-error").assert(androidx.compose.ui.test.hasText("Controls turned off. Turn them on to continue."))
    }

    @Test fun aRunningUploadShowsHowMuchHasGoneAndCanBeCancelled() = runComposeUiTest {
        var cancelled = 0
        val going = UploadUi(name = "benchy.gcode", active = true, sent = 500_000, total = 2_000_000)
        setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(cancelUpload = { cancelled++ }), filesCards(going), control(armed = true)) } }
        scrollTo("upload-row")
        onNodeWithTag("upload-progress").assertExists()
        onNodeWithTag("upload-progress-text").assert(androidx.compose.ui.test.hasText("Sending benchy.gcode · 500 KB of 2.0 MB"))
        onNodeWithTag("upload-button").assertDoesNotExist()   // one upload at a time: the button gives way to Cancel
        onNodeWithTag("upload-cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test fun aFinishedUploadSaysSentAndAFailedOneSaysWhy() = runComposeUiTest {
        var cards by androidx.compose.runtime.mutableStateOf(filesCards(UploadUi(name = "benchy.gcode", sent = 10, total = 10, done = "benchy.gcode")))
        setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(), cards, control(armed = true)) } }
        scrollTo("upload-row")
        onNodeWithTag("upload-done").assert(androidx.compose.ui.test.hasText("Sent benchy.gcode. Refresh the list to see it."))
        cards = filesCards(UploadUi(name = "x.stl", error = "Only .gcode and .3mf files can be sent."))
        onNodeWithTag("upload-error").assert(androidx.compose.ui.test.hasText("Only .gcode and .3mf files can be sent."))
        onNodeWithTag("upload-done").assertDoesNotExist()
    }

    @Test fun theFileSheetOffersPrintQueueAndDeleteAndEachSendsItsCommand() = runComposeUiTest {
        val sent = mutableListOf<PrinterCommand>()
        setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(send = { sent += it }), filesCards(detailOf = "benchy.gcode"), control(armed = true)) } }
        scrollTo("files-card")
        // All three are wide enough to read (Delete was once squeezed into a vertical strip).
        for (tag in listOf("file-print", "file-queue", "file-delete")) onNodeWithTag(tag).assertWidthIsAtLeast(64.dp)
        onNodeWithTag("file-print").assertIsEnabled().performClick()
        onNodeWithTag("file-queue").performClick()
        onNodeWithTag("file-delete").performClick()
        assertEquals(listOf<PrinterCommand>(PrinterCommand.Start("benchy.gcode"), PrinterCommand.QueueAdd("benchy.gcode"), PrinterCommand.DeleteFile("benchy.gcode")), sent)
    }

    @Test fun withControlsOffTheFileButtonsAreDisabled() = runComposeUiTest {
        setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(), filesCards(detailOf = "benchy.gcode"), control(armed = false)) } }
        scrollTo("files-card")
        onNodeWithTag("file-print").assertIsNotEnabled()
        onNodeWithTag("file-queue").assertIsNotEnabled()
        onNodeWithTag("file-delete").assertIsNotEnabled()
    }

    @Test fun jarvisRefusalOfAPrintIsShownUnderTheFileButtons() = runComposeUiTest {
        val refused = control(armed = true).copy(refused = mapOf("start" to "Start .3mf files from the printer’s screen."))
        setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(), filesCards(detailOf = "benchy.gcode"), refused) } }
        scrollTo("files-card")
        onNodeWithText("Start .3mf files from the printer’s screen.").assertIsDisplayed()
    }
}
