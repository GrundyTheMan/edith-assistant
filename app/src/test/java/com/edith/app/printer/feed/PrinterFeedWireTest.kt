package com.edith.app.printer.feed

import com.edith.app.jarvis.feedJson
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

class PrinterFeedWireTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/printer-feed/$name.json")) { "missing fixture $name" }
            .bufferedReader().readText()

    private fun state(name: String) = feedJson.decodeFromString(StateAnswer.serializer(), fixture(name))

    @Test
    fun `an idle printer parses with every card's data`() {
        val a = state("state-ready-idle")
        assertEquals(41, a.rev)
        assertEquals(1763400000, a.epoch)
        assertEquals(120, a.consoleSeq)
        assertTrue(a.state.isReady)
        assertEquals(PrinterStates.STANDBY, a.state.print.state)
        assertNull(a.state.print.timeLeft)
        assertEquals(listOf("Nozzle", "Bed"), a.state.heaters.map { it.label })
        assertEquals("", a.state.sensors[1].unit)
        assertTrue(a.state.fans[0].settable)
        assertFalse(a.state.fans[1].settable)
        assertEquals(0.0, a.state.light)
        assertEquals("xyz", a.state.toolhead.homed)
        assertEquals(0.4, a.state.extruder.nozzleDiameter)
        assertEquals(0.8, a.state.retraction?.length)
        assertEquals("F72224", a.state.slots[0].hex)
        assertEquals(listOf("video", "screen"), a.state.cameras.map { it.id })
        assertEquals("ready", a.state.queue?.state)
        assertFalse(a.quick.getValue("pause").allowed)
        assertEquals("Nothing is printing.", a.quick.getValue("pause").reason)
    }

    @Test
    fun `a print in progress parses`() {
        val a = state("state-printing")
        val p = a.state.print
        assertTrue(p.isActive)
        assertEquals(0.4, p.progress, 0.0)
        assertEquals(12, p.layer?.current)
        assertEquals(240, p.layer?.total)
        assertEquals("slicer", p.timeLeftFrom)
        assertEquals(2700.0, p.timeLeft)
        assertEquals(listOf(90.0, 90.0), a.state.objects[0].polygon?.get(0))
        assertEquals(listOf(100.0, 100.0), a.state.objects[0].center)
        assertTrue(a.arm.armed)
        assertEquals(1763400160000, a.arm.until)
    }

    @Test
    fun `the other states parse`() {
        assertEquals(PrinterStates.PAUSED, state("state-paused").state.print.state)
        val gone = state("state-unreachable").state
        assertEquals(Connections.UNREACHABLE, gone.connection)
        assertFalse(gone.isReady)
        assertEquals(1763399000000, gone.lastSeen)
        assertEquals(Connections.KLIPPY_SHUTDOWN, state("state-klippy-shutdown").state.connection)
        assertEquals("Filament runout detected", state("state-alert").state.alert?.text)
    }

    @Test
    fun `the shared entry says what the owner allowed`() {
        val shared = feedJson.parseToJsonElement(fixture("shared-with-printer")).jsonObject.getValue("printer")
        val p = feedJson.decodeFromJsonElement<SharedPrinter>(shared)
        assertEquals("FlashForge AD5X", p.name)
        assertTrue(p.control)
        assertEquals(2, p.armMinutes)
        assertEquals(Preset("PLA", 220, 60), p.presets[0])
        assertEquals("screen", p.cameras[1].id)
        assertTrue("recordings" in p.features)
        assertEquals(1, p.schema)
    }

    @Test
    fun `the list answers parse`() {
        val files = feedJson.decodeFromString(FilesAnswer.serializer(), fixture("files"))
        assertEquals(3, files.files.size)
        assertTrue(files.files[0].printedBefore)
        assertEquals("3mf", files.files[2].kind)
        assertEquals(1763399000000, files.cachedAt)

        val history = feedJson.decodeFromString(HistoryAnswer.serializer(), fixture("history"))
        assertEquals("completed", history.jobs[0].status)
        assertNull(history.jobs[1].end)
        assertEquals(966, history.totals.jobs)

        val temps = feedJson.decodeFromString(TempAnswer.serializer(), fixture("temperatures"))
        assertEquals(3, temps.series.size)
        assertEquals(listOf(0.0, 0.0, 220.0, 220.0), temps.series[0].targets)
        assertNull(temps.series[2].targets)

        val macros = feedJson.decodeFromString(MacrosAnswer.serializer(), fixture("macros"))
        assertEquals("calibration", macros.macros[1].group)
        assertEquals("default", macros.macros[1].params[0].default)
        assertEquals(Risks.DANGER, macros.macros[1].risk)
        assertEquals(listOf("SECRET_MACRO"), macros.hidden)
        assertEquals("Filament and nozzle", macros.groups[0].title)

        val console = feedJson.decodeFromString(ConsoleAnswer.serializer(), fixture("console"))
        assertEquals(121, console.seq)
        assertEquals("error", console.lines[2].kind)
    }

    @Test
    fun `a refused command carries the check or the reason`() {
        val confirm = feedJson.decodeFromString(CheckErrorBody.serializer(), fixture("check-confirm")).check
        assertTrue(confirm.allowed)
        assertEquals(Risks.CONFIRM, confirm.risk)
        assertEquals("Home all axes?", confirm.question)
        assertEquals("Home", confirm.confirm)
        // The shared error parser reads the same bodies.
        val refused = feedJson.decodeFromString(com.edith.app.jarvis.FeedErrorReply.serializer(), fixture("error-refused")).error
        assertEquals("REFUSED", refused.code)
        val off = feedJson.decodeFromString(com.edith.app.jarvis.FeedErrorReply.serializer(), fixture("error-controls-off")).error
        assertEquals("CONTROLS_OFF", off.code)
    }

    @Test
    fun `missing fields take defaults and unknown ones are ignored`() {
        val a = feedJson.decodeFromString(StateAnswer.serializer(), """{"rev":3,"brandNew":{"x":1},"state":{"connection":"ready","somethingNew":true}}""")
        assertEquals(3, a.rev)
        assertTrue(a.state.heaters.isEmpty())
        assertEquals(PrinterStates.STANDBY, a.state.print.state)
        assertFalse(a.arm.armed)
        assertTrue(a.quick.isEmpty())
    }

    @Test
    fun `a state value from a newer Jarvis does not throw`() {
        val a = feedJson.decodeFromString(
            StateAnswer.serializer(),
            """{"rev":1,"state":{"connection":"hibernating","print":{"state":"levelling"}}}""",
        )
        assertEquals("hibernating", a.state.connection)
        assertEquals("levelling", a.state.print.state)
        assertFalse(a.state.print.isActive)
    }
}
