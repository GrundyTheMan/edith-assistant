package com.edith.app.printer.feed

import com.edith.app.printer.feed.PrinterInput.PresetRow
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertNull
import kotlin.test.Test

/** C39 E10b: the temperature presets the owner edits, checked the way Jarvis checks them (its `parseSettings`). */
class PrinterPresetsInputTest {
    private fun row(name: String = "PLA", nozzle: String = "220", bed: String = "60") = PresetRow(name, nozzle, bed)

    @Test fun `good presets are sent as whole degrees`() {
        assertEquals(listOf(Preset("PLA", 220, 60), Preset("PETG", 240, 80)), PrinterInput.presets(listOf(row(), row("PETG", "240", "80"))))
        assertEquals("a fractional temperature is rounded", listOf(Preset("PLA", 221, 60)), PrinterInput.presets(listOf(row(nozzle = "220.6"))))
        assertEquals("a comma is a decimal point", listOf(Preset("PLA", 220, 55)), PrinterInput.presets(listOf(row(bed = "54,6"))))
    }

    @Test fun `a name is trimmed`() {
        assertEquals("PLA", PrinterInput.presets(listOf(row("  PLA  ")))!!.single().name)
    }

    @Test fun `no presets at all is allowed and means Jarvis's own`() {
        assertEquals(emptyList<Preset>(), PrinterInput.presets(emptyList()))
        assertNull(PrinterInput.presetsProblem(emptyList()))
    }

    @Test fun `the temperatures are 0 up to 300 for the nozzle and 0 up to 120 for the bed both ends allowed`() {
        assertEquals(Preset("X", 0, 0), PrinterInput.presets(listOf(row("X", "0", "0")))!!.single())
        assertEquals(Preset("X", 300, 120), PrinterInput.presets(listOf(row("X", "300", "120")))!!.single())
        assertEquals("The nozzle temperature of X must be from 0 to 300 °C.", PrinterInput.presetsProblem(listOf(row("X", "301", "60"))))
        assertEquals("The nozzle temperature of X must be from 0 to 300 °C.", PrinterInput.presetsProblem(listOf(row("X", "-1", "60"))))
        assertEquals("The bed temperature of X must be from 0 to 120 °C.", PrinterInput.presetsProblem(listOf(row("X", "220", "121"))))
    }

    @Test fun `an empty or non-numeric box is a problem not a zero`() {
        assertEquals("The nozzle temperature of X must be from 0 to 300 °C.", PrinterInput.presetsProblem(listOf(row("X", "", "60"))))
        assertEquals("The bed temperature of X must be from 0 to 120 °C.", PrinterInput.presetsProblem(listOf(row("X", "220", "warm"))))
        assertNull(PrinterInput.presets(listOf(row("X", "", "60"))))
    }

    @Test fun `every preset needs a name of at most 30 characters and no two are alike in any case`() {
        assertEquals("Every preset needs a name.", PrinterInput.presetsProblem(listOf(row("   "))))
        assertEquals("A preset's name is at most 30 characters.", PrinterInput.presetsProblem(listOf(row("N".repeat(31)))))
        assertNull(PrinterInput.presetsProblem(listOf(row("N".repeat(30)))))
        assertEquals("There are two presets called pla.", PrinterInput.presetsProblem(listOf(row("PLA"), row("pla"))))
        assertEquals("two names that differ only by spaces around them are the same", "There are two presets called PLA.", PrinterInput.presetsProblem(listOf(row("PLA"), row(" PLA "))))
    }

    @Test fun `at most 10 presets`() {
        val ten = (1..10).map { row("P$it") }
        assertNull(PrinterInput.presetsProblem(ten))
        assertEquals("At most 10 presets.", PrinterInput.presetsProblem(ten + row("P11")))
        assertNull(PrinterInput.presets(ten + row("P11")))
    }

    @Test fun `the first problem is the one told`() {
        assertEquals("Every preset needs a name.", PrinterInput.presetsProblem(listOf(row(""), row("X", "999", "60"))))
    }
}
