package com.edith.app.ring

import com.edith.app.home.EventKind
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C27 §1: Ring's sign-in (password grant, 2FA, refusals), the rotating refresh, the session, devices, snapshots and events. */
class RingApiTest {
    private val ring = FakeRing()
    @After fun tearDown() = ring.shutdown()

    private fun grant(i: Int) = ring.tokenBodies[i]["grant_type"].toString().trim('"')

    @Test fun `a sign-in sends the password grant with the Android client and the hardware id, and returns both tokens`() = runBlocking {
        val r = ring.api().signIn("owner@example.com", "hunter2", null, "hw-1") as RingLogin.Ok
        assertEquals(listOf("at-1", "rt-1"), listOf(r.tokens.accessToken, r.tokens.refreshToken))
        val sent = ring.tokenBodies.single()
        assertEquals(
            listOf("ring_official_android", "client", "password", "owner@example.com"),
            listOf("client_id", "scope", "grant_type", "username").map { sent[it].toString().trim('"') },
        )
        val request = ring.requests.single()
        assertEquals("hw-1", request.headers["hardware_id"])
        assertEquals("true", request.headers["2fa-support"])
        assertEquals("", request.headers["2fa-code"])
        assertEquals("android:com.ringapp", request.headers["User-Agent"])
    }

    @Test fun `2FA - Ring answers 412 and says where the code went, then the code goes in the header`() = runBlocking {
        ring.wantCode = "123456"
        val asked = ring.api().signIn("a@b.c", "hunter2", null, "hw") as RingLogin.NeedsCode
        assertTrue(asked.prompt, "sms" in asked.prompt && "+1xxxxxx89" in asked.prompt)
        assertEquals(RingLogin.WrongCode, ring.api().signIn("a@b.c", "hunter2", "000000", "hw"))
        assertTrue(ring.api().signIn("a@b.c", "hunter2", "123456", "hw") is RingLogin.Ok)
        assertEquals("123456", ring.requests.last().headers["2fa-code"])
    }

    @Test fun `a wrong password, too many codes and an unreachable Ring are told apart`() = runBlocking {
        assertEquals(RingLogin.BadLogin, ring.api().signIn("a@b.c", "nope", null, "hw"))
        ring.overrides["/oauth/token"] = { MockResponse.Builder().code(429).body("""{"error_description":"too many requests from dependency service"}""").build() }
        assertEquals(RingLogin.TooManyCodes, ring.api().signIn("a@b.c", "hunter2", null, "hw"))
        ring.shutdown()
        assertTrue(ring.api().signIn("a@b.c", "hunter2", null, "hw") is RingLogin.Failed)
    }

    @Test fun `a refresh spends the old refresh token and returns the next one, and a spent one is Rejected`() = runBlocking {
        ring.signedInBefore()
        val ok = ring.api().refresh("rt-0", "hw") as RingRefresh.Ok
        assertEquals("rt-1", ok.tokens.refreshToken)
        assertEquals("the old token is spent", RingRefresh.Rejected, ring.api().refresh("rt-0", "hw"))
        assertEquals("refresh_token", grant(1))
    }

    @Test fun `devices - doorbells and cameras are kept, with battery, offline and motion-detection, chimes are not`() = runBlocking {
        ring.validAccess += "tok"
        ring.devicesJson = """{"doorbots":[${ring.camera(1, "Front Door", "doorbell_v4", battery = "\"87\"")}],
            "stickup_cams":[${ring.camera(2, "Driveway", battery = "55", connection = "offline")},${ring.camera(3, "Shed", motion = false)}],
            "authorized_doorbots":[${ring.camera(4, "Neighbour", "doorbell_v3")}],
            "chimes":[{"id":9,"description":"Hall chime"}],"other":[]}"""
        val list = (ring.api().cameras("tok", "hw") as RingReply.Ok).value
        assertEquals(listOf("Front Door", "Driveway", "Shed", "Neighbour"), list.map { it.name })
        assertEquals(listOf(true, false, false, true), list.map { it.doorbell })
        assertEquals(listOf(87, 55, null, null), list.map { it.battery })
        assertEquals(listOf(false, true, false, false), list.map { it.offline })
        assertEquals(listOf(true, true, false, true), list.map { it.motionDetection })
        assertEquals("Bearer tok", ring.requests.last().headers["authorization"])
        assertEquals("hw", ring.requests.last().headers["hardware_id"])
    }

    @Test fun `a 401 is Unauthorized, and a 404 naming this phone is NoSession`() = runBlocking {
        assertEquals(RingReply.Unauthorized, ring.api().cameras("not-valid", "hw"))
        ring.overrides["/clients_api/ring_devices"] = { MockResponse.Builder().code(404).body("""{"error":"Session with hardware_id hw not found"}""").build() }
        assertEquals(RingReply.NoSession, ring.api().cameras("t", "hw"))
        ring.overrides["/clients_api/ring_devices"] = { MockResponse.Builder().code(429).build() }
        assertTrue(ring.api().cameras("t", "hw") is RingReply.Failed)
    }

    @Test fun `a snapshot asks for a forced new picture and returns the JPEG bytes`() = runBlocking {
        ring.validAccess += "tok"
        val r = ring.api().snapshot("tok", "hw", "7", afterMs = 1234) as RingReply.Ok
        assertEquals(listOf<Byte>(1, 2, 3), r.value.toList())
        val sent = ring.requests.last()
        assertEquals("/snapshots/next/7", sent.url.encodedPath)
        assertEquals(listOf("1234", "force"), listOf(sent.url.queryParameter("after-ms"), sent.url.queryParameter("extras")))
        assertEquals("image/jpeg", sent.headers["accept"])
        ring.snapshotBytes = null; ring.snapshotCode = 404
        assertTrue(ring.api().snapshot("tok", "hw", "7", 0) is RingReply.Failed)
    }

    @Test fun `events - kinds, a person over plain motion, newest first, and only since the cut-off`() = runBlocking {
        ring.validAccess += "tok"
        ring.eventsJson = """{"events":[${ring.event("motion", "2026-11-14T10:00:00Z")},${ring.event("ding", "2026-11-14T12:00:00Z")},
            ${ring.event("motion", "2026-11-14T11:00:00Z", person = true)},${ring.event("on_demand", "2026-11-14T09:00:00Z")},
            ${ring.event("alarm", "2026-11-13T09:00:00Z")}]}"""
        val camera = RingCamera("5", "Front", true, "loc-1", false, null, true)
        val since = java.time.Instant.parse("2026-11-14T08:00:00Z").toEpochMilli()
        val list = (ring.api().events("tok", "hw", camera, since) as RingReply.Ok).value
        assertEquals(listOf(EventKind.Ring, EventKind.Person, EventKind.Motion, EventKind.LiveView), list.map { it.kind })
        assertEquals("/clients_api/locations/loc-1/devices/5/events", ring.requests.last().url.encodedPath)
        assertNull(ring.api().event(kotlinx.serialization.json.JsonPrimitive("x")))
    }

    @Test fun `clips - the search asks for one camera and a window, newest first, and says whether a plan kept them`() = runBlocking {
        ring.validAccess += "tok"
        ring.clipsJson = """{"video_search":[${clipRowJson("9", 3000, "ding")},${clipRowJson("8", 2000, person = true, seconds = 31)},${clipRowJson("7", 1000, hadPlan = false)}]}"""
        val camera = RingCamera("5", "Front Door", true, "loc-1", false, null, true)
        val rows = (ring.api().clips("tok", "hw", camera, 500, 4000) as RingReply.Ok).value
        assertEquals(listOf("9", "8", "7"), rows.map { it.id })
        assertEquals(listOf(EventKind.Ring, EventKind.Person, EventKind.Motion), rows.map { it.kind })
        assertEquals(listOf(true, true, false), rows.map { it.hadPlan })
        assertEquals(listOf(null, 31, 24), rows.map { it.seconds }.let { listOf(null, it[1], it[2]) })
        val sent = ring.requests.last().url
        assertEquals(listOf("5", "500", "4000", "desc"), listOf("doorbot_id", "date_from", "date_to", "order").map { sent.queryParameter(it) })
    }

    @Test fun `a clip's address is asked for at play time, and only an https one is accepted`() = runBlocking {
        ring.validAccess += "tok"
        assertEquals("https://clips.example.test/video.mp4", (ring.api().clipUrl("tok", "hw", "9") as RingReply.Ok).value)
        assertEquals("/clients_api/dings/9/recording", ring.requests.last().url.encodedPath)
        assertEquals("true", ring.requests.last().url.queryParameter("disable_redirect"))
        ring.recordingUrl = "http://clips.example.test/plain.mp4"
        assertTrue("never a cleartext address", ring.api().clipUrl("tok", "hw", "9") is RingReply.Failed)
        ring.recordingUrl = null
        assertTrue("Ring hasn't saved it yet", ring.api().clipUrl("tok", "hw", "9") is RingReply.Failed)
    }
}
