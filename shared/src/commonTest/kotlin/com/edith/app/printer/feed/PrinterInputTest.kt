package com.edith.app.printer.feed

import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertNull
import com.edith.app.testing.assertTrue
import kotlin.test.Test

/** C39 E8: what the owner types, and the commands made from it. An empty or wrong box sends nothing; the JSON is exactly Jarvis's. */
class PrinterInputTest {
    @Test fun `numbers are read the way people write them`() {
        assertEquals(1.5, PrinterInput.number("1.5")!!, 0.0)
        assertEquals(1.5, PrinterInput.number("1,5")!!, 0.0)
        assertEquals(220.0, PrinterInput.number("  220  ")!!, 0.0)
        assertEquals(-5.0, PrinterInput.number("-5")!!, 0.0)
    }

    @Test fun `nothing words NaN and infinity are not numbers`() {
        for (bad in listOf("", "   ", "abc", "1.2.3", "NaN", "Infinity", "-Infinity", "1e999", "12abc")) assertNull(bad, PrinterInput.number(bad))
    }

    @Test fun `a number in a range includes both ends`() {
        assertEquals(0.0, PrinterInput.between("0", 0.0, 300.0)!!, 0.0)
        assertEquals(300.0, PrinterInput.between("300", 0.0, 300.0)!!, 0.0)
        assertNull(PrinterInput.between("301", 0.0, 300.0))
        assertNull(PrinterInput.between("-1", 0.0, 300.0))
    }

    @Test fun `a target is made for a heater and refused above what Klipper allows`() {
        assertEquals("""{"kind":"setTemp","heater":"extruder","target":220.0}""", PrinterInput.setTemp("extruder", "220", 300.0)!!.toJson())
        assertEquals("""{"kind":"setTemp","heater":"heater_bed","target":0.0}""", PrinterInput.setTemp("heater_bed", "0", 120.0)!!.toJson())
        assertNull(PrinterInput.setTemp("heater_bed", "130", 120.0))
        assertNull(PrinterInput.setTemp("extruder", "", 300.0))
        assertNull(PrinterInput.setTemp("extruder", "hot", 300.0))
    }

    @Test fun `a heater that reports no maximum falls back to 300`() {
        assertEquals(250.0, (PrinterInput.setTemp("extruder", "250", 0.0) as PrinterCommand.SetTemp).target, 0.0)
        assertNull(PrinterInput.setTemp("extruder", "301", 0.0))
    }

    @Test fun `extrude and retract are one command with the sign on the length`() {
        assertEquals("""{"kind":"extrude","length":10.0,"speed":5.0}""", PrinterInput.extrude("10", "5", retract = false)!!.toJson())
        assertEquals("""{"kind":"extrude","length":-10.0,"speed":5.0}""", PrinterInput.extrude("10", "5", retract = true)!!.toJson())
    }

    @Test fun `extrude needs both boxes and sensible numbers`() {
        assertNull(PrinterInput.extrude("", "5", false))
        assertNull(PrinterInput.extrude("10", "", false))
        assertNull(PrinterInput.extrude("0", "5", false))
        assertNull(PrinterInput.extrude("-10", "5", false))
        assertNull("not more than 100 mm at a time", PrinterInput.extrude("101", "5", false))
        assertNull("not faster than 50 mm/s", PrinterInput.extrude("10", "51", false))
    }

    @Test fun `limits send only the boxes that have a number`() {
        assertEquals("""{"kind":"limits","velocity":200.0}""", PrinterInput.limits("200", "", "", "")!!.toJson())
        assertEquals("""{"kind":"limits","velocity":200.0,"accel":3000.0,"scv":5.0,"cruiseRatio":0.5}""", PrinterInput.limits("200", "3000", "5", "0.5")!!.toJson())
    }

    @Test fun `limits with nothing typed or a wrong number anywhere send nothing`() {
        assertNull(PrinterInput.limits("", "", "", ""))
        assertNull(PrinterInput.limits("0", "", "", ""))
        assertNull(PrinterInput.limits("200", "abc", "", ""))
        assertNull("a cruise ratio is a fraction", PrinterInput.limits("", "", "", "1.5"))
        assertTrue(PrinterInput.limits("", "", "", "0") != null)
    }

    @Test fun `retraction sends only what was typed and a length of zero is allowed`() {
        assertEquals("""{"kind":"retraction","length":0.8}""", PrinterInput.retraction("0.8", "", "", "")!!.toJson())
        assertEquals("""{"kind":"retraction","length":0.0,"speed":35.0}""", PrinterInput.retraction("0", "35", "", "")!!.toJson())
        assertEquals("""{"kind":"retraction","length":0.8,"speed":35.0,"extraLength":0.0,"unretractSpeed":30.0}""", PrinterInput.retraction("0.8", "35", "0", "30")!!.toJson())
    }

    @Test fun `retraction with nothing typed a negative or a zero speed sends nothing`() {
        assertNull(PrinterInput.retraction("", "", "", ""))
        assertNull(PrinterInput.retraction("-1", "", "", ""))
        assertNull(PrinterInput.retraction("", "0", "", ""))
        assertNull(PrinterInput.retraction("x", "", "", ""))
    }

    @Test fun `a slider's values become the whole percentages and levels Jarvis expects`() {
        assertEquals(85.0, PrinterInput.percent(84.6f), 0.0)
        assertEquals(1.0, PrinterInput.level(100f), 0.0)
        assertEquals(0.5, PrinterInput.level(50.2f), 0.0)
        assertEquals("""{"kind":"light","level":0.5}""", PrinterCommand.Light(PrinterInput.level(50f)).toJson())
        assertEquals("""{"kind":"speedFactor","percent":85.0}""", PrinterCommand.SpeedFactor(PrinterInput.percent(85f)).toJson())
    }

    @Test fun `the jog steps are Jarvis's and 100 mm is not offered for Z`() {
        assertEquals(listOf(0.1, 1.0, 10.0, 50.0, 100.0), PrinterInput.stepsFor(Axis.X))
        assertEquals(listOf(0.1, 1.0, 10.0, 50.0, 100.0), PrinterInput.stepsFor(Axis.Y))
        assertEquals(listOf(0.1, 1.0, 10.0, 50.0), PrinterInput.stepsFor(Axis.Z))
        assertEquals(listOf("0.1", "1", "10", "50", "100"), PrinterInput.STEPS.map { PrinterInput.stepLabel(it) })
        assertEquals(listOf(0.01, 0.05, 0.1), PrinterInput.Z_OFFSET_STEPS)
    }

    @Test fun `a jog and a Z offset are the literal commands`() {
        assertEquals("""{"kind":"jog","axis":"x","distance":-10.0}""", PrinterCommand.Jog(Axis.X, -10.0).toJson())
        assertEquals("""{"kind":"jog","axis":"z","distance":0.1}""", PrinterCommand.Jog(Axis.Z, 0.1).toJson())
        assertEquals("""{"kind":"zOffset","delta":-0.05}""", PrinterCommand.ZOffset(-0.05).toJson())
    }
}
