package com.edith.app.blink

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.home.Capability
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceType
import com.edith.app.home.EventKind
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInResult
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import java.net.ServerSocket
import java.net.Socket
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** C29: signing in, keeping only the refresh token, snapshots by command, clips and motion, and a Blink that stops answering. */
class BlinkServiceTest {
    private val blink = FakeBlink()
    private val secrets = InMemorySecretStore()
    private val clock = FakeClock(Instant.parse("2026-11-15T15:00:00Z"))
    private val waits = mutableListOf<Long>()
    private fun service() = AndroidBlinkService(secrets, blink.api(), clock) { waits += it }
    @After fun tearDown() = blink.shutdown()

    private val login = mapOf("email" to "a@b.c", "password" to "hunter2")
    private suspend fun signedIn(): BlinkService = service().also { assertEquals(SignInResult.Done, it.signIn(login)) }
    private fun <T> Outcome<T>.value(): T = (this as Outcome.Success).value
    private fun Outcome<*>.why(): String = ((this as Outcome.Failure).error as AppError.ToolFailed).let { "${it.tool}: ${it.detail}" }

    @Test fun `sign-in with a code - the code is asked for, then only the sign-in is kept`() = runBlocking {
        blink.wantCode = "123456"
        val s = service()
        assertTrue(s.signIn(login) is SignInResult.NeedsCode)
        assertEquals(ServiceState.NotConnected, s.state.value)
        assertTrue("a wrong code asks again", s.signIn(login + ("code" to "000000")) is SignInResult.NeedsCode)
        assertEquals(SignInResult.Done, s.signIn(login + ("code" to "123456")))
        assertEquals(ServiceState.Connected, s.state.value)
        val kept = secrets.get(BlinkService.SECRET_KEY)!!
        assertTrue(kept, "rt-1" in kept && "u014" in kept && "4242" in kept)
        assertFalse("the password is never kept", "hunter2" in kept)
        assertEquals("one sign-in page for the whole sign-in, however many tries at the code", 1, blink.requests.count { it.method == "GET" && it.url.encodedPath == "/oauth/v2/signin" })
    }

    @Test fun `the hardware id is a UUID, made once and sent with every sign-in and refresh`() = runBlocking {
        val s = signedIn()
        val id = blink.tokenForms.single()["hardware_id"]!!
        assertTrue(id, Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}").matches(id))
        assertEquals(SignInResult.Done, s.signIn(login))
        assertEquals("the same phone again", setOf(id), blink.tokenForms.map { it["hardware_id"] }.toSet())
        clock.advance(Duration.ofHours(2))
        s.backend.devices()
        assertEquals(setOf(id), blink.tokenForms.map { it["hardware_id"] }.toSet())
    }

    @Test fun `a wrong password and a changed sign-in page are plain messages with no retry loop`() = runBlocking {
        val s = service()
        val bad = s.signIn(mapOf("email" to "a@b.c", "password" to "nope"))
        assertTrue(bad is SignInResult.Failed && "isn't right" in bad.message)
        val before = blink.requests.size
        blink.signInPageHtml = "<html>new</html>"
        val changed = s.signIn(login)
        assertTrue(changed is SignInResult.Failed && "changed" in changed.message)
        assertEquals("the page was loaded once and nothing was retried", before + 2, blink.requests.size)
        assertEquals(ServiceState.NotConnected, s.state.value)
        assertTrue(s.signIn(mapOf("email" to "", "password" to "")) is SignInResult.Failed)
    }

    @Test fun `cameras become devices that take pictures and report events, and nothing can be commanded`() = runBlocking {
        blink.homescreen = FakeBlink.homescreenJson(cameras = listOf(FakeBlink.camera("1", "Backyard")), doorbells = listOf(FakeBlink.camera("2", "Front door")))
        val s = signedIn()
        val devices = s.backend.devices().value()
        assertEquals(listOf("Backyard", "Front door"), devices.map { it.name })
        assertEquals(listOf(DeviceType.Camera, DeviceType.Doorbell), devices.map { it.type })
        assertTrue(devices.all { Capability.Snapshot in it.capabilities && Capability.Events in it.capabilities && it.backend == "blink" })
        assertTrue(s.backend.command("1", DeviceCommand.TurnOn) is Outcome.Failure)
    }

    @Test fun `a sync module that is offline makes its cameras offline`() = runBlocking {
        blink.homescreen = FakeBlink.homescreenJson(cameras = listOf(FakeBlink.camera("1", "Backyard")), syncOffline = true)
        val s = signedIn()
        assertFalse(s.backend.devices().value().single().online)
        assertTrue(s.backend.snapshot("1").why(), "offline" in s.backend.snapshot("1").why())
        assertEquals("no snapshot was asked for", 0, blink.requests.count { it.method == "POST" && it.url.encodedPath.endsWith("/thumbnail") })
    }

    @Test fun `a snapshot asks the camera, waits for the command, then fetches the new picture`() = runBlocking {
        val s = signedIn()
        assertEquals(listOf<Byte>(9, 8, 7), s.backend.snapshot("1").value().toList())
        val paths = blink.requests.map { it.method + " " + it.url.encodedPath }.filter { "/rest/" in it }
        val ask = paths.indexOf("POST /rest/network/5/camera/1/thumbnail")
        assertTrue(paths.toString(), ask >= 0 && paths.indexOf("GET /rest/network/5/command/77") > ask && paths.last() == "GET /rest/media/thumb/1-new.jpg")
        assertTrue("bearer on every call", blink.bearers.all { it == "at-1" })
    }

    @Test fun `a snapshot that never arrives says so, and a busy sync module is asked again`() = runBlocking {
        blink.thumbnailAfterSnapshot = null
        blink.snapshotAnswers += MockResponse.Builder().code(409).body("""{"message":"busy"}""").build()
        blink.snapshotAnswers += MockResponse.Builder().code(200).body("""{"id":77,"network_id":5}""").build()
        val s = signedIn()
        val r = s.backend.snapshot("1")
        assertTrue(r.why(), "didn't send a new picture" in r.why())
        assertEquals("asked twice: busy, then accepted", 2, blink.requests.count { it.method == "POST" && it.url.encodedPath.endsWith("/thumbnail") })
        assertTrue("waited between tries, not in real time", 3_000L in waits)
    }

    @Test fun `a refresh token that Blink no longer accepts turns the service to sign in again`() = runBlocking {
        val s = signedIn()
        blink.validRefresh.clear()
        clock.advance(Duration.ofHours(2))
        val r = s.backend.devices()
        assertTrue(r.why(), "signing in again" in r.why())
        assertEquals(ServiceState.NeedsSignIn, s.state.value)
    }

    @Test fun `the new refresh token is saved before the access token it came with is used`() = runBlocking {
        val s = signedIn()
        clock.advance(Duration.ofHours(2))
        s.backend.devices().value()
        assertTrue(secrets.get(BlinkService.SECRET_KEY)!!, "rt-2" in secrets.get(BlinkService.SECRET_KEY)!!)
        assertEquals("at-2", blink.bearers.last())
    }

    @Test fun `clips are listed in the period, and motion events are the clips of that camera`() = runBlocking {
        blink.homescreen = FakeBlink.homescreenJson(cameras = listOf(FakeBlink.camera("1", "Backyard"), FakeBlink.camera("2", "Garage")))
        blink.mediaJson = """{"media":[${FakeBlink.media("11", "1", "Backyard", "2026-11-15T14:00:00+00:00")},${FakeBlink.media("12", "2", "Garage", "2026-11-15T13:00:00+00:00")},""" +
            """${FakeBlink.media("13", "1", "Backyard", "2026-11-10T13:00:00+00:00")},${FakeBlink.media("14", "1", "Backyard", "2026-11-15T12:00:00+00:00", deleted = true)}]}"""
        val s = signedIn()
        val since = Instant.parse("2026-11-14T00:00:00Z").toEpochMilli()
        val until = Instant.parse("2026-11-16T00:00:00Z").toEpochMilli()
        val page = s.backend.clips(since, until).value()
        assertEquals(listOf("11", "12"), page.clips.map { it.id })
        assertEquals(listOf("Backyard", "Garage"), page.clips.map { it.deviceName })
        assertFalse(page.noPlan)
        assertEquals(listOf(EventKind.Motion), s.backend.events("1", since).value().map { it.kind })
    }

    @Test fun `a clip plays from the account's server with Blink's sign-in as a header`() = runBlocking {
        blink.mediaJson = """{"media":[${FakeBlink.media("11", "1", "Backyard", "2026-11-15T14:00:00+00:00")}]}"""
        val s = signedIn()
        s.backend.clips(0, Long.MAX_VALUE)
        val src = s.backend.clipSource("11").value()
        assertTrue(src.url, src.url.endsWith("/rest/api/v2/accounts/4242/media/clip/11.mp4"))
        assertEquals(mapOf("Authorization" to "Bearer at-1"), src.headers)
        assertTrue("an unlisted clip is a plain message", "isn't in the list" in s.backend.clipSource("999").why())
    }

    @Test fun `disconnecting forgets everything and a service that isn't signed in says so`() = runBlocking {
        val s = signedIn()
        s.disconnect()
        assertNull(secrets.get(BlinkService.SECRET_KEY))
        assertEquals(ServiceState.NotConnected, s.state.value)
        assertTrue(s.backend.devices().why(), "isn't signed in" in s.backend.devices().why())
    }

    @Test fun `a saved sign-in is picked up at start`() = runBlocking {
        signedIn()
        val again = service()
        again.load()
        assertEquals(ServiceState.Connected, again.state.value)
        assertEquals(1, again.backend.devices().value().size)
    }

    // ---- live view (C32) ----

    private fun video(vararg b: Int): ByteArray {
        val h = ByteArray(9); h[8] = b.size.toByte()
        return h + ByteArray(b.size) { b[it].toByte() }
    }

    /** A pretend stream server that sends [chunks] and then waits; returns what it received. */
    private fun streamServer(vararg chunks: ByteArray): Pair<ServerSocket, java.io.ByteArrayOutputStream> {
        val server = ServerSocket(0)
        val got = java.io.ByteArrayOutputStream()
        Thread {
            val s = server.accept()
            Thread { runCatching { s.getInputStream().copyTo(got) } }.apply { isDaemon = true }.start()
            chunks.forEach { s.getOutputStream().write(it) }
            s.getOutputStream().flush()
        }.apply { isDaemon = true; start() }
        return server to got
    }

    private fun liveService(limitMs: Long = 60_000) = AndroidBlinkService(secrets, blink.api(), clock, { Socket(it.host, it.port) }, limitMs, 100L, 50L) { waits += it }

    @Test fun `a live view asks Blink, connects to the stream server with the camera's serial, and passes the video on`() = runBlocking {
        val (server, got) = streamServer(video(0x47, 1), video(0x47, 2))
        blink.liveServer = "immis://127.0.0.1:${server.localPort}/CONN__x?client_id=9"
        val s = liveService().also { assertEquals(SignInResult.Done, it.signIn(login)) }
        assertTrue(s.backend.hasLive)
        val feed = s.backend.startLive("1").value() as com.edith.app.home.StreamLiveFeed
        val first = withTimeout(5_000) { feed.video.receive() }
        val second = withTimeout(5_000) { feed.video.receive() }
        assertEquals(listOf(listOf(0x47, 1), listOf(0x47, 2)), listOf(first, second).map { c -> c.map { it.toInt() } })
        assertEquals(1, blink.requests.count { it.method == "POST" && it.url.encodedPath == "/rest/api/v6/accounts/4242/networks/5/cameras/1/liveview" })
        // The server reads the handshake on its own thread: the video can arrive before those bytes are recorded.
        withTimeout(5_000) { while (got.size() < 24) delay(20) }
        assertTrue("the handshake names the camera", String(got.toByteArray().copyOfRange(8, 24)).startsWith("SN1"))
        assertNull(feed.ended.value)
        feed.stop()
        assertEquals("Stopped.", feed.ended.value)
        server.close()
    }

    @Test fun `a stream that closes at once is asked for again, once, and the second one plays`() = runBlocking {
        val server = ServerSocket(0)
        Thread {
            server.accept().apply { getInputStream().readNBytes(122); close() }
            val s = server.accept()
            Thread { runCatching { s.getInputStream().copyTo(java.io.OutputStream.nullOutputStream()) } }.apply { isDaemon = true }.start()
            s.getOutputStream().write(video(0x47, 7)); s.getOutputStream().flush()
        }.apply { isDaemon = true; start() }
        blink.liveServer = "immis://127.0.0.1:${server.localPort}/CONN?client_id=9"
        val feed = liveService().also { it.signIn(login) }.backend.startLive("1").value() as com.edith.app.home.StreamLiveFeed
        assertEquals(listOf(0x47, 7), withTimeout(8_000) { feed.video.receive() }.map { it.toInt() })
        assertNull(feed.ended.value)
        assertEquals(2, blink.requests.count { it.method == "POST" && it.url.encodedPath.endsWith("/liveview") })
        feed.stop()
        server.close()
    }

    @Test fun `a stream that closes early twice ends the view instead of asking forever`() = runBlocking {
        val server = ServerSocket(0)
        Thread { repeat(2) { server.accept().apply { getInputStream().readNBytes(122); close() } } }.apply { isDaemon = true; start() }
        blink.liveServer = "immis://127.0.0.1:${server.localPort}/CONN?client_id=9"
        val feed = liveService().also { it.signIn(login) }.backend.startLive("1").value()
        withTimeout(8_000) { while (feed.ended.value == null) delay(50) }
        assertTrue(feed.ended.value!!, feed.ended.value!!.startsWith("Blink's stream server closed"))
        assertEquals(2, blink.requests.count { it.method == "POST" && it.url.encodedPath.endsWith("/liveview") })
        server.close()
    }

    @Test fun `Blink ending the command ends the view and the video channel closes`() = runBlocking {
        val (server, _) = streamServer(video(0x47, 1))
        blink.liveServer = "immis://127.0.0.1:${server.localPort}/CONN?client_id=9"
        val feed = liveService().also { it.signIn(login) }.backend.startLive("1").value() as com.edith.app.home.StreamLiveFeed
        withTimeout(5_000) { feed.video.receive() }
        blink.liveState = "done"
        withTimeout(8_000) { while (feed.ended.value == null) delay(50) }
        assertTrue(feed.ended.value, Regex("Blink ended the live view after \\d+ s: its command finished normally.*Start it again to keep watching\\.").matches(feed.ended.value!!))
        withTimeout(5_000) { assertTrue(feed.video.receiveCatching().isClosed) }
        server.close()
    }

    @Test fun `stopping a view while video is still arriving does not crash`() = runBlocking {
        // The owner's phone crashed this way when the screen rotated: video arrived just after the view was stopped.
        val server = ServerSocket(0)
        Thread {
            val s = server.accept()
            Thread { runCatching { s.getInputStream().copyTo(java.io.OutputStream.nullOutputStream()) } }.apply { isDaemon = true }.start()
            runCatching { repeat(400) { s.getOutputStream().write(video(0x47, it)); s.getOutputStream().flush(); Thread.sleep(2) } }
        }.apply { isDaemon = true; start() }
        blink.liveServer = "immis://127.0.0.1:${server.localPort}/CONN?client_id=9"
        val crashes = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crashes += e }
        try {
            val feed = liveService().also { it.signIn(login) }.backend.startLive("1").value()
            withTimeout(5_000) { (feed as com.edith.app.home.StreamLiveFeed).video.receive() }
            feed.stop()
            delay(1_500)   // the server keeps sending; the stream reads it after the stop
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            server.close()
        }
        assertTrue(crashes.joinToString { it.toString() }, crashes.isEmpty())
    }

    @Test fun `a stream Blink closes says how long it ran and that Blink still shows the view as running`() = runBlocking {
        val server = ServerSocket(0)
        // Twice: a close within 10 s is asked for again once, and the second close is the one reported.
        Thread { repeat(2) { server.accept().apply { getInputStream().readNBytes(122); getOutputStream().write(video(0x47, 1)); getOutputStream().flush(); Thread.sleep(1_100); close() } } }.apply { isDaemon = true; start() }
        blink.liveServer = "immis://127.0.0.1:${server.localPort}/CONN?client_id=9"
        val feed = liveService().also { it.signIn(login) }.backend.startLive("1").value()
        withTimeout(8_000) { while (feed.ended.value == null) delay(50) }
        val why = feed.ended.value!!
        assertTrue(why, Regex("Blink's stream server closed the connection after 0 KB of video \\(\\d+ s in\\)\\. Blink still shows the view as running, so the connection was lost rather than ended by Blink\\.").matches(why))
        server.close()
    }

    @Test fun `a stream Blink closes after ending its command says what Blink reported`() = runBlocking {
        val server = ServerSocket(0)
        Thread { repeat(2) { server.accept().apply { getInputStream().readNBytes(122); getOutputStream().write(video(0x47, 1)); getOutputStream().flush(); Thread.sleep(1_100); close() } } }.apply { isDaemon = true; start() }
        blink.liveServer = "immis://127.0.0.1:${server.localPort}/CONN?client_id=9"
        blink.liveState = "error"
        val feed = liveService().also { it.signIn(login) }.backend.startLive("1").value()
        withTimeout(8_000) { while (feed.ended.value == null) delay(50) }
        assertTrue(feed.ended.value!!, feed.ended.value!!.contains("state_condition error"))
        server.close()
    }

    @Test fun `a view Blink ends normally says so, and that starting it again keeps watching`() = runBlocking {
        val server = ServerSocket(0)
        Thread { repeat(2) { server.accept().apply { getInputStream().readNBytes(122); getOutputStream().write(video(0x47, 1)); getOutputStream().flush(); Thread.sleep(1_100); close() } } }.apply { isDaemon = true; start() }
        blink.liveServer = "immis://127.0.0.1:${server.localPort}/CONN?client_id=9"
        blink.liveState = "done"
        val feed = liveService().also { it.signIn(login) }.backend.startLive("1").value()
        withTimeout(8_000) { while (feed.ended.value == null) delay(50) }
        val why = feed.ended.value!!
        assertTrue(why, Regex("Blink ended the live view after \\d+ s: its command finished normally.*Start it again to keep watching\\.").matches(why))
        server.close()
    }

    @Test fun `a view is ended at the time limit`() = runBlocking {
        val (server, _) = streamServer(video(0x47, 1))
        blink.liveServer = "immis://127.0.0.1:${server.localPort}/CONN?client_id=9"
        val feed = liveService(limitMs = 300).also { it.signIn(login) }.backend.startLive("1").value()
        withTimeout(5_000) { while (feed.ended.value == null) delay(50) }
        assertTrue(feed.ended.value!!, feed.ended.value!!.startsWith("Live view stops after"))
        server.close()
    }

    @Test fun `a live view says why it can't start`() = runBlocking {
        suspend fun fresh() = liveService().also { it.signIn(login) }
        val s = liveService().also { it.signIn(login) }
        blink.liveServer = null
        assertTrue(s.backend.startLive("1").why(), s.backend.startLive("1").why().contains("form Edith can't play"))
        blink.liveServer = "rtsp://x/y"
        assertTrue(s.backend.startLive("1").why().contains("form Edith can't play"))
        blink.homescreen = FakeBlink.homescreenJson(cameras = listOf(FakeBlink.camera("1", "Backyard", serial = null)))
        assertTrue(fresh().backend.startLive("1").why().contains("serial number"))
        blink.homescreen = FakeBlink.homescreenJson(cameras = listOf(FakeBlink.camera("1", "Backyard", status = "offline")))
        assertTrue(fresh().backend.startLive("1").why().contains("offline"))
        assertTrue(s.backend.startLive("404").why().contains("no longer on the account"))
    }
}
