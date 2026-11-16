package com.edith.app.printer

import com.edith.app.home.PrintState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C34: what the owner types, and Moonraker's answers (made-up captures in the shapes it sends) read into a print job. */
class MoonrakerParseTest {
    private fun parse(s: JsonObject, estimate: Double? = null) = MoonrakerParse.status(s, estimate)

    // ---- Addresses ----

    @Test fun `a bare address means the API on 7125 and the web server on 80`() {
        val u = PrinterAddress.parse("192.168.4.86").getOrThrow()
        assertEquals("http://192.168.4.86:7125", u.api); assertEquals("http://192.168.4.86", u.web)
        assertEquals(u, PrinterAddress.parse("  http://192.168.4.86:7125/ ").getOrThrow())
        assertEquals("a name works too", "http://ad5x.local:7125", PrinterAddress.parse("ad5x.local").getOrThrow().api)
    }

    @Test fun `any other port serves both, and a bad address says what is wrong`() {
        val u = PrinterAddress.parse("http://10.0.0.5:8080/some/path").getOrThrow()
        assertEquals("http://10.0.0.5:8080", u.api); assertEquals("http://10.0.0.5:8080", u.web)
        assertTrue(PrinterAddress.parse("").exceptionOrNull()!!.message!!.contains("Type the printer's address"))
        assertTrue(PrinterAddress.parse("ftp://printer").exceptionOrNull()!!.message!!.contains("http://"))
        assertTrue(PrinterAddress.parse("http://").isFailure)
    }

    // ---- A print ----

    @Test fun `a print in progress reads its file, progress, layers and heaters`() {
        val s = parse(printerStatus(layers = 126 to 300)).state
        val job = s.print!!
        assertEquals(PrintState.Printing, job.state)
        assertEquals("Benchy", job.title)
        assertEquals(0.42, job.progress, 0.0001)
        assertEquals(126, job.layer); assertEquals(300, job.layers)
        assertEquals(listOf("Nozzle", "Bed"), s.heaters.map { it.label })
        assertEquals(215.0, s.heaters[0].temperature, 0.0); assertEquals(220.0, s.heaters[0].target, 0.0)
        assertEquals(60.0, s.heaters[1].target, 0.0)
    }

    @Test fun `the slicer's own progress is shown when the file sends it, and its estimate gives the time left`() {
        // M73: the screen says 50% while the file position is 30%.
        val st = parse(printerStatus(fileProgress = 0.30, displayProgress = 0.50, printDuration = 1000.0), estimate = 3600.0).state.print!!
        assertEquals(0.50, st.progress, 0.0001)
        assertEquals("by the slicer's progress: half of 3600 s", 1800L, st.timeLeftSeconds)
    }

    @Test fun `with only the file position the time left is the estimate less the time printed`() {
        val st = parse(printerStatus(fileProgress = 0.30, printDuration = 1000.0), estimate = 3600.0).state.print!!
        assertEquals(2600L, st.timeLeftSeconds)
    }

    @Test fun `with no estimate the time left comes from the pace, and is not guessed too early`() {
        assertEquals(1800L, parse(printerStatus(fileProgress = 0.25, printDuration = 600.0)).state.print!!.timeLeftSeconds)
        assertNull("under a minute printed", parse(printerStatus(fileProgress = 0.25, printDuration = 30.0)).state.print!!.timeLeftSeconds)
        assertNull("under 1%", parse(printerStatus(fileProgress = 0.005, printDuration = 600.0)).state.print!!.timeLeftSeconds)
    }

    @Test fun `the estimate never goes below zero`() {
        assertEquals(0L, parse(printerStatus(fileProgress = 0.9, printDuration = 5000.0), estimate = 3600.0).state.print!!.timeLeftSeconds)
    }

    @Test fun `states map from Klipper's names, and time left is only for a print that is going`() {
        val expected = mapOf("printing" to PrintState.Printing, "paused" to PrintState.Paused, "complete" to PrintState.Complete,
            "cancelled" to PrintState.Cancelled, "error" to PrintState.Error, "standby" to PrintState.Idle, "something new" to PrintState.Idle)
        for ((klipper, state) in expected) assertEquals(klipper, state, parse(printerStatus(state = klipper)).state.print!!.state)
        assertNotNull(parse(printerStatus(state = "paused"), estimate = 3600.0).state.print!!.timeLeftSeconds)
        assertNull(parse(printerStatus(state = "complete"), estimate = 3600.0).state.print!!.timeLeftSeconds)
        assertNull(parse(printerStatus(state = "standby", filename = null), estimate = 3600.0).state.print!!.timeLeftSeconds)
    }

    @Test fun `a print's message is kept for an error`() {
        assertEquals("Heater extruder not heating at expected rate", parse(printerStatus(state = "error", message = "Heater extruder not heating at expected rate")).state.print!!.message)
    }

    @Test fun `a printer with no heater objects still reads`() {
        val s = parse(printerStatus(nozzle = null, bed = null)).state
        assertTrue(s.heaters.isEmpty()); assertEquals(PrintState.Printing, s.print!!.state)
    }

    // ---- Klipper not ready ----

    @Test fun `Klipper shut down or starting is an error with its words, not a print`() {
        val down = parse(printerStatus(klippy = "shutdown", klippyMessage = "MCU 'mcu' shutdown: Timer too close\nOnce the underlying issue is corrected...")).also { assertEquals(Klippy.Shutdown, it.klippy) }
        assertEquals(PrintState.Error, down.state.print!!.state)
        assertEquals("only its first line", "MCU 'mcu' shutdown: Timer too close", down.state.print!!.message)
        val starting = parse(printerStatus(klippy = "startup"))
        assertEquals(Klippy.Startup, starting.klippy); assertEquals("Klipper is starting up", starting.state.print!!.message)
        assertEquals(Klippy.Error, parse(printerStatus(klippy = "error")).klippy)
    }

    // ---- Names ----

    @Test fun `file names are made readable`() {
        assertEquals("Benchy", MoonrakerParse.fileTitle("Benchy.gcode"))
        assertEquals("My Model v2", MoonrakerParse.fileTitle("folder/My+Model%20v2.gcode"))
        assertEquals("Chimney and Platforms (plate 1)", MoonrakerParse.fileTitle("3mf/model/Chimney+and+Platforms_plate_1/Metadata/plate_1.gcode"))
        assertEquals("kept as is when nothing is left", "x", MoonrakerParse.fileTitle("x"))
    }

    // ---- Webcams ----

    @Test fun `webcams get absolute snapshot addresses, and ones that can't give a picture are left out`() {
        val urls = PrinterAddress.parse("192.168.4.86").getOrThrow()
        val result = Json.parseToJsonElement("""
            {"webcams":[
              {"name":"Chamber","snapshot_url":"/webcam/?action=snapshot","enabled":true},
              {"name":"Remote","snapshot_url":"http://cam.local/snap.jpg"},
              {"name":"Off","snapshot_url":"/off","enabled":false},
              {"name":"No picture","stream_url":"/stream"}]}""")
        val cams = MoonrakerParse.cameras(result, urls)
        assertEquals(listOf("Chamber", "Remote"), cams.map { it.name })
        assertEquals("http://192.168.4.86/webcam/?action=snapshot", cams[0].snapshotUrl)
        assertEquals("http://cam.local/snap.jpg", cams[1].snapshotUrl)
        assertTrue(MoonrakerParse.cameras(Json.parseToJsonElement("{}"), urls).isEmpty())
    }

    @Test fun `odd answers never throw`() {
        val empty = parse(JsonObject(emptyMap()))
        assertEquals(PrintState.Idle, empty.state.print!!.state); assertEquals(Klippy.Ready, empty.klippy)
        assertNull(MoonrakerParse.estimatedTime(null))
        assertNull(MoonrakerParse.estimatedTime(Json.parseToJsonElement("""{"estimated_time":"soon"}""")))
        assertEquals(3600.0, MoonrakerParse.estimatedTime(Json.parseToJsonElement("""{"estimated_time":3600.5}"""))!!, 1.0)
        assertTrue(MoonrakerParse.statusOf(null).isEmpty())
    }
}
