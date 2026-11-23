package com.edith.app.google.tasks

import com.edith.app.core.net.HttpClients
import android.app.Activity
import android.app.PendingIntent
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.sources.AccountErrors
import com.edith.app.accounts.tasks.TaskPatch
import com.edith.app.accounts.tasks.TasksAccess
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.google.GoogleAccountAuth
import com.edith.app.google.GoogleAuthorizer
import com.edith.app.google.GoogleCall
import com.edith.app.google.GoogleConnection
import com.edith.app.google.GoogleScope
import com.edith.app.testing.AccountsHarness
import com.edith.app.testing.AppDispatchersForTests
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
import kotlinx.datetime.LocalDate
import java.util.Collections
import java.util.TimeZone

/**
 * C35 §1–2 against a stand-in for Google Tasks (MockWebServer). The responses follow the shapes in the Tasks API
 * reference (tasklists.list, tasks.list/insert/patch/move/delete); they're made up, not the owner's data. The JVM runs
 * in Los Angeles here, west of UTC, so a due date read as an instant would move a day back (trap 1).
 */
class TasksTest {
    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()
    private lateinit var h: AccountsHarness
    private lateinit var client: TasksClient
    private lateinit var auth: GoogleAccountAuth
    private val savedZone = TimeZone.getDefault()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()
    private val id get() = h.accounts.accounts.value.single().first.id
    private fun body(r: RecordedRequest): JsonObject = Json.parseToJsonElement(r.body!!.utf8()).jsonObject

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val key = "${request.method} ${request.url.encodedPath}"
                return routes.entries.filter { key.startsWith(it.key) }.maxByOrNull { it.key.length }?.value?.invoke(request) ?: json("{}", 404)
            }
        }
        server.start()
        h = AccountsHarness(baseUrl = server.url("/").toString())
        runBlocking { h.accounts.addGoogle(Activity()) }
        auth = (h.accounts.connection(id) as GoogleConnection).auth
        client = TasksClient(lazy { HttpClients.over(OkHttpClient()) }, auth, AppDispatchersForTests.unconfined, server.url("/tasks/v1").toString(), id)
        routes["GET /tasks/v1/users/@me/lists"] = { r -> json(if (r.url.queryParameter("pageToken") == "p2") LISTS_PAGE_2 else LISTS_PAGE_1) }
        routes["GET /tasks/v1/lists/L1/tasks"] = { r -> json(if (r.url.queryParameter("pageToken") == "n2") TASKS_PAGE_2 else TASKS_PAGE_1) }
    }

    @After fun tearDown() {
        TimeZone.setDefault(savedZone)
        server.close()
    }

    // --- The client ---------------------------------------------------------------------------------------------

    @Test fun `lists are read page by page`() = runBlocking {
        val lists = (client.lists() as Outcome.Success).value
        assertEquals(listOf("My Tasks", "Shopping", "House"), lists.map { it.title })
        assertEquals(listOf("L1", "L2", "L3"), lists.map { it.id })
        val pages = requests.filter { it.url.encodedPath.endsWith("/lists") }
        assertEquals(listOf(null, "p2"), pages.map { it.url.queryParameter("pageToken") })
        assertEquals("Bearer ya29.secret-token", pages.first().headers["Authorization"])
        assertEquals("only the Tasks scope is asked for", setOf(GoogleScope.Tasks), h.play.asked.last().second)
    }

    @Test fun `tasks are read page by page in Google's order, subtasks under their parent, deleted and empty ones left out`() = runBlocking {
        val tasks = (client.tasks("L1", includeDone = false) as Outcome.Success).value
        assertEquals(listOf("Call the dentist", "Pack for the trip", "Socks", "Passport", "Renew the car's plates"), tasks.map { it.title })
        assertEquals("p1", tasks[2].parent)
        assertTrue(tasks.all { it.accountId == id && it.listId == "L1" })
        val q = requests.first { it.url.encodedPath.endsWith("/lists/L1/tasks") }.url
        assertEquals("false", q.queryParameter("showCompleted"))
        assertEquals("false", q.queryParameter("showHidden"))
        assertEquals("100", q.queryParameter("maxResults"))
        assertEquals(listOf(null, "n2"), requests.filter { it.url.encodedPath.endsWith("/lists/L1/tasks") }.map { it.url.queryParameter("pageToken") })
    }

    @Test fun `due dates stay the dates Google keeps, west of UTC too`() = runBlocking {
        val tasks = (client.tasks("L1", includeDone = false) as Outcome.Success).value
        assertEquals(LocalDate(2026, 11, 14), tasks.first { it.title == "Call the dentist" }.due)
        assertNull(tasks.first { it.title == "Pack for the trip" }.due)
        assertEquals(LocalDate(2026, 11, 5), TaskDue.fromGoogle("2026-11-05T00:00:00.000Z"))
        assertEquals("2026-11-05T00:00:00.000Z", TaskDue.toGoogle(LocalDate(2026, 11, 5)))
        assertNull(TaskDue.fromGoogle("garbage"))
        assertNull(TaskDue.fromGoogle(null))
    }

    @Test fun `done tasks only when asked for, after the open ones`() = runBlocking {
        routes["GET /tasks/v1/lists/L1/tasks"] = { json(TASKS_WITH_DONE) }
        val tasks = (client.tasks("L1", includeDone = true) as Outcome.Success).value
        assertEquals(listOf("Open", "Done later", "Done earlier"), tasks.map { it.title })
        assertTrue(tasks[1].done)
        assertEquals("true", requests.last().url.queryParameter("showCompleted"))
        assertEquals("true", requests.last().url.queryParameter("showHidden"))
    }

    @Test fun `insert sends the date at UTC midnight and a parent when given`() = runBlocking {
        routes["POST /tasks/v1/lists/L1/tasks"] = { json("""{"id":"new1","title":"Call the dentist","status":"needsAction","due":"2026-11-14T00:00:00.000Z","position":"00000000000000000000"}""") }
        val t = (client.insert("L1", " Call the dentist ", "Ask about Tuesday", LocalDate(2026, 11, 14), parent = "p1") as Outcome.Success).value
        assertEquals(LocalDate(2026, 11, 14), t.due)
        val r = requests.last()
        assertEquals(mapOf("title" to JsonPrimitive("Call the dentist"), "notes" to JsonPrimitive("Ask about Tuesday"), "due" to JsonPrimitive("2026-11-14T00:00:00.000Z")), body(r))
        assertEquals("p1", r.url.queryParameter("parent"))
        assertNull("new tasks go to the top: no previous", r.url.queryParameter("previous"))
    }

    @Test fun `patch sends only the changed fields, and null to clear`() = runBlocking {
        routes["PATCH /tasks/v1/lists/L1/tasks/t1"] = { json("""{"id":"t1","title":"Call the vet","status":"needsAction"}""") }
        client.patch("L1", "t1", TaskPatch(title = "Call the vet"))
        assertEquals(mapOf("title" to JsonPrimitive("Call the vet")), body(requests.last()))
        client.patch("L1", "t1", TaskPatch(notes = "", clearDue = true))
        assertEquals(mapOf("notes" to JsonNull, "due" to JsonNull), body(requests.last()))
        client.patch("L1", "t1", TaskPatch(due = LocalDate(2026, 12, 1)))
        assertEquals(mapOf("due" to JsonPrimitive("2026-12-01T00:00:00.000Z")), body(requests.last()))
    }

    @Test fun `complete and reopen`() = runBlocking {
        routes["PATCH /tasks/v1/lists/L1/tasks/t1"] = { json("""{"id":"t1","title":"Call the dentist","status":"completed","completed":"2026-11-13T15:00:00.000Z"}""") }
        assertTrue((client.complete("L1", "t1", true) as Outcome.Success).value.done)
        assertEquals(mapOf("status" to JsonPrimitive("completed")), body(requests.last()))
        client.complete("L1", "t1", false)
        assertEquals("reopening clears completed", mapOf("status" to JsonPrimitive("needsAction"), "completed" to JsonNull), body(requests.last()))
    }

    @Test fun `move with parent and previous, and delete`() = runBlocking {
        routes["POST /tasks/v1/lists/L1/tasks/s1/move"] = { json("""{"id":"s1","title":"Socks","parent":"p1","position":"2"}""") }
        routes["DELETE /tasks/v1/lists/L1/tasks/t1"] = { MockResponse.Builder().code(204).build() }
        client.move("L1", "s1", parent = "p1", previous = "s2")
        val m = requests.last()
        assertEquals("POST", m.method)
        assertEquals("p1", m.url.queryParameter("parent"))
        assertEquals("s2", m.url.queryParameter("previous"))
        client.move("L1", "s1", parent = null, previous = null)
        assertNull(requests.last().url.queryParameter("previous"))
        assertNull(requests.last().url.queryParameter("parent"))
        assertEquals(Outcome.Success(Unit), client.delete("L1", "t1"))
        assertEquals("DELETE", requests.last().method)
    }

    @Test fun `the Tasks API turned off is a setup message, not a permission problem`() = runBlocking {
        routes["GET /tasks/v1/users/@me/lists"] = { json(API_DISABLED_BODY, 403) }
        val r = client.lists()
        assertEquals(Outcome.Failure(AppError.ToolFailed("tasks", TasksClient.API_DISABLED)), r)
        assertEquals("not \"needs permission\": Grant access wouldn't help", TasksAccess.Unknown, auth.tasks.value)
        assertEquals("calendar and email unaffected", AccountState.Connected, auth.state.value)
    }

    // --- Capabilities and scopes ------------------------------------------------------------------------------

    @Test fun `without the Tasks scope only the to-dos need permission, calendar and mail stay connected`() = runBlocking {
        h.play.byScopes = { _, scopes -> if (GoogleScope.Tasks in scopes) GoogleAuthorizer.Result.NeedsConsent(null) else null }
        val before = requests.size
        val r = client.lists()
        assertEquals(Outcome.Failure(AppError.GoogleAuth(AccountErrors.TASKS_NEED_PERMISSION)), r)
        assertEquals("no Tasks request without the scope", before, requests.size)
        assertEquals(TasksAccess.NeedsPermission, auth.tasks.value)
        assertEquals(AccountState.Connected, auth.state.value)
        assertEquals(mapOf(id to TasksAccess.NeedsPermission), h.accounts.tasksAccess.value)
        assertTrue("calendar still works", auth.accessToken(setOf(GoogleScope.CalendarEvents)) is Outcome.Success)
        val line = h.accounts.connectedNote(emptyList())!!
        assertTrue(line, line.contains("(Google: calendar and email; to-dos need permission)"))
    }

    @Test fun `a 403 for the Tasks scope also only touches the to-dos`() = runBlocking {
        routes["GET /tasks/v1/users/@me/lists"] = { json("""{"error":{"code":403,"errors":[{"reason":"insufficientPermissions"}],"status":"PERMISSION_DENIED"}}""", 403) }
        assertEquals(Outcome.Failure(AppError.GoogleAuth(AccountErrors.TASKS_NEED_PERMISSION)), client.lists())
        assertEquals(TasksAccess.NeedsPermission, auth.tasks.value)
        assertEquals(AccountState.Connected, auth.state.value)
    }

    @Test fun `Grant access for to-dos asks Play services for the Tasks scope alone, then works`() = runBlocking {
        h.play.byScopes = { _, scopes -> if (GoogleScope.Tasks in scopes) GoogleAuthorizer.Result.NeedsConsent(somePendingIntent()) else null }
        client.lists()
        assertEquals(TasksAccess.NeedsPermission, auth.tasks.value)
        var shown = 0
        h.accounts.grantTasks(id, Activity()) { shown++ }
        assertEquals("Google's consent screen", 1, shown)
        h.play.byScopes = { _, _ -> null }
        h.accounts.onConsentResult(null)
        assertEquals(TasksAccess.Granted, auth.tasks.value)
        assertEquals(AccountState.Connected, auth.state.value)
        assertTrue(client.lists() is Outcome.Success)
        assertTrue("calendar's scopes were never asked again with Tasks", h.play.asked.none { it.second.contains(GoogleScope.Tasks) && it.second.size > 1 })
    }

    @Test fun `a declined Tasks consent leaves calendar and mail alone`() = runBlocking {
        h.play.byScopes = { _, scopes -> if (GoogleScope.Tasks in scopes) GoogleAuthorizer.Result.NeedsConsent(somePendingIntent()) else null }
        client.lists()
        h.accounts.grantTasks(id, Activity()) { }
        h.play.consent = GoogleAuthorizer.Result.Failed("Access wasn't approved.")
        h.accounts.onConsentResult(null)
        assertEquals(TasksAccess.NeedsPermission, auth.tasks.value)
        assertEquals(AccountState.Connected, auth.state.value)
    }

    @Test fun `startup doesn't ask for Tasks, and a recheck forgets what it knew`() = runBlocking {
        assertTrue(h.play.asked.none { GoogleScope.Tasks in it.second })
        h.play.byScopes = { _, scopes -> if (GoogleScope.Tasks in scopes) GoogleAuthorizer.Result.NeedsConsent(null) else null }
        client.lists()
        assertEquals(TasksAccess.NeedsPermission, auth.tasks.value)
        auth.check()
        assertEquals(TasksAccess.Unknown, auth.tasks.value)
    }

    @Test fun `on the browser path Grant access for to-dos signs in again, and without the scope no request is made`() = runBlocking {
        var signIns = 0
        var calls = 0
        val browserAuth = GoogleAccountAuth(
            account = { h.accounts.accounts.value.single().first },
            authorizer = object : GoogleAuthorizer {
                // As BrowserGoogle answers when the refresh response's `scope` lacks Tasks.
                override suspend fun authorize(scopes: Set<GoogleScope>) =
                    if (GoogleScope.Tasks in scopes) GoogleAuthorizer.Result.NeedsConsent(null) else GoogleAuthorizer.Result.Granted("ya29.b")
                override fun fromConsent(data: android.content.Intent?) = GoogleAuthorizer.Result.Failed("none")
                override suspend fun clearToken(token: String) = Unit
                override suspend fun revoke(email: String) = Unit
            },
            usesBrowser = { true },
            signInAgain = { signIns++ },
        )
        browserAuth.check()
        assertEquals(AccountState.Connected, browserAuth.state.value)
        val r = browserAuth.tasksAuthorized { calls++; GoogleCall.Ok(Unit) }
        assertEquals(Outcome.Failure(AppError.GoogleAuth(AccountErrors.TASKS_NEED_PERMISSION)), r)
        assertEquals(0, calls)
        assertEquals(TasksAccess.NeedsPermission, browserAuth.tasks.value)
        assertEquals(AccountState.Connected, browserAuth.state.value)
        browserAuth.grantTasks(Activity()) { error("no consent screen on the browser path") }
        assertEquals(1, signIns)
        assertTrue("the browser sign-in asks for every scope, Tasks included", GoogleScope.Tasks in GoogleScope.entries)
        assertFalse("startup's check doesn't", GoogleScope.Tasks in GoogleScope.CORE)
    }

    companion object {
        /** A stand-in for Google's consent intent (JVM tests have no real PendingIntent to create). */
        fun somePendingIntent(): PendingIntent {
            val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = f.get(null)
            return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, PendingIntent::class.java) as PendingIntent
        }

        const val LISTS_PAGE_1 = """{"kind":"tasks#taskLists","etag":"\"e1\"","nextPageToken":"p2","items":[
            {"kind":"tasks#taskList","id":"L1","etag":"\"a\"","title":"My Tasks","updated":"2026-11-10T12:00:00.000Z"},
            {"kind":"tasks#taskList","id":"L2","etag":"\"b\"","title":"Shopping","updated":"2026-11-09T12:00:00.000Z"}]}"""
        const val LISTS_PAGE_2 = """{"kind":"tasks#taskLists","etag":"\"e2\"","items":[{"kind":"tasks#taskList","id":"L3","title":"House"}]}"""

        // Out of order on purpose: Google's own order is `position`, not the order returned (trap 3).
        const val TASKS_PAGE_1 = """{"kind":"tasks#tasks","etag":"\"x\"","nextPageToken":"n2","items":[
            {"kind":"tasks#task","id":"p1","title":"Pack for the trip","status":"needsAction","position":"00000000000000000002"},
            {"kind":"tasks#task","id":"s2","title":"Passport","status":"needsAction","parent":"p1","position":"00000000000000000001"},
            {"kind":"tasks#task","id":"t1","title":"Call the dentist","notes":"Ask about Tuesday","status":"needsAction","due":"2026-11-14T00:00:00.000Z","position":"00000000000000000001"},
            {"kind":"tasks#task","id":"gone","title":"Old","status":"needsAction","deleted":true,"position":"00000000000000000000"}]}"""
        const val TASKS_PAGE_2 = """{"kind":"tasks#tasks","items":[
            {"kind":"tasks#task","id":"s1","title":"Socks","status":"needsAction","parent":"p1","position":"00000000000000000000"},
            {"kind":"tasks#task","id":"e1","title":"","status":"needsAction","position":"00000000000000000005"},
            {"kind":"tasks#task","id":"t3","title":"Renew the car's plates","status":"needsAction","due":"2026-11-01T00:00:00.000Z","position":"00000000000000000003"}]}"""
        const val TASKS_WITH_DONE = """{"items":[
            {"id":"d1","title":"Done earlier","status":"completed","completed":"2026-11-01T10:00:00.000Z","position":"1"},
            {"id":"o1","title":"Open","status":"needsAction","position":"2"},
            {"id":"d2","title":"Done later","status":"completed","completed":"2026-11-05T10:00:00.000Z","position":"0"}]}"""
        const val API_DISABLED_BODY = """{"error":{"code":403,"message":"Google Tasks API has not been used in project 123456 before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/tasks.googleapis.com/overview?project=123456 then retry.",
            "errors":[{"message":"…","domain":"usageLimits","reason":"accessNotConfigured"}],"status":"PERMISSION_DENIED",
            "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED","domain":"googleapis.com","metadata":{"consumer":"projects/123456","service":"tasks.googleapis.com"}}]}}"""
    }
}
