package com.edith.app.printer.ui

import com.edith.app.jarvis.FeedResult
import com.edith.app.printer.MemoryPrinterPrefs
import com.edith.app.printer.PrinterCards
import com.edith.app.printer.feed.Connections
import com.edith.app.printer.feed.FakePrinterFeed
import com.edith.app.printer.feed.FakePrinterRoutes
import com.edith.app.printer.feed.MacroGroup
import com.edith.app.printer.feed.MacroInfo
import com.edith.app.printer.feed.MacrosAnswer
import com.edith.app.printer.feed.PrinterAccess
import com.edith.app.printer.feed.PrinterSession
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.feed.PrinterWords
import com.edith.app.printer.feed.SharedPrinter
import com.edith.app.printer.feed.StateAnswer
import com.edith.app.testing.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** C39 E9: the Macros card reads Jarvis's list when it opens, and hide and show go through Jarvis; the console keeps what was sent. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrinterMacroDataTest {
    private val clock = FakeClock()
    private class Rig(val routes: FakePrinterRoutes, val data: PrinterCardData, val prefs: MemoryPrinterPrefs)

    private fun TestScope.rig(): Rig {
        val routes = FakePrinterRoutes()
        val session = PrinterSession(FakePrinterFeed(PrinterAccess.Ready(routes, SharedPrinter(name = "AD5X", control = true))), clock, backgroundScope as CoroutineScope)
        val prefs = MemoryPrinterPrefs()
        val data = PrinterCardData(session, prefs, backgroundScope as CoroutineScope)
        session.attach(); runCurrent()
        routes.say(StateAnswer(rev = 1, epoch = 1, state = PrinterState(connection = Connections.READY)))
        runCurrent()
        return Rig(routes, data, prefs)
    }

    private val list = MacrosAnswer(
        macros = listOf(MacroInfo("CLEAN_NOZZLE", group = "print"), MacroInfo("LOAD_FILAMENT", group = "filament")),
        groups = listOf(MacroGroup("filament", "Filament and nozzle"), MacroGroup("print", "Printing")),
        hidden = listOf("SECRET"),
    )

    @Test fun `the macros are not read until the Macros card opens, and once for each visit`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.macrosAnswer = FeedResult.Ok(list)
        runCurrent()
        assertEquals("nothing is read before the card opens", 0, r.routes.macrosCalls)
        r.data.visible(PrinterCards.MACROS, true); runCurrent()
        assertEquals(1, r.routes.macrosCalls)
        assertEquals(2, r.data.ui.value.macros.answer?.macros?.size)
        r.data.visible(PrinterCards.MACROS, false); r.data.visible(PrinterCards.MACROS, true); runCurrent()
        assertEquals("scrolling away and back does not read it again", 1, r.routes.macrosCalls)
        r.data.detached()
    }

    @Test fun `a macro list Jarvis cannot give shows Jarvis's words`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.macrosAnswer = FeedResult.Err(502, "PRINTER_UNREACHABLE", "x", "")
        r.data.visible(PrinterCards.MACROS, true); runCurrent()
        assertNull(r.data.ui.value.macros.answer)
        assertEquals(PrinterWords.PRINTER_UNREACHABLE, r.data.ui.value.macros.error)
        r.data.detached()
    }

    @Test fun `the filter text is kept`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.data.setMacroQuery("nozzle")
        assertEquals("nozzle", r.data.ui.value.macros.query)
        r.data.detached()
    }

    @Test fun `hiding a macro sends the whole hidden list to Jarvis and reads the list again`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.macrosAnswer = FeedResult.Ok(list)
        r.data.visible(PrinterCards.MACROS, true); runCurrent()
        r.routes.macrosAnswer = FeedResult.Ok(list.copy(hidden = listOf("SECRET", "CLEAN_NOZZLE")))
        r.data.setHiddenMacros(listOf("SECRET", "CLEAN_NOZZLE")); runCurrent()
        assertEquals(listOf<Pair<List<com.edith.app.printer.feed.Preset>?, List<String>?>>(null to listOf("SECRET", "CLEAN_NOZZLE")), r.routes.settingsCalls)
        assertEquals("the list is read again", 2, r.routes.macrosCalls)
        assertEquals(listOf("SECRET", "CLEAN_NOZZLE"), r.data.ui.value.macros.answer?.hidden)
        r.data.detached()
    }

    @Test fun `a refused hide shows Jarvis's words and does not read the list again`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.macrosAnswer = FeedResult.Ok(list)
        r.data.visible(PrinterCards.MACROS, true); runCurrent()
        r.routes.settingsAnswer = FeedResult.Err(409, "CONTROLS_OFF", "x", "")
        r.data.setHiddenMacros(listOf("SECRET", "CLEAN_NOZZLE")); runCurrent()
        assertEquals(PrinterWords.CONTROLS_OFF, r.data.ui.value.macros.error)
        assertEquals(1, r.routes.macrosCalls)
        r.data.detached()
    }

    @Test fun `the lines sent to the console are kept newest first, and a line sent again moves up`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.data.rememberConsoleLine("M105"); r.data.rememberConsoleLine("G28"); r.data.rememberConsoleLine("M105"); runCurrent()
        assertEquals(listOf("M105", "G28"), r.data.ui.value.console.history)
        r.data.detached()
    }

    @Test fun `the history survives the page being left and opened again`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.data.rememberConsoleLine("M105"); runCurrent()
        r.data.detached(); r.data.attached(); runCurrent()
        assertEquals(listOf("M105"), r.data.ui.value.console.history)
    }
}
