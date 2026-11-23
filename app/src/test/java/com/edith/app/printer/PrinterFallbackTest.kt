package com.edith.app.printer

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.home.Capability
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceState
import com.edith.app.home.Heat
import com.edith.app.home.Home
import com.edith.app.home.MemoryCommandLog
import com.edith.app.home.MemoryHomePrefs
import com.edith.app.home.PrintJob
import com.edith.app.home.PrintState
import com.edith.app.home.PrinterStatusTool
import com.edith.app.home.SignInResult
import com.edith.app.printer.feed.Connections
import com.edith.app.printer.feed.Heater
import com.edith.app.printer.feed.PrintInfo
import com.edith.app.printer.feed.PrinterAlert
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.feed.PrinterStates
import com.edith.app.printer.feed.PrinterToHome
import com.edith.app.printer.feed.Layer
import com.edith.app.settings.Settings
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import com.edith.app.tools.framework.IntegrationStatus
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * C39 E6: when the printer does not answer directly (away from home, or the phone is on another network) and Jarvis shares it,
 * Home and the printer tool use Jarvis's answer, say so, and never use Jarvis when the direct read works.
 */
class PrinterFallbackTest {
    private val printer = FakeMoonraker()
    private val clock = FakeClock(Instant.parse("2026-11-18T18:00:00Z"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel(); printer.shutdown() }

    private class FakeJarvisRead(var answer: DeviceState? = null, var boom: Boolean = false) : JarvisPrinterRead {
        var calls = 0
        override suspend fun read(): DeviceState? { calls++; if (boom) error("Jarvis exploded"); return answer }
    }

    private val viaJarvis = DeviceState(
        print = PrintJob(PrintState.Printing, "Benchy", 0.4, 2_700, 12, 240, viaJarvis = true),
        heaters = listOf(Heat("Nozzle", 219.8, 220.0), Heat("Bed", 60.0, 60.0)),
    )

    private suspend fun connected(read: JarvisPrinterRead?): PrinterService {
        val s = PrinterService(InMemorySecretStore(), printer.api(), clock, read)
        assertEquals(SignInResult.Done, s.signIn(mapOf("address" to printer.address, "apikey" to "")))
        return s
    }

    private suspend fun PrinterService.devices() = (backend.devices() as Outcome.Success).value.single()
    private fun why(o: Outcome<*>): String = ((o as Outcome.Failure).error as AppError.ToolFailed).detail

    // --- The mapping ----------------------------------------------------------------------------------------------------

    private fun state(connection: String = Connections.READY, print: PrintInfo = PrintInfo(), alert: PrinterAlert? = null, message: String? = null) =
        PrinterState(connection = connection, print = print, alert = alert, message = message, heaters = listOf(Heater("extruder", "Nozzle", 215.0, 220.0), Heater("heater_bed", "Bed", 60.0, 60.0)))

    @Test fun `a print on Jarvis becomes the Home tab's print, with the layer and the temperatures`() {
        val s = PrinterToHome.deviceState(state(print = PrintInfo(PrinterStates.PRINTING, title = "Benchy", progress = 0.42, timeLeft = 3_900.0, layer = Layer(126, 300))))!!
        val job = s.print!!
        assertEquals(PrintState.Printing, job.state)
        assertEquals("Benchy", job.title)
        assertEquals(0.42, job.progress, 0.0001)
        assertEquals(3_900L, job.timeLeftSeconds)
        assertEquals(126, job.layer); assertEquals(300, job.layers)
        assertTrue(job.viaJarvis)
        assertEquals(listOf(Heat("Nozzle", 215.0, 220.0), Heat("Bed", 60.0, 60.0)), s.heaters)
    }

    @Test fun `an idle printer has no time left, and each print state maps`() {
        val idle = PrinterToHome.deviceState(state(print = PrintInfo(PrinterStates.STANDBY, timeLeft = 99.0)))!!.print!!
        assertEquals(PrintState.Idle, idle.state); assertNull(idle.timeLeftSeconds)
        assertEquals(PrintState.Paused, PrinterToHome.deviceState(state(print = PrintInfo(PrinterStates.PAUSED)))!!.print!!.state)
        assertEquals(PrintState.Complete, PrinterToHome.deviceState(state(print = PrintInfo(PrinterStates.COMPLETE)))!!.print!!.state)
        assertEquals(PrintState.Cancelled, PrinterToHome.deviceState(state(print = PrintInfo(PrinterStates.CANCELLED)))!!.print!!.state)
        assertEquals(PrintState.Error, PrinterToHome.deviceState(state(print = PrintInfo(PrinterStates.ERROR)))!!.print!!.state)
        assertEquals("a state from a newer Jarvis is shown as idle", PrintState.Idle, PrinterToHome.deviceState(state(print = PrintInfo("levelling")))!!.print!!.state)
    }

    @Test fun `the printer's own error line is the message when Klipper sent none`() {
        assertEquals("Filament runout", PrinterToHome.deviceState(state(alert = PrinterAlert("Filament runout", 1)))!!.print!!.message)
        assertEquals("Klipper's own", PrinterToHome.deviceState(state(print = PrintInfo(message = "Klipper's own"), alert = PrinterAlert("Filament runout", 1)))!!.print!!.message)
    }

    @Test fun `Klipper trouble is an error with Jarvis's words, and a printer Jarvis cannot see is no answer`() {
        val down = PrinterToHome.deviceState(state(Connections.KLIPPY_SHUTDOWN, message = "Heater not heating"))!!.print!!
        assertEquals(PrintState.Error, down.state); assertEquals("Heater not heating", down.message); assertTrue(down.viaJarvis)
        assertNull(PrinterToHome.deviceState(state(Connections.UNREACHABLE)))
        assertNull(PrinterToHome.deviceState(state(Connections.NONE)))
    }

    // --- The service ----------------------------------------------------------------------------------------------------

    @Test fun `when the printer answers directly Jarvis is never asked`() = runBlocking {
        val jarvis = FakeJarvisRead(viaJarvis)
        val s = connected(jarvis)
        val d = s.devices()
        assertTrue(d.online)
        assertFalse("direct state is not marked", d.state?.print?.viaJarvis == true)
        assertEquals(0, jarvis.calls)
        assertTrue(s.backend.state("printer") is Outcome.Success)
        assertEquals(0, jarvis.calls)
    }

    @Test fun `when the printer does not answer and Jarvis shares it, the tile shows Jarvis's state and says so`() = runBlocking {
        val jarvis = FakeJarvisRead(viaJarvis)
        val s = connected(jarvis)
        printer.shutdown()
        val d = s.devices()
        assertTrue(d.online)
        assertEquals("Benchy", d.state?.print?.title)
        assertTrue(d.state?.print?.viaJarvis == true)
        assertFalse("no direct camera is asked for through Jarvis", d.has(Capability.Snapshot))
        assertEquals(1, jarvis.calls)
        val st = s.backend.state("printer") as Outcome.Success
        assertEquals(PrintState.Printing, st.value.print?.state)
    }

    @Test fun `without a Jarvis answer the printer is simply not reachable, as before`() = runBlocking {
        val s = connected(FakeJarvisRead(answer = null))
        printer.shutdown()
        val d = s.devices()
        assertFalse(d.online); assertNull(d.state)
        assertTrue(why(s.backend.state("printer")).startsWith("it isn't reachable"))
    }

    @Test fun `a Jarvis that fails is no answer, never a crash`() = runBlocking {
        val s = connected(FakeJarvisRead(boom = true))
        printer.shutdown()
        assertFalse(s.devices().online)
    }

    @Test fun `a service with no Jarvis wired in behaves exactly as it did`() = runBlocking {
        val s = connected(null)
        printer.shutdown()
        assertFalse(s.devices().online)
        assertEquals(PrinterService.AWAY, why(s.backend.command("printer", DeviceCommand.Pause)))
    }

    @Test fun `a command while away points to the Printer page and never goes through Jarvis`() = runBlocking {
        val jarvis = FakeJarvisRead(viaJarvis)
        val s = connected(jarvis)
        printer.shutdown()
        val why = why(s.backend.command("printer", DeviceCommand.Pause))
        assertEquals(PrinterService.AWAY_COMMAND, why)
        assertTrue(why.contains("Printer page"))
        assertEquals("a command does not ask Jarvis for anything", 0, jarvis.calls)
    }

    // --- The chat tool --------------------------------------------------------------------------------------------------

    private val ctx get() = ToolContext(clock, Settings(), AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined))

    private suspend fun statusText(service: PrinterService): String {
        assertEquals(SignInResult.Done, service.signIn(mapOf("address" to printer.address, "apikey" to "")))
        val home = Home(MemoryHomePrefs(), MemoryCommandLog(), scope, clock).also { h ->
            h.register(service)
            withTimeout(3_000) { h.backends.first { it.size == 1 } }
            withTimeout(3_000) { h.status.first { it == IntegrationStatus.Connected } }
        }
        printer.shutdown()
        home.refresh()
        val tool = PrinterStatusTool(home) { clock.now().toEpochMilliseconds() }
        return (tool.execute(ToolArgs(buildJsonObject {}, tool.spec), ctx) as ToolResult.Ok).text
    }

    @Test fun `printer_status says it came through Jarvis, with the layer and temperatures`() = runBlocking {
        val text = statusText(PrinterService(InMemorySecretStore(), printer.api(), clock, FakeJarvisRead(viaJarvis)))
        assertTrue(text, text.contains("Printing Benchy, 40%, about 45 min left, layer 12 of 240, Nozzle 219.8/220°C, Bed 60/60°C"))
        assertTrue(text, text.contains("(via Jarvis)"))
    }

    @Test fun `printer_status mentions the printer's latest error line even while it is printing`() = runBlocking {
        val withAlert = viaJarvis.copy(print = viaJarvis.print!!.copy(message = "Filament runout detected"))
        val text = statusText(PrinterService(InMemorySecretStore(), printer.api(), clock, FakeJarvisRead(withAlert)))
        assertTrue(text, text.contains("The printer's latest error line: Filament runout detected"))
    }

    @Test fun `printer_status without Jarvis says not reachable, as before`() = runBlocking {
        val text = statusText(PrinterService(InMemorySecretStore(), printer.api(), clock, null))
        assertTrue(text, text.contains("not reachable"))
        assertFalse(text.contains("via Jarvis"))
    }
}
