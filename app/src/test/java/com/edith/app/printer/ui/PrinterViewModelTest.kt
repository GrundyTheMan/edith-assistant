package com.edith.app.printer.ui

import com.edith.app.printer.MemoryPrinterPrefs
import com.edith.app.printer.PrinterCards
import com.edith.app.printer.feed.FakePrinterFeed
import com.edith.app.printer.feed.FakePrinterRoutes
import com.edith.app.printer.feed.PrinterAccess
import com.edith.app.printer.feed.PrinterAlert
import com.edith.app.printer.feed.PrinterSession
import com.edith.app.printer.feed.PrinterScreenState
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.feed.PrinterStates
import com.edith.app.printer.feed.PrintInfo
import com.edith.app.printer.feed.SharedPrinter
import com.edith.app.printer.feed.StateAnswer
import com.edith.app.printer.feed.Connections
import com.edith.app.testing.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** C39 E4: what the Printer page shows for each state (the words are the chunk's, part 6.2), and its ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrinterViewModelTest {
    private val zone = ZoneId.of("America/Detroit")
    private val nowMs = Instant.parse("2026-11-18T18:00:00Z").toEpochMilli()
    private val clock = FakeClock(Instant.ofEpochMilli(nowMs), zone)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun ui(screen: PrinterScreenState, dismissed: Long? = null) = PrinterUi(screen, "AD5X", PrinterCards.COLLAPSED_AT_FIRST, dismissed, nowMs, zone)
    private fun answer(state: PrinterState, rev: Long = 1) = StateAnswer(rev = rev, state = state)
    private fun live(state: PrinterState) = PrinterScreenState.Live(answer(state), nowMs, 0)

    // --- The six states of part 6.2 ----------------------------------------------------------------------------------

    @Test fun `no Jarvis asks to pair`() {
        val u = ui(PrinterScreenState.NoJarvis)
        assertEquals("Pair Edith with Jarvis to use the printer away from home.", u.message)
        assertFalse(u.showsCards)
    }

    @Test fun `a printer Jarvis does not share says where to switch it on`() {
        val u = ui(PrinterScreenState.NotShared)
        assertTrue(u.message!!.startsWith("Jarvis isn't sharing the printer with this phone."))
        assertTrue(u.message!!.contains("Let paired phones see the printer"))
    }

    @Test fun `connecting shows no message and a connecting chip`() {
        val u = ui(PrinterScreenState.Connecting)
        assertNull(u.message)
        assertEquals(ConnectionChip("Connecting…", Tone.Muted), u.chip)
        assertFalse(u.showsCards)
    }

    @Test fun `a ready printer is connected, with no banner, and not greyed`() {
        val u = ui(live(PrinterState(connection = Connections.READY)))
        assertEquals(ConnectionChip("Connected", Tone.Good), u.chip)
        assertNull(u.banner)
        assertNull(u.message)
        assertFalse(u.greyed)
        assertTrue(u.showsCards)
    }

    @Test fun `a printer Jarvis cannot reach is greyed with how long ago it was seen`() {
        val u = ui(live(PrinterState(connection = Connections.UNREACHABLE, lastSeen = nowMs - 125_000)))
        assertEquals(ConnectionChip("Printer not answering (last seen 2 min ago)", Tone.Bad), u.chip)
        assertEquals("Jarvis can't reach the printer (is it on?). Showing what it last saw 2 min ago.", u.banner)
        assertTrue(u.greyed)
        assertTrue(u.showsCards)
    }

    @Test fun `a printer never seen is greyed without an age`() {
        val u = ui(live(PrinterState(connection = Connections.UNREACHABLE)))
        assertEquals("Printer not answering", u.chip.text)
        assertEquals("Jarvis can't reach the printer (is it on?). Showing what it last saw.", u.banner)
    }

    @Test fun `Klipper trouble shows Jarvis's own words`() {
        val shutdown = ui(live(PrinterState(connection = Connections.KLIPPY_SHUTDOWN, message = "Heater not heating")))
        assertEquals("Klipper: shutdown – Heater not heating", shutdown.chip.text)
        assertEquals("Heater not heating", shutdown.banner)
        assertFalse("the printer is reachable: cards stay in colour", shutdown.greyed)
        assertEquals("Klipper is starting…", ui(live(PrinterState(connection = Connections.KLIPPY_STARTUP))).chip.text)
        assertEquals("Klipper: error", ui(live(PrinterState(connection = Connections.KLIPPY_ERROR))).chip.text)
    }

    @Test fun `no printer set up in Jarvis is its own message`() {
        val u = ui(live(PrinterState(connection = Connections.NONE)))
        assertEquals("No printer is set up in Jarvis.", u.message)
        assertFalse(u.showsCards)
    }

    @Test fun `Jarvis away keeps the last page greyed with the reason and a way to try again`() {
        val last = answer(PrinterState(connection = Connections.READY))
        val u = ui(PrinterScreenState.JarvisAway(last, "Edith can't reach Jarvis. Is the PC on?"))
        assertEquals(ConnectionChip("Jarvis not reachable", Tone.Bad), u.chip)
        assertEquals("Edith can't reach Jarvis. Is the PC on?", u.banner)
        assertTrue(u.greyed)
        assertTrue(u.showsCards)
        assertNull("the cards are shown, so there is no full-page message", u.message)
    }

    @Test fun `a phone Jarvis removed says so in the header, not that Jarvis is unreachable`() {
        val u = ui(PrinterScreenState.JarvisAway(null, com.edith.app.printer.feed.PrinterWords.UNAUTHORIZED))
        assertEquals(ConnectionChip("Jarvis removed this phone", Tone.Bad), u.chip)
    }

    @Test fun `Jarvis away with nothing to show is a full-page message`() {
        val u = ui(PrinterScreenState.JarvisAway(null, "Jarvis removed this phone: pair it again."))
        assertEquals("Jarvis removed this phone: pair it again.", u.message)
        assertFalse(u.showsCards)
    }

    @Test fun `the printer's own error line shows until the owner dismisses that one`() {
        val state = PrinterState(connection = Connections.READY, alert = PrinterAlert("Filament runout", time = 42))
        assertEquals("Filament runout", ui(live(state)).alert?.text)
        assertNull(ui(live(state), dismissed = 42).alert)
        assertNotNull("a newer error is shown again", ui(live(state.copy(alert = PrinterAlert("Jam", 43))), dismissed = 42).alert)
    }

    // --- The ViewModel ------------------------------------------------------------------------------------------------

    private class Fixture(val routes: FakePrinterRoutes, val vm: PrinterViewModel, val prefs: MemoryPrinterPrefs, val session: PrinterSession)

    // A page that stays attached keeps a 30 second refresh going; every test that attaches ends with detach(), or the test never finishes.
    private fun TestScopeFixture(scope: kotlinx.coroutines.CoroutineScope): Fixture {
        val routes = FakePrinterRoutes()
        val session = PrinterSession(FakePrinterFeed(PrinterAccess.Ready(routes, SharedPrinter(name = "FlashForge AD5X"))), clock, scope)
        val prefs = MemoryPrinterPrefs()
        return Fixture(routes, PrinterViewModel(session, prefs, clock), prefs, session)
    }

    @Test fun `the page takes its name and state from Jarvis once it is on screen`() = runTest(UnconfinedTestDispatcher()) {
        val f = TestScopeFixture(backgroundScope)
        backgroundScope.launch { f.vm.ui.collect {} }
        assertEquals("Printer", f.vm.ui.value.name)
        f.vm.attach(); runCurrent()
        f.routes.say(answer(PrinterState(connection = Connections.READY, print = PrintInfo(PrinterStates.PRINTING, title = "Benchy", progress = 0.4)), rev = 7))
        runCurrent()
        assertEquals("FlashForge AD5X", f.vm.ui.value.name)
        assertEquals("Benchy", f.vm.ui.value.state?.print?.title)
        assertEquals(ConnectionChip("Connected", Tone.Good), f.vm.ui.value.chip)
        f.vm.detach()
    }

    @Test fun `folding and opening a card is remembered`() = runTest(UnconfinedTestDispatcher()) {
        val f = TestScopeFixture(backgroundScope)
        backgroundScope.launch { f.vm.ui.collect {} }
        assertFalse(PrinterCards.STATUS in f.vm.ui.value.collapsed)
        assertTrue(PrinterCards.FILES in f.vm.ui.value.collapsed)
        f.vm.toggleCard(PrinterCards.STATUS); runCurrent()
        assertTrue(PrinterCards.STATUS in f.vm.ui.value.collapsed)
        f.vm.toggleCard(PrinterCards.FILES); runCurrent()
        assertFalse(PrinterCards.FILES in f.vm.ui.value.collapsed)
    }

    @Test fun `a dismissed error stays hidden until the page is opened again`() = runTest(UnconfinedTestDispatcher()) {
        val f = TestScopeFixture(backgroundScope)
        backgroundScope.launch { f.vm.ui.collect {} }
        f.vm.attach(); runCurrent()
        f.routes.say(answer(PrinterState(connection = Connections.READY, alert = PrinterAlert("Jam", 9)))); runCurrent()
        assertEquals("Jam", f.vm.ui.value.alert?.text)
        f.vm.dismissAlert(9); runCurrent()
        assertNull(f.vm.ui.value.alert)
        f.vm.detach(); f.vm.attach(); runCurrent()
        assertEquals("the page was closed and opened: the error shows again", "Jam", f.vm.ui.value.alert?.text)
        f.vm.detach()
    }

    @Test fun `leaving the page stops the questions`() = runTest(UnconfinedTestDispatcher()) {
        val f = TestScopeFixture(backgroundScope)
        f.vm.attach(); runCurrent()
        f.routes.say(1); runCurrent()
        val asked = f.routes.asked.size
        f.vm.detach(); runCurrent()
        f.routes.say(2); runCurrent()
        assertEquals(asked, f.routes.asked.size)
    }

    @Test fun `no Jarvis shows the pairing message through the ViewModel too`() = runTest(UnconfinedTestDispatcher()) {
        val session = PrinterSession(FakePrinterFeed(PrinterAccess.NoJarvis), clock, backgroundScope)
        val vm = PrinterViewModel(session, MemoryPrinterPrefs(), clock)
        backgroundScope.launch { vm.ui.collect {} }
        vm.attach(); runCurrent()
        assertEquals(PrinterUi.NO_JARVIS, vm.ui.value.message)
        vm.detach()
    }
}
