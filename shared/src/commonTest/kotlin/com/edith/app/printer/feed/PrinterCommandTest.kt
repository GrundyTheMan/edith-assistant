package com.edith.app.printer.feed

import kotlin.test.Test
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertTrue
import kotlinx.serialization.ExperimentalSerializationApi

class PrinterCommandTest {
    /** Every kind Jarvis's `PrinterCommand` has, with the exact JSON Edith sends for one example. */
    private val table: List<Pair<PrinterCommand, String>> = listOf(
        PrinterCommand.Pause to """{"kind":"pause"}""",
        PrinterCommand.Resume to """{"kind":"resume"}""",
        PrinterCommand.Cancel to """{"kind":"cancel"}""",
        PrinterCommand.Start("benchy.gcode") to """{"kind":"start","path":"benchy.gcode"}""",
        PrinterCommand.EStop to """{"kind":"estop"}""",
        PrinterCommand.Restart to """{"kind":"restart"}""",
        PrinterCommand.FirmwareRestart to """{"kind":"firmwareRestart"}""",
        PrinterCommand.SetTemp("extruder", 220.0) to """{"kind":"setTemp","heater":"extruder","target":220.0}""",
        PrinterCommand.Preset("PLA") to """{"kind":"preset","name":"PLA"}""",
        PrinterCommand.HeatersOff to """{"kind":"heatersOff"}""",
        PrinterCommand.Home(HomeAxes.XY) to """{"kind":"home","axes":"xy"}""",
        PrinterCommand.Jog(Axis.X, 10.0) to """{"kind":"jog","axis":"x","distance":10.0}""",
        PrinterCommand.ZOffset(-0.05) to """{"kind":"zOffset","delta":-0.05}""",
        PrinterCommand.Extrude(10.0, 5.0) to """{"kind":"extrude","length":10.0,"speed":5.0}""",
        PrinterCommand.MotorsOff to """{"kind":"motorsOff"}""",
        PrinterCommand.SpeedFactor(85.0) to """{"kind":"speedFactor","percent":85.0}""",
        PrinterCommand.FlowFactor(100.0) to """{"kind":"flowFactor","percent":100.0}""",
        PrinterCommand.Limits(velocity = 200.0, scv = 5.0) to """{"kind":"limits","velocity":200.0,"scv":5.0}""",
        PrinterCommand.Retraction(length = 0.8) to """{"kind":"retraction","length":0.8}""",
        PrinterCommand.Fan("fan_generic fanM106", 0.5) to """{"kind":"fan","fan":"fan_generic fanM106","speed":0.5}""",
        PrinterCommand.Light(1.0) to """{"kind":"light","level":1.0}""",
        PrinterCommand.AirCirculation(AirMode.INTERNAL) to """{"kind":"airCirculation","mode":"internal"}""",
        PrinterCommand.FilamentSensor("filament_switch_sensor head", false) to
            """{"kind":"filamentSensor","sensor":"filament_switch_sensor head","enabled":false}""",
        PrinterCommand.Camera(true) to """{"kind":"camera","on":true}""",
        PrinterCommand.Macro("LOAD_FILAMENT") to """{"kind":"macro","name":"LOAD_FILAMENT"}""",
        PrinterCommand.Macro("BED_MESH_CALIBRATE", mapOf("PROFILE" to "default")) to
            """{"kind":"macro","name":"BED_MESH_CALIBRATE","params":{"PROFILE":"default"}}""",
        PrinterCommand.ExcludeObject("Chimney") to """{"kind":"excludeObject","name":"Chimney"}""",
        PrinterCommand.Gcode("M105") to """{"kind":"gcode","script":"M105"}""",
        PrinterCommand.DeleteFile("old.gcode") to """{"kind":"deleteFile","path":"old.gcode"}""",
        PrinterCommand.ScreenTap(400, 240) to """{"kind":"screenTap","x":400,"y":240}""",
        PrinterCommand.QueueAdd("benchy.gcode") to """{"kind":"queueAdd","path":"benchy.gcode"}""",
        PrinterCommand.QueueRemove("000123") to """{"kind":"queueRemove","jobId":"000123"}""",
        PrinterCommand.QueueStart to """{"kind":"queueStart"}""",
        PrinterCommand.QueuePause to """{"kind":"queuePause"}""",
    )

    @Test
    fun `every command is written as the exact JSON Jarvis expects`() {
        for ((command, json) in table) assertEquals(command.toString(), json, command.toJson())
    }

    @Test
    fun `the kind of a command is the name Jarvis knows it by`() {
        assertEquals("pause", PrinterCommand.Pause.kind)
        assertEquals("setTemp", PrinterCommand.SetTemp("extruder", 0.0).kind)
        assertEquals("firmwareRestart", PrinterCommand.FirmwareRestart.kind)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `the table lists all 33 kinds and misses none`() {
        // The sealed serializer's descriptor lists every subclass: a command added without a row here fails this test.
        val sub = PrinterCommand.serializer().descriptor.getElementDescriptor(1)
        val declared = (0 until sub.elementsCount).map { sub.getElementName(it) }.toSet()
        val tabled = table.map { it.first.kind }.toSet()
        assertEquals("Jarvis has 33 command kinds", 33, declared.size)
        assertEquals(declared, tabled)
        assertTrue(table.size >= declared.size)
    }
}
