package com.edith.app.printer.ui

import com.edith.app.printer.feed.ArmState
import com.edith.app.printer.feed.Axis
import com.edith.app.printer.feed.Check
import com.edith.app.printer.feed.CommandAnswer
import com.edith.app.printer.feed.Connections
import com.edith.app.printer.feed.FakePrinterFeed
import com.edith.app.printer.feed.FakePrinterRoutes
import com.edith.app.printer.feed.HomeAxes
import com.edith.app.printer.feed.PrintInfo
import com.edith.app.printer.feed.PrinterAccess
import com.edith.app.printer.feed.PrinterCommand
import com.edith.app.printer.feed.PrinterScreenState
import com.edith.app.printer.feed.PrinterSession
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.feed.PrinterStates
import com.edith.app.printer.feed.PrinterWords
import com.edith.app.printer.feed.QuickCheck
import com.edith.app.printer.feed.Risks
import com.edith.app.printer.feed.SharedPrinter
import com.edith.app.printer.feed.StateAnswer
import com.edith.app.printer.feed.Toolhead
import com.edith.app.testing.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C39 E7: Controls, the confirm flow, pending buttons and the emergency stop. Jarvis decides; this only asks and shows its words. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrinterControlTest {
    private val clock = FakeClock()

    private class Rig(val routes: FakePrinterRoutes, val session: PrinterSession, val control: PrinterControl)

    private fun TestScope.rig(control: Boolean = true): Rig {
        val routes = FakePrinterRoutes()
        val session = PrinterSession(FakePrinterFeed(PrinterAccess.Ready(routes, SharedPrinter(name = "AD5X", control = control))), clock, backgroundScope as CoroutineScope)
        val c = PrinterControl(session, clock, backgroundScope as CoroutineScope)
        session.attach(); runCurrent()
        return Rig(routes, session, c)
    }

    private val printing = PrinterState(connection = Connections.READY, print = PrintInfo(PrinterStates.PRINTING), toolhead = Toolhead(homed = "xyz"))
    private val paused = printing.copy(print = PrintInfo(PrinterStates.PAUSED, paused = true))

    private fun TestScope.say(r: Rig, rev: Long, state: PrinterState = printing, arm: ArmState = ArmState(), quick: Map<String, QuickCheck> = emptyMap()) {
        r.routes.say(StateAnswer(rev = rev, epoch = 1, state = state, arm = arm, quick = quick, serverTime = 0))
        runCurrent()
    }

    private fun cancelCheck() = Check(allowed = true, risk = Risks.DANGER, question = "Cancel the print of Benchy? It can’t be resumed.", confirm = "Cancel print")

    // --- Who may control ------------------------------------------------------------------------------------------------

    @Test fun `a phone Jarvis lets only look has no control`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(control = false); say(r, 1)
        assertFalse(r.control.ui.value.mayControl)
        assertTrue(rig(control = true).also { say(it, 1) }.control.ui.value.mayControl)
    }

    // --- Sending, and the confirm flow ----------------------------------------------------------------------------------

    @Test fun `a command that needs no yes is sent at once as a phone command with a request id`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.control.send(PrinterCommand.Jog(Axis.X, 10.0)); runCurrent()
        val call = r.routes.commandCalls.single()
        assertEquals("phone", call.via); assertFalse(call.confirmed); assertFalse(call.dryRun)
        assertTrue("a request id is sent", !call.requestId.isNullOrBlank())
        assertNull(r.control.ui.value.sheet)
    }

    @Test fun `a command the server wants confirmed opens a sheet with the server's own words and sends nothing yet`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.routes.commandAnswers += CommandAnswer.NeedsConfirm(cancelCheck())
        r.control.send(PrinterCommand.Cancel); runCurrent()
        val sheet = r.control.ui.value.sheet!!
        assertEquals("Cancel the print of Benchy? It can’t be resumed.", sheet.question)
        assertEquals("Cancel print", sheet.confirm)
        assertTrue(sheet.danger)
        assertEquals("a Cancel confirm is answered with Keep printing", "Keep printing", sheet.keepLabel)
        assertEquals("only the unconfirmed ask has been made", 1, r.routes.commandCalls.size)
        assertFalse(r.routes.commandCalls.single().confirmed)
    }

    @Test fun `the owner's yes sends it again with confirmed, under the same request id`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.routes.commandAnswers += CommandAnswer.NeedsConfirm(cancelCheck())
        r.control.send(PrinterCommand.Cancel); runCurrent()
        r.control.confirm(); runCurrent()
        assertEquals(2, r.routes.commandCalls.size)
        val (first, second) = r.routes.commandCalls
        assertFalse(first.confirmed); assertTrue(second.confirmed)
        assertEquals(first.requestId, second.requestId)
        assertNull(r.control.ui.value.sheet)
    }

    @Test fun `keeping going sends nothing and closes the sheet`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.routes.commandAnswers += CommandAnswer.NeedsConfirm(cancelCheck())
        r.control.send(PrinterCommand.Cancel); runCurrent()
        r.control.dismissSheet(); runCurrent()
        assertNull(r.control.ui.value.sheet)
        assertEquals(1, r.routes.commandCalls.size)
        r.control.confirm(); runCurrent()
        assertEquals("a closed sheet cannot be confirmed", 1, r.routes.commandCalls.size)
    }

    @Test fun `a sheet nobody answers closes itself after ten seconds and sends nothing`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.routes.commandAnswers += CommandAnswer.NeedsConfirm(cancelCheck())
        r.control.send(PrinterCommand.Cancel); runCurrent()
        advanceTimeBy(9_999); runCurrent()
        assertTrue(r.control.ui.value.sheet != null)
        advanceTimeBy(2); runCurrent()
        assertNull(r.control.ui.value.sheet)
        assertEquals(1, r.routes.commandCalls.size)
    }

    @Test fun `a new command replaces the sheet that is open`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.routes.commandAnswers += CommandAnswer.NeedsConfirm(cancelCheck())
        r.control.send(PrinterCommand.Cancel); runCurrent()
        r.routes.commandAnswers += CommandAnswer.NeedsConfirm(Check(true, Risks.CONFIRM, question = "Home all axes?", confirm = "Home"))
        r.control.send(PrinterCommand.Home(HomeAxes.ALL)); runCurrent()
        val sheet = r.control.ui.value.sheet!!
        assertEquals("Home all axes?", sheet.question)
        assertFalse(sheet.danger)
        assertEquals("Keep going", sheet.keepLabel)
        assertTrue(sheet.command is PrinterCommand.Home)
    }

    @Test fun `if Jarvis asks again after the yes it is said and nothing else is sent`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.routes.commandAnswers += CommandAnswer.NeedsConfirm(cancelCheck())
        r.routes.commandAnswers += CommandAnswer.NeedsConfirm(cancelCheck())
        r.control.send(PrinterCommand.Cancel); runCurrent()
        r.control.confirm(); runCurrent()
        assertEquals("Jarvis asked again. Try again.", r.control.ui.value.message)
        assertNull(r.control.ui.value.sheet)
        assertEquals(2, r.routes.commandCalls.size)
    }

    @Test fun `the emergency stop asks first and its button says Stop now`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.routes.commandAnswers += CommandAnswer.NeedsConfirm(Check(true, Risks.DANGER, question = "Emergency stop? The printer stops at once.", confirm = "Emergency stop"))
        r.control.send(PrinterCommand.EStop); runCurrent()
        val sheet = r.control.ui.value.sheet!!
        assertEquals("Stop now", sheet.confirm)
        assertTrue(sheet.danger)
        assertTrue("the e-stop is sent whether or not Controls are on", r.routes.commandCalls.single().command is PrinterCommand.EStop)
    }

    @Test fun `the two buttons of a sheet never say the same thing`() {
        for (confirm in listOf("Cancel print", "Home", "Run", "Stop now", "Heat")) {
            val s = ConfirmUi("Q?", confirm, danger = false, PrinterCommand.Pause, "id")
            assertNotEquals(s.confirm, s.keepLabel)
            assertFalse(s.keepLabel.equals("OK", true) || s.keepLabel.equals("Yes", true))
        }
    }

    // --- What the server says -------------------------------------------------------------------------------------------

    @Test fun `Controls turned off under us switches the switch off and says so`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1, arm = ArmState(true, 5_000))
        assertTrue(r.control.ui.value.armed)
        r.routes.commandAnswers += CommandAnswer.ControlsOff
        r.control.send(PrinterCommand.Pause); runCurrent()
        assertFalse(r.control.ui.value.armed)
        assertEquals(PrinterWords.CONTROLS_OFF, r.control.ui.value.message)
    }

    @Test fun `a refusal is shown with Jarvis's reason, also under the control that asked`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.routes.commandAnswers += CommandAnswer.Refused("Home the printer first.")
        r.control.send(PrinterCommand.Jog(Axis.X, 10.0)); runCurrent()
        assertEquals("Home the printer first.", r.control.ui.value.message)
        assertEquals("Home the printer first.", r.control.ui.value.refused["jog"])
        r.control.send(PrinterCommand.Jog(Axis.X, 10.0)); runCurrent()
        assertNull("a new try clears the old refusal", r.control.ui.value.refused["jog"])
    }

    @Test fun `any other failure is shown in its plain words`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.routes.commandAnswers += CommandAnswer.Failed(PrinterWords.PRINTER_UNREACHABLE, "PRINTER_UNREACHABLE")
        r.control.send(PrinterCommand.Pause); runCurrent()
        assertEquals(PrinterWords.PRINTER_UNREACHABLE, r.control.ui.value.message)
    }

    @Test fun `a message is shown once`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.routes.commandAnswers += CommandAnswer.Failed("Nope.", null)
        r.control.send(PrinterCommand.Pause); runCurrent()
        r.control.messageShown()
        assertNull(r.control.ui.value.message)
    }

    // --- Pending buttons ------------------------------------------------------------------------------------------------

    @Test fun `a sent pause shows a spinner until the printer shows paused`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.control.send(PrinterCommand.Pause); runCurrent()
        assertEquals(setOf("pause"), r.control.ui.value.pending)
        say(r, 2, state = printing)
        assertEquals("still printing: still waiting", setOf("pause"), r.control.ui.value.pending)
        say(r, 3, state = paused)
        assertTrue(r.control.ui.value.pending.isEmpty())
    }

    @Test fun `a sent command makes the state be asked again at once`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        val before = r.routes.asked.size
        r.control.send(PrinterCommand.Pause); runCurrent()
        assertNull("asked again with no revision, without waiting", r.routes.asked.drop(before).firstOrNull { it.after == null }?.after)
        assertTrue(r.routes.asked.size > before)
    }

    @Test fun `a spinner gives up at the command's limit`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.control.send(PrinterCommand.Light(1.0)); runCurrent()
        assertEquals(setOf("light"), r.control.ui.value.pending)
        advanceTimeBy(9_999); runCurrent()
        assertEquals(setOf("light"), r.control.ui.value.pending)
        advanceTimeBy(2); runCurrent()
        assertTrue(r.control.ui.value.pending.isEmpty())
    }

    @Test fun `a command with nothing to watch shows no spinner`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.control.send(PrinterCommand.MotorsOff); runCurrent()
        assertTrue(r.control.ui.value.pending.isEmpty())
    }

    @Test fun `homing is done when the axes are homed and the toolhead has moved and stopped`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        val unhomed = printing.copy(toolhead = Toolhead(homed = ""))
        say(r, 1, state = unhomed)
        r.control.send(PrinterCommand.Home(HomeAxes.ALL)); runCurrent()
        assertEquals(setOf("home:xyz"), r.control.ui.value.pending)
        say(r, 2, state = printing.copy(toolhead = Toolhead(homed = "xyz", liveVelocity = 0.0)))
        assertEquals("homed at once, no motion seen yet: still waiting", setOf("home:xyz"), r.control.ui.value.pending)
        say(r, 3, state = printing.copy(toolhead = Toolhead(homed = "xyz", liveVelocity = 80.0)))
        say(r, 4, state = printing.copy(toolhead = Toolhead(homed = "xyz", liveVelocity = 0.0)))
        assertTrue("moved and stopped", r.control.ui.value.pending.isEmpty())
    }

    // --- Controls on and off --------------------------------------------------------------------------------------------

    @Test fun `switching Controls on shows them on until Jarvis's time, and off asks Jarvis to end them`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1)
        r.routes.armUntil = 123_456
        r.control.setControls(true); runCurrent()
        assertTrue(r.control.ui.value.armed)
        assertEquals(123_456L, r.control.ui.value.armUntilMs)
        r.control.setControls(false); runCurrent()
        assertFalse(r.control.ui.value.armed)
        assertEquals(listOf(true, false), r.routes.controlsCalls)
    }

    @Test fun `the countdown follows Jarvis's own answers`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1, arm = ArmState(true, 90_000))
        assertEquals(90_000L, r.control.ui.value.armUntilMs)
        say(r, 2, arm = ArmState(true, 150_000))
        assertEquals("every accepted command extends it", 150_000L, r.control.ui.value.armUntilMs)
        say(r, 3, arm = ArmState(false))
        assertFalse("Jarvis ended it", r.control.ui.value.armed)
    }

    @Test fun `Controls go off when Jarvis cannot be reached`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1, arm = ArmState(true, 90_000))
        r.routes.fail(com.edith.app.jarvis.FeedResult.Unreachable); runCurrent()
        assertTrue(r.session.state.value is PrinterScreenState.JarvisAway)
        assertFalse(r.control.ui.value.armed)
    }

    @Test fun `Controls are ended when the printer stops being ready`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1, arm = ArmState(true, 90_000))
        say(r, 2, state = PrinterState(connection = Connections.KLIPPY_SHUTDOWN), arm = ArmState(true, 90_000))
        assertFalse(r.control.ui.value.armed)
        assertEquals("Jarvis is asked to end them too", listOf(false), r.routes.controlsCalls)
    }

    @Test fun `leaving the page closes the sheet, forgets the spinners and turns Controls off`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); say(r, 1, arm = ArmState(true, 90_000))
        r.control.send(PrinterCommand.Pause); runCurrent()
        r.routes.commandAnswers += CommandAnswer.NeedsConfirm(cancelCheck())
        r.control.send(PrinterCommand.Cancel); runCurrent()
        r.control.detached(); r.session.detach(); runCurrent()
        val ui = r.control.ui.value
        assertNull(ui.sheet); assertTrue(ui.pending.isEmpty()); assertFalse(ui.armed)
        assertEquals("the session ends Controls on the server as the page leaves", listOf(false), r.routes.controlsCalls)
    }

    // --- Which control may be pressed -----------------------------------------------------------------------------------

    private fun ui(quick: Map<String, QuickCheck> = emptyMap(), greyed: Boolean = false) = PrinterUi(
        if (greyed) PrinterScreenState.JarvisAway(StateAnswer(state = printing), "away") else PrinterScreenState.Live(StateAnswer(rev = 1, state = printing, quick = quick), 0, 0),
    )

    @Test fun `with Controls off every control says to turn them on`() {
        assertEquals("Turn on Controls to use this", disabledReason(ui(), ControlUi(mayControl = true, armed = false), "pause"))
        assertEquals("Turn on Controls to use this", disabledReason(ui(), ControlUi(mayControl = true, armed = false)))
    }

    @Test fun `with Controls on, the server's reason shows for a command it will not take`() {
        val quick = mapOf("pause" to QuickCheck(false, "Nothing is printing."), "cancel" to QuickCheck(true))
        val on = ControlUi(mayControl = true, armed = true)
        assertEquals("Nothing is printing.", disabledReason(ui(quick), on, "pause"))
        assertNull(disabledReason(ui(quick), on, "cancel"))
        assertNull("a control with a parameter is open", disabledReason(ui(quick), on))
    }

    @Test fun `with Jarvis away nothing can be pressed`() {
        assertEquals("Jarvis isn't reachable right now.", disabledReason(ui(greyed = true), ControlUi(mayControl = true, armed = true), "pause"))
    }

    @Test fun `each control has its own key`() {
        assertEquals("home:xyz", controlKey(PrinterCommand.Home(HomeAxes.ALL)))
        assertEquals("home:z", controlKey(PrinterCommand.Home(HomeAxes.Z)))
        assertEquals("jog", controlKey(PrinterCommand.Jog(Axis.Y, 1.0)))
        assertEquals("setTemp:extruder", controlKey(PrinterCommand.SetTemp("extruder", 220.0)))
        assertEquals("preset:PLA", controlKey(PrinterCommand.Preset("PLA")))
        assertEquals("pause", controlKey(PrinterCommand.Pause))
    }
}
