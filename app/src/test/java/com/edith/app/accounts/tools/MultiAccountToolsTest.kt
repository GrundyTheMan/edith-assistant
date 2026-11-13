package com.edith.app.accounts.tools

import android.app.Activity
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountDirectory
import com.edith.app.accounts.AccountEntry
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.Capabilities
import com.edith.app.accounts.Provider
import com.edith.app.accounts.sources.CalendarEvent
import com.edith.app.accounts.sources.CalendarInfo
import com.edith.app.accounts.sources.CalendarSource
import com.edith.app.accounts.sources.EventChange
import com.edith.app.accounts.sources.EventDraft
import com.edith.app.accounts.sources.MailQuery
import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.chat.engine.TurnEvent
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.google.GoogleAuthorizer
import com.edith.app.google.GoogleSignIn
import com.edith.app.google.gmail.GmailQuery
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
import com.edith.app.settings.Settings
import com.edith.app.testing.AccountsHarness
import com.edith.app.testing.FakeClock
import com.edith.app.testing.FakeSettingsRepository
import com.edith.app.tools.framework.Confirmations
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolRegistry
import com.edith.app.tools.framework.ToolTurnRunner
import com.edith.app.core.dispatchers.AppDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections

/**
 * C22 §4: reads across accounts (merged, labelled, an invite once, a failing account named), find_free_time across
 * accounts, handles that keep their account, and the "which account?" rule through the real tool loop: when Edith
 * must ask, no card and no request.
 */
class MultiAccountToolsTest {
    private val zone = ZoneId.of("America/Detroit")
    private val clock = FakeClock(Instant.parse("2026-11-02T18:00:00Z"), zone)
    private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private val ctx = ToolContext(clock, Settings(), dispatchers)
    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone)

    private val personal = Account("p1", Provider.Google, "a@gmail.com", "personal")
    private val work = Account("w2", Provider.Google, "sam@contoso.com", "work")

    // --- Fakes for merged reads ---------------------------------------------------------------------------------

    private class FakeCalendar(
        override val account: Account,
        val list: List<CalendarEvent>,
        val fail: AppError? = null,
        val cals: List<CalendarInfo> = listOf(CalendarInfo("primary", "Calendar", isDefault = true)),
        val slow: Boolean = false,
    ) : CalendarSource {
        val written = mutableListOf<String>()
        override suspend fun calendars() = Outcome.Success(cals)
        override suspend fun events(from: Instant, to: Instant, text: String?): Outcome<List<CalendarEvent>> {
            if (slow) delay(60_000)
            return fail?.let { Outcome.Failure(it) } ?: Outcome.Success(list)
        }
        override suspend fun create(calendarId: String?, draft: EventDraft): Outcome<CalendarEvent> { written += "create $calendarId"; error("not used") }
        override suspend fun update(event: CalendarEvent, change: EventChange): Outcome<CalendarEvent> { written += "update ${event.id}"; return Outcome.Success(event.copy(title = change.title ?: event.title)) }
        override suspend fun delete(event: CalendarEvent): Outcome<Unit> { written += "delete ${event.id}"; return Outcome.Success(Unit) }
    }

    private class FakeDir(var list: List<AccountEntry>) : AccountDirectory {
        override fun entries() = list
    }

    private fun entry(source: FakeCalendar, state: AccountState = AccountState.Connected) =
        AccountEntry(source.account, state, Capabilities(calendar = true, mail = true), source, null)

    private fun ev(account: Account, id: String, title: String, s: ZonedDateTime, e: ZonedDateTime, uid: String? = null, cal: String = "Calendar") =
        CalendarEvent(id, "primary", cal, title, s, e, allDay = false, accountId = account.id, iCalUid = uid)

    private fun args(tool: Tool, vararg pairs: Pair<String, String>) = ToolArgs(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }), tool.spec)

    @Test fun `events from two accounts are merged by time and labelled, an invite listed once`() = runBlocking {
        val p = FakeCalendar(personal, listOf(
            ev(personal, "p-lunch", "Lunch", at(3, 12), at(3, 13)),
            ev(personal, "p-meet", "Team sync", at(3, 9), at(3, 10), uid = "inv-1@google.com"),
        ))
        val w = FakeCalendar(work, listOf(
            ev(work, "w-meet", "Team sync", at(3, 9), at(3, 10), uid = "inv-1@google.com", cal = "Work"),
            ev(work, "w-review", "Review", at(3, 10, 30), at(3, 11), cal = "Work"),
        ))
        val handles = CalendarHandles()
        val tool = ListCalendarEventsTool(FakeDir(listOf(entry(p), entry(w))), handles)
        val lines = tool.execute(args(tool, "start" to "2026-11-03T00:00", "end" to "2026-11-03T23:59"), ctx).text.lines()
        assertEquals(
            listOf(
                "Tue Nov 3, 9:00–10:00 AM — Team sync [Calendar · personal; Work · work] {event_id: e1}",
                "Tue Nov 3, 10:30–11:00 AM — Review [Work · work] {event_id: e2}",
                "Tue Nov 3, 12:00–1:00 PM — Lunch [Calendar · personal] {event_id: e3}",
            ),
            lines,
        )
        assertEquals("the handle keeps its account", work.id, handles.event("e2")!!.accountId)
    }

    @Test fun `with one account there are no account labels`() = runBlocking {
        val p = FakeCalendar(personal, listOf(ev(personal, "x", "Lunch", at(3, 12), at(3, 13))))
        val tool = ListCalendarEventsTool(FakeDir(listOf(entry(p))), CalendarHandles())
        assertEquals("Tue Nov 3, 12:00–1:00 PM — Lunch {event_id: e1}", tool.execute(args(tool, "start" to "2026-11-03", "end" to "2026-11-04"), ctx).text)
    }

    @Test fun `one account failing still lists the others, then says which couldn't be read`() = runBlocking {
        val p = FakeCalendar(personal, listOf(ev(personal, "x", "Lunch", at(3, 12), at(3, 13))))
        val w = FakeCalendar(work, emptyList())
        val down = FakeCalendar(work.copy(id = "d3", address = "x@y.z", label = "other"), emptyList(), fail = AppError.ToolFailed("calendar", "Couldn't reach Google Calendar. Check the phone's connection."))
        val tool = ListCalendarEventsTool(FakeDir(listOf(entry(p), entry(w, AccountState.NeedsSignIn("needs signing in again")), entry(down))), CalendarHandles())
        val text = tool.execute(args(tool, "start" to "2026-11-03", "end" to "2026-11-04"), ctx).text
        assertEquals(
            "Tue Nov 3, 12:00–1:00 PM — Lunch [Calendar · personal] {event_id: e1}\n" +
                "Couldn't read work (sam@contoso.com): it needs signing in again — Settings → Accounts.\n" +
                "Couldn't read other (x@y.z): Couldn't reach Google Calendar. Check the phone's connection.",
            text,
        )
    }

    @Test fun `a slow account times out on its own`() = runBlocking {
        val p = FakeCalendar(personal, listOf(ev(personal, "x", "Lunch", at(3, 12), at(3, 13))))
        val slow = FakeCalendar(work, emptyList(), slow = true)
        val read = com.edith.app.accounts.sources.Reads.readAll(
            listOf(com.edith.app.accounts.sources.Readable(personal, AccountState.Connected, p as CalendarSource), com.edith.app.accounts.sources.Readable(work, AccountState.Connected, slow)),
            timeoutMs = 200,
        ) { it.events(Instant.EPOCH, Instant.EPOCH) }
        assertEquals(listOf(personal), read.results.map { it.first })
        assertEquals("Couldn't read work (sam@contoso.com): it didn't answer within 10 seconds.", read.failures.single().line)
    }

    @Test fun `busy in any account is busy`() = runBlocking {
        val p = FakeCalendar(personal, listOf(ev(personal, "a", "Gym", at(3, 8), at(3, 12))))
        val w = FakeCalendar(work, listOf(ev(work, "b", "Meetings", at(3, 12), at(3, 19))))
        val tool = FindFreeTimeTool(FakeDir(listOf(entry(p), entry(w))))
        assertEquals("Free: Tue Nov 3, 7:00–8:00 PM", tool.execute(args(tool, "start" to "2026-11-03T00:00", "end" to "2026-11-03T23:59", "duration_minutes" to "30"), ctx).text)
    }

    @Test fun `changes and deletes go to the event's own account, and the card says which`() = runBlocking {
        val p = FakeCalendar(personal, listOf(ev(personal, "same-id", "Lunch", at(3, 12), at(3, 13))))
        val w = FakeCalendar(work, listOf(ev(work, "same-id", "Standup", at(3, 9), at(3, 10), cal = "Work")))
        val dir = FakeDir(listOf(entry(p), entry(w)))
        val handles = CalendarHandles()
        ListCalendarEventsTool(dir, handles).let { it.execute(args(it, "start" to "2026-11-03", "end" to "2026-11-04"), ctx) }
        assertEquals(work.id, handles.event("e1")!!.accountId)

        val update = UpdateCalendarEventTool(dir, handles) { zone }
        val u = args(update, "event_id" to "e1", "title" to "Stand-up")
        assertEquals("Account" to "work (sam@contoso.com) — Work", update.confirmation(u)!!.details.last())
        update.execute(u, ctx)
        val delete = DeleteCalendarEventTool(dir, handles)
        val d = args(delete, "event_id" to "e2")
        assertEquals("Account" to "personal (a@gmail.com) — Calendar", delete.confirmation(d)!!.details.last())
        delete.execute(d, ctx)
        assertEquals(listOf("update same-id"), w.written)
        assertEquals(listOf("delete same-id"), p.written)
    }

    @Test fun `a change to an event whose account needs signing in gets no card`() = runBlocking {
        val w = FakeCalendar(work, listOf(ev(work, "x", "Standup", at(3, 9), at(3, 10))))
        val dir = FakeDir(listOf(entry(w)))
        val handles = CalendarHandles()
        ListCalendarEventsTool(dir, handles).let { it.execute(args(it, "start" to "2026-11-03", "end" to "2026-11-04"), ctx) }
        dir.list = listOf(entry(w, AccountState.NeedsSignIn("needs signing in again")))
        val delete = DeleteCalendarEventTool(dir, handles)
        assertNull(delete.confirmation(args(delete, "event_id" to "e1")))
        assertTrue(delete.execute(args(delete, "event_id" to "e1"), ctx).text.contains("Settings → Accounts"))
        assertTrue(w.written.isEmpty())
    }

    // --- Two real Google accounts against a stand-in for Google (MockWebServer) -----------------------------------

    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val writes get() = requests.filter { it.method != "GET" }
    private lateinit var h: AccountsHarness

    @After fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    /** Two Google accounts, "personal" (calendars Personal and Family) and "work" (calendar Work), each with its own token. */
    private fun twoGoogleAccounts(workState: GoogleAuthorizer.Result? = null) = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val work = request.headers["Authorization"] == "Bearer tok-work"
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/users/me/calendarList") -> json(
                        if (work) """{"items":[{"id":"sam@contoso.com","summary":"Work","primary":true,"selected":true,"accessRole":"owner"}]}"""
                        else """{"items":[{"id":"a@gmail.com","summary":"Personal","primary":true,"selected":true,"accessRole":"owner"},{"id":"fam","summary":"Family","selected":true,"accessRole":"writer"}]}""",
                    )
                    request.method == "POST" && path.contains("/events") ->
                        json("""{"id":"new1","summary":"Test","start":{"dateTime":"2026-11-03T15:00:00-05:00"},"end":{"dateTime":"2026-11-03T16:00:00-05:00"}}""")
                    path.endsWith("/messages") && request.method == "GET" -> json(
                        if (work) """{"messages":[{"id":"wm1","threadId":"wt1"}],"nextPageToken":"more"}""" else """{"messages":[{"id":"pm1","threadId":"pt1"}]}""",
                    )
                    path.endsWith("/messages/wm1") -> json(meta("wm1", "wt1", "Boss <boss@contoso.com>", "Budget", "1793300000000"))
                    path.endsWith("/messages/pm1") -> json(meta("pm1", "pt1", "Mom <mom@example.com>", "Dinner", "1793290000000"))
                    path.endsWith("/drafts") -> json("""{"id":"d1"}""")
                    else -> json("{}", 404)
                }
            }
        }
        server.start()
        h = AccountsHarness(baseUrl = server.url("/").toString())
        h.play.perAccount["a@gmail.com"] = GoogleAuthorizer.Result.Granted("tok-personal")
        h.play.perAccount["sam@contoso.com"] = workState ?: GoogleAuthorizer.Result.Granted("tok-work")
        h.signIn.result = GoogleSignIn.Result.SignedIn("a@gmail.com", null)
        h.accounts.addGoogle(Activity())
        h.signIn.result = GoogleSignIn.Result.SignedIn("sam@contoso.com", null)
        h.accounts.addGoogle(Activity())
        val (p, w) = h.accounts.accounts.value.map { it.first.id }
        h.accounts.rename(p, "personal")
        h.accounts.rename(w, "work")
        requests.clear()
    }

    private fun meta(id: String, thread: String, from: String, subject: String, date: String) = """
        {"id":"$id","threadId":"$thread","labelIds":["INBOX","UNREAD"],"snippet":"s","internalDate":"$date",
         "payload":{"headers":[{"name":"From","value":"$from"},{"name":"Subject","value":"$subject"},{"name":"Message-ID","value":"<$id@x>"}]}}"""

    /** One turn of the real tool loop: the model calls [tool] with [arguments]; the owner answers any card with [approve]. */
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
        val registry = ToolRegistry(calendarTools(h.accounts, calHandles) + mailTools(h.accounts, mailHandles), listOf(h.accounts))
        val runner = ToolTurnRunner(ollama, registry, confirmations, FakeSettingsRepository(), clock, dispatchers)
        var card: ConfirmationRequest? = null
        runner.run("http://ollama", ModelInfo("gemma4:12b", setOf("tools")), listOf(OllamaMessage("user", "go"))).collect { e ->
            if (e is TurnEvent.NeedsConfirmation) { card = e.request; confirmations.resolve(e.request.id, approve) }
        }
        Turn(card, result)
    }

    private val calHandles = CalendarHandles()
    private val mailHandles = MailHandles()
    private val add = arrayOf("title" to "Test", "start" to "2026-11-03T15:00")

    @Test fun `a new event with two accounts - Edith must ask, with no card and no request`() {
        twoGoogleAccounts()
        val t = turn("create_calendar_event", *add)
        assertNull("no card", t.card)
        assertEquals(
            "Ask the owner which account to use: personal (a@gmail.com) or work (sam@contoso.com). Then call this tool again with `account`.",
            t.result,
        )
        assertTrue("no request at all", requests.isEmpty())
    }

    @Test fun `naming the account picks it, and the card says so`() {
        twoGoogleAccounts()
        val t = turn("create_calendar_event", *add, "account" to "work")
        assertEquals("Account" to "work (sam@contoso.com) — Work", t.card!!.details.last())
        assertEquals("Added \"Test\" on Tue Nov 3, 3:00–4:00 PM to work (sam@contoso.com).", t.result)
        val post = writes.single()
        assertEquals("Bearer tok-work", post.headers["Authorization"])
        assertTrue(post.url.encodedPath.contains("sam%40contoso.com/events"))
    }

    @Test fun `an account that isn't there is named back with the real ones, and nothing is sent`() {
        twoGoogleAccounts()
        val t = turn("create_calendar_event", *add, "account" to "school")
        assertNull(t.card)
        assertEquals("There's no account called 'school'. The accounts are: personal (a@gmail.com), work (sam@contoso.com).", t.result)
        assertTrue(requests.isEmpty())
    }

    @Test fun `a calendar only one account has picks that account`() {
        twoGoogleAccounts()
        val t = turn("create_calendar_event", *add, "calendar" to "family")
        assertEquals("Account" to "personal (a@gmail.com) — Family", t.card!!.details.last())
        val post = writes.single()
        assertEquals("Bearer tok-personal", post.headers["Authorization"])
        assertTrue(post.url.encodedPath.contains("/calendars/fam/events"))
    }

    @Test fun `with only one account connected there's no question`() {
        twoGoogleAccounts(workState = GoogleAuthorizer.Result.NeedsConsent(null))
        val t = turn("create_calendar_event", *add)
        assertEquals("Account" to "personal (a@gmail.com) — Personal", t.card!!.details.last())
        assertEquals("Bearer tok-personal", writes.single().headers["Authorization"])
    }

    @Test fun `declining the card sends nothing`() {
        twoGoogleAccounts()
        val t = turn("create_calendar_event", *add, "account" to "personal", approve = false)
        assertTrue(t.card != null)
        assertTrue(writes.isEmpty())
    }

    @Test fun `a new email with two accounts - Edith must ask, and named, the card's From says which`() {
        twoGoogleAccounts()
        val ask = turn("draft_email", "to" to "x@y.z", "subject" to "Hi", "body" to "Hello")
        assertNull(ask.card)
        assertTrue(ask.result, ask.result.startsWith("Ask the owner which account to use: personal (a@gmail.com) or work (sam@contoso.com)."))
        assertTrue(requests.isEmpty())

        val named = turn("draft_email", "to" to "x@y.z", "subject" to "Hi", "body" to "Hello", "account" to "personal")
        assertEquals("From" to "personal (a@gmail.com)", named.card!!.details.first())
        assertEquals("Bearer tok-personal", writes.single().headers["Authorization"])
    }

    @Test fun `email from both accounts is listed newest first, labelled, and says when more match`() {
        twoGoogleAccounts()
        val t = turn("search_email", "unread_only" to "true", "after" to "today")
        val lines = t.result.lines()
        assertTrue(lines[0], lines[0].contains("\"Budget\"") && lines[0].endsWith("{message_id: m1} [work]"))
        assertTrue(lines[1], lines[1].contains("\"Dinner\"") && lines[1].endsWith("{message_id: m2} [personal]"))
        assertEquals("More emails match than are shown here; don't say this is all of them.", lines[2])
        val nov2 = LocalDate.of(2026, 11, 2).atStartOfDay(zone).toEpochSecond()
        val queries = requests.filter { it.url.encodedPath.endsWith("/messages") }.map { it.url.queryParameter("q") }
        assertEquals(listOf("is:unread after:$nov2", "is:unread after:$nov2"), queries)
    }

    @Test fun `a reply goes from the account the email came to, with no question`() {
        twoGoogleAccounts()
        turn("search_email", "from" to "boss")
        val workMessage = mailHandles.email("m1")!!.takeIf { it.subject == "Budget" }?.let { "m1" } ?: "m2"
        requests.clear()
        val t = turn("draft_email", "reply_to_message_id" to workMessage, "body" to "Thanks!")
        assertEquals("From" to "work (sam@contoso.com)", t.card!!.details.first())
        assertEquals("To" to "boss@contoso.com", t.card.details[1])
        val post = writes.single()
        assertEquals("Bearer tok-work", post.headers["Authorization"])
        assertEquals("wt1", Json.parseToJsonElement(post.body!!.utf8()).jsonObject["message"]!!.jsonObject["threadId"]!!.jsonPrimitive.content)
    }

    // --- C23: a Microsoft account next to a Google one ----------------------------------------------------------

    /** "outlook" (Microsoft, Graph at /graph/v1.0) saved from an earlier run, and "personal" (Google) added now. */
    /** When true, Graph's calendarView doesn't answer for 60 s (C23: it once took 21 s to send a 502). */
    @Volatile private var outlookStalls = false

    private fun googleAndMicrosoft() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/oauth2/v2.0/token") -> json(com.edith.app.microsoft.MicrosoftAuthTest.tokens(access = "eyJ.outlook", refresh = "M.R3_next"))
                    path == "/graph/v1.0/me/calendars" -> json("""{"value":[{"id":"AAMkCal","name":"Calendar","isDefaultCalendar":true,"canEdit":true}]}""")
                    path == "/graph/v1.0/me/calendars/AAMkCal/calendarView" && outlookStalls ->
                        MockResponse.Builder().headersDelay(60, java.util.concurrent.TimeUnit.SECONDS).code(502).body("{}").build()
                    path == "/graph/v1.0/me/calendars/AAMkCal/calendarView" -> json(
                        """{"value":[{"id":"ev1","subject":"Outlook lunch","start":{"dateTime":"2026-11-03T12:00:00.0000000","timeZone":"Eastern Standard Time"},"end":{"dateTime":"2026-11-03T13:00:00.0000000","timeZone":"Eastern Standard Time"}}]}""",
                    )
                    path == "/graph/v1.0/me/calendars/AAMkCal/events" && request.method == "POST" -> json(
                        """{"id":"new1","subject":"Test","start":{"dateTime":"2026-11-03T15:00:00.0000000","timeZone":"Eastern Standard Time"},"end":{"dateTime":"2026-11-03T16:00:00.0000000","timeZone":"Eastern Standard Time"}}""",
                        201,
                    )
                    path.endsWith("/users/me/calendarList") ->
                        json("""{"items":[{"id":"a@gmail.com","summary":"Personal","primary":true,"selected":true,"accessRole":"owner"}]}""")
                    path.endsWith("/calendars/a%40gmail.com/events") ->
                        json("""{"items":[{"id":"g1","summary":"Gym","start":{"dateTime":"2026-11-03T08:00:00-05:00"},"end":{"dateTime":"2026-11-03T09:00:00-05:00"}}]}""")
                    else -> json("{}", 404)
                }
            }
        }
        server.start()
        val outlook = Account("m3", Provider.Microsoft, "owner@outlook.com", "outlook")
        h = AccountsHarness(baseUrl = server.url("/").toString(), store = com.edith.app.testing.InMemoryAccountStore(listOf(outlook)))
        h.secrets.put(com.edith.app.accounts.AccountSecrets.refreshToken("m3"), "M.R3")
        h.accounts.load()
        h.play.perAccount["a@gmail.com"] = GoogleAuthorizer.Result.Granted("tok-personal")
        h.signIn.result = GoogleSignIn.Result.SignedIn("a@gmail.com", null)
        h.accounts.addGoogle(Activity())
        h.accounts.rename(h.accounts.accounts.value.last().first.id, "personal")
        kotlinx.coroutines.withTimeout(5_000) { while (h.accounts.accounts.value.any { it.second != AccountState.Connected }) delay(10) }
        requests.clear()
    }

    @Test fun `Outlook and Google events are listed together, labelled`() {
        googleAndMicrosoft()
        val t = turn("list_calendar_events", "start" to "2026-11-03T00:00", "end" to "2026-11-04T00:00")
        val lines = t.result.lines().filter { it.contains("{event_id:") }
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].contains("Gym") && lines[0].contains("personal"))
        assertTrue(lines[1], lines[1].contains("Outlook lunch") && lines[1].contains("outlook"))
        assertTrue("Graph was asked", requests.any { it.url.encodedPath.endsWith("/calendarView") && it.headers["Authorization"] == "Bearer eyJ.outlook" })
    }

    @Test fun `Outlook not answering times out on its own - Google's events still come back`() {
        googleAndMicrosoft()
        outlookStalls = true
        val started = System.nanoTime()
        val t = turn("list_calendar_events", "start" to "2026-11-03T00:00", "end" to "2026-11-04T00:00")
        val seconds = (System.nanoTime() - started) / 1_000_000_000
        assertTrue("within the tool's 20 s: $seconds s", seconds < 15)
        assertTrue(t.result, t.result.contains("Gym"))
        assertTrue(t.result, t.result.contains("Couldn't read outlook (owner@outlook.com): it didn't answer within 10 seconds."))
    }

    @Test fun `a new event with Google and Microsoft - Edith asks, offering both, and nothing is sent`() {
        googleAndMicrosoft()
        val t = turn("create_calendar_event", *add)
        assertNull(t.card)
        assertEquals(
            "Ask the owner which account to use: outlook (owner@outlook.com) or personal (a@gmail.com). Then call this tool again with `account`.",
            t.result,
        )
        assertTrue(requests.isEmpty())
    }

    @Test fun `named, the Outlook card says so - declining sends nothing, accepting posts to Graph`() {
        googleAndMicrosoft()
        val declined = turn("create_calendar_event", *add, "account" to "outlook", approve = false)
        assertEquals("Account" to "outlook (owner@outlook.com) — Calendar", declined.card!!.details.last())
        assertTrue(writes.isEmpty())

        val added = turn("create_calendar_event", *add, "account" to "outlook")
        assertTrue(added.result, added.result.startsWith("Added \"Test\""))
        val post = writes.single()
        assertEquals("/graph/v1.0/me/calendars/AAMkCal/events", post.url.encodedPath)
        assertEquals("Bearer eyJ.outlook", post.headers["Authorization"])
    }

    // --- Gmail's query (C22 §3) -----------------------------------------------------------------------------------

    @Test fun `each search field becomes Gmail's syntax, with exact local days`() {
        val today = LocalDate.of(2026, 11, 11)
        val nov11 = today.atStartOfDay(zone).toInstant()
        fun q(m: MailQuery) = GmailQuery.build(m, today, zone)
        assertEquals("from:sam", q(MailQuery(from = "sam")))
        assertEquals("from:\"Lincoln Elementary\"", q(MailQuery(from = "Lincoln \"Elementary\"")))
        assertEquals("to:owner@gmail.com", q(MailQuery(to = "owner@gmail.com")))
        assertEquals("subject:(field trip)", q(MailQuery(subject = "field trip")))
        assertEquals("is:unread", q(MailQuery(unreadOnly = true)))
        assertEquals("after:${nov11.epochSecond} before:${nov11.epochSecond + 86_400}", q(MailQuery(after = nov11, before = nov11.plusSeconds(86_400))))
        assertEquals("free text keeps Gmail's words, dates made local", "has:attachment after:${nov11.epochSecond}", q(MailQuery(text = "has:attachment after:today")))
        assertEquals("", q(MailQuery(from = " ", text = "")))
    }

    @Test fun `search dates are words or days, made local midnight by Edith`() {
        val today = LocalDate.of(2026, 11, 11)
        val nov11 = today.atStartOfDay(zone).toInstant()
        assertEquals(nov11, MailDates.parse("today", today, zone))
        assertEquals(nov11.minusSeconds(86_400), MailDates.parse("Yesterday", today, zone))
        assertEquals(nov11, MailDates.parse("2026/11/11", today, zone))
        assertEquals(nov11, MailDates.parse("2026-11-11T09:30:00", today, zone))
        assertNull(MailDates.parse("2026-02-30", today, zone))
        assertNull(MailDates.parse("last week", today, zone))
        assertFalse(LocalDate.of(2026, 7, 4).atStartOfDay(zone).offset == ZonedDateTime.of(2026, 11, 11, 0, 0, 0, 0, zone).offset)
    }
}
