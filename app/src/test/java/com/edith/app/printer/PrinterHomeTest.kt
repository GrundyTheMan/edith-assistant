package com.edith.app.printer

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.home.Approval
import com.edith.app.home.Capability
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceState
import com.edith.app.home.DeviceType
import com.edith.app.home.Heat
import com.edith.app.home.Home
import com.edith.app.home.HomeActions
import com.edith.app.home.HomeControlTool
import com.edith.app.home.HomeDevice
import com.edith.app.home.HomeDevicesTool
import com.edith.app.home.MemoryCommandLog
import com.edith.app.home.MemoryHomePrefs
import com.edith.app.home.PrintJob
import com.edith.app.home.PrintState
import com.edith.app.home.PrinterStatusTool
import com.edith.app.home.SignInResult
import com.edith.app.settings.Settings
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import com.edith.app.tools.framework.IntegrationStatus
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.framework.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** C34 on top of C25: the words for a print, the tools' answers, the cancel card, and away from home showing no banner. */
class PrinterHomeTest {
    private val printer = FakeMoonraker()
    private val clock = FakeClock(Instant.parse("2026-11-15T18:00:00Z"))
    private val service = PrinterService(InMemorySecretStore(), printer.api(), clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel(); printer.shutdown() }

    private val ctx get() = ToolContext(clock, Settings(), AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined))
    private fun args(spec: ToolSpec, vararg pairs: Pair<String, String>) = ToolArgs(buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }, spec)

    private suspend fun printerHome(): Home {
        assertEquals(SignInResult.Done, service.signIn(mapOf("address" to printer.address, "apikey" to "")))
        return Home(MemoryHomePrefs(), MemoryCommandLog(), scope, clock).also { h ->
            h.register(service)
            // Home publishes its backends and status asynchronously: wait for them before asking.
            withTimeout(3_000) { h.backends.first { it.size == 1 } }
            withTimeout(3_000) { h.status.first { it == IntegrationStatus.Connected } }
        }
    }

    private fun device(print: PrintJob?, heaters: List<Heat> = emptyList(), online: Boolean = true) = HomeDevice(
        "printer", "3D printer", null, DeviceType.Printer, setOf(Capability.PrintControl, Capability.Snapshot), "printer",
        online = online, state = DeviceState(print = print, heaters = heaters),
    )

    // ---- The words ----

    @Test fun `a print is described in words`() {
        val heaters = listOf(Heat("Nozzle", 215.0, 220.0), Heat("Bed", 60.0, 60.0))
        assertEquals("Printing Benchy, 42%, about 1 h 5 min left, layer 126 of 300, Nozzle 215/220°C, Bed 60/60°C",
            HomeActions.stateText(device(PrintJob(PrintState.Printing, "Benchy", 0.42, 3_900, 126, 300), heaters)))
        assertEquals("Paused Benchy, 42%, Nozzle 215/220°C, Bed 60/60°C", HomeActions.stateText(device(PrintJob(PrintState.Paused, "Benchy", 0.42, 3_900), heaters)))
        assertEquals("Complete Benchy", HomeActions.stateText(device(PrintJob(PrintState.Complete, "Benchy", 1.0))))
        assertEquals("Idle, Nozzle 24°C, Bed 23.5°C", HomeActions.stateText(device(PrintJob(PrintState.Idle, "old file"), listOf(Heat("Nozzle", 24.0, 0.0), Heat("Bed", 23.5, 0.0)))))
        assertEquals("Error: Heater failed", HomeActions.stateText(device(PrintJob(PrintState.Error, message = "Heater failed"))))
        assertEquals("Cancelled Benchy", HomeActions.stateText(device(PrintJob(PrintState.Cancelled, "Benchy"))))
    }

    @Test fun `times are said the way a person would`() {
        assertEquals("under a minute", HomeActions.duration(20))
        assertEquals("1 min", HomeActions.duration(60))
        assertEquals("12 min", HomeActions.duration(720))
        assertEquals("1 h", HomeActions.duration(3_600))
        assertEquals("1 h 5 min", HomeActions.duration(3_900))
        assertEquals("rounded to the nearest minute", "30 min", HomeActions.duration(1_799))
    }

    // ---- The commands ----

    @Test fun `pause, resume and cancel are the printer's actions, and only the printer's`() {
        val p = device(PrintJob(PrintState.Printing, "Benchy"))
        assertEquals(DeviceCommand.Pause, HomeActions.parse(p, "pause", null).getOrThrow())
        assertEquals(DeviceCommand.Resume, HomeActions.parse(p, "resume", null).getOrThrow())
        assertEquals(DeviceCommand.CancelPrint, HomeActions.parse(p, "cancel", null).getOrThrow())
        assertEquals(listOf("pause", "resume", "cancel"), HomeActions.actionsFor(p.copy(capabilities = setOf(Capability.PrintControl))))
        val lamp = HomeDevice("l", "Lamp", null, DeviceType.Light, setOf(Capability.OnOff), "demo")
        assertTrue(HomeActions.parse(lamp, "pause", null).isFailure)
        assertTrue(HomeActions.parse(p, "turn_on", null).isFailure)
    }

    @Test fun `cancelling a print always needs a card, pausing and resuming do not`() {
        val p = device(PrintJob(PrintState.Printing, "Benchy"))
        assertEquals(Approval.Card, HomeActions.approval(p, DeviceCommand.CancelPrint))
        assertEquals(Approval.None, HomeActions.approval(p, DeviceCommand.Pause))
        assertEquals(Approval.None, HomeActions.approval(p, DeviceCommand.Resume))
        val card = HomeControlTool.card(p, DeviceCommand.CancelPrint)!!
        assertEquals("Cancel the print: 3D printer?", card.title)
        assertEquals("Cancel print", card.confirmLabel); assertEquals("Keep printing", card.denyLabel)
        assertFalse("a card the owner can also approve by voice in hands-free is not screen-only", card.screenOnly)
        assertTrue(card.details.any { it.second.startsWith("Printing Benchy") })
        assertNull(HomeControlTool.card(p, DeviceCommand.Pause))
    }

    @Test fun `a command is done when the state shows it`() {
        fun s(state: PrintState) = DeviceState(print = PrintJob(state))
        assertTrue(HomeActions.shows(s(PrintState.Paused), DeviceCommand.Pause)); assertFalse(HomeActions.shows(s(PrintState.Printing), DeviceCommand.Pause))
        assertTrue(HomeActions.shows(s(PrintState.Printing), DeviceCommand.Resume)); assertFalse(HomeActions.shows(s(PrintState.Paused), DeviceCommand.Resume))
        assertTrue(HomeActions.shows(s(PrintState.Cancelled), DeviceCommand.CancelPrint)); assertTrue(HomeActions.shows(s(PrintState.Idle), DeviceCommand.CancelPrint))
        assertFalse(HomeActions.shows(s(PrintState.Printing), DeviceCommand.CancelPrint))
        assertEquals(Pair("cancel", null), HomeActions.logParts(DeviceCommand.CancelPrint))
    }

    // ---- The tools ----

    @Test fun `how's the print going - the answer has the job and the temperatures`() = runBlocking {
        val tool = PrinterStatusTool(printerHome()) { clock.now().toEpochMilli() }
        val text = (tool.execute(args(tool.spec), ctx) as ToolResult.Ok).text
        assertEquals("3D printer: Printing Benchy, 42%, about 30 min left, Nozzle 215/220°C, Bed 60/60°C.", text)
    }

    @Test fun `home_devices lists the printer too`() = runBlocking {
        val tool = HomeDevicesTool(printerHome()) { clock.now().toEpochMilli() }
        val text = (tool.execute(args(tool.spec, "filter" to "printer"), ctx) as ToolResult.Ok).text
        assertTrue(text, "3D printer (3D printer): Printing Benchy, 42%" in text)
    }

    @Test fun `cancelling by chat shows a card first, and then cancels`() = runBlocking {
        val home = printerHome()
        printer.onPrint = { if (it == "cancel") printer.status = printerStatus(state = "cancelled") }
        val tool = HomeControlTool(home)
        val a = args(tool.spec, "device" to "3D printer", "action" to "cancel")
        assertEquals("Cancel the print: 3D printer?", tool.confirmation(a)!!.title)
        assertTrue("nothing is sent before the owner agrees", printer.posts().isEmpty())
        val result = tool.execute(a, ctx) as ToolResult.Ok
        assertTrue(result.text, "Done: cancel the print" in result.text)
        assertEquals(listOf("/printer/print/cancel"), printer.posts())
    }

    @Test fun `pausing by chat needs no card`() = runBlocking {
        val home = printerHome()
        printer.onPrint = { if (it == "pause") printer.status = printerStatus(state = "paused") }
        val tool = HomeControlTool(home)
        val a = args(tool.spec, "device" to "3D printer", "action" to "pause")
        assertNull(tool.confirmation(a))
        val result = tool.execute(a, ctx) as ToolResult.Ok
        assertTrue(result.text, "It now reports: Paused Benchy" in result.text)
    }

    @Test fun `a refused command is said, not claimed done`() = runBlocking {
        val home = printerHome()
        printer.status = printerStatus(state = "standby", filename = null)
        val tool = HomeControlTool(home)
        val result = tool.execute(args(tool.spec, "device" to "3D printer", "action" to "pause"), ctx)
        assertTrue(result.toString(), result is ToolResult.Error && "it isn't printing" in result.text)
    }

    @Test fun `no printer connected says so`() = runBlocking {
        val home = Home(MemoryHomePrefs(), MemoryCommandLog(), scope, clock)
        val tool = PrinterStatusTool(home) { clock.now().toEpochMilli() }
        assertTrue((tool.execute(args(tool.spec), ctx) as ToolResult.Ok).text.contains("No 3D printer is connected"))
    }

    // ---- Away from home ----

    @Test fun `away from home the Home tab has no banner, and the tools say so with the last report`() = runBlocking {
        val home = printerHome()
        val tool = PrinterStatusTool(home) { clock.now().toEpochMilli() }
        assertTrue(home.refresh().problems.isEmpty())
        printer.shutdown()
        clock.advance(java.time.Duration.ofMinutes(5))
        val view = home.refresh()
        assertTrue("no problem banner: ${view.problems}", view.problems.isEmpty())
        val d = view.devices.single()
        assertFalse(d.online)
        val text = (tool.execute(args(tool.spec), ctx) as ToolResult.Ok).text
        assertTrue(text, text.startsWith("3D printer: not reachable now (it works on the home Wi-Fi). Last reported 5 min ago: Printing Benchy"))
        val devices = HomeDevicesTool(home) { clock.now().toEpochMilli() }
        assertTrue((devices.execute(args(devices.spec), ctx) as ToolResult.Ok).text.contains("not reachable (it works on the home Wi-Fi, or it is off)"))
    }

    @Test fun `a printer never reached has no last report to show`() = runBlocking {
        assertEquals(SignInResult.Done, service.signIn(mapOf("address" to printer.address, "apikey" to "")))
        printer.shutdown()
        val home = Home(MemoryHomePrefs(), MemoryCommandLog(), scope, clock).also { h ->
            h.register(service)
            withTimeout(3_000) { h.backends.first { it.size == 1 } }
            withTimeout(3_000) { h.status.first { it == IntegrationStatus.Connected } }
        }
        val tool = PrinterStatusTool(home) { clock.now().toEpochMilli() }
        assertEquals("3D printer: not reachable. It works on the home Wi-Fi, or it is off.", (tool.execute(args(tool.spec), ctx) as ToolResult.Ok).text)
    }
}
