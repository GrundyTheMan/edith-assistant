package com.edith.app.jarvis

import com.edith.app.accounts.Account
import kotlinx.datetime.TimeZone
import com.edith.app.accounts.Provider
import com.edith.app.accounts.sources.MailQuery
import com.edith.app.accounts.sources.OutgoingMail
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.net.HttpClients
import com.edith.app.testing.testDispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * C38: Jarvis's feed as Edith reads it. A TLS MockWebServer with a throwaway self-signed certificate stands in for the
 * PC; Edith pins that certificate's fingerprint, as she does after pairing. Never touches the network.
 */
class JarvisFeedTest {
    private val server = MockWebServer()
    private lateinit var certificate: HeldCertificate
    private lateinit var fingerprint: String
    private val account = Account("a1b2c3", Provider.Jarvis, "BEN-PC", "Work")
    private val dispatchers = testDispatchers()
    private val http = HttpClients { OkHttpClient() }.secure
    private var unauthorized = 0

    @Before fun setUp() {
        certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName).build()
        fingerprint = JarvisPairing.fingerprint(certificate.certificate.encoded)
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory())
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun link(token: String = "tok-123", pinned: String = fingerprint) = JarvisLink(server.url("/").toString().trimEnd('/'), pinned, token, "BEN-PC")
    private fun api(l: JarvisLink? = link()) = JarvisApi({ l }, OkHttpPinnedHttp(http), dispatchers, onUnauthorized = { unauthorized++ })
    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    // --- Pairing, the pure parts -------------------------------------------------------------------------------

    @Test fun `an address gets https and the feed's port unless the owner gave one`() {
        assertEquals("https://192.168.1.20:47821/", JarvisPairing.address("192.168.1.20").first.toString())
        assertEquals("https://100.64.0.1:5000/", JarvisPairing.address("100.64.0.1:5000").first.toString())
        assertEquals("https://pc.local:47821/", JarvisPairing.address(" https://pc.local/ ").first.toString())
        assertEquals("https://[fd7a:115c:a1e0::1]:47821/", JarvisPairing.address("https://[fd7a:115c:a1e0::1]").first.toString())
    }

    @Test fun `plain http and nonsense are refused with a reason`() {
        assertEquals(JarvisPairing.CLEARTEXT, JarvisPairing.address("http://192.168.1.20:47821").second)
        assertEquals(JarvisPairing.NOT_AN_ADDRESS, JarvisPairing.address("   ").second)
        assertNull(JarvisPairing.address("not an address at all").first)
    }

    @Test fun `the proof is Jarvis's HMAC, the same for any spelling of the code`() {
        val fp = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        val expected = "c1fdf40ac14a134c3754fdc75a316a9a139f730dcbac5b9dc75f5d5226d6f1de"   // from Jarvis's pairingProof (Node's createHmac)
        assertEquals(expected, JarvisPairing.proof("ABCDE-FGHJK", fp))
        assertEquals(expected, JarvisPairing.proof("abcde fghjk", fp))
        assertEquals(expected, JarvisPairing.proof("ABCDEFGHJK", fp.uppercase()))
    }

    @Test fun `the fingerprint is shown in groups of four, as Jarvis shows it`() {
        assertEquals("a559 fd3a cd73 9f17 e54c 7701", JarvisPairing.shortFingerprint("a559fd3acd739f17e54c7701460bf64d159619bf944b478879f251935920afc0"))
    }

    // --- Pairing over TLS ---------------------------------------------------------------------------------------

    @Test fun `probe finds the certificate fingerprint and checks it's Jarvis`() = runBlocking {
        server.enqueue(json("""{"app":"jarvis-feed","version":1}"""))
        val r = api(null).probe(io.ktor.http.Url(server.url("/").toString()))
        assertEquals(fingerprint, (r as Outcome.Success).value)
    }

    @Test fun `probe says so when the address answers but isn't Jarvis`() = runBlocking {
        server.enqueue(json("""{"hello":"world"}"""))
        val r = api(null).probe(io.ktor.http.Url(server.url("/").toString())) as Outcome.Failure
        assertEquals(JarvisApi.NOT_JARVIS, (r.error as AppError.ToolFailed).detail)
    }

    @Test fun `pairing sends the proof, never the code, and keeps what Jarvis returns`() = runBlocking {
        server.enqueue(json("""{"token":"T0KEN","deviceId":"d1","pcName":"BEN-PC","version":1}"""))
        val r = api(null).pair(io.ktor.http.Url(server.url("/").toString()), fingerprint, "ABCDE-FGHJK", "Pixel 9") as Outcome.Success
        assertEquals("T0KEN", r.value.token)
        assertEquals(fingerprint, r.value.fingerprint)
        assertEquals("BEN-PC", r.value.pcName)
        val sent = server.takeRequest()
        assertEquals("/v1/pair", sent.url.encodedPath)
        val body = sent.body!!.utf8()
        assertFalse("the code must not be sent: $body", body.contains("ABCDE") || body.contains("FGHJK"))
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertEquals("Pixel 9", parsed["name"]!!.jsonPrimitive.content)
        assertEquals(JarvisPairing.proof("ABCDE-FGHJK", fingerprint), parsed["proof"]!!.jsonPrimitive.content)
    }

    @Test fun `a refused code is explained`() = runBlocking {
        server.enqueue(json("""{"error":{"code":"PAIRING_REFUSED","message":"no"}}""", 403))
        val r = api(null).pair(io.ktor.http.Url(server.url("/").toString()), fingerprint, "ABCDE-FGHJK", "Pixel") as Outcome.Failure
        assertEquals(JarvisApi.PAIRING_REFUSED, (r.error as AppError.ToolFailed).detail)
    }

    @Test fun `a PC whose certificate isn't the pinned one is refused before anything is sent`() = runBlocking {
        server.enqueue(json("""{"items":[],"more":false}"""))
        val wrong = "00".repeat(32)
        val r = api(link(pinned = wrong)).get("shared", emptyMap(), Shared.serializer()) as Outcome.Failure
        assertEquals(JarvisApi.MOVED, (r.error as AppError.ToolFailed).detail)
        assertEquals("no request reached the server", 0, server.requestCount)
    }

    // --- Reading --------------------------------------------------------------------------------------------------

    @Test fun `every request carries the device token`() = runBlocking {
        server.enqueue(json("""{"pcName":"BEN-PC","mail":[{"id":"m1","label":"Work","email":"me@corp.com"}],"calendars":[],"tasks":[]}"""))
        val r = api().get("shared", emptyMap(), Shared.serializer()) as Outcome.Success
        assertEquals("me@corp.com", r.value.mail.single().email)
        assertEquals("Bearer tok-123", server.takeRequest().headers["Authorization"])
    }

    @Test fun `a removed phone is told to pair again`() = runBlocking {
        server.enqueue(json("""{"error":{"code":"UNAUTHORIZED","message":"x"}}""", 401))
        val r = api().get("shared", emptyMap(), Shared.serializer()) as Outcome.Failure
        assertEquals(JarvisApi.UNAUTHORIZED, r.error)
        assertEquals(1, unauthorized)
    }

    @Test fun `no pairing at all reads as needing to pair`() = runBlocking {
        val r = api(null).get("shared", emptyMap(), Shared.serializer()) as Outcome.Failure
        assertEquals(JarvisApi.UNAUTHORIZED, r.error)
        assertEquals("nothing was sent", 0, server.requestCount)
    }

    @Test fun `error codes become plain words`() {
        fun detail(status: Int, code: String) = (JarvisApi.failureFor(status, """{"error":{"code":"$code","message":"m"}}""") as AppError.ToolFailed).detail
        assertEquals(JarvisApi.OUTLOOK_DOWN, detail(503, "OUTLOOK_UNAVAILABLE"))
        assertTrue(detail(404, "NOT_SHARED").contains("Settings → Edith"))
        assertTrue(detail(429, "TOO_MANY").contains("wait"))
        assertEquals(AppError.Timeout, JarvisApi.failureFor(504, """{"error":{"code":"TIMEOUT","message":"m"}}"""))
        assertTrue(detail(500, "FEED_ERROR").contains("500"))
        assertTrue("an unreadable body still gives an answer", JarvisApi.failureFor(502, "<html>").let { it is AppError.ToolFailed })
    }

    @Test fun `mail comes back as summaries, with ids that keep their colons`() = runBlocking {
        server.enqueue(json("""{"items":[{"id":"acc:1:42","account":"acc","from":{"name":"Sam","address":"sam@corp.com"},"to":["me@corp.com"],"cc":[],
            "subject":"Budget","date":1763320000000,"unread":true,"flagged":false,"answered":false,"toMe":"to","preview":"Numbers inside","score":7}],"more":true}"""))
        val src = JarvisMailSource(account, api())
        val found = (src.search(MailQuery(text = "budget"), 10) as Outcome.Success).value
        assertTrue(found.more)
        val m = found.messages.single()
        assertEquals("acc:1:42", m.id)
        assertEquals("Sam <sam@corp.com>", m.from)
        assertEquals(account.id, m.accountId)
        assertEquals(Instant.fromEpochMilliseconds(1763320000000), m.date)
        val sent = server.takeRequest()
        assertEquals("budget", sent.url.queryParameter("text"))
        assertEquals("10", sent.url.queryParameter("limit"))

        server.enqueue(json("""{"message":{"id":"acc:1:42","from":{"name":"Sam","address":"sam@corp.com"},"cc":["boss@corp.com"],"subject":"Budget","date":1763320000000,"unread":true},
            "text":"Hello","truncated":true,"attachments":[]}"""))
        val read = (src.read("acc:1:42") as Outcome.Success).value
        assertTrue(read.body.startsWith("Hello"))
        assertTrue("a cut-off body says so", read.body.contains("cut off"))
        assertEquals("boss@corp.com", read.cc)
        assertEquals("/v1/mail/messages/acc:1:42", server.takeRequest().url.encodedPath.replace("%3A", ":"))
    }

    @Test fun `a reply is saved as a draft on the message it answers, and nothing can be sent`() = runBlocking {
        server.enqueue(json("""{"saved":true,"note":"ok"}"""))
        val src = JarvisMailSource(account, api())
        assertTrue(src.draft(OutgoingMail("sam@corp.com", "Re: Budget", "Thanks, looks fine.", replyToId = "acc:1:42")) is Outcome.Success)
        val sent = server.takeRequest()
        assertEquals("/v1/mail/drafts", sent.url.encodedPath)
        val body = Json.parseToJsonElement(sent.body!!.utf8()).jsonObject
        assertEquals("acc:1:42", body["replyTo"]!!.jsonPrimitive.content)
        assertEquals("Thanks, looks fine.", body["text"]!!.jsonPrimitive.content)
        assertFalse(src.canSend)
        assertTrue(src.send(OutgoingMail("a@b.com", "s", "b")) is Outcome.Failure)
        assertEquals("send made no request", 1, server.requestCount)
    }

    @Test fun `a new draft goes from the first shared mailbox`() = runBlocking {
        server.enqueue(json("""{"pcName":"BEN-PC","mail":[{"id":"acc","label":"Work","email":"me@corp.com"}],"calendars":[],"tasks":[]}"""))
        server.enqueue(json("""{"saved":true}"""))
        JarvisMailSource(account, api()).draft(OutgoingMail("sam@corp.com", "Hello", "Hi Sam"))
        server.takeRequest()
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("acc", body["account"]!!.jsonPrimitive.content)
        assertEquals("sam@corp.com", body["to"]!!.jsonPrimitive.content)
        assertNull(body["replyTo"])
    }

    @Test fun `events are asked for in pieces Jarvis allows, merged and sorted, and cancelled ones are left out`() = runBlocking {
        val day = 86_400_000L
        val from = Instant.parse("2026-11-16T00:00:00Z")
        server.enqueue(json("""{"pcName":"x","mail":[],"calendars":[{"id":"c:1","label":"Work"}],"tasks":[]}"""))
        repeat(3) { i ->
            val items = when (i) {
                0 -> """[{"id":"e2","calendar":"c:1","title":"Later","start":${from.toEpochMilliseconds() + 2 * day},"end":${from.toEpochMilliseconds() + 2 * day + 3_600_000}},
                        {"id":"e0","calendar":"c:1","title":"Cancelled","start":${from.toEpochMilliseconds() + day},"end":${from.toEpochMilliseconds() + day + 3_600_000},"cancelled":true}]"""
                1 -> """[{"id":"e1","calendar":"c:1","title":"Earlier","start":${from.toEpochMilliseconds() + day},"end":${from.toEpochMilliseconds() + day + 1800_000},"location":"Room 4","joinUrl":"https://teams/x"}]"""
                else -> "[]"
            }
            server.enqueue(json("""{"items":$items,"more":false}"""))
        }
        val src = JarvisCalendarSource(account, api())
        val events = (src.events(from, from + (200 * 86_400L).seconds, null) as Outcome.Success).value
        assertEquals(listOf("Earlier", "Later"), events.map { it.title })
        assertEquals("Work", events[0].calendarName)
        assertEquals("Room 4", events[0].location)
        assertTrue(events[0].description!!.contains("https://teams/x"))
        server.takeRequest()   // shared
        val spans = (1..3).map { server.takeRequest().url.let { u -> u.queryParameter("to")!!.toLong() - u.queryParameter("from")!!.toLong() } }
        assertTrue("each piece at most 92 days: $spans", spans.all { it <= 92 * day })
        assertTrue(src.readOnly)
    }

    @Test fun `an all-day event runs from midnight to the next midnight in the phone's zone`() {
        val e = WireEvent("e", "c:1", "Holiday", start = Instant.parse("2026-12-25T00:00:00Z").toEpochMilliseconds(), end = Instant.parse("2026-12-26T00:00:00Z").toEpochMilliseconds(), allDay = true)
            .toEvent("a", "Work")
        assertTrue(e.allDay)
        assertEquals("2026-12-25", e.start.toLocalDateTime(TimeZone.UTC).date.toString())
        assertEquals("2026-12-26", e.end.toLocalDateTime(TimeZone.UTC).date.toString())
    }

    @Test fun `tasks come back as to-dos in Jarvis's order and can't be changed`() = runBlocking {
        server.enqueue(json("""{"items":[{"id":"t1","title":"Send report","due":"2026-11-20","done":false,"notes":"n"},{"id":"t2","title":"Done one","done":true,"completed":1763320000000}],"more":false}"""))
        val src = JarvisTaskSource(account, api())
        val tasks = (src.tasks("outlook:1", includeDone = true) as Outcome.Success).value
        assertEquals(listOf("Send report", "Done one"), tasks.map { it.title })
        assertEquals("2026-11-20", tasks[0].due.toString())
        assertTrue(tasks[1].done)
        assertNotNull(tasks[1].completedAt)
        assertTrue(tasks[0].position < tasks[1].position)
        val req = server.takeRequest()
        assertEquals("outlook:1", req.url.queryParameter("list"))
        assertEquals("1", req.url.queryParameter("completed"))
        assertTrue(src.readOnly)
        assertTrue(src.complete("outlook:1", "t1", true) is Outcome.Failure)
        assertTrue(src.insert("outlook:1", "x", null, null) is Outcome.Failure)
        assertEquals("changes made no request", 1, server.requestCount)
    }

    @Test fun `the link never prints its token`() {
        assertFalse(link(token = "SECRET-TOKEN").toString().contains("SECRET"))
    }

    @Test fun `a time limit cancels a slow Jarvis call at once instead of waiting for it`() = runBlocking {
        // dev.14 on the owner's phone: a slow Outlook left a blocking call that no timeout could interrupt, and the reply never came.
        server.enqueue(MockResponse.Builder().code(200).headersDelay(20, java.util.concurrent.TimeUnit.SECONDS).body("{}").build())
        val started = System.nanoTime()
        val r = kotlinx.coroutines.withTimeoutOrNull(300) { api().get("shared", emptyMap(), Shared.serializer()) }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertNull("the limit fired", r)
        assertTrue("stopped in $tookMs ms, not after the call finished", tookMs < 3_000)
    }
}
