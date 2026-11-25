package com.edith.app.printer.ui

import com.edith.app.jarvis.FeedResult
import com.edith.app.printer.MemoryPrinterPrefs
import com.edith.app.printer.feed.Connections
import com.edith.app.printer.feed.FakePrinterFeed
import com.edith.app.printer.feed.FakePrinterRoutes
import com.edith.app.printer.feed.FilesAnswer
import com.edith.app.printer.feed.PrinterAccess
import com.edith.app.printer.feed.PrinterCommand
import com.edith.app.printer.feed.PrinterFile
import com.edith.app.printer.feed.PrinterSession
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.feed.PrinterUpload
import com.edith.app.printer.feed.PrinterWords
import com.edith.app.printer.feed.SharedPrinter
import com.edith.app.printer.feed.StateAnswer
import com.edith.app.testing.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlinx.io.asSource
import kotlinx.io.buffered

/** C39 E11: sending a file from the phone, and what the Files sheet's commands do to the list. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrinterUploadDataTest {
    private val clock = FakeClock()
    private class Rig(val routes: FakePrinterRoutes, val session: PrinterSession, val data: PrinterCardData)

    private fun TestScope.rig(): Rig {
        val routes = FakePrinterRoutes()
        val session = PrinterSession(FakePrinterFeed(PrinterAccess.Ready(routes, SharedPrinter(name = "AD5X", control = true))), clock, backgroundScope as CoroutineScope)
        val data = PrinterCardData(session, MemoryPrinterPrefs(), backgroundScope as CoroutineScope, UnconfinedTestDispatcher(testScheduler))
        session.attach(); runCurrent()
        routes.say(StateAnswer(rev = 1, epoch = 1, state = PrinterState(connection = Connections.READY)))
        runCurrent()
        return Rig(routes, session, data)
    }

    private val gcode = ByteArray(5_000) { (it % 251).toByte() }
    /** Picks the file and presses Send, as the owner does. */
    private fun Rig.send(name: String = "benchy.gcode", size: Long = gcode.size.toLong(), bytes: ByteArray = gcode) {
        data.chooseUpload(name, size) { ByteArrayInputStream(bytes).asSource().buffered() }
        data.sendUpload()
    }
    private fun Rig.choose(name: String = "benchy.gcode", size: Long = gcode.size.toLong(), bytes: ByteArray = gcode) = data.chooseUpload(name, size) { ByteArrayInputStream(bytes).asSource().buffered() }

    @Test fun `an upload sends the exact bytes under the name and shows what Jarvis saved`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.send(); runCurrent(); settle()
        val call = r.routes.uploadCalls.single()
        assertEquals("benchy.gcode", call.name)
        assertEquals(gcode.size.toLong(), call.size)
        assertArrayEquals(gcode, call.bytes)
        val u = r.data.ui.value.files.upload
        assertEquals("benchy.gcode", u.done)
        assertFalse(u.active)
        assertNull(u.error)
        assertFalse("the list shows all files, so the new one is not hidden by Printed before", r.data.ui.value.files.printedOnly)
        r.data.detached()
    }

    /** Lets everything that is ready run (the file is opened on the test's own dispatcher, so there is no real thread to wait for). */
    private fun TestScope.settle() = runCurrent()

    @Test fun `a wrong name or size is told at once and nothing is sent`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.choose(name = "model.stl"); runCurrent()
        assertEquals(PrinterUpload.NAME_WORDS, r.data.ui.value.files.upload.error)
        r.choose(size = PrinterUpload.MAX_BYTES + 1); runCurrent()
        assertEquals(PrinterUpload.TOO_BIG_WORDS, r.data.ui.value.files.upload.error)
        r.choose(size = 0); runCurrent()
        assertEquals(PrinterUpload.EMPTY_WORDS, r.data.ui.value.files.upload.error)
        r.choose(size = -1); runCurrent()
        assertEquals(PrinterUpload.SIZE_UNKNOWN_WORDS, r.data.ui.value.files.upload.error)
        assertTrue(r.routes.uploadCalls.isEmpty())
        r.data.detached()
    }

    @Test fun `a file that cannot be opened says so`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.data.chooseUpload("a.gcode", 10) { null }
        r.data.sendUpload()
        settle()
        assertEquals(PrinterUpload.UNREADABLE_WORDS, r.data.ui.value.files.upload.error)
        r.data.chooseUpload("a.gcode", 10) { throw java.io.FileNotFoundException() }
        r.data.sendUpload()
        settle()
        assertEquals(PrinterUpload.UNREADABLE_WORDS, r.data.ui.value.files.upload.error)
        assertTrue(r.routes.uploadCalls.isEmpty())
        r.data.detached()
    }

    @Test fun `Jarvis refusing the upload is shown in its words and nothing is marked done`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.uploadAnswer = FeedResult.Err(409, "CONTROLS_OFF", "x", "")
        r.send(); settle()
        val u = r.data.ui.value.files.upload
        assertEquals(PrinterWords.CONTROLS_OFF, u.error)
        assertNull(u.done)
        assertFalse(u.active)
        r.routes.uploadAnswer = FeedResult.Unreachable
        r.send(); settle()
        assertEquals(PrinterWords.UNREACHABLE, r.data.ui.value.files.upload.error)
        r.data.detached()
    }

    @Test fun `progress is shown while the upload goes and Cancel stops it and leaves nothing on screen`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.uploadGate = CompletableDeferred()   // the fake reports all its bytes, then waits: an upload in the middle of its answer
        r.send(); settle()
        val going = r.data.ui.value.files.upload
        assertTrue(going.active)
        assertEquals(gcode.size.toLong(), going.sent)
        assertEquals(gcode.size.toLong(), going.total)
        r.data.cancelUpload(); settle()
        assertEquals(UploadUi(), r.data.ui.value.files.upload)
        assertTrue("a cancelled upload is not recorded as sent", r.routes.uploadCalls.isEmpty())
        r.data.detached()
    }

    @Test fun `only one upload goes at a time`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.uploadGate = CompletableDeferred()
        r.send(name = "one.gcode"); settle()
        r.send(name = "two.gcode"); settle()   // ignored: one is going
        assertEquals("one.gcode", r.data.ui.value.files.upload.name)
        r.routes.uploadGate!!.complete(Unit); settle()
        assertEquals(listOf("one.gcode"), r.routes.uploadCalls.map { it.name })
        r.data.detached()
    }

    @Test fun `leaving the page stops an upload and says so on return`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.uploadGate = CompletableDeferred()
        r.send(); settle()
        r.data.detached()
        val u = r.data.ui.value.files.upload
        assertFalse(u.active)
        assertNotNull(u.error)
        assertEquals("benchy.gcode", u.name)
        assertTrue(r.routes.uploadCalls.isEmpty())
    }

    @Test fun `the result is read and cleared`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.send(); settle()
        assertNotNull(r.data.ui.value.files.upload.done)
        r.data.clearUpload()
        assertEquals(UploadUi(), r.data.ui.value.files.upload)
        r.data.detached()
    }

    @Test fun choosing_a_file_sends_nothing_until_send_is_pressed() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.choose(); settle()
        val u = r.data.ui.value.files.upload
        assertTrue(u.ready); assertFalse(u.active); assertEquals("benchy.gcode", u.name); assertEquals(gcode.size.toLong(), u.total)
        assertTrue(r.routes.uploadCalls.isEmpty())
        r.data.sendUpload(); settle()
        assertEquals(1, r.routes.uploadCalls.size)
        assertFalse(r.data.ui.value.files.upload.ready)
        r.data.detached()
    }

    @Test fun send_with_nothing_chosen_does_nothing() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.data.sendUpload(); settle()
        assertTrue(r.routes.uploadCalls.isEmpty())
        assertEquals(UploadUi(), r.data.ui.value.files.upload)
        r.data.detached()
    }

    @Test fun discard_drops_the_chosen_file() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.choose(); r.data.cancelUpload(); r.data.sendUpload(); settle()
        assertEquals(UploadUi(), r.data.ui.value.files.upload)
        assertTrue(r.routes.uploadCalls.isEmpty())
        r.data.detached()
    }

    @Test fun controls_off_at_send_keeps_the_file_chosen_so_send_can_be_pressed_again() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.uploadAnswer = FeedResult.Err(409, "CONTROLS_OFF", "x", "")
        r.send(); settle()
        val u = r.data.ui.value.files.upload
        assertTrue("still chosen", u.ready)
        assertEquals(PrinterWords.CONTROLS_OFF, u.error)
        r.routes.uploadAnswer = FeedResult.Ok("")
        r.data.sendUpload(); settle()
        assertEquals("benchy.gcode", r.data.ui.value.files.upload.done)
        assertEquals("both attempts reached Jarvis, the refused one and the retry", 2, r.routes.uploadCalls.size)
        assertArrayEquals(gcode, r.routes.uploadCalls.last().bytes)
        r.data.detached()
    }

    @Test fun leaving_the_page_drops_a_chosen_file_quietly() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.choose(); r.data.detached()
        assertEquals(UploadUi(), r.data.ui.value.files.upload)
        r.data.sendUpload(); settle()
        assertTrue(r.routes.uploadCalls.isEmpty())
    }

    @Test fun a_second_pick_replaces_the_first() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.choose(name = "one.gcode"); r.choose(name = "two.gcode")
        r.data.sendUpload(); settle()
        assertEquals(listOf("two.gcode"), r.routes.uploadCalls.map { it.name })
        r.data.detached()
    }

    // --- The Files sheet's commands -----------------------------------------------------------------------------------------

    private val files = listOf(PrinterFile(path = "a.gcode", title = "A"), PrinterFile(path = "b.gcode", title = "B"))
    private fun Rig.listed() { routes.filesAnswer = FeedResult.Ok(FilesAnswer(files, cachedAt = 1)); data.loadFiles(false); data.openFile("a.gcode") }

    @Test fun `a deleted file leaves the list and the sheet closes, without asking Jarvis for the list again`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); r.listed(); runCurrent()
        r.data.commandSent(PrinterCommand.DeleteFile("a.gcode"))
        val f = r.data.ui.value.files
        assertEquals(listOf("b.gcode"), f.files?.map { it.path })
        assertNull(f.detailOf)
        assertEquals(listOf(false), r.routes.filesFresh)   // listed once, by the owner; the delete did not list again
        r.data.detached()
    }

    @Test fun `print and add to queue close the sheet and keep the list`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); r.listed(); runCurrent()
        r.data.commandSent(PrinterCommand.Start("a.gcode"))
        assertNull(r.data.ui.value.files.detailOf)
        assertEquals(2, r.data.ui.value.files.files?.size)
        r.data.openFile("b.gcode"); runCurrent()
        r.data.commandSent(PrinterCommand.QueueAdd("b.gcode"))
        assertNull(r.data.ui.value.files.detailOf)
        assertEquals(2, r.data.ui.value.files.files?.size)
        r.data.detached()
    }

    @Test fun `any other command leaves the sheet alone`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(); r.listed(); runCurrent()
        r.data.commandSent(PrinterCommand.Pause)
        assertEquals("a.gcode", r.data.ui.value.files.detailOf)
        r.data.detached()
    }
}
