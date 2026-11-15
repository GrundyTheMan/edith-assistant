package com.edith.app.printer

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.home.Capability
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceType
import com.edith.app.home.PrintState
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInResult
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import com.edith.app.tools.framework.IntegrationStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** C34: connecting a printer, reading it, away from home, and pause / resume / cancel with their safety checks. */
class PrinterServiceTest {
    private val printer = FakeMoonraker()
    private val secrets = InMemorySecretStore()
    private val clock = FakeClock(Instant.parse("2026-11-15T15:00:00Z"))
    private fun service() = PrinterService(secrets, printer.api(), clock)
    @After fun tearDown() = printer.shutdown()

    private fun form(address: String = printer.address, key: String = "") = mapOf("address" to address, "apikey" to key)

    private suspend fun connected(): PrinterService {
        val s = service()
        assertEquals(SignInResult.Done, s.signIn(form()))
        return s
    }

    private fun why(o: Outcome<*>): String = ((o as Outcome.Failure).error as AppError.ToolFailed).detail

    private suspend fun PrinterService.printerDevice() = (backend.devices() as Outcome.Success).value.single()

    // ---- Connecting ----

    @Test fun `connecting saves the address and key, and a later start picks them up`() = runBlocking {
        printer.requireKey = "k-123"
        val s = service()
        assertEquals(SignInResult.Done, s.signIn(form(key = "k-123")))
        assertEquals(ServiceState.Connected, s.state.value)
        assertEquals(IntegrationStatus.Connected, s.backend.status.value)
        val later = service().also { it.load() }
        assertEquals(ServiceState.Connected, later.state.value)
        assertTrue("the saved key still works", later.backend.devices() is Outcome.Success)
        assertTrue(printer.requests.last().headers["X-Api-Key"] == "k-123")
    }

    @Test fun `the form asks for an address and an optional key`() {
        val f = service().form
        assertEquals(listOf("address", "apikey"), f.fields.map { it.key })
        assertTrue(f.fields[1].secret && f.fields[1].optional); assertFalse(f.fields[0].optional)
    }

    @Test fun `a bad or unreachable address is said in words and nothing is saved`() = runBlocking {
        val s = service()
        assertTrue((s.signIn(form(address = "")) as SignInResult.Failed).message.contains("Type the printer's address"))
        val away = printer.address
        printer.shutdown()
        assertTrue((s.signIn(form(address = away)) as SignInResult.Failed).message.contains("Couldn't reach"))
        assertNull(secrets.get(PrinterService.SECRET_KEY))
        assertEquals(ServiceState.NotConnected, s.state.value)
    }

    @Test fun `a printer that wants a key asks for it`() = runBlocking {
        printer.requireKey = "needed"
        val s = service()
        assertTrue((s.signIn(form()) as SignInResult.Failed).message.contains("API key"))
        assertTrue((s.signIn(form(key = "wrong")) as SignInResult.Failed).message.contains("API key"))
        assertEquals(SignInResult.Done, s.signIn(form(key = "needed")))
    }

    @Test fun `disconnecting forgets the printer`() = runBlocking {
        val s = connected()
        s.disconnect()
        assertEquals(ServiceState.NotConnected, s.state.value)
        assertNull(secrets.get(PrinterService.SECRET_KEY))
        assertTrue(s.backend.devices() is Outcome.Failure)
    }

    // ---- Reading ----

    @Test fun `the printer is one device with its job, temperatures and camera`() = runBlocking {
        val d = connected().printerDevice()
        assertEquals(DeviceType.Printer, d.type)
        assertEquals("3D printer", d.name)
        assertTrue(d.online)
        assertEquals(setOf(Capability.PrintControl, Capability.Snapshot), d.capabilities)
        val job = d.state!!.print!!
        assertEquals(PrintState.Printing, job.state); assertEquals("Benchy", job.title)
        assertEquals("the slicer's 3600 s estimate, 1800 s printed", 1800L, job.timeLeftSeconds)
        assertEquals(2, d.state!!.heaters.size)
    }

    @Test fun `a printer with no webcam has no camera button`() = runBlocking {
        printer.webcams = emptyList()
        assertEquals(setOf<Capability>(Capability.PrintControl), connected().printerDevice().capabilities)
    }

    @Test fun `a file's estimate is asked for once while it prints`() = runBlocking {
        val s = connected()
        s.printerDevice(); s.printerDevice(); s.printerDevice()
        assertEquals(1, printer.count("/server/files/metadata"))
        assertEquals("the webcams too", 1, printer.count("/server/webcams/list"))
    }

    @Test fun `no estimate from the printer falls back to the pace`() = runBlocking {
        printer.estimatedTime = null
        printer.status = printerStatus(fileProgress = 0.25, printDuration = 600.0)
        assertEquals(1800L, connected().printerDevice().state!!.print!!.timeLeftSeconds)
    }

    @Test fun `an idle printer asks for no estimate`() = runBlocking {
        printer.status = printerStatus(state = "standby", filename = null)
        val d = connected().printerDevice()
        assertEquals(PrintState.Idle, d.state!!.print!!.state)
        assertEquals(0, printer.count("/server/files/metadata"))
    }

    @Test fun `Klipper not ready is shown as an error on the card`() = runBlocking {
        printer.status = printerStatus(klippy = "shutdown", klippyMessage = "Heater failed")
        val job = connected().printerDevice().state!!.print!!
        assertEquals(PrintState.Error, job.state); assertEquals("Heater failed", job.message)
    }

    // ---- Away from home ----

    @Test fun `away from home is not an error - the card is offline and nothing fails`() = runBlocking {
        val s = connected()
        s.printerDevice()
        printer.shutdown()
        val r = s.backend.devices()
        assertTrue("a Success, so the Home tab shows no banner", r is Outcome.Success)
        val d = (r as Outcome.Success).value.single()
        assertFalse(d.online); assertNull(d.state)
        assertTrue("it keeps its camera button from before", d.has(Capability.Snapshot))
        assertEquals(ServiceState.Connected, s.state.value)
    }

    @Test fun `asking one printer's state away from home says so`() = runBlocking {
        val s = connected()
        printer.shutdown()
        assertTrue(why(s.backend.state("printer")).contains("home Wi-Fi"))
    }

    @Test fun `a refused key at a later read means connect again`() = runBlocking {
        val s = connected()
        printer.requireKey = "changed"
        assertTrue(s.backend.devices() is Outcome.Failure)
        assertEquals(ServiceState.NeedsSignIn, s.state.value)
    }

    // ---- Commands ----

    private fun paused() { printer.status = printerStatus(state = "paused") }

    @Test fun `pausing a print sends the command and answers what the printer reports after`() = runBlocking {
        val s = connected()
        printer.onPrint = { if (it == "pause") paused() }
        val r = s.backend.command("printer", DeviceCommand.Pause)
        assertEquals(PrintState.Paused, (r as Outcome.Success).value.print!!.state)
        assertEquals(listOf("/printer/print/pause"), printer.posts())
    }

    @Test fun `resuming and cancelling send theirs`() = runBlocking {
        val s = connected()
        printer.status = printerStatus(state = "paused")
        printer.onPrint = { if (it == "resume") printer.status = printerStatus(state = "printing") }
        assertEquals(PrintState.Printing, (s.backend.command("printer", DeviceCommand.Resume) as Outcome.Success).value.print!!.state)
        printer.onPrint = { if (it == "cancel") printer.status = printerStatus(state = "cancelled") }
        assertEquals(PrintState.Cancelled, (s.backend.command("printer", DeviceCommand.CancelPrint) as Outcome.Success).value.print!!.state)
        assertEquals(listOf("/printer/print/resume", "/printer/print/cancel"), printer.posts())
    }

    @Test fun `asking for what is already so is a success and sends nothing`() = runBlocking {
        val s = connected()
        assertTrue("resuming a print that is going", s.backend.command("printer", DeviceCommand.Resume) is Outcome.Success)
        paused()
        assertTrue("pausing a paused print", s.backend.command("printer", DeviceCommand.Pause) is Outcome.Success)
        assertEquals(emptyList<String>(), printer.posts())
    }

    @Test fun `a command that makes no sense now is refused in words and sends nothing`() = runBlocking {
        val s = connected()
        printer.status = printerStatus(state = "standby", filename = null)
        assertEquals("it isn't printing", why(s.backend.command("printer", DeviceCommand.Pause)))
        assertEquals("nothing is paused", why(s.backend.command("printer", DeviceCommand.Resume)))
        assertEquals("there's no print to cancel", why(s.backend.command("printer", DeviceCommand.CancelPrint)))
        printer.status = printerStatus(state = "complete")
        assertEquals("there's no print to cancel", why(s.backend.command("printer", DeviceCommand.CancelPrint)))
        assertEquals(emptyList<String>(), printer.posts())
    }

    @Test fun `commands wait for Klipper, and a printer can only pause resume or cancel`() = runBlocking {
        val s = connected()
        printer.status = printerStatus(klippy = "shutdown", klippyMessage = "MCU shutdown")
        assertTrue(why(s.backend.command("printer", DeviceCommand.Pause)).contains("Klipper isn't ready: MCU shutdown"))
        printer.status = printerStatus()
        assertTrue(why(s.backend.command("printer", DeviceCommand.TurnOn)).contains("only pause, resume or cancel"))
        assertEquals(emptyList<String>(), printer.posts())
    }

    @Test fun `a printer that refuses the command says why`() = runBlocking {
        val s = connected()
        printer.overrides["/printer/print/pause"] = { mockwebserver3.MockResponse.Builder().code(400).body("""{"error":{"message":"Printer busy"}}""").build() }
        assertEquals("Printer busy", why(s.backend.command("printer", DeviceCommand.Pause)))
    }

    @Test fun `a command away from home is refused without a fuss`() = runBlocking {
        val s = connected()
        printer.shutdown()
        assertTrue(why(s.backend.command("printer", DeviceCommand.Pause)).contains("home Wi-Fi"))
    }

    // ---- The camera ----

    @Test fun `the camera's picture comes from the webcam's snapshot address`() = runBlocking {
        val r = connected().backend.snapshot("printer")
        assertEquals(printer.picture.toList(), (r as Outcome.Success).value.toList())
    }

    @Test fun `no camera, or away from home, is said in words`() = runBlocking {
        printer.webcams = emptyList()
        val s = connected()
        assertTrue(why(s.backend.snapshot("printer")).contains("no camera"))
        printer.webcams = listOf("Chamber" to "/webcam/?action=snapshot")
        val t = connected()
        t.printerDevice()
        printer.shutdown()
        assertTrue(why(t.backend.snapshot("printer")).contains("home Wi-Fi"))
    }

    @Test fun `a service that isn't connected refuses everything`() = runBlocking {
        val s = service()
        assertTrue(s.backend.devices() is Outcome.Failure)
        assertTrue(s.backend.command("printer", DeviceCommand.Pause) is Outcome.Failure)
        assertTrue(s.backend.snapshot("printer") is Outcome.Failure)
    }
}
