package com.edith.app.printer.feed

import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertFalse
import com.edith.app.testing.assertTrue
import kotlin.test.Test

/** C39 E7: every case of Jarvis's `commandSettled` / `settleTimeout` tests (`printerCommands.test.ts`), plus the ones its code has. */
class PrinterSettleTest {
    private val idle = PrinterState(
        connection = Connections.READY,
        heaters = listOf(Heater("extruder", "Nozzle", 24.0, 0.0), Heater("heater_bed", "Bed", 23.0, 0.0)),
        fans = listOf(Fan("fan_generic fanM106", "Part cooling", 0.0, true)),
        light = 0.0,
        toolhead = Toolhead(position = listOf(100.0, 100.0, 50.0), homed = "xyz"),
    )
    private val printing = idle.copy(print = PrintInfo(PrinterStates.PRINTING))
    private val paused = printing.copy(print = PrintInfo(PrinterStates.PAUSED, paused = true))
    private val info = SettleInfo(elapsedMs = 1_000, sawMotion = false)

    private fun PrinterState.moved(f: (Toolhead) -> Toolhead) = copy(toolhead = f(toolhead))
    private fun PrinterState.withTargets(nozzle: Double, bed: Double) = copy(heaters = heaters.map { if (it.id == "extruder") it.copy(target = nozzle) else it.copy(target = bed) })
    private fun PrinterState.withQueue(state: String, jobs: List<QueueJob> = listOf(QueueJob("0001", "a.gcode", "A", 1))) = copy(queue = JobQueue(state, jobs))
    private fun ok(c: PrinterCommand, before: PrinterState, now: PrinterState, i: SettleInfo = info) = PrinterSettle.settled(c, before, now, i)

    @Test fun `homing waits for the toolhead to move and stop not only for ZMOD to say homed`() {
        val before = idle.moved { it.copy(homed = "") }
        val home = PrinterCommand.Home(HomeAxes.ALL)
        assertFalse("ZMOD says homed at once, before moving", ok(home, before, idle.moved { it.copy(homed = "xyz", liveVelocity = 0.0) }))
        assertFalse("still moving", ok(home, before, idle.moved { it.copy(homed = "xyz", liveVelocity = 80.0) }, SettleInfo(3_000, sawMotion = true)))
        assertTrue("moved and stopped", ok(home, before, idle.moved { it.copy(homed = "xyz", liveVelocity = 0.0) }, SettleInfo(9_000, sawMotion = true)))
        assertTrue("never seen moving: done after a while", ok(home, before, idle.moved { it.copy(homed = "xyz", liveVelocity = 0.0) }, SettleInfo(6_000, sawMotion = false)))
    }

    @Test fun `homing only some axes needs only those`() {
        val before = idle.moved { it.copy(homed = "") }
        assertTrue(ok(PrinterCommand.Home(HomeAxes.Z), before, idle.moved { it.copy(homed = "z") }, SettleInfo(9_000, true)))
        assertFalse(ok(PrinterCommand.Home(HomeAxes.XY), before, idle.moved { it.copy(homed = "z") }, SettleInfo(9_000, true)))
    }

    @Test fun `a jog waits to reach its target and stop`() {
        val before = idle.moved { it.copy(position = listOf(100.0, 100.0, 50.0)) }
        val jog = PrinterCommand.Jog(Axis.X, 10.0)
        assertFalse(ok(jog, before, idle.moved { it.copy(position = listOf(105.0, 100.0, 50.0), liveVelocity = 60.0) }))
        assertTrue(ok(jog, before, idle.moved { it.copy(position = listOf(110.0, 100.0, 50.0), liveVelocity = 0.0) }))
        assertTrue("within 0.05 mm", ok(jog, before, idle.moved { it.copy(position = listOf(110.04, 100.0, 50.0)) }))
        assertFalse("not within 0.05 mm", ok(jog, before, idle.moved { it.copy(position = listOf(110.2, 100.0, 50.0)) }))
        assertTrue("Y and Z are looked at on their own axis", ok(PrinterCommand.Jog(Axis.Z, -5.0), before, idle.moved { it.copy(position = listOf(100.0, 100.0, 45.0)) }))
    }

    @Test fun `a temperature target waits for the printer to show it`() {
        val heated = idle.withTargets(220.0, 0.0)
        val set = PrinterCommand.SetTemp("extruder", 220.0)
        assertFalse(ok(set, idle, idle))
        assertTrue(ok(set, idle, heated))
        assertTrue("a fractional target is rounded", ok(PrinterCommand.SetTemp("extruder", 219.6), idle, heated))
    }

    @Test fun `a preset waits for both targets and a preset Jarvis no longer has is done at once`() {
        val pla = SettleInfo(1_000, false, listOf(Preset("PLA", 220, 60)))
        val preset = PrinterCommand.Preset("PLA")
        assertFalse(ok(preset, idle, idle.withTargets(220.0, 0.0), pla))
        assertTrue(ok(preset, idle, idle.withTargets(220.0, 60.0), pla))
        assertTrue(ok(PrinterCommand.Preset("GONE"), idle, idle, pla))
    }

    @Test fun `heaters off waits for every target to be zero`() {
        assertFalse(ok(PrinterCommand.HeatersOff, idle, idle.withTargets(220.0, 0.0)))
        assertTrue(ok(PrinterCommand.HeatersOff, idle, idle))
    }

    @Test fun `pause resume cancel and start wait for the print state`() {
        assertFalse(ok(PrinterCommand.Pause, printing, printing))
        assertTrue(ok(PrinterCommand.Pause, printing, paused))
        assertTrue(ok(PrinterCommand.Resume, paused, printing))
        assertFalse(ok(PrinterCommand.Resume, paused, paused))
        assertFalse("printing but still flagged paused", ok(PrinterCommand.Resume, paused, printing.copy(print = PrintInfo(PrinterStates.PRINTING, paused = true))))
        assertTrue(ok(PrinterCommand.Cancel, printing, idle))
        assertFalse(ok(PrinterCommand.Cancel, printing, paused))
        assertTrue(ok(PrinterCommand.Start("a.gcode"), idle, printing))
        assertTrue(ok(PrinterCommand.Start("a.gcode"), idle, paused))
        assertFalse(ok(PrinterCommand.Start("a.gcode"), idle, idle))
    }

    @Test fun `light fans factors and the Z offset wait for their value`() {
        assertTrue(ok(PrinterCommand.Light(1.0), idle, idle.copy(light = 1.0)))
        assertFalse(ok(PrinterCommand.Light(1.0), idle, idle.copy(light = 0.5)))
        assertFalse("a printer with no light never settles a light command", ok(PrinterCommand.Light(1.0), idle, idle.copy(light = null)))
        assertTrue(ok(PrinterCommand.Fan("fan_generic fanM106", 0.5), idle, idle.copy(fans = listOf(Fan("fan_generic fanM106", "Part cooling", 0.5, true)))))
        assertFalse(ok(PrinterCommand.Fan("fan_generic fanM106", 0.5), idle, idle))
        assertTrue("a fan that is gone is done", ok(PrinterCommand.Fan("gone", 0.5), idle, idle))
        assertTrue(ok(PrinterCommand.SpeedFactor(85.0), idle, idle.moved { it.copy(speedFactor = 0.85) }))
        assertFalse(ok(PrinterCommand.SpeedFactor(85.0), idle, idle))
        assertTrue(ok(PrinterCommand.FlowFactor(95.0), idle, idle.moved { it.copy(extrudeFactor = 0.95) }))
        assertTrue(ok(PrinterCommand.ZOffset(0.01), idle, idle.moved { it.copy(zOffset = it.zOffset + 0.01) }))
        assertFalse(ok(PrinterCommand.ZOffset(0.01), idle, idle))
    }

    @Test fun `the queue commands wait for the queue to show them`() {
        val q = idle.withQueue("paused")
        assertTrue(ok(PrinterCommand.QueueStart, idle.withQueue("paused"), idle.withQueue("ready")))
        assertFalse(ok(PrinterCommand.QueueStart, q, q))
        assertTrue(ok(PrinterCommand.QueuePause, idle.withQueue("ready"), idle.withQueue("paused")))
        assertFalse(ok(PrinterCommand.QueuePause, idle.withQueue("ready"), idle.withQueue("ready")))
        assertTrue(ok(PrinterCommand.QueueRemove("0001"), q, idle.withQueue("paused", emptyList())))
        assertFalse(ok(PrinterCommand.QueueRemove("0001"), q, q))
        assertTrue(ok(PrinterCommand.QueueAdd("a.gcode"), idle.withQueue("paused", emptyList()), q))
        assertFalse(ok(PrinterCommand.QueueAdd("a.gcode"), q, q))
    }

    @Test fun `controls with nothing to watch are done at once and every waiting one has a limit`() {
        assertEquals(0L, PrinterSettle.timeoutMs(PrinterCommand.MotorsOff))
        assertEquals(0L, PrinterSettle.timeoutMs(PrinterCommand.EStop))
        assertTrue(ok(PrinterCommand.MotorsOff, idle, idle))
        val waiting = listOf(
            PrinterCommand.Home(HomeAxes.ALL), PrinterCommand.Jog(Axis.X, 1.0), PrinterCommand.SetTemp("extruder", 1.0), PrinterCommand.Pause,
            PrinterCommand.Resume, PrinterCommand.Cancel, PrinterCommand.Start("a"), PrinterCommand.Light(1.0), PrinterCommand.Fan("f", 1.0), PrinterCommand.ZOffset(0.1),
        )
        for (c in waiting) {
            val t = PrinterSettle.timeoutMs(c)
            assertTrue("$c waits", t > 0)
            assertTrue("$c has a limit", t <= 120_000)
        }
    }

    @Test fun `the limits are Jarvis's`() {
        assertEquals(120_000L, PrinterSettle.timeoutMs(PrinterCommand.Home(HomeAxes.ALL)))
        assertEquals(120_000L, PrinterSettle.timeoutMs(PrinterCommand.Pause))
        assertEquals(60_000L, PrinterSettle.timeoutMs(PrinterCommand.Start("a")))
        assertEquals(30_000L, PrinterSettle.timeoutMs(PrinterCommand.Jog(Axis.X, 1.0)))
        assertEquals(15_000L, PrinterSettle.timeoutMs(PrinterCommand.Preset("PLA")))
        assertEquals(15_000L, PrinterSettle.timeoutMs(PrinterCommand.HeatersOff))
        assertEquals(10_000L, PrinterSettle.timeoutMs(PrinterCommand.Light(1.0)))
        assertEquals(10_000L, PrinterSettle.timeoutMs(PrinterCommand.QueueRemove("1")))
        assertEquals(0L, PrinterSettle.timeoutMs(PrinterCommand.Gcode("M105")))
    }
}
