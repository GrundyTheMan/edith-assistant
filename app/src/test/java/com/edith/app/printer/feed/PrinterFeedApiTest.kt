package com.edith.app.printer.feed

import com.edith.app.core.net.HttpClients
import com.edith.app.jarvis.FeedResult
import com.edith.app.jarvis.JarvisApi
import com.edith.app.jarvis.JarvisLink
import com.edith.app.jarvis.JarvisPairing
import com.edith.app.testing.testDispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * C39 E2: the printer routes of Jarvis's feed. A TLS MockWebServer with a throwaway certificate stands in for the PC (as in
 * `JarvisFeedTest`); the fixtures are the ones `PrinterFeedWireTest` parses. Never touches the network.
 */
class PrinterFeedApiTest {
    private val server = MockWebServer()
    private lateinit var certificate: HeldCertificate
    private lateinit var fingerprint: String
    private val http = HttpClients { OkHttpClient() }.secure
    private var unauthorized = 0

    @Before fun setUp() {
        certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName).build()
        fingerprint = JarvisPairing.fingerprint(certificate.certificate.encoded)
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory())
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun link(token: String = "tok-SECRET-123") = JarvisLink(server.url("/").toString().trimEnd('/'), fingerprint, token, "BEN-PC")
    private fun feed(l: JarvisLink? = link()) = PrinterFeedApi(JarvisApi({ l }, http, testDispatchers(), onUnauthorized = { unauthorized++ }))
    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/printer-feed/$name.json")).bufferedReader().readText()
    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    // --- The reads ----------------------------------------------------------------------------------------------------

    @Test fun `state asks with the revision, epoch, wait and quiet, signed with the token`() = runBlocking {
        server.enqueue(json(fixture("state-printing")))
        val r = feed().state(after = 57, epoch = 1763400000, waitS = 20, quiet = true)
        val sent = server.takeRequest()
        assertEquals("GET", sent.method)
        assertEquals("/v1/printer/state", sent.url.encodedPath)
        assertEquals("57", sent.url.queryParameter("after"))
        assertEquals("1763400000", sent.url.queryParameter("epoch"))
        assertEquals("20", sent.url.queryParameter("wait"))
        assertEquals("1", sent.url.queryParameter("quiet"))
        assertEquals("Bearer tok-SECRET-123", sent.headers["Authorization"])
        assertEquals(57L, (r as FeedResult.Ok).value.rev)
    }

    @Test fun `the first state question sends no revision and does not wait`() = runBlocking {
        server.enqueue(json(fixture("state-ready-idle")))
        feed().state(after = null, epoch = null, waitS = 0)
        val url = server.takeRequest().url
        assertNull(url.queryParameter("after"))
        assertNull(url.queryParameter("wait"))
        assertNull(url.queryParameter("quiet"))
    }

    @Test fun `a wait over 25 seconds is cut to 25`() = runBlocking {
        server.enqueue(json(fixture("state-ready-idle")))
        feed().state(after = 1, epoch = 1, waitS = 120)
        assertEquals("25", server.takeRequest().url.queryParameter("wait"))
    }

    @Test fun `the list routes read their answers`() = runBlocking {
        server.enqueue(json(fixture("temperatures")))
        server.enqueue(json(fixture("macros")))
        server.enqueue(json(fixture("console")))
        server.enqueue(json(fixture("files")))
        server.enqueue(json(fixture("history")))
        val f = feed()
        assertEquals(3, (f.temperatures() as FeedResult.Ok).value.size)
        assertEquals(2, (f.macros() as FeedResult.Ok).value.macros.size)
        assertEquals(121L, (f.console(after = 118, count = 50) as FeedResult.Ok).value.seq)
        assertEquals(3, (f.files(fresh = true) as FeedResult.Ok).value.files.size)
        assertEquals(966, (f.history(start = 50) as FeedResult.Ok).value.totals.jobs)
        assertEquals("/v1/printer/temperatures", server.takeRequest().url.encodedPath)
        assertEquals("/v1/printer/macros", server.takeRequest().url.encodedPath)
        val console = server.takeRequest().url
        assertEquals("118", console.queryParameter("after"))
        assertEquals("50", console.queryParameter("count"))
        assertEquals("1", server.takeRequest().url.queryParameter("fresh"))
        assertEquals("50", server.takeRequest().url.queryParameter("start"))
    }

    @Test fun `a file path with odd characters is one encoded query value, never glued into the URL`() = runBlocking {
        server.enqueue(json("""{"path":"a b/c&d.gcode"}"""))
        feed().fileDetail("a b/c&d.gcode")
        val url = server.takeRequest().url
        assertEquals("/v1/printer/files/detail", url.encodedPath)
        assertEquals("a b/c&d.gcode", url.queryParameter("path"))
    }

    @Test fun `a camera picture comes back as bytes, and the id is one path segment`() = runBlocking {
        val picture = byteArrayOf(1, 2, 3, 4, -1, -40)
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body(Buffer().write(picture)).build())
        val r = feed().snapshot("cam/1 x")
        assertArrayEquals(picture, (r as FeedResult.Ok).value)
        assertEquals("/v1/printer/camera/cam%2F1%20x/snapshot", server.takeRequest().url.encodedPath)
    }

    // --- Controls and commands ---------------------------------------------------------------------------------------

    @Test fun `controls sends on or off and returns the arm state`() = runBlocking {
        server.enqueue(json("""{"arm":{"armed":true,"until":1763400160000}}"""))
        val r = feed().controls(true)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/v1/printer/controls", sent.url.encodedPath)
        assertEquals("true", Json.parseToJsonElement(sent.body!!.utf8()).jsonObject.getValue("on").jsonPrimitive.content)
        assertTrue((r as FeedResult.Ok).value.armed)
    }

    @Test fun `a command is sent as typed JSON with its request id`() = runBlocking {
        server.enqueue(json("""{"sent":"G28","risk":"confirm"}"""))
        val r = feed().command(PrinterCommand.Home(HomeAxes.ALL), via = "phone", confirmed = true, dryRun = false, requestId = "req-1")
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("home", body.getValue("command").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("xyz", body.getValue("command").jsonObject.getValue("axes").jsonPrimitive.content)
        assertEquals("phone", body.getValue("via").jsonPrimitive.content)
        assertEquals("true", body.getValue("confirmed").jsonPrimitive.content)
        assertEquals("req-1", body.getValue("requestId").jsonPrimitive.content)
        assertEquals(CommandAnswer.Sent("G28", "confirm"), r)
    }

    @Test fun `a dry run sends no request id and says it is a dry run`() = runBlocking {
        server.enqueue(json("""{"allowed":true,"risk":"confirm","question":"Home all axes?","confirm":"Home"}"""))
        val r = feed().command(PrinterCommand.Home(HomeAxes.ALL), via = "chat", confirmed = false, dryRun = true, requestId = "ignored")
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("true", body.getValue("dryRun").jsonPrimitive.content)
        assertFalse("requestId" in body)
        assertEquals("Home all axes?", (r as CommandAnswer.Checked).check.question)
    }

    @Test fun `a command the owner must confirm comes back with the server's own question`() = runBlocking {
        server.enqueue(json(fixture("check-confirm"), code = 409))
        val r = feed().command(PrinterCommand.Home(HomeAxes.ALL), "phone", confirmed = false, dryRun = false, requestId = "r")
        val check = (r as CommandAnswer.NeedsConfirm).check
        assertEquals("Home all axes?", check.question)
        assertEquals("Home", check.confirm)
        assertEquals(Risks.CONFIRM, check.risk)
    }

    @Test fun `Controls being off and a refusal are told apart`() = runBlocking {
        server.enqueue(json(fixture("error-controls-off"), code = 409))
        server.enqueue(json(fixture("error-refused"), code = 422))
        val f = feed()
        assertEquals(CommandAnswer.ControlsOff, f.command(PrinterCommand.Pause, "phone", false, false, "a"))
        assertEquals(CommandAnswer.Refused("Home the printer first."), f.command(PrinterCommand.Jog(Axis.X, 1.0), "phone", false, false, "b"))
    }

    @Test fun `other errors are put in plain words with their code kept`() = runBlocking {
        server.enqueue(json("""{"error":{"code":"PRINTER_UNREACHABLE","message":"x"}}""", 502))
        server.enqueue(json("""{"error":{"code":"CONTROL_OFF","message":"x"}}""", 403))
        server.enqueue(json("""{"error":{"code":"TOO_MANY","message":"x"}}""", 429))
        val f = feed()
        assertEquals(CommandAnswer.Failed(PrinterWords.PRINTER_UNREACHABLE, "PRINTER_UNREACHABLE"), f.command(PrinterCommand.Pause, "phone", false, false, "a"))
        assertEquals(CommandAnswer.Failed(PrinterWords.CONTROL_OFF, "CONTROL_OFF"), f.command(PrinterCommand.Pause, "phone", false, false, "b"))
        assertEquals(CommandAnswer.Failed(PrinterWords.TOO_MANY, "TOO_MANY"), f.command(PrinterCommand.Pause, "phone", false, false, "c"))
    }

    @Test fun `a refusal read from a read route keeps the server's words`() = runBlocking {
        server.enqueue(json("""{"error":{"code":"REFUSED","message":"Not while printing: the printer would stop answering for several seconds."}}""", 422))
        val r = feed().files(fresh = true) as FeedResult.Err
        assertEquals(422, r.status)
        assertEquals("REFUSED", r.code)
        assertEquals("Not while printing: the printer would stop answering for several seconds.", PrinterWords.forError(r))
    }

    // --- Upload, settings, events -----------------------------------------------------------------------------------

    @Test fun `an upload streams the bytes with the file name`() = runBlocking {
        server.enqueue(json("""{"path":"benchy.gcode"}"""))
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val r = feed().upload("benchy.gcode", ByteArrayInputStream(bytes), bytes.size.toLong())
        val sent = server.takeRequest()
        assertEquals("/v1/printer/files/upload", sent.url.encodedPath)
        assertEquals("benchy.gcode", sent.url.queryParameter("name"))
        assertEquals("application/octet-stream", sent.headers["Content-Type"])
        assertArrayEquals(bytes, sent.body!!.toByteArray())
        assertEquals("benchy.gcode", (r as FeedResult.Ok).value)
    }

    @Test fun `a bigger upload arrives byte for byte and the progress reaches its size`() = runBlocking {
        server.enqueue(json("""{"path":"big.3mf"}"""))
        val bytes = ByteArray(2_500_000) { (it * 31 % 253).toByte() }
        val progress = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val r = feed().upload("big.3mf", ByteArrayInputStream(bytes), bytes.size.toLong()) { progress += it }
        val sent = server.takeRequest()
        assertEquals(bytes.size.toLong(), sent.headers["Content-Length"]?.toLong())
        assertArrayEquals(bytes, sent.body!!.toByteArray())
        assertEquals(bytes.size.toLong(), progress.last())
        assertEquals("big.3mf", (r as FeedResult.Ok).value)
    }

    @Test fun `an upload Jarvis refuses is answered in words`() = runBlocking {
        server.enqueue(json("""{"error":{"code":"CONTROLS_OFF","message":"x"}}""", 409))
        val off = feed().upload("a.gcode", ByteArrayInputStream(ByteArray(10)), 10)
        assertEquals(PrinterWords.CONTROLS_OFF, PrinterWords.forFailure(off))
        server.enqueue(json("""{"error":{"code":"TOO_LARGE","message":"x"}}""", 413))
        val big = feed().upload("a.gcode", ByteArrayInputStream(ByteArray(10)), 10)
        assertEquals(PrinterWords.TOO_LARGE, PrinterWords.forFailure(big))
        server.enqueue(json("""{"error":{"code":"BAD_REQUEST","message":"x"}}""", 400))
        val bad = feed().upload("a.gcode", ByteArrayInputStream(ByteArray(10)), 10)
        assertEquals(PrinterWords.BAD_REQUEST, PrinterWords.forFailure(bad))
    }

    @Test fun `an upload with no answer from the PC says Jarvis is away`() = runBlocking {
        server.close()
        val r = feed().upload("a.gcode", ByteArrayInputStream(ByteArray(10)), 10)
        assertEquals(FeedResult.Unreachable, r)
    }

    @Test fun `settings sends only what was given`() = runBlocking {
        server.enqueue(json("""{"presets":[{"name":"PLA","extruder":210,"bed":60}],"hiddenMacros":[]}"""))
        val r = feed().settings(presets = listOf(Preset("PLA", 210, 60)), hiddenMacros = null)
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertTrue("presets" in body)
        assertFalse("hiddenMacros" in body)
        assertEquals(210, (r as FeedResult.Ok).value.presets[0].extruder)
    }

    @Test fun `events ask after the last number and wait`() = runBlocking {
        server.enqueue(json("""{"seq":99,"events":[{"seq":99,"time":1,"kind":"finished","title":"Print finished","text":"Done."}]}"""))
        val r = feed().events(after = 98, waitS = 20)
        val url = server.takeRequest().url
        assertEquals("98", url.queryParameter("after"))
        assertEquals("20", url.queryParameter("wait"))
        assertEquals("finished", (r as FeedResult.Ok).value.events[0].kind)
    }

    // --- When Jarvis can't be reached or has forgotten the phone -----------------------------------------------------

    @Test fun `a removed phone is Unauthorized and the connection is told`() = runBlocking {
        server.enqueue(json("""{"error":{"code":"UNAUTHORIZED","message":"no"}}""", 401))
        assertEquals(FeedResult.Unauthorized, feed().state(null, null, 0))
        assertEquals(1, unauthorized)
        assertEquals(PrinterWords.UNAUTHORIZED, PrinterWords.forFailure(FeedResult.Unauthorized))
    }

    @Test fun `no pairing at all is Unauthorized without a request`() = runBlocking {
        assertEquals(FeedResult.Unauthorized, feed(l = null).state(null, null, 0))
        assertEquals(0, server.requestCount)
    }

    @Test fun `a PC that is off is Unreachable`() = runBlocking {
        val f = feed()
        server.close()
        assertEquals(FeedResult.Unreachable, f.state(null, null, 0))
        assertEquals(PrinterWords.UNREACHABLE, PrinterWords.forFailure(FeedResult.Unreachable))
    }

    @Test fun `another PC's certificate is refused with its own words`() = runBlocking {
        val other = PrinterFeedApi(JarvisApi({ link().copy(fingerprint = "00".repeat(32)) }, http, testDispatchers()))
        val r = other.state(null, null, 0) as FeedResult.Err
        assertEquals("CERTIFICATE_CHANGED", r.code)
        assertTrue(PrinterWords.forError(r).contains("certificate changed"))
    }

    @Test fun `an answer that is not what was promised is not a crash`() = runBlocking {
        server.enqueue(json("this is not json"))
        val r = feed().state(null, null, 0) as FeedResult.Err
        assertEquals("UNREADABLE", r.code)
        assertEquals(PrinterWords.UNEXPECTED, PrinterWords.forError(r))
    }

    @Test fun `the token never appears in the words of an error`() = runBlocking {
        server.enqueue(json("""{"error":{"code":"BOOM","message":"went wrong"}}""", 500))
        val r = feed().state(null, null, 0)
        assertFalse(PrinterWords.forFailure(r).contains("SECRET"))
        assertFalse(r.toString().contains("SECRET"))
    }

    @Test fun `a time limit cancels a held poll at once instead of waiting for Jarvis`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).headersDelay(20, TimeUnit.SECONDS).body("{}").build())
        val started = System.nanoTime()
        val r = withTimeoutOrNull(300) { feed().state(after = 1, epoch = 1, waitS = 20) }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertNull("the limit fired", r)
        assertTrue("stopped in $tookMs ms, not after the call finished", tookMs < 3_000)
    }

    @Test fun `words for each code are the table's`() {
        fun err(code: String, message: String = "x") = FeedResult.Err(400, code, message, "")
        assertEquals(PrinterWords.NOT_SHARED, PrinterWords.forError(err("PRINTER_NOT_SHARED")))
        assertEquals(PrinterWords.CONTROLS_OFF, PrinterWords.forError(err("CONTROLS_OFF")))
        assertEquals(PrinterWords.TIMEOUT, PrinterWords.forError(err("TIMEOUT")))
        assertEquals(PrinterWords.TOO_LARGE, PrinterWords.forError(err("TOO_LARGE")))
        assertEquals(PrinterWords.NOT_FOUND, PrinterWords.forError(err("NOT_FOUND")))
        assertEquals(PrinterWords.BAD_REQUEST, PrinterWords.forError(err("BAD_REQUEST")))
        assertEquals("This isn’t allowed away from home: only look.", PrinterWords.forError(err("AWAY_LIMITED", "This isn’t allowed away from home: only look.")))
        assertEquals(PrinterWords.UNEXPECTED, PrinterWords.forError(err("WEIRD", "")))
        assertEquals("A sentence from Jarvis.", PrinterWords.forError(err("WEIRD", "A sentence from Jarvis.")))
        assertEquals(PrinterWords.UNEXPECTED, PrinterWords.forError(err("WEIRD", "SOME_CODE")))
    }
}
