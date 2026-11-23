package com.edith.app.icloud

import kotlinx.datetime.toKotlinTimeZone
import android.app.Activity
import com.edith.app.accounts.AccountSecrets
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.Provider
import com.edith.app.accounts.tools.CalendarHandles
import com.edith.app.accounts.tools.MailHandles
import com.edith.app.accounts.tools.calendarTools
import com.edith.app.accounts.tools.mailTools
import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.chat.engine.TurnEvent
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.LogRingBuffer
import com.edith.app.google.GoogleAuthorizer
import com.edith.app.google.GoogleSignIn
import com.edith.app.icloud.mail.MailServers
import com.edith.app.ollama.ChatCall
import com.edith.app.ollama.ChatEvent
import com.edith.app.ollama.ModelInfo
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaFunctionCall
import com.edith.app.ollama.OllamaMessage
import com.edith.app.ollama.OllamaModelTag
import com.edith.app.ollama.OllamaShow
import com.edith.app.ollama.OllamaToolCall
import com.edith.app.ollama.ReplyStats
import com.edith.app.testing.AccountsHarness
import com.edith.app.testing.FakeClock
import com.edith.app.testing.FakeSettingsRepository
import com.edith.app.tools.framework.ApprovalChannel
import com.edith.app.tools.framework.Confirmations
import com.edith.app.tools.framework.ToolRegistry
import com.edith.app.tools.framework.ToolTurnRunner
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Collections

/**
 * C24 §1 and the tools: the add-account check (a wrong password, mail only, both, the full address as login), and an
 * iCloud account next to a Google one through C22's real tool loop. Mail is GreenMail; CalDAV and Google are a
 * MockWebServer.
 */
class ICloudAccountsTest {
    private val zone = ZoneId.of("America/Detroit")
    private val clock = FakeClock(Instant.parse("2026-11-02T18:00:00Z"), zone)
    private val mail = GreenMail(ServerSetup.dynamicPort(ServerSetupTest.SMTP_IMAP))
    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    @Volatile private var calendarWorks = true
    private val password = "abcd-efgh-ijkl-mnop"
    private val auth = Credentials.basic("owner@icloud.com", password, Charsets.UTF_8)
    private lateinit var h: AccountsHarness

    private fun xml(body: String) = MockResponse.Builder().code(207).body(body).addHeader("Content-Type", "application/xml").build()
    private fun json(body: String) = MockResponse.Builder().code(200).body(body).addHeader("Content-Type", "application/json").build()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                if (path.startsWith("/calendar/v3")) return google(path)
                if (!calendarWorks) return MockResponse.Builder().code(503).build()
                if (request.headers["Authorization"] != auth) return MockResponse.Builder().code(401).build()
                return when {
                    path == "/" -> xml("""<multistatus xmlns="DAV:"><response><href>/</href><propstat><prop><current-user-principal><href>/1/principal/</href></current-user-principal></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>""")
                    path == "/1/principal/" -> xml("""<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/1/principal/</d:href><d:propstat><d:prop><c:calendar-home-set><d:href>/1/calendars/</d:href></c:calendar-home-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
                    path == "/1/calendars/" -> xml("""<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/1/calendars/home/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Home</d:displayname><c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
                    request.method == "REPORT" -> xml(
                        """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/1/calendars/home/a.ics</d:href><d:propstat><d:prop><d:getetag>"1"</d:getetag><c:calendar-data>BEGIN:VCALENDAR
BEGIN:VEVENT
UID:a
SUMMARY:Piano lesson
DTSTART:20261103T220000Z
DTEND:20261103T230000Z
END:VEVENT
END:VCALENDAR
</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""" +
                            // A daily series, expanded by the server into instances (C24: iCloud honours <C:expand>).
                            """<d:response><d:href>/1/calendars/home/workout.ics</d:href><d:propstat><d:prop><d:getetag>"w1"</d:getetag><c:calendar-data>BEGIN:VCALENDAR
BEGIN:VEVENT
UID:workout
RECURRENCE-ID:20261103T230000Z
SUMMARY:Workout
DTSTART:20261103T230000Z
DTEND:20261104T000000Z
END:VEVENT
END:VCALENDAR
</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
                    )
                    request.method == "GET" && path == "/1/calendars/home/workout.ics" -> MockResponse.Builder().code(200).addHeader("ETag", "\"w1\"")
                        .body("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:workout\r\nRRULE:FREQ=DAILY\r\nSUMMARY:Workout\r\nDTSTART:20261101T230000Z\r\nDTEND:20261102T000000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n").build()
                    request.method == "DELETE" -> MockResponse.Builder().code(204).build()
                    request.method == "PUT" -> MockResponse.Builder().code(201).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        mail.start()
        LogRingBuffer.clear()
    }

    @After fun tearDown() {
        server.close()
        mail.stop()
    }

    private fun google(path: String): MockResponse = when {
        path.endsWith("/users/me/calendarList") -> json("""{"items":[{"id":"a@gmail.com","summary":"Personal","primary":true,"selected":true,"accessRole":"owner"}]}""")
        path.endsWith("/events") -> json("""{"items":[{"id":"g1","summary":"Gym","start":{"dateTime":"2026-11-03T08:00:00-05:00"},"end":{"dateTime":"2026-11-03T09:00:00-05:00"}}]}""")
        else -> MockResponse.Builder().code(404).build()
    }

    private fun harness() = AccountsHarness(
        baseUrl = server.url("/").toString(),
        icloudMail = MailServers(imapHost = "127.0.0.1", imapPort = mail.imap.port, imapSsl = false, smtpHost = "127.0.0.1", smtpPort = mail.smtp.port, smtpStartTls = false, timeoutMs = 5_000),
        zone = zone,
        clock = clock,
    ).also { h = it }

    // --- The add-account check ------------------------------------------------------------------------------------

    @Test fun `a wrong password is refused with Apple's advice, and nothing is saved`() = runBlocking {
        mail.setUser("owner@icloud.com", "owner", password)
        val h = harness()
        assertEquals(ICloudAccounts.WRONG_PASSWORD, h.accounts.addICloud("owner@icloud.com", "my-apple-account-password"))
        assertTrue(h.accounts.accounts.value.isEmpty())
        assertTrue(requests.none { it.url.encodedPath == "/" })
    }

    @Test fun `mail and calendar - added, Connected, the password kept encrypted and never logged`() = runBlocking {
        mail.setUser("owner@icloud.com", "owner", password)
        val h = harness()
        assertNull(h.accounts.addICloud(" owner@icloud.com ", " $password "))
        val (account, state) = h.accounts.accounts.value.single()
        assertEquals(Provider.ICloud, account.provider)
        assertEquals("owner@icloud.com", account.address)
        assertEquals("the mail name worked first", "owner", account.login)
        assertEquals(AccountState.Connected, state)
        assertEquals(password, h.secrets.get(AccountSecrets.password(account.id)))
        assertTrue(h.accounts.entries().single().capabilities.calendar)
        val log = LogRingBuffer.tail(LogRingBuffer.CAPACITY).joinToString("\n")
        assertFalse("trap 4: never the password", log.contains(password))
        assertFalse("never the address", log.contains("owner@icloud.com"))
    }

    @Test fun `mail only when the calendar fails, and the full address when the mail name is refused`() = runBlocking {
        mail.setUser("owner@icloud.com", "owner@icloud.com", password)   // this server only knows the full address
        calendarWorks = false
        val h = harness()
        assertEquals(ICloudAccounts.CALENDAR_UNAVAILABLE, h.accounts.addICloud("owner@icloud.com", password))
        val entry = h.accounts.entries().single()
        assertEquals("owner@icloud.com", entry.account.login)
        assertFalse(entry.capabilities.calendar)
        assertTrue(entry.capabilities.mail)
        assertNull(entry.calendar)
        assertEquals(AccountState.Connected, entry.state)
    }

    @Test fun `disconnect deletes the password from the phone`() = runBlocking {
        mail.setUser("owner@icloud.com", "owner", password)
        val h = harness()
        h.accounts.addICloud("owner@icloud.com", password)
        val id = h.accounts.accounts.value.single().first.id
        h.accounts.disconnect(id)
        assertNull(h.secrets.get(AccountSecrets.password(id)))
        assertTrue(h.accounts.accounts.value.isEmpty())
    }

    // --- Through the tool loop, next to Google -------------------------------------------------------------------

    private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private val calHandles = CalendarHandles()
    private val mailHandles = MailHandles()

    private fun googleAndICloud() = runBlocking {
        mail.setUser("owner@icloud.com", "owner", password)
        val h = harness()
        h.play.perAccount["a@gmail.com"] = GoogleAuthorizer.Result.Granted("tok-personal")
        h.signIn.result = GoogleSignIn.Result.SignedIn("a@gmail.com", null)
        h.accounts.addGoogle(Activity())
        h.accounts.rename(h.accounts.accounts.value.single().first.id, "personal")
        h.accounts.addICloud("owner@icloud.com", password)
        h.accounts.rename(h.accounts.accounts.value.last().first.id, "icloud")
        requests.clear()
    }

    private class Turn(val card: ConfirmationRequest?, val result: String)

    private fun turn(tool: String, vararg arguments: Pair<String, String>, approve: Boolean = true): Turn = runBlocking {
        var result = ""
        val ollama = object : OllamaClient {
            var round = 0
            override fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent> = flow {
                if (round++ == 0) {
                    val call = OllamaToolCall(function = OllamaFunctionCall(tool, JsonObject(arguments.associate { it.first to JsonPrimitive(it.second) })))
                    emit(ChatEvent.ToolCalls(OllamaMessage("assistant", "", toolCalls = listOf(call))))
                } else {
                    result = request.messages.last { it.role == "tool" }.content
                    emit(ChatEvent.Delta("ok"))
                }
                emit(ChatEvent.Done(ReplyStats(1, 1, 1)))
            }
            override suspend fun chat(baseUrl: String, request: ChatCall) = Outcome.Failure(AppError.EmptyReply)
            override suspend fun listModels(baseUrl: String) = Outcome.Success(emptyList<OllamaModelTag>())
            override suspend fun show(baseUrl: String, model: String) = Outcome.Success(OllamaShow(listOf("tools")))
            override suspend fun version(baseUrl: String) = Outcome.Success("x")
        }
        val confirmations = Confirmations()
        val registry = ToolRegistry(calendarTools(h.accounts, calHandles) { zone.toKotlinTimeZone() } + mailTools(h.accounts, mailHandles), listOf(h.accounts))
        val runner = ToolTurnRunner(ollama, registry, confirmations, FakeSettingsRepository(), clock, dispatchers)
        var card: ConfirmationRequest? = null
        runner.run("http://ollama", ModelInfo("gemma4:12b", setOf("tools")), listOf(OllamaMessage("user", "go"))).collect { e ->
            if (e is TurnEvent.NeedsConfirmation) { card = e.request; confirmations.resolve(e.request.id, approve, ApprovalChannel.Tap) }
        }
        Turn(card, result)
    }

    @Test fun `iCloud and Google events are listed together, labelled`() {
        googleAndICloud()
        val t = turn("list_calendar_events", "start" to "2026-11-03T00:00", "end" to "2026-11-04T00:00")
        val lines = t.result.lines().filter { it.contains("{event_id:") }
        assertEquals(3, lines.size)
        assertTrue(lines[0], lines[0].contains("Gym") && lines[0].contains("personal"))
        assertTrue(lines[1], lines[1].contains("Piano lesson") && lines[1].contains("icloud"))
        assertTrue("an occurrence of the daily series: ${lines[2]}", lines[2].contains("Workout") && lines[2].contains("icloud"))
    }

    @Test fun `a new event - Edith asks which account, offering both, and declining sends nothing`() {
        googleAndICloud()
        val add = arrayOf("title" to "Test", "start" to "2026-11-03T15:00")
        val ask = turn("create_calendar_event", *add)
        assertNull(ask.card)
        assertEquals(
            "Ask the owner which account to use: personal (a@gmail.com) or icloud (owner@icloud.com). Then call this tool again with `account`.",
            ask.result,
        )
        val declined = turn("create_calendar_event", *add, "account" to "icloud", approve = false)
        assertEquals("Account" to "icloud (owner@icloud.com) — Home", declined.card!!.details.last())
        assertTrue(requests.none { it.method == "PUT" })

        turn("create_calendar_event", *add, "account" to "icloud")
        val put = requests.single { it.method == "PUT" }
        assertTrue(put.url.encodedPath.startsWith("/1/calendars/home/"))
        assertEquals("*", put.headers["If-None-Match"])
    }

    @Test fun `a repeating iCloud event - no card without whole_series, then the series card and one DELETE`() {
        googleAndICloud()
        val listing = turn("list_calendar_events", "start" to "2026-11-03T00:00", "end" to "2026-11-04T00:00")
        val workout = Regex("""Workout.*\{event_id: (e\d+)\}""").find(listing.result)!!.groupValues[1]
        requests.clear()

        val ask = turn("delete_calendar_event", "event_id" to workout)
        assertNull("no card", ask.card)
        assertTrue(ask.result, ask.result.contains("Ask the owner whether to delete the whole series"))
        assertTrue("nothing sent", requests.isEmpty())

        val declined = turn("delete_calendar_event", "event_id" to workout, "whole_series" to "true", approve = false)
        assertEquals("Delete the whole repeating series?", declined.card!!.title)
        assertTrue(declined.card.details.any { it.first == "Deletes" && it.second.startsWith("Every occurrence") })
        assertTrue("declined: nothing deleted", requests.none { it.method == "DELETE" })

        val done = turn("delete_calendar_event", "event_id" to workout, "whole_series" to "true")
        assertEquals("Deleted the whole repeating series \"Workout\" from icloud (owner@icloud.com).", done.result)
        val delete = requests.single { it.method == "DELETE" }
        assertEquals("/1/calendars/home/workout.ics", delete.url.encodedPath)
        assertEquals("\"w1\"", delete.headers["If-Match"])
    }
}
