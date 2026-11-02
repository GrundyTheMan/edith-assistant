package com.edith.app.google.calendar

import android.app.Activity
import android.content.Intent
import com.edith.app.core.error.Outcome
import com.edith.app.google.GoogleAuth
import com.edith.app.google.GoogleAuthorizer
import com.edith.app.google.GoogleScope
import com.edith.app.google.GoogleSignIn
import com.edith.app.google.GoogleState
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
import com.edith.app.chat.engine.TurnEvent
import com.edith.app.core.error.AppError
import com.edith.app.settings.Settings
import com.edith.app.testing.FakeClock
import com.edith.app.testing.FakeSettingsRepository
import com.edith.app.tools.framework.Confirmations
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolRegistry
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.framework.ToolTurnRunner
import com.edith.app.core.dispatchers.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections

/**
 * C18 against a stand-in for Google Calendar (MockWebServer). The responses follow the shapes in Google's Calendar API
 * reference (calendarList.list, events.list, events.insert/patch/delete); they're made up, not the owner's data.
 */
class CalendarTest {
    private val zone = ZoneId.of("America/Detroit")
    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = FakeSettingsRepository()
    private val tokens = ArrayDeque<String>()
    private val cleared = mutableListOf<String>()
    private lateinit var google: GoogleAuth
    private lateinit var client: CalendarClient
    private lateinit var session: CalendarSession
    private val clock = FakeClock(Instant.parse("2026-11-02T14:00:00Z"), zone)
    private val ctx get() = ToolContext(clock, Settings(), dispatchers)

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val key = "${request.method} ${request.url.encodedPath}"
                return routes.entries.firstOrNull { key.startsWith(it.key) }?.value?.invoke() ?: json("{}", 404)
            }
        }
        server.start()
        val signIn = object : GoogleSignIn {
            override suspend fun signIn(activity: Activity, webClientId: String) = GoogleSignIn.Result.SignedIn("owner@gmail.com", null)
            override suspend fun signOut() {}
        }
        val authorizer = object : GoogleAuthorizer {
            override suspend fun authorize(scopes: Set<GoogleScope>) = GoogleAuthorizer.Result.Granted(tokens.removeFirstOrNull() ?: "token")
            override fun fromConsent(data: Intent?) = GoogleAuthorizer.Result.Granted("token")
            override suspend fun clearToken(token: String) { cleared += token }
            override suspend fun revoke(email: String) {}
        }
        google = GoogleAuth(signIn, authorizer, settings, "web", scope)
        runBlocking { google.signIn(Activity()) }
        client = CalendarClient(lazy { OkHttpClient() }, google, dispatchers, { zone }, server.url("/calendar/v3").toString())
        session = CalendarSession(client)
        routes["GET /calendar/v3/users/me/calendarList"] = { json(CALENDAR_LIST) }
        routes["GET /calendar/v3/calendars/primary%40gmail.com/events"] = { json(PRIMARY_EVENTS) }
        routes["GET /calendar/v3/calendars/family"] = { json(FAMILY_EVENTS) }
    }

    @After fun tearDown() = server.close()

    private fun args(tool: com.edith.app.tools.framework.Tool, vararg pairs: Pair<String, String>) =
        ToolArgs(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }), tool.spec)

    // --- The client ---------------------------------------------------------------------------------------------

    @Test fun `only checked calendars, primary first, with the owner's own names`() = runBlocking {
        val cals = (client.calendars() as Outcome.Success).value
        assertEquals(listOf("primary@gmail.com", "family"), cals.map { it.id })
        assertEquals(listOf("Blake", "Sample family"), cals.map { it.name })   // summaryOverride wins
        assertTrue(cals.first().primary)
        assertEquals("reader", requests.last().url.queryParameter("minAccessRole"))
    }

    @Test fun `events are parsed in the device zone, all-day and recurring included, cancelled left out`() = runBlocking {
        val cals = (client.calendars() as Outcome.Success).value
        val from = ZonedDateTime.of(2026, 11, 2, 0, 0, 0, 0, zone)
        val events = (client.events(cals, from, from.plusDays(7)) as Outcome.Success).value
        assertEquals(listOf("Standup", "Dentist", "Mom's birthday", "Soccer practice"), events.map { it.title })

        val dentist = events[1]
        assertEquals("a Pacific-time event shown at Detroit's 1 PM", ZonedDateTime.of(2026, 11, 2, 13, 0, 0, 0, zone), dentist.start)
        assertEquals("Main St Dental", dentist.location)
        val standup = events[0]
        assertNull("no location", standup.location)
        val birthday = events[2]
        assertTrue(birthday.allDay)
        assertEquals(ZonedDateTime.of(2026, 11, 4, 0, 0, 0, 0, zone), birthday.start)
        assertEquals("Sample family", events[3].calendarName)

        val q = requests.first { it.url.encodedPath.contains("primary") }.url
        assertEquals("true", q.queryParameter("singleEvents"))
        assertEquals("startTime", q.queryParameter("orderBy"))
        assertEquals("2026-11-02T00:00:00-05:00", q.queryParameter("timeMin"))
        assertEquals("Bearer token", requests.last().headers["Authorization"])
    }

    @Test fun `a refused token is cleared and the call tried once more`() = runBlocking {
        var first = true
        routes["GET /calendar/v3/users/me/calendarList"] = { if (first) { first = false; json("{}", 401) } else json(CALENDAR_LIST) }
        tokens += listOf("stale", "fresh")
        assertTrue(client.calendars() is Outcome.Success)
        assertEquals(listOf("stale"), cleared)
        assertEquals("Bearer fresh", requests.last().headers["Authorization"])
    }

    @Test fun `a 403 for a missing scope asks for permission`() = runBlocking {
        routes["GET /calendar/v3/users/me/calendarList"] = { json("""{"error":{"code":403,"errors":[{"reason":"insufficientPermissions"}]}}""", 403) }
        val r = ListCalendarEventsTool(session, client).let { it.execute(args(it, "start" to "2026-11-02", "end" to "2026-11-03"), ctx) }
        assertTrue(r.text, r is ToolResult.Error && r.text.contains("Grant access"))
        assertEquals(GoogleState.NeedsConsent("owner@gmail.com"), google.state.value)
    }

    // --- Tools -----------------------------------------------------------------------------------------------

    @Test fun `list gives each event a short id and says which calendar`() = runBlocking {
        val tool = ListCalendarEventsTool(session, client)
        val r = tool.execute(args(tool, "start" to "2026-11-02T00:00", "end" to "2026-11-08T23:59"), ctx)
        val lines = r.text.lines()
        assertEquals("Mon Nov 2, 9:00–9:15 AM — Standup [Blake] {event_id: e1}", lines[0])
        assertEquals("Mon Nov 2, 1:00–2:00 PM — Dentist (Main St Dental) [Blake] {event_id: e2}", lines[1])
        assertEquals("Wed Nov 4, all day — Mom's birthday [Blake] {event_id: e3}", lines[2])
        assertEquals("Thu Nov 5, 5:30–7:00 PM — Soccer practice [Sample family] {event_id: e4}", lines[3])
        assertEquals("Dentist", session.event("e2")!!.title)
    }

    @Test fun `a day written in UTC by the model is read as the owner's local day`() = runBlocking {
        // Live, gemma4 sent 2026-11-03T00:00:00Z..23:59:59Z for "tomorrow" in Detroit (7 PM the day before in UTC terms).
        val tool = ListCalendarEventsTool(session, client)
        tool.execute(args(tool, "start" to "2026-11-03T00:00:00Z", "end" to "2026-11-03T23:59:59Z"), ctx)
        val q = requests.first { it.url.encodedPath.contains("primary") }.url
        assertEquals("2026-11-03T00:00:00-05:00", q.queryParameter("timeMin"))
        assertEquals("2026-11-03T23:59:59-05:00", q.queryParameter("timeMax"))
        val keep = args(tool, "start" to "2026-11-03T09:00:00-08:00")
        assertEquals("another zone's offset is kept", ZonedDateTime.of(2026, 11, 3, 12, 0, 0, 0, zone), keep.localTime("start", zone))
        // Live, "tomorrow at 3pm" came as 15:00-04:00 the day after daylight saving ended (Detroit was -05:00).
        val summer = args(tool, "start" to "2026-11-03T15:00:00-04:00")
        assertEquals("the owner's own other offset is a guess: 3 PM", ZonedDateTime.of(2026, 11, 3, 15, 0, 0, 0, zone), summer.localTime("start", zone))
        assertEquals("compact offsets too", ZonedDateTime.of(2026, 11, 3, 15, 0, 0, 0, zone), args(tool, "start" to "2026-11-03T15:00:00-0400").localTime("start", zone))
        val utcPhone = ZoneId.of("UTC")
        assertEquals("on a UTC phone, Z is just Z", ZonedDateTime.of(2026, 11, 3, 0, 0, 0, 0, utcPhone), args(tool, "start" to "2026-11-03T00:00:00Z").localTime("start", utcPhone))
    }

    @Test fun `no events says so`() = runBlocking {
        routes["GET /calendar/v3/calendars/primary%40gmail.com/events"] = { json("""{"items":[]}""") }
        routes["GET /calendar/v3/calendars/family"] = { json("""{"items":[]}""") }
        val tool = ListCalendarEventsTool(session, client)
        assertEquals("No events.", tool.execute(args(tool, "start" to "2026-11-09", "end" to "2026-11-10"), ctx).text)
    }

    @Test fun `free time is the gaps between 8 AM and 8 PM, all-day events not blocking`() {
        fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone)
        val events = listOf(
            CalendarEvent("1", "p", "P", "Standup", at(2, 9), at(2, 9, 30), false),
            CalendarEvent("2", "p", "P", "Lunch", at(2, 12), at(2, 13), false),
            CalendarEvent("3", "p", "P", "Holiday", at(2, 0), at(3, 0), true),
        )
        val slots = FindFreeTimeTool.freeSlots(events, at(2, 0), at(3, 0), Duration.ofMinutes(60), zone)
        assertEquals(
            listOf(at(2, 8) to at(2, 9), at(2, 9, 30) to at(2, 12), at(2, 13) to at(2, 20)),
            slots,
        )
    }

    @Test fun `create asks first and then adds to the primary calendar`() = runBlocking {
        routes["POST /calendar/v3/calendars/primary%40gmail.com/events"] = {
            json("""{"id":"new1","summary":"Lunch with Sam","location":"Café X","start":{"dateTime":"2026-11-06T12:00:00-05:00"},"end":{"dateTime":"2026-11-06T13:00:00-05:00"}}""")
        }
        val tool = CreateCalendarEventTool(session, client) { zone }
        val a = args(tool, "title" to "Lunch with Sam", "start" to "2026-11-06T12:00", "location" to "Café X")
        val card = tool.confirmation(a)
        assertEquals("Add to your calendar?", card.title)
        assertEquals(listOf("What" to "Lunch with Sam", "When" to "Fri Nov 6, 12:00–1:00 PM", "Where" to "Café X"), card.details)

        assertEquals("Added \"Lunch with Sam\" on Fri Nov 6, 12:00–1:00 PM.", tool.execute(a, ctx).text)
        val body = Json.parseToJsonElement(requests.last { it.method == "POST" }.body!!.utf8()).jsonObject
        assertEquals("Lunch with Sam", body["summary"]!!.jsonPrimitive.content)
        assertEquals("2026-11-06T12:00:00-05:00", body["start"]!!.jsonObject["dateTime"]!!.jsonPrimitive.content)
        assertEquals("an hour by default", "2026-11-06T13:00:00-05:00", body["end"]!!.jsonObject["dateTime"]!!.jsonPrimitive.content)
        assertFalse("nothing unset is sent", body.containsKey("description"))
    }

    @Test fun `an all-day event is sent with dates`() = runBlocking {
        routes["POST /calendar/v3/calendars/primary%40gmail.com/events"] = {
            json("""{"id":"new2","summary":"Day off","start":{"date":"2026-11-09"},"end":{"date":"2026-11-10"}}""")
        }
        val tool = CreateCalendarEventTool(session, client) { zone }
        assertEquals("Added \"Day off\" on Mon Nov 9, all day.", tool.execute(args(tool, "title" to "Day off", "start" to "2026-11-09", "all_day" to "true"), ctx).text)
        val body = Json.parseToJsonElement(requests.last { it.method == "POST" }.body!!.utf8()).jsonObject
        assertEquals("2026-11-09", body["start"]!!.jsonObject["date"]!!.jsonPrimitive.content)
        assertEquals("2026-11-10", body["end"]!!.jsonObject["date"]!!.jsonPrimitive.content)
    }

    @Test fun `update shows before and after, keeps the length, and sends only the change`() = runBlocking {
        ListCalendarEventsTool(session, client).let { it.execute(args(it, "start" to "2026-11-02", "end" to "2026-11-08"), ctx) }
        routes["PATCH /calendar/v3/calendars/primary%40gmail.com/events/dentist1"] = {
            json("""{"id":"dentist1","summary":"Dentist","location":"Main St Dental","start":{"dateTime":"2026-11-02T15:00:00-05:00"},"end":{"dateTime":"2026-11-02T16:00:00-05:00"}}""")
        }
        val tool = UpdateCalendarEventTool(session, client) { zone }
        val a = args(tool, "event_id" to "e2", "start" to "2026-11-02T15:00")
        val card = tool.confirmation(a)!!
        assertEquals("Change this event?", card.title)
        assertEquals("When" to "Mon Nov 2, 1:00–2:00 PM → Mon Nov 2, 3:00–4:00 PM", card.details[1])
        assertEquals("Changed \"Dentist\": now Mon Nov 2, 3:00–4:00 PM at Main St Dental.", tool.execute(a, ctx).text)
        val body = Json.parseToJsonElement(requests.last { it.method == "PATCH" }.body!!.utf8()).jsonObject
        assertEquals(setOf("start", "end"), body.keys)
    }

    @Test fun `delete shows the event and removes it`() = runBlocking {
        ListCalendarEventsTool(session, client).let { it.execute(args(it, "start" to "2026-11-02", "end" to "2026-11-08"), ctx) }
        routes["DELETE /calendar/v3/calendars/primary%40gmail.com/events/standup1_20261102T140000Z"] = { MockResponse.Builder().code(204).build() }
        val tool = DeleteCalendarEventTool(session, client)
        val a = args(tool, "event_id" to "e1")
        assertEquals(listOf("Event" to "Standup", "When" to "Mon Nov 2, 9:00–9:15 AM"), tool.confirmation(a)!!.details)
        assertEquals("Deleted \"Standup\" (Mon Nov 2, 9:00–9:15 AM).", tool.execute(a, ctx).text)
        assertNull("forgotten", session.event("e1"))
    }

    @Test fun `an unknown event id sends nothing and says to list first`() = runBlocking {
        val tool = DeleteCalendarEventTool(session, client)
        assertNull(tool.confirmation(args(tool, "event_id" to "e99")))
        val r = tool.execute(args(tool, "event_id" to "e99"), ctx)
        assertTrue(r is ToolResult.Error && r.text.contains("list_calendar_events"))
        assertTrue(requests.isEmpty())
    }

    @Test fun `declining the card means Google is never called`() = runBlocking {
        val tool = CreateCalendarEventTool(session, client) { zone }
        val confirmations = Confirmations()
        val ollama = object : OllamaClient {
            var round = 0
            override fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent> = flow {
                if (round++ == 0) {
                    val call = OllamaToolCall(function = OllamaFunctionCall("create_calendar_event",
                        JsonObject(mapOf("title" to JsonPrimitive("Edith test"), "start" to JsonPrimitive("2026-11-03T15:00")))))
                    emit(ChatEvent.ToolCalls(OllamaMessage("assistant", "", toolCalls = listOf(call))))
                } else {
                    emit(ChatEvent.Delta("Okay, I won't add it."))
                }
                emit(ChatEvent.Done(ReplyStats(1, 1, 1)))
            }
            override suspend fun chat(baseUrl: String, request: ChatCall) = Outcome.Failure(AppError.EmptyReply)
            override suspend fun listModels(baseUrl: String) = Outcome.Success(emptyList<OllamaModelTag>())
            override suspend fun show(baseUrl: String, model: String) = Outcome.Success(OllamaShow(listOf("tools")))
            override suspend fun version(baseUrl: String) = Outcome.Success("x")
        }
        val runner = ToolTurnRunner(ollama, ToolRegistry(listOf(tool), listOf(google)), confirmations, settings, clock, dispatchers)
        var asked = false
        runner.run("http://ollama", ModelInfo("gemma4:12b", setOf("tools")), listOf(OllamaMessage("user", "add it"))).collect { e ->
            if (e is TurnEvent.NeedsConfirmation) { asked = true; confirmations.resolve(e.request.id, false) }
        }
        assertTrue(asked)
        assertTrue("no request reached Google", requests.isEmpty())
    }

    @Test fun `formats - same half of the day, crossing noon, all day, several days`() {
        fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone)
        assertEquals("Mon Nov 2, 9:00–9:30 AM", CalendarFormat.whenText(at(2, 9), at(2, 9, 30), false))
        assertEquals("Mon Nov 2, 11:30 AM–12:30 PM", CalendarFormat.whenText(at(2, 11, 30), at(2, 12, 30), false))
        assertEquals("Wed Nov 4, all day", CalendarFormat.whenText(at(4, 0), at(5, 0), true))
        assertEquals("Wed Nov 4–Fri Nov 6, all day", CalendarFormat.whenText(at(4, 0), at(7, 0), true))
    }

    companion object {
        const val CALENDAR_LIST = """{"kind":"calendar#calendarList","items":[
            {"id":"holidays","summary":"Holidays in United States","accessRole":"reader","selected":false},
            {"id":"family","summary":"Family","summaryOverride":"Sample family","accessRole":"owner","selected":true},
            {"id":"primary@gmail.com","summary":"Blake","accessRole":"owner","primary":true,"selected":true}]}"""
        const val PRIMARY_EVENTS = """{"kind":"calendar#events","timeZone":"America/Detroit","items":[
            {"id":"dentist1","status":"confirmed","summary":"Dentist","location":"Main St Dental",
             "start":{"dateTime":"2026-11-02T10:00:00-08:00","timeZone":"America/Los_Angeles"},"end":{"dateTime":"2026-11-02T11:00:00-08:00"}},
            {"id":"standup1_20261102T140000Z","recurringEventId":"standup1","status":"confirmed","summary":"Standup",
             "start":{"dateTime":"2026-11-02T09:00:00-05:00"},"end":{"dateTime":"2026-11-02T09:15:00-05:00"}},
            {"id":"gone1","status":"cancelled","summary":"Cancelled thing","start":{"dateTime":"2026-11-03T09:00:00-05:00"},"end":{"dateTime":"2026-11-03T10:00:00-05:00"}},
            {"id":"bday1","status":"confirmed","summary":"Mom's birthday","start":{"date":"2026-11-04"},"end":{"date":"2026-11-05"}}]}"""
        const val FAMILY_EVENTS = """{"kind":"calendar#events","items":[
            {"id":"soccer1","status":"confirmed","summary":"Soccer practice","start":{"dateTime":"2026-11-05T17:30:00-05:00"},"end":{"dateTime":"2026-11-05T19:00:00-05:00"}}]}"""
    }
}
