package com.edith.app.google.gmail

import android.app.Activity
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import android.content.Intent
import com.edith.app.chat.engine.TurnEvent
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.accounts.sources.OutgoingMail
import com.edith.app.accounts.tools.MailHandles
import com.edith.app.accounts.tools.mailTools
import com.edith.app.testing.AccountsHarness
import com.edith.app.google.GoogleAuthorizer
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
import com.edith.app.testing.FakeClock
import com.edith.app.testing.FakeSettingsRepository
import com.edith.app.tools.framework.ApprovalChannel
import com.edith.app.tools.framework.Confirmations
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolRegistry
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.framework.ToolTurnRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.Collections

/**
 * C19 against a stand-in for Gmail (MockWebServer). The messages follow the shapes in Gmail's API reference
 * (messages.list, messages.get with format=metadata/full, drafts.create, messages.send); they're made up.
 */
class GmailTest {
    private val zone = TimeZone.of("America/Detroit")
    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private val settings = FakeSettingsRepository()
    private lateinit var h: AccountsHarness

    private val handles = MailHandles()
    private val clock = FakeClock(Instant.parse("2026-11-02T18:00:00Z"), zone)
    private val ctx get() = ToolContext(clock, Settings(), dispatchers)
    private val tools by lazy { mailTools(h.accounts, handles).associateBy { it.spec.name } }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val key = "${request.method} ${request.url.encodedPath}"
                return routes.entries.firstOrNull { key == it.key }?.value?.invoke() ?: json("{}", 404)
            }
        }
        server.start()
        h = AccountsHarness(baseUrl = server.url("/").toString())
        h.play.default = GoogleAuthorizer.Result.Granted("token")
        runBlocking { h.accounts.addGoogle(Activity()) }
        routes["GET /gmail/v1/users/me/messages"] = { json("""{"messages":[{"id":"a1","threadId":"t1"},{"id":"b2","threadId":"t2"}],"resultSizeEstimate":2}""") }
        routes["GET /gmail/v1/users/me/messages/a1"] = { json(metadata("a1", "t1", "Lincoln Elementary <office@lincoln.example>", "Field trip Friday", "<abc@lincoln.example>", "Permission slips are due &amp; buses leave at 8.", unread = true)) }
        routes["GET /gmail/v1/users/me/messages/b2"] = { json(metadata("b2", "t2", "Sam Lee <sam@example.com>", "Friday?", "<sam-1@example.com>", "Are we still on for Friday?", unread = false)) }
    }

    @After fun tearDown() = server.close()

    private fun metadata(id: String, thread: String, from: String, subject: String, msgId: String, snippet: String, unread: Boolean) = """
        {"id":"$id","threadId":"$thread","labelIds":["INBOX"${if (unread) ",\"UNREAD\"" else ""}],"snippet":"$snippet","internalDate":"1793289600000",
         "payload":{"headers":[{"name":"From","value":"$from"},{"name":"To","value":"owner@gmail.com"},{"name":"Subject","value":"$subject"},
           {"name":"Date","value":"Thu, 29 Oct 2026 12:00:00 -0400"},{"name":"Message-ID","value":"$msgId"}]}}"""

    private fun args(tool: Tool, vararg pairs: Pair<String, String>) = ToolArgs(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }), tool.spec)

    // --- MIME and the outgoing message -------------------------------------------------------------------------

    private fun part(mime: String, text: String? = null, parts: List<PartDto> = emptyList(), filename: String? = null) =
        PartDto(mimeType = mime, filename = filename, body = text?.let { BodyDto(b64(it)) }, parts = parts)

    @Test fun `plain text is preferred, html is made readable, attachments are skipped`() {
        assertEquals("Hello\nSee you Friday.", MailText.body(part("multipart/alternative", parts = listOf(part("text/plain", "Hello\nSee you Friday."), part("text/html", "<p>Hello</p>")))))
        assertTrue(MailText.body(part("text/html", "<html><body><p>Field trip <b>Friday</b>.</p><p>Bring lunch.</p></body></html>")).let { it.contains("Field trip Friday.") && it.contains("Bring lunch.") })
        val mixed = part("multipart/mixed", parts = listOf(
            part("text/plain", "attached notes", filename = "notes.txt"),
            part("multipart/alternative", parts = listOf(part("text/plain", "The real body"))),
        ))
        assertEquals("The real body", MailText.body(mixed))
        assertEquals("base64url with - and _ decoded", "é?>>", MailText.body(part("text/plain", "é?>>")))
    }

    @Test fun `quoted history is removed where it's obvious`() {
        val gmail = "Sounds good!\n\nOn Thu, Oct 29, 2026 at 12:00 PM Sam Lee <sam@example.com> wrote:\n> Are we on for Friday?\n> Sam"
        assertEquals("Sounds good!", MailText.stripQuoted(gmail).trim())
        assertEquals("Yes.", MailText.stripQuoted("Yes.\n-----Original Message-----\nFrom: x").trim())
        assertEquals("Line one\nLine two", MailText.stripQuoted("Line one\n> quoted\nLine two"))
        assertEquals("On Friday I'll bring cake.", MailText.stripQuoted("On Friday I'll bring cake."))
    }

    @Test fun `a long body is cut at 6000 characters`() {
        val body = MailText.body(part("text/plain", "x".repeat(10_000)))
        assertTrue(body.startsWith("x".repeat(MailText.MAX_BODY)) && body.endsWith("[…the rest of the email is cut]"))
    }

    @Test fun `the raw message has the headers, a UTF-8 subject in RFC 2047, and the body`() {
        val raw = MailText.raw(OutgoingMail("sam@example.com", "Café on Friday", "Thanks!\nSee you there.", inReplyTo = "<sam-1@example.com>"))
        val text = String(Base64.getUrlDecoder().decode(raw))
        val (head, body) = text.split("\r\n\r\n", limit = 2)
        assertTrue(head.contains("To: sam@example.com\r\n"))
        assertTrue(head.contains("Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString("Café on Friday".toByteArray()) + "?="))
        assertTrue(head.contains("In-Reply-To: <sam-1@example.com>\r\nReferences: <sam-1@example.com>"))
        assertTrue(head.contains("Content-Type: text/plain; charset=UTF-8"))
        assertEquals("Thanks!\r\nSee you there.", String(Base64.getMimeDecoder().decode(body)))
        assertEquals("plain ASCII stays", "Lunch", MailText.encodeHeader("Lunch"))
        assertFalse("no header injection", String(Base64.getUrlDecoder().decode(MailText.raw(OutgoingMail("a@b.c\r\nBcc: x@y.z", "s", "b")))).contains("\r\nBcc:"))
    }

    // --- Tools -----------------------------------------------------------------------------------------------

    @Test fun `search lists the newest first with short ids, unread marked, entities decoded`() = runBlocking {
        val r = tools.getValue("search_email").execute(args(tools.getValue("search_email"), "query" to "is:unread newer_than:7d"), ctx)
        val lines = r.text.lines()
        assertEquals("1. Thu Oct 29 — Lincoln Elementary <office@lincoln.example> — \"Field trip Friday\" (unread) — Permission slips are due & buses leave at 8. {message_id: m1}", lines[0])
        assertTrue(lines[1].endsWith("{message_id: m2}"))
        val list = requests.first().url
        assertEquals("is:unread newer_than:7d", list.queryParameter("q"))
        assertEquals("5", list.queryParameter("maxResults"))
        assertEquals("metadata", requests[1].url.queryParameter("format"))
    }

    @Test fun `dates in a search become local midnight in seconds, not Gmail's Pacific midnight`() = runBlocking {
        // 2026-11-11 00:00 in Detroit (EST, -05:00) = 05:00 UTC; Gmail would have used 08:00 UTC.
        val today = LocalDate(2026, 11, 11)
        val nov11 = today.atStartOfDayIn(zone).epochSeconds
        assertEquals(1794373200L, nov11)
        fun q(s: String) = GmailQuery.localDates(s, today, zone)
        assertEquals("is:unread after:$nov11 before:${nov11 + 86_400}", q("is:unread after:2026/11/11 before:2026-11-12"))
        assertEquals("from:sam newer:$nov11", q("from:sam newer:2026/11/11"))
        assertEquals("unchanged", "newer_than:1d subject:2026/11/11 after:2026/02/30", q("newer_than:1d subject:2026/11/11 after:2026/02/30"))
        val summer = LocalDate(2026, 7, 4).atStartOfDayIn(zone).epochSeconds
        assertEquals("summer uses EDT", 1783137600L, summer)
    }

    @Test fun `today and yesterday are worked out by Edith, not the model`() = runBlocking {
        val today = LocalDate(2026, 11, 11)
        val nov11 = today.atStartOfDayIn(zone).epochSeconds
        assertEquals("after:${nov11 - 86_400} before:$nov11", GmailQuery.localDates("after:yesterday before:Today", today, zone))
        assertEquals("before:${nov11 + 86_400}", GmailQuery.localDates("before:tomorrow", today, zone))
        // The test clock says 2026-11-02 13:00 in Detroit, so "today" is Nov 2.
        val nov2 = LocalDate(2026, 11, 2).atStartOfDayIn(zone).epochSeconds
        tools.getValue("search_email").execute(args(tools.getValue("search_email"), "query" to "is:unread after:today"), ctx)
        assertEquals("is:unread after:$nov2", requests.first().url.queryParameter("q"))
    }

    @Test fun `read gives the headers and the cleaned body`() = runBlocking {
        tools.getValue("search_email").execute(args(tools.getValue("search_email"), "query" to "from:sam"), ctx)
        routes["GET /gmail/v1/users/me/messages/b2"] = {
            json("""{"id":"b2","threadId":"t2","internalDate":"1793289600000","labelIds":[],"payload":{"mimeType":"multipart/alternative",
              "headers":[{"name":"From","value":"Sam Lee <sam@example.com>"},{"name":"To","value":"owner@gmail.com"},{"name":"Subject","value":"Friday?"}],
              "parts":[{"mimeType":"text/plain","body":{"data":"${b64("Are we still on for Friday at 7?\n\nOn Wed, Oct 28 Owner wrote:\n> earlier")}"}},
                       {"mimeType":"text/html","body":{"data":"${b64("<p>html</p>")}"}}]}}""")
        }
        val r = tools.getValue("read_email").execute(args(tools.getValue("read_email"), "message_id" to "m2"), ctx)
        assertEquals("From: Sam Lee <sam@example.com>\nTo: owner@gmail.com\nDate: Thu Oct 29, 12:00 PM\nSubject: Friday?\n\nAre we still on for Friday at 7?", r.text)
        assertEquals("full", requests.last().url.queryParameter("format"))
    }

    @Test fun `a reply draft answers the sender, says Re, stays in the thread, and asks first`() = runBlocking {
        tools.getValue("search_email").execute(args(tools.getValue("search_email"), "query" to "from:sam"), ctx)
        routes["POST /gmail/v1/users/me/drafts"] = { json("""{"id":"d1","message":{"id":"x"}}""") }
        val draft = tools.getValue("draft_email")
        val a = args(draft, "reply_to_message_id" to "m2", "body" to "Thanks, see you Friday!")
        val card = draft.confirmation(a)!!
        assertEquals("Save this draft?", card.title)
        assertEquals(listOf("From" to "owner@gmail.com", "To" to "sam@example.com", "Subject" to "Re: Friday?", "Message" to "Thanks, see you Friday!"), card.details)
        assertEquals("Draft saved in owner@gmail.com — open that mailbox to send it.", draft.execute(a, ctx).text)
        val body = Json.parseToJsonElement(requests.last().body!!.utf8()).jsonObject["message"]!!.jsonObject
        assertEquals("t2", body["threadId"]!!.jsonPrimitive.content)
        val raw = String(Base64.getUrlDecoder().decode(body["raw"]!!.jsonPrimitive.content))
        assertTrue(raw.contains("In-Reply-To: <sam-1@example.com>"))
    }

    @Test fun `send shows the whole email first and sends to messages send`() = runBlocking {
        routes["POST /gmail/v1/users/me/messages/send"] = { json("""{"id":"s1","threadId":"t9"}""") }
        val send = tools.getValue("send_email")
        val a = args(send, "to" to "sam@example.com", "subject" to "Friday", "body" to "I'll be there at 7.")
        val card = send.confirmation(a)!!
        assertEquals("Send this email?", card.title)
        assertEquals("Send", card.confirmLabel)
        assertEquals("Message" to "I'll be there at 7.", card.details.last())
        assertEquals("Sent to sam@example.com from owner@gmail.com.", send.execute(a, ctx).text)
        assertEquals("/gmail/v1/users/me/messages/send", requests.last().url.encodedPath)
    }

    @Test fun `a long email is shown on the card in full, exactly as it is sent`() = runBlocking {
        routes["POST /gmail/v1/users/me/messages/send"] = { json("""{"id":"s2","threadId":"t9"}""") }
        val send = tools.getValue("send_email")
        val body = (1..400).joinToString("\n") { "Line $it of a long email." }
        val a = args(send, "to" to "sam@example.com", "subject" to "Long", "body" to body)
        assertEquals("Message" to body, send.confirmation(a)!!.details.last())
        send.execute(a, ctx)
        val raw = Json.parseToJsonElement(requests.last().body!!.utf8()).jsonObject["raw"]!!.jsonPrimitive.content
        val sent = String(Base64.getUrlDecoder().decode(raw)).split("\r\n\r\n", limit = 2)[1]
        assertEquals(body.replace("\n", "\r\n"), String(Base64.getMimeDecoder().decode(sent)))
    }

    @Test fun `declining the send card means nothing is sent`() = runBlocking {
        val confirmations = Confirmations()
        val ollama = object : OllamaClient {
            var round = 0
            override fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent> = flow {
                if (round++ == 0) {
                    val call = OllamaToolCall(function = OllamaFunctionCall("send_email", JsonObject(mapOf(
                        "to" to JsonPrimitive("sam@example.com"), "subject" to JsonPrimitive("Hi"), "body" to JsonPrimitive("Hello")))))
                    emit(ChatEvent.ToolCalls(OllamaMessage("assistant", "", toolCalls = listOf(call))))
                } else emit(ChatEvent.Delta("Okay."))
                emit(ChatEvent.Done(ReplyStats(1, 1, 1)))
            }
            override suspend fun chat(baseUrl: String, request: ChatCall) = Outcome.Failure(AppError.EmptyReply)
            override suspend fun listModels(baseUrl: String) = Outcome.Success(emptyList<OllamaModelTag>())
            override suspend fun show(baseUrl: String, model: String) = Outcome.Success(OllamaShow(listOf("tools")))
            override suspend fun version(baseUrl: String) = Outcome.Success("x")
        }
        val runner = ToolTurnRunner(ollama, ToolRegistry(mailTools(h.accounts, handles), listOf(h.accounts)), confirmations, settings, clock, dispatchers)
        var asked = false
        runner.run("http://ollama", ModelInfo("gemma4:12b", setOf("tools")), listOf(OllamaMessage("user", "send it"))).collect { e ->
            if (e is TurnEvent.NeedsConfirmation) { asked = true; confirmations.resolve(e.request.id, false, ApprovalChannel.Tap) }
        }
        assertTrue(asked)
        assertTrue("nothing reached Gmail", requests.isEmpty())
    }

    @Test fun `every tool that can send or save asks first`() = runBlocking {
        val write = tools.values.filter { it.spec.name in setOf("draft_email", "send_email") }
        assertEquals(2, write.size)
        write.forEach { t -> assertTrue(t.spec.name, t.confirmation(args(t, "to" to "a@b.c", "subject" to "s", "body" to "b")) != null) }
        // The only way to Gmail's send endpoint is SendEmailTool's, behind its confirmation (D5).
        val sources = listOf("src/main/java/com/edith/app", "../shared/src/commonMain/kotlin/com/edith/app").flatMap { File(it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        assertEquals("Gmail's send: only GmailSource", listOf("GoogleSources.kt"), sources.filter { it.readText().contains("client.send(") }.map { it.name })
        assertEquals("a source's send: only the tools", listOf("MailTools.kt"), sources.filter { it.readText().contains("source.send(") }.map { it.name })
    }

    @Test fun `an unknown message id sends nothing and says to search first`() = runBlocking {
        val r = tools.getValue("read_email").execute(args(tools.getValue("read_email"), "message_id" to "m42"), ctx)
        assertTrue(r is ToolResult.Error && r.text.contains("search_email"))
        assertTrue(requests.isEmpty())
    }

    @Test fun `a reply to an unknown message is an argument error, before any card`() = runBlocking {
        val draft = tools.getValue("draft_email")
        val e = try { draft.confirmation(args(draft, "reply_to_message_id" to "m9", "body" to "hi")); null } catch (x: com.edith.app.tools.framework.ArgumentError) { x }
        assertTrue(e is com.edith.app.tools.framework.ArgumentError)
    }
}
