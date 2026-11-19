package com.edith.app.printer.ui

import com.edith.app.jarvis.FeedResult
import com.edith.app.printer.MemoryPrinterPrefs
import com.edith.app.printer.PrinterCards
import com.edith.app.printer.feed.Connections
import com.edith.app.printer.feed.ConsoleAnswer
import com.edith.app.printer.feed.ConsoleLine
import com.edith.app.printer.feed.FakePrinterFeed
import com.edith.app.printer.feed.FakePrinterRoutes
import com.edith.app.printer.feed.FilesAnswer
import com.edith.app.printer.feed.HistoryAnswer
import com.edith.app.printer.feed.HistoryJob
import com.edith.app.printer.feed.HistoryTotals
import com.edith.app.printer.feed.PrinterAccess
import com.edith.app.printer.feed.PrinterCamera
import com.edith.app.printer.feed.PrinterFile
import com.edith.app.printer.feed.PrinterSession
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.feed.SharedPrinter
import com.edith.app.printer.feed.StateAnswer
import com.edith.app.printer.feed.TempSeries
import com.edith.app.printer.feed.Heater
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C39 E5: what the cards read from Jarvis, and when. The file list is never asked for before Load files; the camera only while open. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrinterCardDataTest {
    private val clock = FakeClock()
    private val cameras = listOf(PrinterCamera("video", "Camera"), PrinterCamera("screen", "Printer screen"))

    private class Rig(val routes: FakePrinterRoutes, val session: PrinterSession, val data: PrinterCardData)

    private fun TestScope.rig(prefs: MemoryPrinterPrefs = MemoryPrinterPrefs()): Rig {
        val routes = FakePrinterRoutes()
        val session = PrinterSession(FakePrinterFeed(PrinterAccess.Ready(routes, SharedPrinter(name = "AD5X"))), clock, backgroundScope as CoroutineScope)
        val data = PrinterCardData(session, prefs, backgroundScope as CoroutineScope)
        session.attach()
        return Rig(routes, session, data)
    }

    private fun printerState(connection: String = Connections.READY) = PrinterState(
        connection = connection, cameras = cameras, heaters = listOf(Heater("extruder", "Nozzle", 24.0, 0.0)),
    )

    private fun say(r: Rig, rev: Long, consoleSeq: Long = 0, state: PrinterState = printerState()) {
        r.routes.say(StateAnswer(rev = rev, epoch = 1, state = state, consoleSeq = consoleSeq))
    }

    private fun TestScope.ready(r: Rig, consoleSeq: Long = 0): Rig { runCurrent(); say(r, 1, consoleSeq); runCurrent(); return r }

    // --- Files: nothing until asked ---------------------------------------------------------------------------------

    @Test fun `no file list is requested until Load files, however many cards are open`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig())
        for (card in PrinterCards.ALL) r.data.visible(card, true)
        runCurrent(); advanceTimeBy(30_000)
        assertTrue("no file list was asked for", r.routes.filesFresh.isEmpty())
        assertNull(r.data.ui.value.files.files)
        r.data.detached()
    }

    @Test fun `Load files asks once, and Refresh asks for a fresh list`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig())
        r.routes.filesAnswer = FeedResult.Ok(FilesAnswer(listOf(PrinterFile("a.gcode", 1, 5, "A", "gcode", true)), cachedAt = 99))
        r.data.loadFiles(fresh = false); runCurrent()
        assertEquals(listOf(false), r.routes.filesFresh)
        assertEquals(1, r.data.ui.value.files.files?.size)
        assertEquals(99L, r.data.ui.value.files.cachedAt)
        r.data.loadFiles(fresh = true); runCurrent()
        assertEquals(listOf(false, true), r.routes.filesFresh)
        r.data.detached()
    }

    @Test fun `a refused listing shows Jarvis's words and leaves the list unset`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig())
        r.routes.filesAnswer = FeedResult.Err(422, "REFUSED", "Not while printing: the printer would stop answering for several seconds.", "")
        r.data.loadFiles(false); runCurrent()
        assertEquals("Not while printing: the printer would stop answering for several seconds.", r.data.ui.value.files.error)
        assertNull(r.data.ui.value.files.files)
        assertFalse(r.data.ui.value.files.loading)
        r.data.detached()
    }

    @Test fun `opening a file asks for its detail, and closing forgets it`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig())
        r.data.openFile("a.gcode"); runCurrent()
        assertEquals(listOf("a.gcode"), r.routes.detailPaths)
        assertEquals("a.gcode", r.data.ui.value.files.detailOf)
        r.data.closeFile()
        assertNull(r.data.ui.value.files.detailOf)
        r.data.detached()
    }

    @Test fun `search and the printed-before switch are kept`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig())
        r.data.setFileQuery("benchy"); r.data.setPrintedOnly(false)
        assertEquals("benchy", r.data.ui.value.files.query)
        assertFalse(r.data.ui.value.files.printedOnly)
        r.data.detached()
    }

    // --- Temperatures, history, console: loaded when opened ----------------------------------------------------------

    @Test fun `the chart is read once when Temperatures opens and then grows with the live answers`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig())
        r.routes.temperaturesAnswer = FeedResult.Ok(listOf(TempSeries("extruder", "Nozzle", listOf(20.0, 21.0, 22.0))))
        assertEquals("nothing is read before the card opens", 0, r.routes.temperaturesCalls)
        r.data.visible(PrinterCards.TEMPERATURES, true); runCurrent()
        assertEquals(1, r.routes.temperaturesCalls)
        assertEquals(listOf(20.0, 21.0, 22.0), r.data.ui.value.chart.single { it.id == "extruder" }.values)
        r.data.visible(PrinterCards.TEMPERATURES, false); r.data.visible(PrinterCards.TEMPERATURES, true); runCurrent()
        assertEquals("scrolling away and back does not read it again", 1, r.routes.temperaturesCalls)
        say(r, 2); runCurrent()
        assertEquals(4, r.data.ui.value.chart.single { it.id == "extruder" }.values.size)
        r.data.detached()
    }

    @Test fun `after leaving the page the chart is read again, because it has a gap`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig())
        r.data.visible(PrinterCards.TEMPERATURES, true); runCurrent()
        r.data.detached(); r.data.attached(); runCurrent()
        assertEquals(2, r.routes.temperaturesCalls)
        r.data.detached()
    }

    @Test fun `a legend tap hides a line and another shows it`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig())
        r.data.toggleSeries("extruder")
        assertEquals(setOf("extruder"), r.data.ui.value.chartHidden)
        r.data.toggleSeries("extruder")
        assertTrue(r.data.ui.value.chartHidden.isEmpty())
        r.data.detached()
    }

    private fun job(n: Int) = HistoryJob("j$n", "f$n.gcode", "Job $n", "completed", start = n.toLong())

    @Test fun `history loads the first 50 when it opens, and More asks for the next page`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig())
        r.routes.historyAnswer = { start -> FeedResult.Ok(HistoryAnswer((start until start + 50).map(::job), HistoryTotals(jobs = 966))) }
        r.data.visible(PrinterCards.HISTORY, true); runCurrent()
        assertEquals(listOf(0), r.routes.historyStarts)
        assertEquals(50, r.data.ui.value.history.jobs.size)
        assertTrue(r.data.ui.value.history.more)
        r.data.moreHistory(); runCurrent()
        assertEquals(listOf(0, 50), r.routes.historyStarts)
        assertEquals(100, r.data.ui.value.history.jobs.size)
        assertEquals(966, r.data.ui.value.history.totals?.jobs)
        r.data.detached()
    }

    @Test fun `a short last page means no More`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig())
        r.routes.historyAnswer = { FeedResult.Ok(HistoryAnswer((0 until 3).map(::job))) }
        r.data.visible(PrinterCards.HISTORY, true); runCurrent()
        assertFalse(r.data.ui.value.history.more)
        r.data.detached()
    }

    private fun line(seq: Long, text: String) = ConsoleLine(seq, seq, text, "response")

    @Test fun `the console reads 200 lines when it opens and then only the new ones when Jarvis says there are`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig(), consoleSeq = 5)
        r.routes.consoleAnswer = { after -> if (after == null) FeedResult.Ok(ConsoleAnswer((1L..5L).map { line(it, "l$it") }, 5)) else FeedResult.Ok(ConsoleAnswer(listOf(line(6, "l6"), line(7, "l7")), 7)) }
        r.data.visible(PrinterCards.CONSOLE, true); runCurrent()
        assertEquals(listOf<Long?>(null), r.routes.consoleAfters)
        assertEquals(5, r.data.ui.value.console.lines.size)
        advanceTimeBy(60_000); runCurrent()
        assertEquals("no polling: nothing new was announced", listOf<Long?>(null), r.routes.consoleAfters)
        say(r, 2, consoleSeq = 7); runCurrent()
        assertEquals(listOf<Long?>(null, 5L), r.routes.consoleAfters)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), r.data.ui.value.console.lines.map { it.seq })
        r.data.detached()
    }

    @Test fun `Clear empties the view only and the hide-values switch is remembered`() = runTest(UnconfinedTestDispatcher()) {
        val prefs = MemoryPrinterPrefs()
        val r = ready(rig(prefs), consoleSeq = 1)
        r.routes.consoleAnswer = { FeedResult.Ok(ConsoleAnswer(listOf(line(1, "x")), 1)) }
        r.data.visible(PrinterCards.CONSOLE, true); runCurrent()
        r.data.clearConsole()
        assertTrue(r.data.ui.value.console.lines.isEmpty())
        assertTrue(r.data.ui.value.console.hideValues)
        r.data.setHideValues(false); runCurrent()
        assertFalse(r.data.ui.value.console.hideValues)
        r.data.detached()
    }

    // --- The camera -------------------------------------------------------------------------------------------------

    @Test fun `the camera is asked for about once a second while its card is open, and not at all before or after`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig())
        advanceTimeBy(5_000); runCurrent()
        assertTrue("closed card: no pictures", r.routes.snapshotIds.isEmpty())
        r.data.visible(PrinterCards.CAMERA, true); runCurrent()
        assertEquals(1, r.routes.snapshotIds.size)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(4, r.routes.snapshotIds.size)
        assertEquals(listOf("video"), r.routes.snapshotIds.distinct())
        assertNotNull(r.data.ui.value.camera.frame)
        r.data.visible(PrinterCards.CAMERA, false); runCurrent()
        val after = r.routes.snapshotIds.size
        advanceTimeBy(10_000); runCurrent()
        assertEquals("folding stops it", after, r.routes.snapshotIds.size)
        r.data.detached()
    }

    @Test fun `leaving the page stops the camera too`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig())
        r.data.visible(PrinterCards.CAMERA, true); runCurrent()
        r.data.detached()
        val n = r.routes.snapshotIds.size
        advanceTimeBy(10_000); runCurrent()
        assertEquals(n, r.routes.snapshotIds.size)
    }

    @Test fun `two failed pictures in a row say the camera is offline, and one good one clears it`() = runTest(UnconfinedTestDispatcher()) {
        val r = ready(rig())
        var good = false
        r.routes.snapshotAnswer = { if (good) FeedResult.Ok(byteArrayOf(9)) else FeedResult.Unreachable }
        r.data.visible(PrinterCards.CAMERA, true); runCurrent()
        assertFalse("one failure is not enough", r.data.ui.value.camera.offline)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(r.data.ui.value.camera.offline)
        good = true
        advanceTimeBy(1_000); runCurrent()
        assertFalse(r.data.ui.value.camera.offline)
        r.data.visible(PrinterCards.CAMERA, false); r.data.detached()
    }

    @Test fun `no pictures are asked for while the printer is not ready`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        runCurrent(); say(r, 1, state = printerState(Connections.UNREACHABLE)); runCurrent()
        r.data.visible(PrinterCards.CAMERA, true); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertTrue(r.routes.snapshotIds.isEmpty())
        r.data.visible(PrinterCards.CAMERA, false); r.data.detached()
    }

    @Test fun `the camera the owner chose is used, and is remembered`() = runTest(UnconfinedTestDispatcher()) {
        val prefs = MemoryPrinterPrefs()
        val r = ready(rig(prefs))
        r.data.chooseCamera("screen"); runCurrent()
        r.data.visible(PrinterCards.CAMERA, true); runCurrent()
        assertEquals("screen", r.routes.snapshotIds.last())
        assertEquals("screen", r.data.ui.value.camera.cameraId)
        r.data.visible(PrinterCards.CAMERA, false); r.data.detached()
    }
}
