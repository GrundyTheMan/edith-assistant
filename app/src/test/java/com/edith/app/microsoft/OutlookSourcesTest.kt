package com.edith.app.microsoft

import com.edith.app.core.net.HttpClients
import com.edith.app.accounts.Account
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import com.edith.app.accounts.AccountSecrets
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.Provider
import com.edith.app.accounts.sources.CalendarEvent
import com.edith.app.accounts.sources.EventChange
import com.edith.app.accounts.sources.MailQuery
import com.edith.app.accounts.sources.OutgoingMail
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.google.browser.BrowserOAuth
import com.edith.app.testing.AppDispatchersForTests
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant
import java.util.Collections

/**
 * C23 §2–4: Outlook's calendar and mail over Graph, against a stand-in for Graph (MockWebServer) whose answers follow
 * Graph's reference (made up).
 */
class OutlookSourcesTest {
    private val zone = TimeZone.of("America/Detroit")
    private val clock = FakeClock(Instant.parse("2026-11-02T18:00:00Z"), zone)
    private val account = Account("m1", Provider.Microsoft, "owner@outlook.com", "outlook")
    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val graph get() = requests.filter { it.url.encodedPath.startsWith("/graph/") }
    private val writes get() = graph.filter { it.method != "GET" }
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val slept = mutableListOf<Long>()
    private lateinit var auth: MicrosoftAccountAuth

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    @Before fun setUp() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                if (path.endsWith("/oauth2/v2.0/token")) return json(MicrosoftAuthTest.tokens(access = "eyJ.graph", refresh = "M.R3_next"))
                return routes["${request.method} $path"]?.invoke() ?: json("""{"error":{"code":"ErrorItemNotFound","message":"Not found."}}""", 404)
            }
        }
        server.start()
        val secrets = InMemorySecretStore().apply { put(AccountSecrets.refreshToken("m1"), "M.R3") }
        val oauth = BrowserOAuth(lazy { HttpClients.over(OkHttpClient()) }, AppDispatchersForTests.unconfined, MicrosoftOAuth.provider("ms-client-id", loginBase = server.url("/ms").toString()))
        auth = MicrosoftAccountAuth({ account }, oauth, secrets, clock) {}
    }

    @After fun tearDown() = server.close()

    private fun client(tool: String, service: String) = GraphClient(
        lazy { HttpClients.over(OkHttpClient()) }, auth, AppDispatchersForTests.unconfined, tool, service, "gone", server.url("/graph/v1.0").toString(),
        sleep = { slept += it },
    )

    private fun calendar(windows: String? = "Eastern Standard Time") =
        OutlookCalendarSource({ account }, client("calendar", "Outlook Calendar"), { zone }, { windows })

    private fun mail() = OutlookMailSource({ account }, client("email", "Outlook"), { zone })

    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 11, d, h, m).toInstant(zone)

    private fun body(r: RecordedRequest): JsonObject = Json.parseToJsonElement(r.body!!.utf8()).jsonObject

    // --- Calendar -----------------------------------------------------------------------------------------------

    private fun calendars() {
        routes["GET /graph/v1.0/me/calendars"] = {
            json("""{"value":[
                {"id":"AAMkBirthdays=","name":"Birthdays","isDefaultCalendar":false,"canEdit":false},
                {"id":"AAMkCal/1=","name":"Calendar","isDefaultCalendar":true,"canEdit":true}]}""")
        }
    }

    @Test fun `calendars - every one read, the default first, read-only ones marked`() = runBlocking {
        calendars()
        val r = (calendar().calendars() as Outcome.Success).value
        assertEquals(listOf("Calendar", "Birthdays"), r.map { it.name })
        assertTrue(r[0].isDefault && r[0].canWrite)
        assertFalse(r[1].canWrite)
        calendar().calendars()
        assertEquals("id,name,isDefaultCalendar,canEdit", graph.first().url.queryParameter("\$select"))
    }

    @Test fun `calendarView - timed, all-day, recurring and another zone, read in the zone each names`() = runBlocking {
        calendars()
        routes["GET /graph/v1.0/me/calendars/AAMkCal%2F1=/calendarView"] = {
            json("""{"value":[
                {"id":"ev1","subject":"Dentist","isAllDay":false,"isCancelled":false,"iCalUId":"uid-1",
                 "start":{"dateTime":"2026-11-03T15:00:00.0000000","timeZone":"Eastern Standard Time"},
                 "end":{"dateTime":"2026-11-03T16:00:00.0000000","timeZone":"Eastern Standard Time"},
                 "location":{"displayName":"Main St"},"bodyPreview":"Cleaning"},
                {"id":"ev2","subject":"Holiday","isAllDay":true,
                 "start":{"dateTime":"2026-11-04T00:00:00.0000000","timeZone":"Eastern Standard Time"},
                 "end":{"dateTime":"2026-11-05T00:00:00.0000000","timeZone":"Eastern Standard Time"}},
                {"id":"ev3a","subject":"Standup","iCalUId":"uid-standup",
                 "start":{"dateTime":"2026-11-03T09:00:00.0000000","timeZone":"Eastern Standard Time"},
                 "end":{"dateTime":"2026-11-03T09:15:00.0000000","timeZone":"Eastern Standard Time"}},
                {"id":"ev3b","subject":"Standup","iCalUId":"uid-standup",
                 "start":{"dateTime":"2026-11-04T09:00:00.0000000","timeZone":"Eastern Standard Time"},
                 "end":{"dateTime":"2026-11-04T09:15:00.0000000","timeZone":"Eastern Standard Time"}},
                {"id":"ev4","subject":"Call with London",
                 "start":{"dateTime":"2026-11-03T20:00:00.0000000","timeZone":"UTC"},
                 "end":{"dateTime":"2026-11-03T21:00:00.0000000","timeZone":"UTC"}},
                {"id":"ev5","subject":"Cancelled","isCancelled":true,
                 "start":{"dateTime":"2026-11-03T11:00:00.0000000","timeZone":"Eastern Standard Time"},
                 "end":{"dateTime":"2026-11-03T12:00:00.0000000","timeZone":"Eastern Standard Time"}}]}""")
        }
        routes["GET /graph/v1.0/me/calendars/AAMkBirthdays=/calendarView"] = { json("""{"value":[]}""") }
        val events = (calendar().events(at(3, 0), at(5, 0)) as Outcome.Success).value

        assertEquals(listOf("Standup", "Call with London", "Dentist", "Holiday", "Standup"), events.map { it.title })
        val dentist = events.first { it.id == "ev1" }
        assertEquals(at(3, 15), dentist.start)
        assertEquals(at(3, 16), dentist.end)
        assertEquals("Main St", dentist.location)
        assertEquals("Cleaning", dentist.description)
        assertEquals("uid-1", dentist.iCalUid)
        assertEquals("m1", dentist.accountId)
        assertEquals("Calendar", dentist.calendarName)
        assertEquals("trap 2: UTC is read as UTC", at(3, 15), events.first { it.id == "ev4" }.start)
        val holiday = events.first { it.id == "ev2" }
        assertTrue(holiday.allDay)
        assertEquals(at(4, 0), holiday.start)
        assertEquals(at(5, 0), holiday.end)
        assertTrue("cancelled left out", events.none { it.id == "ev5" })

        val view = graph.first { it.url.encodedPath.endsWith("AAMkCal%2F1=/calendarView") }
        assertEquals("outlook.timezone=\"Eastern Standard Time\"", view.headers["Prefer"])
        assertEquals("2026-11-03T05:00:00Z", view.url.queryParameter("startDateTime"))
        assertEquals("2026-11-05T05:00:00Z", view.url.queryParameter("endDateTime"))
        assertEquals("start/dateTime", view.url.queryParameter("\$orderby"))
        assertEquals("50", view.url.queryParameter("\$top"))
        assertEquals("Bearer eyJ.graph", view.headers["Authorization"])

        val found = (calendar().events(at(3, 0), at(5, 0), "main st") as Outcome.Success).value
        assertEquals("text is matched here, on subject and location", listOf("ev1"), found.map { it.id })
    }

    @Test fun `without a Windows zone name Edith asks for UTC and converts`() = runBlocking {
        calendars()
        routes["GET /graph/v1.0/me/calendars/AAMkCal%2F1=/calendarView"] = {
            json("""{"value":[{"id":"ev1","subject":"Dentist","start":{"dateTime":"2026-11-03T20:00:00.0000000","timeZone":"UTC"},"end":{"dateTime":"2026-11-03T21:00:00.0000000","timeZone":"UTC"}}]}""")
        }
        routes["GET /graph/v1.0/me/calendars/AAMkBirthdays=/calendarView"] = { json("""{"value":[]}""") }
        val events = (calendar(windows = null).events(at(3, 0), at(4, 0)) as Outcome.Success).value
        assertEquals(at(3, 15), events.single().start)
        assertEquals("outlook.timezone=\"UTC\"", graph.last().headers["Prefer"])
    }

    @Test fun `create, update and delete send exactly what was asked`() = runBlocking {
        calendars()
        val created = """{"id":"AAMkNew/1=","subject":"Test","start":{"dateTime":"2026-11-03T15:00:00.0000000","timeZone":"Eastern Standard Time"},"end":{"dateTime":"2026-11-03T16:00:00.0000000","timeZone":"Eastern Standard Time"}}"""
        routes["POST /graph/v1.0/me/calendars/AAMkCal%2F1=/events"] = { json(created, 201) }
        routes["PATCH /graph/v1.0/me/events/AAMkNew%2F1="] = { json(created.replace("\"Test\"", "\"Renamed\"")) }
        routes["DELETE /graph/v1.0/me/events/AAMkNew%2F1="] = { MockResponse.Builder().code(204).build() }
        val cal = calendar()

        val event = (cal.create(null, EventChange("Test", at(3, 15), at(3, 16), location = "Main St", description = "Bring forms")) as Outcome.Success).value
        assertEquals("AAMkNew/1=", event.id)
        assertEquals(at(3, 15), event.start)
        val post = body(writes.single())
        assertEquals("Test", post["subject"]!!.jsonPrimitive.content)
        assertEquals("""{"dateTime":"2026-11-03T15:00:00","timeZone":"Eastern Standard Time"}""", post["start"].toString())
        assertEquals("""{"dateTime":"2026-11-03T16:00:00","timeZone":"Eastern Standard Time"}""", post["end"].toString())
        assertEquals("false", post["isAllDay"].toString())
        assertEquals("""{"displayName":"Main St"}""", post["location"].toString())
        assertEquals("""{"contentType":"text","content":"Bring forms"}""", post["body"].toString())

        requests.clear()
        cal.create(null, EventChange("Holiday", at(4, 0), null, allDay = true))
        val allDay = body(writes.single())
        assertEquals("true", allDay["isAllDay"].toString())
        assertEquals("""{"dateTime":"2026-11-04T00:00:00","timeZone":"Eastern Standard Time"}""", allDay["start"].toString())
        assertEquals("midnight to midnight", """{"dateTime":"2026-11-05T00:00:00","timeZone":"Eastern Standard Time"}""", allDay["end"].toString())

        requests.clear()
        val renamed = (cal.update(event, EventChange(title = "Renamed")) as Outcome.Success).value
        assertEquals("Renamed", renamed.title)
        assertEquals("only the changed field", """{"subject":"Renamed"}""", writes.single().body!!.utf8())

        requests.clear()
        assertEquals(Outcome.Success(Unit), cal.delete(event))
        assertEquals("DELETE", writes.single().method)
    }

    // --- Mail ---------------------------------------------------------------------------------------------------

    private fun message(id: String, from: String, subject: String, received: String, read: Boolean) = """
        {"id":"$id","conversationId":"conv-$id","subject":"$subject","bodyPreview":"preview of $id",
         "from":{"emailAddress":{"name":"${from.substringBefore('@')}","address":"$from"}},
         "toRecipients":[{"emailAddress":{"name":"Owner","address":"owner@outlook.com"}}],
         "receivedDateTime":"$received","isRead":$read,"internetMessageId":"<$id@outlook.com>"}"""

    private val inbox = "GET /graph/v1.0/me/mailFolders/inbox/messages"

    @Test fun `search with conditions only - $filter with receivedDateTime first, newest first, nextLink says more`() = runBlocking {
        routes[inbox] = {
            json("""{"value":[${message("AAMk1", "boss@contoso.com", "Budget", "2026-11-02T16:00:00Z", false)}],
                "@odata.nextLink":"https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?${'$'}skip=1"}""")
        }
        val r = (mail().search(MailQuery(unreadOnly = true, after = at(2, 0)), 5) as Outcome.Success).value
        val q = graph.single().url
        assertEquals("receivedDateTime ge 2026-11-02T05:00:00Z and isRead eq false", q.queryParameter("\$filter"))
        assertEquals("receivedDateTime desc", q.queryParameter("\$orderby"))
        assertEquals("5", q.queryParameter("\$top"))
        assertNull(q.queryParameter("\$search"))
        assertTrue(r.more)
        val m = r.messages.single()
        assertEquals("boss <boss@contoso.com>", m.from)
        assertEquals("Owner <owner@outlook.com>", m.to)
        assertEquals("Budget", m.subject)
        assertEquals(at(2, 11), m.date)
        assertTrue(m.unread)
        assertEquals("<AAMk1@outlook.com>", m.messageIdHeader)
        assertEquals("conv-AAMk1", m.threadId)
        assertEquals("m1", m.accountId)
    }

    @Test fun `search with words only - $search in KQL, no $filter or $orderby`() = runBlocking {
        routes[inbox] = { json("""{"value":[${message("AAMk1", "boss@contoso.com", "Quarterly report", "2026-11-02T16:00:00Z", true)}]}""") }
        val r = (mail().search(MailQuery(from = "boss@contoso.com", subject = "quarterly report", text = "budget"), 5) as Outcome.Success).value
        val q = graph.single().url
        assertEquals("\"from:boss@contoso.com subject:\\\"quarterly report\\\" budget\"", q.queryParameter("\$search"))
        assertNull(q.queryParameter("\$filter"))
        assertNull(q.queryParameter("\$orderby"))
        assertEquals("5", q.queryParameter("\$top"))
        assertFalse(r.more)
        assertEquals(1, r.messages.size)
    }

    @Test fun `search with words and conditions - Graph can't combine them, so the conditions are checked here`() = runBlocking {
        routes[inbox] = {
            json("""{"value":[
                ${message("old", "boss@contoso.com", "Budget", "2026-10-30T16:00:00Z", false)},
                ${message("read", "boss@contoso.com", "Budget", "2026-11-02T15:00:00Z", true)},
                ${message("new2", "boss@contoso.com", "Budget", "2026-11-02T14:00:00Z", false)},
                ${message("new1", "boss@contoso.com", "Budget", "2026-11-02T17:00:00Z", false)}]}""")
        }
        val r = (mail().search(MailQuery(from = "boss@contoso.com", unreadOnly = true, after = at(2, 0)), 1) as Outcome.Success).value
        val q = graph.single().url
        assertEquals("\"from:boss@contoso.com\"", q.queryParameter("\$search"))
        assertNull(q.queryParameter("\$filter"))
        assertEquals("a bigger page to filter", "50", q.queryParameter("\$top"))
        assertEquals("newest first, unread, today", listOf("new1"), r.messages.map { it.id })
        assertTrue("another matched", r.more)
    }

    @Test fun `read - the text body, quoted history removed`() = runBlocking {
        routes["GET /graph/v1.0/me/messages/AAMk%2F1="] = {
            json(message("AAMk/1=", "boss@contoso.com", "Budget", "2026-11-02T16:00:00Z", false).trimEnd().removeSuffix("}") +
                ""","ccRecipients":[{"emailAddress":{"address":"kim@contoso.com"}}],"body":{"contentType":"text","content":"Can you send it?\r\n\r\n-----Original Message-----\r\nFrom: Owner\r\nold text"}}""")
        }
        val m = (mail().read("AAMk/1=") as Outcome.Success).value
        assertEquals("Can you send it?", m.body)
        assertEquals("kim@contoso.com", m.cc)
        assertEquals("outlook.body-content-type=\"text\"", graph.single().headers["Prefer"])
    }

    @Test fun `a reply draft is exactly the card's text - createReply, then its body replaced`() = runBlocking {
        routes["POST /graph/v1.0/me/messages/AAMk%2F1=/createReply"] = {
            json("""{"id":"draft/1=","subject":"RE: Budget","body":{"contentType":"html","content":"<p>quoted original</p>"}}""", 201)
        }
        routes["PATCH /graph/v1.0/me/messages/draft%2F1="] = { json("""{"id":"draft/1="}""") }
        val reply = OutgoingMail("Boss <boss@contoso.com>", "Re: Budget", "Thanks!\nSam", inReplyTo = "<AAMk1@outlook.com>", threadId = "conv", replyToId = "AAMk/1=")
        assertEquals(Outcome.Success(Unit), mail().draft(reply))
        assertEquals(listOf("POST", "PATCH"), writes.map { it.method })
        assertEquals("", writes[0].body?.utf8().orEmpty())
        assertEquals(
            """{"subject":"Re: Budget","body":{"contentType":"text","content":"Thanks!\nSam"},"toRecipients":[{"emailAddress":{"address":"boss@contoso.com"}}]}""",
            writes[1].body!!.utf8(),
        )
    }

    @Test fun `send makes the draft and sends that, and makes no other request`() = runBlocking {
        routes["POST /graph/v1.0/me/messages/AAMk1/createReply"] = { json("""{"id":"draft1"}""", 201) }
        routes["PATCH /graph/v1.0/me/messages/draft1"] = { json("""{"id":"draft1"}""") }
        routes["POST /graph/v1.0/me/messages/draft1/send"] = { MockResponse.Builder().code(202).build() }
        routes["POST /graph/v1.0/me/messages"] = { json("""{"id":"draft2"}""", 201) }
        routes["POST /graph/v1.0/me/messages/draft2/send"] = { MockResponse.Builder().code(202).build() }

        assertEquals(Outcome.Success(Unit), mail().send(OutgoingMail("boss@contoso.com", "Re: Budget", "Thanks!", replyToId = "AAMk1")))
        assertEquals(
            listOf("POST /graph/v1.0/me/messages/AAMk1/createReply", "PATCH /graph/v1.0/me/messages/draft1", "POST /graph/v1.0/me/messages/draft1/send"),
            writes.map { "${it.method} ${it.url.encodedPath}" },
        )

        requests.clear()
        assertEquals(Outcome.Success(Unit), mail().send(OutgoingMail("kim@x.org, Sam <sam@y.org>", "Hi", "Hello")))
        assertEquals(listOf("POST /graph/v1.0/me/messages", "POST /graph/v1.0/me/messages/draft2/send"), writes.map { "${it.method} ${it.url.encodedPath}" })
        assertEquals(
            """{"subject":"Hi","body":{"contentType":"text","content":"Hello"},"toRecipients":[{"emailAddress":{"address":"kim@x.org"}},{"emailAddress":{"address":"sam@y.org"}}]}""",
            writes[0].body!!.utf8(),
        )
        assertTrue("never sendMail or reply", graph.none { it.url.encodedPath.endsWith("/sendMail") || it.url.encodedPath.endsWith("/reply") })
    }

    // --- Errors and limits ----------------------------------------------------------------------------------------

    @Test fun `429 waits Retry-After once, at most 10 seconds`() = runBlocking {
        var calls = 0
        routes[inbox] = {
            calls++
            if (calls == 1) MockResponse.Builder().code(429).addHeader("Retry-After", "2").body("{}").build() else json("""{"value":[]}""")
        }
        assertTrue(mail().search(MailQuery(), 5) is Outcome.Success)
        assertEquals(listOf(2000L), slept)

        calls = 0
        routes[inbox] = { calls++; MockResponse.Builder().code(429).addHeader("Retry-After", "30").body("{}").build() }
        val busy = mail().search(MailQuery(), 5) as Outcome.Failure
        assertEquals(AppError.ToolFailed("email", "Outlook is busy right now. Try again in a minute."), busy.error)
        assertEquals("no wait over 10 s, no retry", 1, calls)
    }

    @Test fun `403 means a missing permission - signing in again`() = runBlocking {
        routes[inbox] = { json("""{"error":{"code":"ErrorAccessDenied","message":"Access is denied."}}""", 403) }
        assertTrue(mail().search(MailQuery(), 5) is Outcome.Failure)
        assertTrue(auth.state.value is AccountState.NeedsSignIn)
    }

    @Test fun `a gone event says so`() = runBlocking {
        val gone = CalendarEvent("x", "c", "Calendar", "T", at(3, 1), at(3, 2), false, accountId = "m1")
        assertEquals(Outcome.Failure(AppError.ToolFailed("calendar", "gone")), calendar().delete(gone))
    }
}
