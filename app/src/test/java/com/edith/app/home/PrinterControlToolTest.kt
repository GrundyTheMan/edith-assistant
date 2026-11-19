package com.edith.app.home

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.printer.feed.Check
import com.edith.app.printer.feed.CommandAnswer
import com.edith.app.printer.feed.FakePrinterFeed
import com.edith.app.printer.feed.FakePrinterRoutes
import com.edith.app.printer.feed.PrinterAccess
import com.edith.app.printer.feed.PrinterCommand
import com.edith.app.printer.feed.PrinterWords
import com.edith.app.printer.feed.Risks
import com.edith.app.printer.feed.SharedPrinter
import com.edith.app.settings.Settings
import com.edith.app.testing.FakeClock
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C39 E10: `printer_control` in chat. Jarvis's words on the card, a card for every call, cancel by a tap only, never offered without control. */
class PrinterControlToolTest {
    private val routes = FakePrinterRoutes()
    private val shared = SharedPrinter(name = "FlashForge AD5X", control = true)
    private fun tool(access: PrinterAccess = PrinterAccess.Ready(routes, shared), may: Boolean = true) = PrinterControlTool(FakePrinterFeed(access)) { may }
    private val ctx = ToolContext(FakeClock(), Settings(), AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined))

    private fun args(t: PrinterControlTool, vararg pairs: Pair<String, String>) = ToolArgs(buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }, t.spec)
    private fun ok(r: ToolResult) = (r as ToolResult.Ok).text
    private fun err(r: ToolResult) = (r as ToolResult.Error).text

    private val pauseCheck = Check(true, Risks.CONFIRM, question = "Pause the print of Benchy?", confirm = "Pause")
    private val cancelCheck = Check(true, Risks.DANGER, question = "Cancel the print of Benchy? It can’t be resumed.", confirm = "Cancel print")

    // --- The card ---------------------------------------------------------------------------------------------------------

    @Test fun `the card says what Jarvis says`() = runBlocking {
        val t = tool()
        routes.commandAnswers += CommandAnswer.Checked(pauseCheck)
        val card = t.confirmation(args(t, "action" to "pause"))!!
        assertEquals("Pause the print of Benchy?", card.title)
        assertEquals("Pause", card.confirmLabel)
        assertEquals(listOf("Printer" to "FlashForge AD5X"), card.details)
    }

    @Test fun `the card comes from a dry run through the chat path and sends nothing`() = runBlocking {
        val t = tool()
        routes.commandAnswers += CommandAnswer.Checked(pauseCheck)
        t.confirmation(args(t, "action" to "pause"))
        val call = routes.commandCalls.single()
        assertEquals("chat", call.via)
        assertTrue(call.dryRun); assertFalse(call.confirmed); assertNull(call.requestId)
        assertEquals(PrinterCommand.Pause, call.command)
    }

    @Test fun `cancelling needs a tap on the screen, and its two buttons say different things`() = runBlocking {
        val t = tool()
        routes.commandAnswers += CommandAnswer.Checked(cancelCheck)
        val cancel = t.confirmation(args(t, "action" to "cancel"))!!
        assertTrue("never a voice", cancel.screenOnly)
        assertEquals("Cancel print", cancel.confirmLabel)
        assertEquals("Keep printing", cancel.denyLabel)
        routes.commandAnswers += CommandAnswer.Checked(pauseCheck)
        val pause = t.confirmation(args(t, "action" to "pause"))!!
        assertFalse(pause.screenOnly)
        assertNotEquals(pause.confirmLabel, pause.denyLabel)
    }

    @Test fun `every action gets a card, even when Jarvis sends no question`() = runBlocking {
        val t = tool()
        for ((action, title) in listOf("pause" to "Pause the print?", "resume" to "Resume the print?")) {
            routes.commandAnswers += CommandAnswer.Checked(Check(true, Risks.NONE))
            assertEquals(title, t.confirmation(args(t, "action" to action))!!.title)
        }
        routes.commandAnswers += CommandAnswer.Checked(Check(true, Risks.NONE))
        assertEquals("Set the printer's light to 100%?", t.confirmation(args(t, "action" to "light"))!!.title)
    }

    @Test fun `a refused dry run gives no card`() = runBlocking {
        val t = tool()
        routes.commandAnswers += CommandAnswer.Checked(Check(allowed = false, risk = Risks.NONE, reason = "Nothing is printing."))
        assertNull(t.confirmation(args(t, "action" to "pause")))
    }

    // --- Doing it ---------------------------------------------------------------------------------------------------------

    @Test fun `after the yes it is sent as confirmed through the chat path, under a request id`() = runBlocking {
        val t = tool()
        routes.commandAnswers += CommandAnswer.Sent("PAUSE", Risks.NONE)
        val r = t.execute(args(t, "action" to "pause"), ctx)
        val call = routes.commandCalls.single()
        assertEquals("chat", call.via)
        assertTrue(call.confirmed); assertFalse(call.dryRun)
        assertTrue("a request id is sent", !call.requestId.isNullOrBlank())
        assertEquals("Done: paused the print. Jarvis sent PAUSE.", ok(r))
    }

    @Test fun `each action says what was done`() = runBlocking {
        val t = tool()
        assertEquals("Done: resumed the print. Jarvis sent RESUME.", ok(t.also { routes.commandAnswers += CommandAnswer.Sent("RESUME", Risks.NONE) }.execute(args(t, "action" to "resume"), ctx)))
        routes.commandAnswers += CommandAnswer.Sent("CANCEL_PRINT", Risks.DANGER)
        assertEquals("Done: cancelled the print. Jarvis sent CANCEL_PRINT.", ok(t.execute(args(t, "action" to "cancel"), ctx)))
    }

    @Test fun `Jarvis's refusal is told to the model in Jarvis's words`() = runBlocking {
        val t = tool()
        routes.commandAnswers += CommandAnswer.Refused("Nothing is printing.")
        assertEquals("Nothing is printing.", err(t.execute(args(t, "action" to "pause"), ctx)))
    }

    @Test fun `any other failure is told in plain words`() = runBlocking {
        val t = tool()
        routes.commandAnswers += CommandAnswer.Failed(PrinterWords.PRINTER_UNREACHABLE, "PRINTER_UNREACHABLE")
        assertEquals(PrinterWords.PRINTER_UNREACHABLE, err(t.execute(args(t, "action" to "pause"), ctx)))
    }

    @Test fun `a second ask after the yes is not sent a third time`() = runBlocking {
        val t = tool()
        routes.commandAnswers += CommandAnswer.NeedsConfirm(pauseCheck)
        assertTrue(err(t.execute(args(t, "action" to "pause"), ctx)).contains("Jarvis asked again"))
        assertEquals(1, routes.commandCalls.size)
    }

    // --- The light --------------------------------------------------------------------------------------------------------

    @Test fun `the light goes to the level asked, clamped to 0 to 100, and to 100 with no level`() = runBlocking {
        val t = tool()
        suspend fun level(vararg more: Pair<String, String>): Double {
            routes.commandAnswers += CommandAnswer.Sent("LIGHT", Risks.NONE)
            t.execute(args(t, "action" to "light", *more), ctx)
            return (routes.commandCalls.last().command as PrinterCommand.Light).level
        }
        assertEquals(1.0, level(), 0.0)
        assertEquals(0.5, level("level" to "50"), 0.0)
        assertEquals(0.5, level("level" to "50%"), 0.0)
        assertEquals(0.0, level("level" to "0"), 0.0)
        assertEquals("above 100 is clamped", 1.0, level("level" to "150"), 0.0)
        assertEquals("below 0 is clamped", 0.0, level("level" to "-20"), 0.0)
    }

    @Test fun `a light level that is not a number is not sent`() = runBlocking {
        val t = tool()
        assertEquals("The light level must be a number from 0 to 100.", err(t.execute(args(t, "action" to "light", "level" to "bright"), ctx)))
        assertTrue(routes.commandCalls.isEmpty())
    }

    @Test fun `an unknown action is not sent`() = runBlocking {
        val t = tool()
        assertEquals("The action must be pause, resume, cancel or light.", err(t.execute(args(t, "action" to "explode"), ctx)))
        assertNull(t.confirmation(args(t, "action" to "explode")))
        assertTrue(routes.commandCalls.isEmpty())
    }

    // --- When it can't be used --------------------------------------------------------------------------------------------

    @Test fun `it is not offered unless Jarvis lets this phone control the printer`() {
        assertTrue(tool(may = true).offered())
        assertFalse(tool(may = false).offered())
    }

    @Test fun `it says why when there is no Jarvis, no printer, no control, or Jarvis cannot be reached`() = runBlocking {
        fun words(access: PrinterAccess) = runBlocking { val t = tool(access); err(t.execute(args(t, "action" to "pause"), ctx)) }
        assertTrue(words(PrinterAccess.NoJarvis).contains("hasn't paired"))
        assertEquals(PrinterWords.NOT_SHARED, words(PrinterAccess.NotShared))
        assertEquals(PrinterWords.CONTROL_OFF, words(PrinterAccess.Ready(routes, shared.copy(control = false))))
        assertEquals(PrinterWords.UNREACHABLE, words(PrinterAccess.Away(PrinterWords.UNREACHABLE)))
        assertTrue("nothing was sent", routes.commandCalls.isEmpty())
        val t = tool(PrinterAccess.NotShared)
        assertNull(t.confirmation(args(t, "action" to "pause")))
    }

    @Test fun `the tool describes itself as the model reads it`() {
        val t = tool()
        assertEquals("printer_control", t.spec.name)
        assertEquals(listOf("pause", "resume", "cancel", "light"), t.spec.parameters.first { it.name == "action" }.enumValues)
        assertTrue(t.spec.description.contains("Every call shows the owner a card first"))
        assertNotNull(t.guidance)
        assertEquals("Pausing the print…", t.statusLabelFor(args(t, "action" to "pause")))
        assertEquals("Cancelling the print…", t.statusLabelFor(args(t, "action" to "cancel")))
    }
}
