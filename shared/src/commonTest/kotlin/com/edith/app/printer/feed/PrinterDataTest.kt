package com.edith.app.printer.feed

import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertNull
import com.edith.app.testing.assertTrue
import kotlin.test.Test


class PrinterDataTest {
    private fun state(nozzle: Double, bed: Double, target: Double = 0.0) = PrinterState(
        heaters = listOf(Heater("extruder", "Nozzle", nozzle, target), Heater("heater_bed", "Bed", bed, 0.0)),
        sensors = listOf(Sensor("temperature_sensor head", "Head", 25.5, "°C"), Sensor("temperature_sensor weightValue", "Weight value", 0.0, "")),
    )

    // --- The chart buffer ------------------------------------------------------------------------------------------------

    @Test fun `the chart is seeded from Jarvis and then gets one point per answer`() {
        val b = ChartBuffer()
        b.seed(listOf(TempSeries("extruder", "Nozzle", listOf(24.0, 25.0), listOf(0.0, 220.0))))
        b.append(state(26.0, 23.0, target = 220.0))
        val nozzle = b.lines().first { it.id == "extruder" }
        assertEquals(listOf(24.0, 25.0, 26.0), nozzle.values)
        assertEquals(listOf(0.0, 220.0, 220.0), nozzle.targets)
    }

    @Test fun `a reading that is not a temperature is not charted`() {
        val b = ChartBuffer()
        b.append(state(24.0, 23.0))
        assertEquals(listOf("extruder", "heater_bed", "temperature_sensor head"), b.lines().map { it.id })
    }

    @Test fun `the buffer never grows past its cap and drops the oldest`() {
        val b = ChartBuffer(cap = 5)
        for (i in 1..12) b.append(state(i.toDouble(), 20.0))
        val nozzle = b.lines().first { it.id == "extruder" }
        assertEquals(listOf(8.0, 9.0, 10.0, 11.0, 12.0), nozzle.values)
        assertEquals(5, nozzle.targets!!.size)
    }

    @Test fun `seeding again replaces what was there`() {
        val b = ChartBuffer()
        b.append(state(1.0, 1.0))
        b.seed(listOf(TempSeries("x", "X", listOf(5.0))))
        assertEquals(listOf("x"), b.lines().map { it.id })
    }

    @Test fun `a seed longer than the cap keeps the newest`() {
        val b = ChartBuffer(cap = 3)
        b.seed(listOf(TempSeries("x", "X", listOf(1.0, 2.0, 3.0, 4.0, 5.0))))
        assertEquals(listOf(3.0, 4.0, 5.0), b.lines().single().values)
    }

    // --- The axes --------------------------------------------------------------------------------------------------------

    @Test fun `the y axis is the highest value or target plus 10 percent at least 50`() {
        val lines = listOf(ChartLine("a", "A", listOf(100.0, 180.0), listOf(200.0, 200.0)))
        assertEquals(220.0, ChartMath.yMax(lines), 0.001)
        assertEquals(50.0, ChartMath.yMax(emptyList()), 0.0)
        assertEquals(50.0, ChartMath.yMax(listOf(ChartLine("a", "A", listOf(10.0), null))), 0.0)
    }

    @Test fun `a hidden line does not stretch the axis`() {
        val lines = listOf(ChartLine("hot", "Hot", listOf(300.0), null), ChartLine("bed", "Bed", listOf(60.0), null))
        assertEquals(66.0, ChartMath.yMax(lines, hidden = setOf("hot")), 0.001)
    }

    @Test fun `gridlines are every 50 degrees from 0`() {
        assertEquals(listOf(0.0, 50.0, 100.0, 150.0, 200.0), ChartMath.gridlines(220.0))
        assertEquals(listOf(0.0, 50.0), ChartMath.gridlines(50.0))
    }

    @Test fun `the x labels count minutes back from now`() {
        assertEquals("now", ChartMath.minutesAgo(index = 119, count = 120))
        assertEquals("1 min ago", ChartMath.minutesAgo(index = 59, count = 120))
        assertEquals("2 min ago", ChartMath.minutesAgo(index = 0, count = 120))
    }

    @Test fun `a value is placed between the bottom and the top`() {
        assertEquals(0.5, ChartMath.fraction(110.0, 220.0), 0.0)
        assertEquals(1.0, ChartMath.fraction(500.0, 220.0), 0.0)
        assertEquals(0.0, ChartMath.fraction(-5.0, 220.0), 0.0)
    }

    // --- Files -----------------------------------------------------------------------------------------------------------

    private val files = listOf(
        PrinterFile("benchy.gcode", 2_400_000, modified = 100, title = "Benchy", kind = "gcode", printedBefore = true),
        PrinterFile("3mf/model/Chimney/Metadata/plate_1.gcode", 5_100_000, modified = 300, title = "Chimney and Platforms (plate 1)", kind = "gcode", printedBefore = false),
        PrinterFile("holder.3mf", 900_000, modified = 200, title = "Holder", kind = "3mf", printedBefore = true),
    )

    @Test fun `files are newest first`() {
        assertEquals(listOf("Chimney and Platforms (plate 1)", "Holder", "Benchy"), FileList.shown(files, "", printedOnly = false).map { it.title })
    }

    @Test fun `printed before hides the rest`() {
        assertEquals(listOf("Holder", "Benchy"), FileList.shown(files, "", printedOnly = true).map { it.title })
    }

    @Test fun `a search matches the title or the path in any case`() {
        assertEquals(listOf("Benchy"), FileList.shown(files, "  BENCH ", printedOnly = false).map { it.title })
        assertEquals(listOf("Chimney and Platforms (plate 1)"), FileList.shown(files, "metadata", printedOnly = false).map { it.title })
        assertTrue(FileList.shown(files, "zzz", printedOnly = false).isEmpty())
    }

    @Test fun `sizes are written for people`() {
        assertEquals("2.4 MB", FileList.size(2_400_000, "en-US"))
        assertEquals("900 KB", FileList.size(900_000))
        assertEquals("120 B", FileList.size(120))
    }

    // --- Console ---------------------------------------------------------------------------------------------------------

    private fun line(seq: Long, text: String, kind: String = "response") = ConsoleLine(seq, seq * 1000, text, kind)

    @Test fun `value lines are hidden unless the owner switches that off`() {
        val lines = listOf(line(1, "// value: 5"), line(2, "ok"), line(3, "!! Jam", "error"))
        assertEquals(listOf("ok", "!! Jam"), ConsoleView.visible(lines, hideValues = true).map { it.text })
        assertEquals(3, ConsoleView.visible(lines, hideValues = false).size)
    }

    @Test fun `new lines are added after the last one and never repeated`() {
        val old = listOf(line(1, "a"), line(2, "b"))
        val merged = ConsoleView.append(old, listOf(line(2, "b"), line(3, "c")))
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.seq })
    }

    @Test fun `the console keeps the last 500 lines`() {
        val merged = ConsoleView.append(emptyList(), (1L..620L).map { line(it, "x") })
        assertEquals(500, merged.size)
        assertEquals(121L, merged.first().seq)
    }

    // --- The bed mesh ----------------------------------------------------------------------------------------------------

    @Test fun `the mesh scale is blue low near white in the middle and red high`() {
        val (lr, _, lb) = MeshColors.rgb(-0.1, -0.1, 0.1)
        val (mr, mg, mb) = MeshColors.rgb(0.0, -0.1, 0.1)
        val (hr, _, hb) = MeshColors.rgb(0.1, -0.1, 0.1)
        assertTrue("lowest is blue", lb > lr)
        assertTrue("highest is red", hr > hb)
        assertTrue("the middle is light", mr > 220 && mg > 220 && mb > 220)
    }

    @Test fun `a value outside the range is clamped and a flat mesh is one grey`() {
        assertEquals(MeshColors.rgb(0.1, -0.1, 0.1), MeshColors.rgb(9.0, -0.1, 0.1))
        assertEquals(MeshColors.rgb(-0.1, -0.1, 0.1), MeshColors.rgb(-9.0, -0.1, 0.1))
        assertEquals(Triple(235, 235, 235), MeshColors.rgb(0.2, 0.2, 0.2))
    }

    @Test fun `the extremes and the spread of a mesh`() {
        val e = MeshColors.extremes(listOf(listOf(0.05, 0.02), listOf(-0.03, 0.01)))!!
        assertEquals(0.08, e.spread, 0.0001)
        assertEquals(Triple(-0.03, 1, 0), e.lowest)
        assertEquals(Triple(0.05, 0, 0), e.highest)
        assertNull(MeshColors.extremes(emptyList()))
    }
}
