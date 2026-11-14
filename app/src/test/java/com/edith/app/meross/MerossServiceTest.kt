package com.edith.app.meross

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.home.Approval
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceType
import com.edith.app.home.DoorState
import com.edith.app.home.HomeActions
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInResult
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import com.edith.app.tools.framework.IntegrationStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** C26: the Meross service: sign-in kept without the password, devices and doors, open/close, and a refused token. */
class MerossServiceTest {
    private val cloud = FakeMerossCloud()
    private val bus = FakeMerossBus()
    private val secrets = InMemorySecretStore()
    private val clock = FakeClock(Instant.parse("2026-11-14T15:00:00Z"))
    private val service = MerossService(secrets, cloud.api(clock), bus, clock)
    private val backend get() = service.backend
    private val sign = mapOf("email" to "owner@example.com", "password" to "hunter2")

    @After fun tearDown() = cloud.shutdown()

    private suspend fun signedIn() {
        cloud.answers["/v1/Auth/signIn"] = { cloud.loginOk() }
        assertEquals(SignInResult.Done, service.signIn(sign))
    }

    private fun garage(open: Int = 0, lm: Long = 1_794_000_000) {
        cloud.answers["/v1/Device/devList"] = { cloud.devList(cloud.device("u1", "Garage Door")) }
        bus.replies[Triple("u1", "GET", "Appliance.System.All")] = MerossReply.Ok(systemAll(Triple(0, open, lm)))
    }

    @Test fun `signing in keeps the token and key encrypted but never the password`() = runTest {
        assertEquals(ServiceState.NotConnected, service.state.value)
        assertEquals(IntegrationStatus.NotConnected, backend.status.value)
        signedIn()
        assertEquals(ServiceState.Connected, service.state.value)
        assertEquals(IntegrationStatus.Connected, backend.status.value)
        val saved = secrets.get(MerossService.SECRET_KEY)!!
        assertTrue("tok-1" in saved && "key-1" in saved)
        assertFalse("the password isn't kept", "hunter2" in saved || MerossProtocol.md5("hunter2") in saved)
        // A restart picks it up again.
        val again = MerossService(secrets, cloud.api(clock), bus, clock).also { it.load() }
        assertEquals(ServiceState.Connected, again.state.value)
    }

    @Test fun `sign-in problems come back in plain words`() = runTest {
        cloud.answers["/v1/Auth/signIn"] = { cloud.status(1004) }
        assertEquals(SignInResult.Failed("Meross says the email or password isn't right."), service.signIn(sign))
        cloud.answers["/v1/Auth/signIn"] = { cloud.status(1033) }
        assertTrue(service.signIn(sign) is SignInResult.NeedsCode)
        assertTrue(service.signIn(mapOf("email" to "", "password" to "")) is SignInResult.Failed)
        assertEquals(ServiceState.NotConnected, service.state.value)
        assertNull(secrets.get(MerossService.SECRET_KEY))
    }

    @Test fun `the garage door is read with its state and when it last moved`() = runTest {
        signedIn(); garage(open = 1)
        val d = (backend.devices() as Outcome.Success).value.single()
        assertEquals(listOf("Garage Door", DeviceType.GarageDoor, "u1#0", "meross"), listOf(d.name, d.type, d.id, d.backend))
        assertEquals(DoorState.Open, d.state?.door)
        assertEquals(1_794_000_000_000L, d.state?.changedAt)
        assertEquals("Open", HomeActions.stateText(d))
        assertTrue(d.online)
    }

    @Test fun `only garage openers are kept, an offline one is not responding, and a silent one too`() = runTest {
        signedIn()
        cloud.answers["/v1/Device/devList"] = { cloud.devList(cloud.device("u1", "Garage"), cloud.device("u2", "Lamp", "mss110"), cloud.device("u3", "Back garage", online = 2), cloud.device("u4", "Silent", "msg200")) }
        bus.replies[Triple("u1", "GET", "Appliance.System.All")] = MerossReply.Ok(systemAll(Triple(0, 0, 0)))
        val list = (backend.devices() as Outcome.Success).value
        assertEquals(listOf("Garage", "Back garage", "Silent"), list.map { it.name })
        assertEquals(listOf(true, false, false), list.map { it.online })
        assertEquals(listOf(DoorState.Closed, null, null), list.map { it.state?.door })
        assertEquals("only the online ones were asked, the silent one twice", listOf("u1", "u4", "u4"), bus.seen.map { it.device }.sorted())
    }

    @Test fun `a two-door opener gives two devices`() = runTest {
        signedIn()
        cloud.answers["/v1/Device/devList"] = { cloud.devList(cloud.device("u1", "Garage", "msg200")) }
        bus.replies[Triple("u1", "GET", "Appliance.System.All")] = MerossReply.Ok(systemAll(Triple(1, 0, 0), Triple(2, 1, 0)))
        val list = (backend.devices() as Outcome.Success).value
        assertEquals(listOf("Garage 1" to "u1#1", "Garage 2" to "u1#2"), list.map { it.name to it.id })
        assertEquals(listOf(DoorState.Closed, DoorState.Open), list.map { it.state?.door })
    }

    @Test fun `opening and closing send the signed-off payload and report the door moving`() = runTest {
        signedIn(); garage()
        val door = (backend.devices() as Outcome.Success).value.single()
        bus.replies[Triple("u1", "SET", "Appliance.GarageDoor.State")] = MerossReply.Ok(JsonObject(emptyMap()))
        val opening = (backend.command(door.id, DeviceCommand.Open) as Outcome.Success).value
        assertEquals(DoorState.Opening, opening.door)
        assertEquals(clock.now().toEpochMilli(), opening.changedAt)
        assertEquals(DoorState.Closing, (backend.command(door.id, DeviceCommand.Close) as Outcome.Success).value.door)
        val sets = bus.seen.filter { it.method == "SET" }
        assertEquals("""{"state":{"channel":0,"open":1,"uuid":"u1"}}""", sets[0].payload.toString())
        assertEquals("""{"state":{"channel":0,"open":0,"uuid":"u1"}}""", sets[1].payload.toString())
        assertTrue("nothing else is a garage door's to do", backend.command(door.id, DeviceCommand.TurnOn) is Outcome.Failure)
    }

    @Test fun `opening needs a tap on the screen, closing a card (C25's rule, on this door)`() = runTest {
        signedIn(); garage()
        val door = (backend.devices() as Outcome.Success).value.single()
        assertEquals(Approval.ScreenOnly, HomeActions.approval(door, DeviceCommand.Open))
        assertEquals(Approval.Card, HomeActions.approval(door, DeviceCommand.Close))
    }

    @Test fun `the door's state can be asked alone`() = runTest {
        signedIn(); garage(open = 1)
        assertEquals(DoorState.Open, ((backend.state("u1#0")) as Outcome.Success).value.door)
        bus.replies[Triple("u1", "GET", "Appliance.System.All")] = MerossReply.Ok(systemAll(Triple(0, 0, 0)))
        assertEquals(DoorState.Closed, ((backend.state("u1#0")) as Outcome.Success).value.door)
    }

    @Test fun `a device that refuses the command is a plain failure`() = runTest {
        signedIn(); garage()
        bus.replies[Triple("u1", "SET", "Appliance.GarageDoor.State")] = MerossReply.Failed("the device refused it (code 5001)")
        val r = backend.command("u1#0", DeviceCommand.Open) as Outcome.Failure
        assertEquals("the device refused it (code 5001)", (r.error as AppError.ToolFailed).detail)
        assertEquals("still signed in", ServiceState.Connected, service.state.value)
    }

    @Test fun `a refused token turns the service to sign in again, from the list or from the connection`() = runTest {
        signedIn()
        cloud.answers["/v1/Device/devList"] = { cloud.status(1200) }
        var r = backend.devices() as Outcome.Failure
        assertEquals(MerossService.NEEDS_SIGN_IN, (r.error as AppError.ToolFailed).detail)
        assertEquals(ServiceState.NeedsSignIn, service.state.value)
        assertEquals("Edith still lists it, with a problem line", IntegrationStatus.Connected, backend.status.value)

        signedIn(); garage()
        assertEquals(ServiceState.Connected, service.state.value)
        bus.connect = MerossReply.Rejected
        r = backend.devices() as Outcome.Failure
        assertEquals(ServiceState.NeedsSignIn, service.state.value)
        assertEquals(MerossService.NEEDS_SIGN_IN, (r.error as AppError.ToolFailed).detail)
    }

    @Test fun `without a sign-in nothing is asked of Meross`() = runTest {
        assertTrue(backend.devices() is Outcome.Failure)
        assertTrue(backend.command("u1#0", DeviceCommand.Open) is Outcome.Failure)
        assertTrue(cloud.requests.isEmpty() && bus.seen.isEmpty())
    }

    @Test fun `disconnecting returns the token and forgets everything`() = runTest {
        signedIn()
        cloud.answers["/v1/Profile/logout"] = { cloud.status(0) }
        service.disconnect()
        assertEquals("Basic tok-1", cloud.authorization("/v1/Profile/logout"))
        assertNull(secrets.get(MerossService.SECRET_KEY))
        assertEquals(ServiceState.NotConnected, service.state.value)
        assertEquals(IntegrationStatus.NotConnected, backend.status.value)
        assertTrue(service.signIn(sign) is SignInResult.Done)
    }

    @Test fun `a door that doesn't answer the first time is asked once more`() = runTest {
        signedIn(); garage(open = 1)
        bus.failFirst[Triple("u1", "GET", "Appliance.System.All")] = 1
        val d = (backend.devices() as Outcome.Success).value.single()
        assertTrue(d.online)
        assertEquals(DoorState.Open, d.state?.door)
        assertEquals(2, bus.seen.size)
        bus.seen.clear(); bus.failFirst[Triple("u1", "GET", "Appliance.System.All")] = 5
        assertFalse("twice is enough: then it's not responding", (backend.devices() as Outcome.Success).value.single().online)
        assertEquals(2, bus.seen.size)
    }

    @Test fun `signing in again returns the token it replaces, so the account doesn't pile up tokens`() = runTest {
        signedIn()
        cloud.answers["/v1/Auth/signIn"] = { cloud.loginOk(token = "tok-2") }
        cloud.answers["/v1/Profile/logout"] = { cloud.status(0) }
        assertEquals(SignInResult.Done, service.signIn(sign))
        assertEquals("the old token went back", "Basic tok-1", cloud.authorization("/v1/Profile/logout"))
        assertTrue("tok-2" in secrets.get(MerossService.SECRET_KEY)!! && "tok-1" !in secrets.get(MerossService.SECRET_KEY)!!)
        // The same token again (nothing replaced) sends nothing back.
        cloud.requests.clear()
        cloud.answers["/v1/Auth/signIn"] = { cloud.loginOk(token = "tok-2") }
        assertEquals(SignInResult.Done, service.signIn(sign))
        assertTrue(cloud.requests.none { it.url.encodedPath == "/v1/Profile/logout" })
    }
}
