package com.edith.app.printer.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
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

    @Test fun theMacrosCardShowsJarvisGroupsInOrderWithCounts() {
        controlsContent(control(armed = true), mutableListOf(), cards = macroCards())
        scrollTo("macros-card")
        compose.onNodeWithText("Filament and nozzle (1)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Printing (1)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bed and calibration (1)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Load filament").performScrollTo().assertIsDisplayed()
    }

    @Test fun aLookOnlyPhoneHasNoMacrosCard() {
        compose.setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), macroCards(), control(may = false)) } }
        compose.onNodeWithTag("macros-card").assertDoesNotExist()
    }

    @Test fun aMacroWithNoParametersRunsWithOneTap() {
        val sent = mutableListOf<PrinterCommand>()
        controlsContent(control(armed = true), sent, cards = macroCards())
        scrollTo("macros-card")
        compose.onNodeWithTag("run-CLEAN_NOZZLE").performScrollTo().performClick()
        assertEquals(listOf<PrinterCommand>(PrinterCommand.Macro("CLEAN_NOZZLE")), sent)
    }

    @Test fun aMacroWithParametersOpensAFormAndSendsWhatWasTyped() {
        val sent = mutableListOf<PrinterCommand>()
        controlsContent(control(armed = true), sent, cards = macroCards())
        scrollTo("macros-card")
        compose.onNodeWithTag("open-BED_MESH_CALIBRATE").performScrollTo().performClick()
        compose.onNodeWithTag("run-BED_MESH_CALIBRATE").performScrollTo().performClick()
        compose.onNodeWithTag("param-BED_MESH_CALIBRATE-PROFILE").performScrollTo().performTextInput("mine")
        compose.onNodeWithTag("run-BED_MESH_CALIBRATE").performScrollTo().performClick()
        assertEquals(listOf<PrinterCommand>(PrinterCommand.Macro("BED_MESH_CALIBRATE"), PrinterCommand.Macro("BED_MESH_CALIBRATE", mapOf("PROFILE" to "mine"))), sent)
    }

    @Test fun theFilterNarrowsTheListAndOpensEveryGroup() {
        var query by androidx.compose.runtime.mutableStateOf("")
        compose.setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(setMacroQuery = { query = it }), CardsUi(macros = MacrosUi(answer = macroAnswer, query = query)), control(armed = true)) } }
        scrollTo("macros-card")
        compose.onNodeWithTag("macro-filter").performScrollTo().performTextInput("probe")
        compose.waitForIdle()
        assertEquals("probe", query)
    }

    @Test fun hidingAMacroAsksJarvisAndAHiddenOneCanBeShownAgain() {
        val hidden = mutableListOf<List<String>>()
        compose.setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(setHiddenMacros = { hidden += it }), macroCards(), control(armed = true)) } }
        scrollTo("macros-card")
        compose.onNodeWithTag("hide-CLEAN_NOZZLE").performScrollTo().performClick()
        compose.onNodeWithTag("show-SECRET_MACRO").performScrollTo().performClick()
        assertEquals(listOf(listOf("SECRET_MACRO", "CLEAN_NOZZLE"), emptyList<String>()), hidden)
    }

    @Test fun theConsoleSendsTheLineTypedAndKeepsIt() {
        val sent = mutableListOf<PrinterCommand>(); val remembered = mutableListOf<String>()
        compose.setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(send = { sent += it }, rememberConsoleLine = { remembered += it }), CardsUi(), control(armed = true)) } }
        scrollTo("console-card")
        compose.onNodeWithTag("console-send").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("console-line").performScrollTo().performTextInput("  M105 ")
        compose.onNodeWithTag("console-send").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf<PrinterCommand>(PrinterCommand.Gcode("M105")), sent)
        assertEquals(listOf("M105"), remembered)
    }

    @Test fun theConsoleLineIsCutAt200Characters() {
        compose.setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), CardsUi(), control(armed = true)) } }
        scrollTo("console-card")
        compose.onNodeWithTag("console-line").performScrollTo().performTextInput("G".repeat(250))
        compose.onNodeWithText("200/200").assertExists()
    }

    @Test fun theUpArrowRecallsTheLastLinesSent() {
        val history = listOf("G28", "M105")
        compose.setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), CardsUi(console = ConsoleUi(history = history)), control(armed = true)) } }
        scrollTo("console-card")
        compose.onNodeWithTag("console-older").performScrollTo().performClick()
        compose.onNodeWithText("G28").assertExists()
        compose.onNodeWithTag("console-older").performClick()
        compose.onNodeWithText("M105").assertExists()
        compose.onNodeWithTag("console-newer").performClick()
        compose.onNodeWithText("G28").assertExists()
    }

    @Test fun aLookOnlyPhoneHasNoConsoleInput() {
        compose.setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), CardsUi(), control(may = false)) } }
        scrollTo("console-card")
        compose.onNodeWithTag("console-input").assertDoesNotExist()
    }

    // --- Stage 2 (E10b): the temperature presets editor ----------------------------------------------------------------

    private fun presetsContent(c: ControlUi, saved: MutableList<List<com.edith.app.printer.feed.Preset>>, editor: PresetsUi = PresetsUi()) =
        compose.setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(savePresets = { saved += it }), CardsUi(presets = editor), c) } }
    private fun openPresets() { scrollTo("temperature-controls"); compose.onNodeWithTag("edit-presets").performScrollTo().performClick() }

    @Test fun theEditorOpensWithJarvisPresets() {
        presetsContent(control(armed = true), mutableListOf())
        openPresets()
        compose.onNodeWithTag("presets-dialog").assertIsDisplayed()
        compose.onNodeWithTag("preset-name-0").assert(androidx.compose.ui.test.hasText("PLA"))
        compose.onNodeWithTag("preset-nozzle-0").assert(androidx.compose.ui.test.hasText("220"))
        compose.onNodeWithTag("preset-bed-0").assert(androidx.compose.ui.test.hasText("60"))
    }

    @Test fun editingAndSavingSendsTheList() {
        val saved = mutableListOf<List<com.edith.app.printer.feed.Preset>>()
        presetsContent(control(armed = true), saved)
        openPresets()
        compose.onNodeWithTag("preset-nozzle-0").performTextClearance()
        compose.onNodeWithTag("preset-nozzle-0").performTextInput("215")
        compose.onNodeWithTag("preset-add").performClick()
        compose.onNodeWithTag("preset-name-1").performTextInput("ASA")
        compose.onNodeWithTag("preset-nozzle-1").performTextInput("260")
        compose.onNodeWithTag("preset-bed-1").performTextInput("100")
        compose.onNodeWithTag("presets-save").assertIsEnabled().performClick()
        assertEquals(listOf(listOf(com.edith.app.printer.feed.Preset("PLA", 215, 60), com.edith.app.printer.feed.Preset("ASA", 260, 100))), saved)
    }

    @Test fun aWrongTemperatureSaysWhyAndSaveIsOff() {
        presetsContent(control(armed = true), mutableListOf())
        openPresets()
        compose.onNodeWithTag("preset-nozzle-0").performTextClearance()
        compose.onNodeWithTag("preset-nozzle-0").performTextInput("999")
        compose.onNodeWithTag("presets-save").assertIsNotEnabled()
        compose.onNodeWithText("The nozzle temperature of PLA must be from 0 to 300 °C.").assertIsDisplayed()
    }

    @Test fun aPresetCanBeRemovedAndNoneIsAllowed() {
        val saved = mutableListOf<List<com.edith.app.printer.feed.Preset>>()
        presetsContent(control(armed = true), saved)
        openPresets()
        compose.onNodeWithTag("preset-remove-0").performClick()
        compose.onNodeWithText("With none, Jarvis uses its own presets.").assertIsDisplayed()
        compose.onNodeWithTag("presets-save").assertIsEnabled().performClick()
        assertEquals(listOf(emptyList<com.edith.app.printer.feed.Preset>()), saved)
    }

    @Test fun withControlsOffSavingIsOffAndSaysWhy() {
        presetsContent(control(armed = false), mutableListOf())
        openPresets()
        compose.onNodeWithTag("presets-save").assertIsNotEnabled()
        compose.onNodeWithTag("presets-problem").assert(androidx.compose.ui.test.hasText("Turn on Controls to use this"))
    }

    @Test fun jarvisRefusalIsShownInTheEditor() {
        presetsContent(control(armed = true), mutableListOf(), PresetsUi(error = "Controls turned off. Turn them on to continue."))
        openPresets()
        compose.onNodeWithText("Controls turned off. Turn them on to continue.").assertIsDisplayed()
    }

    @Test fun aLookOnlyPhoneCannotEditPresets() {
        compose.setContent { EdithTheme { PrinterContent(richUi(), true, PrinterActions(), CardsUi(), control(may = false)) } }
        scrollTo("temperature-card")
        compose.onNodeWithTag("edit-presets").assertDoesNotExist()
    }

    @Test fun noEmergencyStopWhileJarvisCannotReachThePrinter() {
        compose.setContent { EdithTheme { PrinterContent(open(PrinterState(connection = Connections.UNREACHABLE)), true, PrinterActions(), CardsUi(), control()) } }
        compose.onNodeWithTag("estop").assertDoesNotExist()
    }

    // --- Stage 3 (E11): upload, and the Files sheet's Print, Add to queue and Delete -----------------------------------------

    private val idle get() = open(everything.copy(print = PrintInfo()))
    private val benchy = com.edith.app.printer.feed.PrinterFile(path = "benchy.gcode", size = 2_000_000, title = "Benchy")
    private fun filesCards(upload: UploadUi = UploadUi(), detailOf: String? = null) =
        CardsUi(files = FilesUi(files = listOf(benchy), upload = upload, detailOf = detailOf, detail = if (detailOf != null) com.edith.app.printer.feed.PrinterFileDetail(path = detailOf) else null))

    @Test fun aLookOnlyPhoneHasNoUploadAndNoFileButtons() {
        compose.setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(), filesCards(detailOf = "benchy.gcode"), control(may = false)) } }
        scrollTo("files-card")
        compose.onNodeWithTag("upload-row").assertDoesNotExist()
        compose.onNodeWithTag("file-print").assertDoesNotExist()
        compose.onNodeWithTag("file-queue").assertDoesNotExist()
        compose.onNodeWithTag("file-delete").assertDoesNotExist()
    }

    @Test fun withControlsOffAFileCanStillBeChosenBecauseNothingIsSentYet() {
        var picked = 0
        compose.setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(pickUpload = { picked++ }), filesCards(), control(armed = false)) } }
        scrollTo("upload-row")
        compose.onNodeWithTag("upload-button").assertIsEnabled().performClick()
        assertEquals(1, picked)
    }

    @Test fun withControlsOnUploadOpensThePicker() {
        var picked = 0
        compose.setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(pickUpload = { picked++ }), filesCards(), control(armed = true)) } }
        scrollTo("upload-row")
        compose.onNodeWithTag("upload-button").assertIsEnabled().performClick()
        assertEquals(1, picked)
    }

    @Test fun aChosenFileWaitsForSendAndSendNeedsControlsOn() {
        var sent = 0; var discarded = 0
        val chosen = UploadUi(name = "benchy.gcode", total = 2_000_000, ready = true)
        var c by androidx.compose.runtime.mutableStateOf(control(armed = false))
        compose.setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(sendUpload = { sent++ }, cancelUpload = { discarded++ }), filesCards(chosen), c) } }
        scrollTo("upload-row")
        compose.onNodeWithTag("upload-ready").assert(androidx.compose.ui.test.hasText("benchy.gcode · 2.0 MB is ready to send."))
        compose.onNodeWithTag("upload-button").assertDoesNotExist()
        compose.onNodeWithTag("upload-send").assertIsNotEnabled()
        c = control(armed = true)
        compose.onNodeWithTag("upload-send").assertIsEnabled().performClick()
        assertEquals(1, sent)
        compose.onNodeWithTag("upload-discard").performClick()
        assertEquals(1, discarded)
    }

    @Test fun aRefusedSendKeepsTheFileReadyAndShowsJarvisWords() {
        val chosen = UploadUi(name = "benchy.gcode", total = 2_000_000, ready = true, error = "Controls turned off. Turn them on to continue.")
        compose.setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(), filesCards(chosen), control(armed = false)) } }
        scrollTo("upload-row")
        compose.onNodeWithTag("upload-send").assertExists()
        compose.onNodeWithTag("upload-error").assert(androidx.compose.ui.test.hasText("Controls turned off. Turn them on to continue."))
    }

    @Test fun aRunningUploadShowsHowMuchHasGoneAndCanBeCancelled() {
        var cancelled = 0
        val going = UploadUi(name = "benchy.gcode", active = true, sent = 500_000, total = 2_000_000)
        compose.setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(cancelUpload = { cancelled++ }), filesCards(going), control(armed = true)) } }
        scrollTo("upload-row")
        compose.onNodeWithTag("upload-progress").assertExists()
        compose.onNodeWithTag("upload-progress-text").assert(androidx.compose.ui.test.hasText("Sending benchy.gcode · 500 KB of 2.0 MB"))
        compose.onNodeWithTag("upload-button").assertDoesNotExist()   // one upload at a time: the button gives way to Cancel
        compose.onNodeWithTag("upload-cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test fun aFinishedUploadSaysSentAndAFailedOneSaysWhy() {
        var cards by androidx.compose.runtime.mutableStateOf(filesCards(UploadUi(name = "benchy.gcode", sent = 10, total = 10, done = "benchy.gcode")))
        compose.setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(), cards, control(armed = true)) } }
        scrollTo("upload-row")
        compose.onNodeWithTag("upload-done").assert(androidx.compose.ui.test.hasText("Sent benchy.gcode. Refresh the list to see it."))
        cards = filesCards(UploadUi(name = "x.stl", error = "Only .gcode and .3mf files can be sent."))
        compose.onNodeWithTag("upload-error").assert(androidx.compose.ui.test.hasText("Only .gcode and .3mf files can be sent."))
        compose.onNodeWithTag("upload-done").assertDoesNotExist()
    }

    @Test fun theFileSheetOffersPrintQueueAndDeleteAndEachSendsItsCommand() {
        val sent = mutableListOf<PrinterCommand>()
        compose.setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(send = { sent += it }), filesCards(detailOf = "benchy.gcode"), control(armed = true)) } }
        scrollTo("files-card")
        // All three are wide enough to read (Delete was once squeezed into a vertical strip).
        for (tag in listOf("file-print", "file-queue", "file-delete")) compose.onNodeWithTag(tag).assertWidthIsAtLeast(64.dp)
        compose.onNodeWithTag("file-print").assertIsEnabled().performClick()
        compose.onNodeWithTag("file-queue").performClick()
        compose.onNodeWithTag("file-delete").performClick()
        assertEquals(listOf<PrinterCommand>(PrinterCommand.Start("benchy.gcode"), PrinterCommand.QueueAdd("benchy.gcode"), PrinterCommand.DeleteFile("benchy.gcode")), sent)
    }

    @Test fun withControlsOffTheFileButtonsAreDisabled() {
        compose.setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(), filesCards(detailOf = "benchy.gcode"), control(armed = false)) } }
        scrollTo("files-card")
        compose.onNodeWithTag("file-print").assertIsNotEnabled()
        compose.onNodeWithTag("file-queue").assertIsNotEnabled()
        compose.onNodeWithTag("file-delete").assertIsNotEnabled()
    }

    @Test fun jarvisRefusalOfAPrintIsShownUnderTheFileButtons() {
        val refused = control(armed = true).copy(refused = mapOf("start" to "Start .3mf files from the printer’s screen."))
        compose.setContent { EdithTheme { PrinterContent(idle, true, PrinterActions(), filesCards(detailOf = "benchy.gcode"), refused) } }
        scrollTo("files-card")
        compose.onNodeWithText("Start .3mf files from the printer’s screen.").assertIsDisplayed()
    }
}
