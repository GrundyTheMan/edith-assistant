package com.edith.app.printer.feed

import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertNull
import com.edith.app.testing.assertTrue
import kotlin.test.Test

/** C39 E9: the Macros card's list, the console's history, and the one-line input check. */
class PrinterMacroConsoleTest {
    private fun macro(name: String, group: String, description: String? = null, risk: String = Risks.CONFIRM, params: List<MacroParam> = emptyList()) =
        MacroInfo(name, description, params, risk, group)

    private val answer = MacrosAnswer(
        macros = listOf(
            macro("LOAD_FILAMENT", "filament", "Load filament"),
            macro("UNLOAD_FILAMENT", "filament", "Unload filament"),
            macro("CLEAN_NOZZLE", "print"),
            macro("BED_MESH_CALIBRATE", "calibration", "Probe the bed", Risks.DANGER, listOf(MacroParam("PROFILE", "default"))),
            macro("MYSTERY", "somewhere-new"),
        ),
        groups = listOf(MacroGroup("filament", "Filament and nozzle"), MacroGroup("print", "Printing"), MacroGroup("calibration", "Bed and calibration"), MacroGroup("system", "System")),
        hidden = listOf("SECRET"),
    )

    // --- The macro list ---------------------------------------------------------------------------------------------------

    @Test fun `groups come in Jarvis's order with Jarvis's titles and empty groups are left out`() {
        val s = MacroList.sections(answer, "")
        assertEquals(listOf("Filament and nozzle", "Printing", "Bed and calibration", "Other"), s.map { it.title })
        assertEquals(listOf("LOAD_FILAMENT", "UNLOAD_FILAMENT"), s[0].macros.map { it.name })
    }

    @Test fun `a macro in a group Jarvis did not list goes under Other`() {
        val other = MacroList.sections(answer, "").last()
        assertEquals("other", other.id)
        assertEquals(listOf("MYSTERY"), other.macros.map { it.name })
    }

    @Test fun `a filter matches the name or the description in any case and keeps only matching groups`() {
        assertEquals(listOf("LOAD_FILAMENT", "UNLOAD_FILAMENT"), MacroList.sections(answer, "  filament ").flatMap { it.macros }.map { it.name })
        assertEquals(listOf("BED_MESH_CALIBRATE"), MacroList.sections(answer, "probe").flatMap { it.macros }.map { it.name })
        assertEquals(listOf("Bed and calibration"), MacroList.sections(answer, "mesh").map { it.title })
        assertTrue(MacroList.sections(answer, "zzz").isEmpty())
    }

    @Test fun `an empty answer has no sections`() {
        assertTrue(MacroList.sections(MacrosAnswer(), "").isEmpty())
    }

    @Test fun `typed parameters are trimmed and an empty box is not sent`() {
        assertEquals(mapOf("PROFILE" to "mine"), MacroList.params(mapOf("PROFILE" to "  mine ", "OTHER" to "  ")))
        assertTrue(MacroList.params(mapOf("A" to "")).isEmpty())
    }

    @Test fun `a macro with parameters is sent as the literal command`() {
        assertEquals("""{"kind":"macro","name":"BED_MESH_CALIBRATE","params":{"PROFILE":"mine"}}""", PrinterCommand.Macro("BED_MESH_CALIBRATE", MacroList.params(mapOf("PROFILE" to "mine"))).toJson())
        assertEquals("""{"kind":"macro","name":"CLEAN_NOZZLE"}""", PrinterCommand.Macro("CLEAN_NOZZLE", MacroList.params(emptyMap())).toJson())
    }

    // --- The console history ----------------------------------------------------------------------------------------------

    @Test fun `the newest line is first and the same line moves to the front instead of repeating`() {
        var h = emptyList<String>()
        h = ConsoleHistory.push(h, "M105"); h = ConsoleHistory.push(h, "G28"); h = ConsoleHistory.push(h, "M105")
        assertEquals(listOf("M105", "G28"), h)
    }

    @Test fun `the history keeps the last 50 only`() {
        var h = emptyList<String>()
        for (i in 1..60) h = ConsoleHistory.push(h, "G1 X$i")
        assertEquals(50, h.size)
        assertEquals("G1 X60", h.first())
        assertEquals("G1 X11", h.last())
    }

    @Test fun `an empty line is not kept and spaces around a line are dropped`() {
        assertEquals(emptyList<String>(), ConsoleHistory.push(emptyList(), "   "))
        assertEquals(listOf("M105"), ConsoleHistory.push(emptyList(), "  M105 "))
    }

    @Test fun `the up arrow goes to older lines and stops at the oldest the down arrow comes back to an empty box`() {
        val h = listOf("c", "b", "a")
        var i = -1
        i = ConsoleHistory.older(h, i); assertEquals("c", ConsoleHistory.at(h, i))
        i = ConsoleHistory.older(h, i); assertEquals("b", ConsoleHistory.at(h, i))
        i = ConsoleHistory.older(h, i); i = ConsoleHistory.older(h, i)
        assertEquals("stops at the oldest", "a", ConsoleHistory.at(h, i))
        i = ConsoleHistory.newer(i); assertEquals("b", ConsoleHistory.at(h, i))
        i = ConsoleHistory.newer(i); i = ConsoleHistory.newer(i)
        assertEquals("past the newest is an empty box", "", ConsoleHistory.at(h, i))
        assertEquals(-1, ConsoleHistory.older(emptyList(), -1))
    }

    // --- The one-line input -----------------------------------------------------------------------------------------------

    @Test fun `a console line is sent as the literal gcode command`() {
        assertEquals("""{"kind":"gcode","script":"M105"}""", PrinterInput.consoleLine("  M105  ")!!.toJson())
    }

    @Test fun `an empty line a line break or more than 200 characters sends nothing`() {
        assertNull(PrinterInput.consoleLine(""))
        assertNull(PrinterInput.consoleLine("   "))
        assertNull(PrinterInput.consoleLine("G28\nM105"))
        assertNull(PrinterInput.consoleLine("G28\rM105"))
        assertNull(PrinterInput.consoleLine("G".repeat(201)))
        assertTrue(PrinterInput.consoleLine("G".repeat(200)) != null)
    }
}
