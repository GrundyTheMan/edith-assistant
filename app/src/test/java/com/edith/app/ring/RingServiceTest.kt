package com.edith.app.ring

import com.edith.app.core.error.Outcome
import com.edith.app.home.Capability
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceType
import com.edith.app.home.EventKind
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInResult
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** C27: sign-in with 2FA, the rotated refresh token saved before it is used, a dead token, snapshots and events. */
class RingServiceTest {
    private val ring = FakeRing()
    private val secrets = InMemorySecretStore()
    private val clock = FakeClock(Instant.parse("2026-11-14T15:00:00Z"))
    private fun service() = RingService(secrets, ring.api(), clock)
    @After fun tearDown() = ring.shutdown()

    private val twoCameras get() =
        """{"doorbots":[${ring.camera(1, "Front Door", "doorbell_v4", battery = "80")}],"stickup_cams":[${ring.camera(2, "Driveway")}]}"""

    private val login = mapOf("email" to "a@b.c", "password" to "hunter2")
    private fun refreshes() = ring.tokenBodies.count { it["grant_type"].toString().trim('"') == "refresh_token" }

    private suspend fun signedIn(): RingService {
        val s = service()
        assertEquals(SignInResult.Done, s.signIn(login))
        return s
    }

    @Test fun `sign-in with 2FA - the code is asked for, then the sign-in is kept and the password is not`() = runBlocking {
        ring.wantCode = "123456"
        val s = service()
        val asked = s.signIn(login)
        assertTrue(asked is SignInResult.NeedsCode && "sms" in asked.message)
        assertEquals(ServiceState.NotConnected, s.state.value)
        assertTrue(s.signIn(login + ("code" to "000000")) is SignInResult.NeedsCode)
        assertEquals(SignInResult.Done, s.signIn(login + ("code" to "123456")))
        assertEquals(ServiceState.Connected, s.state.value)
        val kept = secrets.get(RingService.SECRET_KEY)!!
        assertTrue(kept, "rt-1" in kept)
        assertFalse("the password is never kept", "hunter2" in kept)
        assertEquals("the same phone id on every try", 1, ring.requests.map { it.headers["hardware_id"] }.distinct().size)
    }

    @Test fun `a wrong password is a plain refusal`() = runBlocking {
        val r = service().signIn(mapOf("email" to "a@b.c", "password" to "nope"))
        assertTrue(r is SignInResult.Failed && "isn't right" in r.message)
    }

    @Test fun `the rotated refresh token is saved before the new access token is used`() = runBlocking {
        signedIn()   // saves rt-1
        val later = service().also { it.load() }   // a new run of the app: only the saved sign-in is left
        var savedWhenUsed: String? = null
        ring.overrides["/clients_api/ring_devices"] = {
            // Ring is being asked with the new access token: the new refresh token must already be saved.
            savedWhenUsed = runBlocking { secrets.get(RingService.SECRET_KEY) }
            MockResponse.Builder().code(200).body(twoCameras).build()
        }
        val list = (later.backend.devices() as Outcome.Success).value
        assertEquals(listOf("Driveway", "Front Door"), list.map { it.name })
        assertTrue(savedWhenUsed, "rt-2" in savedWhenUsed!! && "rt-1" !in savedWhenUsed!!)
    }

    @Test fun `several calls at once spend the refresh token once, not once each`() = runBlocking {
        signedIn()
        val later = service().also { it.load() }
        ring.devicesJson = twoCameras
        val results = (1..6).map { async { later.backend.devices() } }.awaitAll()
        assertTrue(results.all { it is Outcome.Success })
        assertEquals("one refresh, with the saved token", 1, refreshes())
        assertEquals(ServiceState.Connected, later.state.value)
    }

    @Test fun `an access token Ring refuses is refreshed once and the call is asked again`() = runBlocking {
        val s = signedIn()
        ring.devicesJson = twoCameras
        assertTrue(s.backend.devices() is Outcome.Success)
        ring.validAccess.clear()   // Ring forgets the access token early
        assertTrue(s.backend.devices() is Outcome.Success)
        assertEquals(1, refreshes())
    }

    @Test fun `an expired access token is refreshed before use`() = runBlocking {
        val s = signedIn()
        ring.devicesJson = twoCameras
        s.backend.devices()
        clock.advance(Duration.ofHours(2))
        assertTrue(s.backend.devices() is Outcome.Success)
        assertEquals(1, refreshes())
    }

    @Test fun `a refresh token Ring no longer accepts means Sign in again`() = runBlocking {
        signedIn()
        ring.validRefresh.clear()
        val later = service().also { it.load() }
        val r = later.backend.devices() as Outcome.Failure
        assertTrue(r.error.userMessage, "signing in again" in r.error.userMessage)
        assertEquals(ServiceState.NeedsSignIn, later.state.value)
    }

    @Test fun `a Ring that can't be reached does not sign the owner out`() = runBlocking {
        signedIn()
        val later = service().also { it.load() }
        ring.shutdown()
        assertTrue(later.backend.devices() is Outcome.Failure)
        assertEquals(ServiceState.Connected, later.state.value)
    }

    @Test fun `disconnect forgets the sign-in`() = runBlocking {
        val s = signedIn()
        s.disconnect()
        assertEquals(null, secrets.get(RingService.SECRET_KEY))
        assertEquals(ServiceState.NotConnected, s.state.value)
        assertTrue(s.backend.devices() is Outcome.Failure)
    }

    @Test fun `devices come as doorbells and cameras that give pictures and events, never controlled`() = runBlocking {
        val s = signedIn()
        ring.devicesJson = twoCameras
        val list = (s.backend.devices() as Outcome.Success).value
        val door = list.first { it.name == "Front Door" }
        assertEquals(DeviceType.Doorbell, door.type)
        assertEquals(setOf(Capability.Snapshot, Capability.Events), door.capabilities)
        assertEquals(80, door.state?.battery)
        assertEquals(DeviceType.Camera, list.first { it.name == "Driveway" }.type)
        assertTrue(s.backend.command("1", DeviceCommand.TurnOn) is Outcome.Failure)
    }

    @Test fun `a snapshot starts the session first, and says why when the camera can't give one`() = runBlocking {
        val s = signedIn()
        ring.devicesJson = """{"doorbots":[${ring.camera(1, "Front Door", "doorbell_v4")}],"stickup_cams":[${ring.camera(2, "Gone", connection = "offline")},${ring.camera(3, "Shed", motion = false)}]}"""
        assertEquals(listOf<Byte>(1, 2, 3), (s.backend.snapshot("1") as Outcome.Success).value.toList())
        assertEquals(1, ring.sessionStarted)
        val offline = s.backend.snapshot("2") as Outcome.Failure
        assertTrue(offline.error.userMessage, "offline" in offline.error.userMessage)
        val blocked = s.backend.snapshot("3") as Outcome.Failure
        assertTrue(blocked.error.userMessage, "Motion detection is off" in blocked.error.userMessage)
        assertEquals("no picture was asked for the two that can't give one", 0, ring.count("/snapshots/next/2") + ring.count("/snapshots/next/3"))
    }

    @Test fun `a forgotten session is started again`() = runBlocking {
        val s = signedIn()
        ring.devicesJson = twoCameras
        s.backend.devices()
        var first = true
        ring.overrides["/clients_api/ring_devices"] = {
            if (first) { first = false; MockResponse.Builder().code(404).body("""{"error":"hardware_id ${it.headers["hardware_id"]} not found"}""").build() }
            else MockResponse.Builder().code(200).body(twoCameras).build()
        }
        assertTrue(s.backend.devices() is Outcome.Success)
        assertEquals(2, ring.sessionStarted)
    }

    @Test fun `events come back newest first for the camera asked about`() = runBlocking {
        val s = signedIn()
        ring.devicesJson = twoCameras
        ring.eventsJson = """{"events":[${ring.event("ding", "2026-11-14T13:00:00Z")},${ring.event("motion", "2026-11-14T14:00:00Z")}]}"""
        val since = Instant.parse("2026-11-14T00:00:00Z").toEpochMilli()
        val e = (s.backend.events("1", since) as Outcome.Success).value
        assertEquals(listOf(EventKind.Motion, EventKind.Ring), e.map { it.kind })
    }

    @Test fun `clips from every camera come back newest first with their camera's name`() = runBlocking {
        val s = signedIn()
        ring.devicesJson = twoCameras
        ring.clipsJson = """{"video_search":[${clipRowJson("2", 2000)},${clipRowJson("1", 1000, "ding")}]}"""
        val page = (s.backend.clips(0, 5000) as Outcome.Success).value
        // Both cameras answer with the same pretend rows: the clip ids repeat per camera, newest first.
        assertEquals(listOf(2000L, 2000L, 1000L, 1000L), page.clips.map { it.at })
        assertEquals(setOf("Front Door", "Driveway"), page.clips.map { it.deviceName }.toSet())
        assertFalse(page.noPlan)
        assertTrue(s.backend.hasClips)
    }

    @Test fun `an account with no Ring Protect plan gets Ring's no-plan answer, not an empty list and not an error`() = runBlocking {
        val s = signedIn()
        ring.devicesJson = twoCameras
        ring.clipsJson = """{"video_search":[${clipRowJson("2", 2000, hadPlan = false)},${clipRowJson("1", 1000, hadPlan = false)}]}"""
        val page = (s.backend.clips(0, 5000) as Outcome.Success).value
        assertTrue(page.noPlan)
        assertTrue(page.clips.isEmpty())
    }

    @Test fun `a plan that began recently keeps only the clips it recorded`() = runBlocking {
        val s = signedIn()
        ring.devicesJson = twoCameras
        ring.clipsJson = """{"video_search":[${clipRowJson("2", 2000)},${clipRowJson("1", 1000, hadPlan = false)}]}"""
        val page = (s.backend.clips(0, 5000) as Outcome.Success).value
        assertFalse(page.noPlan)
        assertEquals(2, page.clips.size)
    }

    @Test fun `a window with nothing in it is simply empty`() = runBlocking {
        val s = signedIn()
        ring.devicesJson = twoCameras
        val page = (s.backend.clips(0, 5000) as Outcome.Success).value
        assertTrue(page.clips.isEmpty() && !page.noPlan)
    }

    @Test fun `a clip's address falls back from one Ring endpoint to the other, then to the one the search listed`() = runBlocking {
        val s = signedIn()
        ring.devicesJson = twoCameras
        ring.clipsJson = """{"video_search":[${clipRowJson("9", 2000, url = "https://clips.example.test/listed.mp4")}]}"""
        s.backend.clips(0, 5000)
        assertEquals("share/play first", "https://clips.example.test/shared.mp4", (s.backend.clipUrl("9") as Outcome.Success).value)
        // The owner's account (2026-11-14): `recording` answered 404, so what plays is what the other endpoint gives.
        ring.recordingUrl = null
        assertEquals("https://clips.example.test/shared.mp4", (s.backend.clipUrl("9") as Outcome.Success).value)
        ring.sharePlayUrl = null; ring.recordingUrl = "https://clips.example.test/video.mp4"
        assertEquals("then the transcoded one", "https://clips.example.test/video.mp4", (s.backend.clipUrl("9") as Outcome.Success).value)
        ring.recordingUrl = null
        assertEquals("then the address the search listed it with", "https://clips.example.test/listed.mp4", (s.backend.clipUrl("9") as Outcome.Success).value)
        val unknown = s.backend.clipUrl("no-such-clip") as Outcome.Failure
        assertTrue(unknown.error.userMessage, "404" in unknown.error.userMessage)
    }

    @Test fun `clips failing for every camera is a failure, and the sign-in rules still apply to the clip address`() = runBlocking {
        val s = signedIn()
        ring.devicesJson = twoCameras
        ring.overrides["/clients_api/video_search/history"] = { MockResponse.Builder().code(500).build() }
        assertTrue(s.backend.clips(0, 5000) is Outcome.Failure)
        assertEquals("https://clips.example.test/shared.mp4", (s.backend.clipUrl("9") as Outcome.Success).value)
        ring.validRefresh.clear(); ring.validAccess.clear()
        val later = service().also { it.load() }
        assertTrue((later.backend.clipUrl("9") as Outcome.Failure).error.userMessage.contains("signing in again"))
    }
}
