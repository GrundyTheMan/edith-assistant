package com.edith.app.tools.framework

import com.edith.app.chat.engine.ChatEngine
import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.chat.engine.ContextBuilder
import com.edith.app.chat.engine.EngineState
import com.edith.app.chat.engine.PromptBuilder
import com.edith.app.chat.engine.TurnEvent
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.Outcome
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.core.permissions.ToolPermissions
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.MessageSource
import com.edith.app.ollama.ChatCall
import com.edith.app.ollama.ChatEvent
import com.edith.app.ollama.ModelCatalog
import com.edith.app.ollama.ModelInfo
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaFunctionCall
import com.edith.app.ollama.OllamaMessage
import com.edith.app.ollama.OllamaModelTag
import com.edith.app.ollama.OllamaShow
import com.edith.app.ollama.OllamaToolCall
import com.edith.app.ollama.ReplyStats
import com.edith.app.settings.ModelSelection
import com.edith.app.testing.MemorySettings
import com.edith.app.testing.SyncList
import com.edith.app.testing.inMemoryTestDatabase
import com.edith.app.testing.TickingClock
import com.edith.app.tools.context.CurrentTimeTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertFalse
import com.edith.app.testing.assertNull
import com.edith.app.testing.assertTrue
import kotlin.test.Test


/**
 * C08 tests 1–5, 7–12: the tool loop against a scripted Ollama that records every request. On the device, so the
 * text-call parser runs on Android's own regex engine (a JVM-only test once missed a crash there; L25).
 */
class ToolTurnRunnerTest {

    /** Each chat request takes the next scripted reply: text, or tool calls (with optional text before them). */
    private class ScriptedToolOllama(vararg replies: Reply) : OllamaClient {
        sealed interface Reply
        data class Text(val text: String) : Reply
        data class Calls(val calls: List<Pair<String, String>>, val text: String = "") : Reply

        private val script = ArrayDeque(replies.toList())
        val requests: SyncList<ChatCall> = SyncList()
        val sentCalls = mutableListOf<OllamaMessage>()   // the assistant messages handed to the runner

        override fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent> = flow {
            requests += request
            when (val reply = script.removeFirstOrNull() ?: Text("Done.")) {
                is Text -> reply.text.split(" ").forEachIndexed { i, w -> emit(ChatEvent.Delta(if (i == 0) w else " $w")) }
                is Calls -> {
                    if (reply.text.isNotEmpty()) emit(ChatEvent.Delta(reply.text))
                    val message = OllamaMessage("assistant", reply.text, toolCalls = reply.calls.mapIndexed { i, (name, args) ->
                        OllamaToolCall("call_$i", OllamaFunctionCall(name, Json.parseToJsonElement(args).jsonObject, i))
                    })
                    sentCalls += message
                    emit(ChatEvent.ToolCalls(message))
                }
            }
            emit(ChatEvent.Done(ReplyStats(1, 1, 1)))
        }
        override suspend fun chat(baseUrl: String, request: ChatCall): Outcome<OllamaMessage> = Outcome.Success(OllamaMessage("assistant", "Title"))
        override suspend fun listModels(baseUrl: String) = Outcome.Success(listOf(OllamaModelTag("gemma4:12b-it-q8_0")))
        override suspend fun show(baseUrl: String, model: String) = Outcome.Success(OllamaShow(listOf("completion", "tools")))
        override suspend fun version(baseUrl: String) = Outcome.Success("0.40.13")
    }

    /** A tool that records its calls; can fail, be slow, or ask for confirmation. */
    private class RecordingTool(
        private val name: String,
        private val result: String = "result of $name",
        private val failWith: Exception? = null,
        private val delayMs: Long = 0,
        private val confirm: Boolean = false,
        private val needs: String? = null,
        params: List<ToolParam> = emptyList(),
    ) : Tool {
        val calls = mutableListOf<JsonObject>()
        override val spec = ToolSpec(name, "Does $name.", params)
        override val statusLabel = "Running $name…"
        override val displayName = name
        override suspend fun confirmation(args: ToolArgs) = if (confirm) ConfirmationRequest("Do $name?", listOf("What" to name)) else null
        override fun permission(args: ToolArgs) = needs?.let { PermissionNeed(it, "No $it.") }
        override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
            calls += args.raw
            args.string("location")   // reads a required argument when declared (missing → ArgumentError)
            delay(delayMs)
            failWith?.let { throw it }
            return ToolResult.Ok(result)
        }
    }

    private val model = ModelInfo("gemma4:12b-it-q8_0", setOf("completion", "tools"))
    private val settings = MemorySettings()
    private val clock = TickingClock()
    private val dispatchers = AppDispatchers(Dispatchers.Default, Dispatchers.Default, Dispatchers.Default)
    private val start = listOf(OllamaMessage("system", "S"), OllamaMessage("user", "Hi"))
    private val confirmations = Confirmations()

    private fun runner(ollama: OllamaClient, vararg tools: Tool, timeoutMs: Long = 2_000, permissions: ToolPermissions = ToolPermissions { true }) =
        ToolTurnRunner(ollama, ToolRegistry(tools.toList()), confirmations, settings, clock, dispatchers, toolTimeoutMs = timeoutMs, permissions = permissions)

    private fun finished(events: List<TurnEvent>) = events.filterIsInstance<TurnEvent.Finished>().single()
    private fun toolMessages(request: ChatCall) = request.messages.filter { it.role == "tool" }

    @Test fun t01_oneToolRound_assistantMessageGoesBackVerbatim_thenTheToolResult() = runBlocking {
        val ollama = ScriptedToolOllama(ScriptedToolOllama.Calls(listOf("get_x" to """{"a":1}"""), text = "Let me check."), ScriptedToolOllama.Text("It is noon."))
        val tool = RecordingTool("get_x", result = "noon")
        val events = runner(ollama, tool).run("http://x", model, start).toList()

        assertEquals(TurnEvent.Finished("It is noon.", listOf("get_x"), ReplyStats(1, 1, 1)), finished(events))
        assertTrue("text before the call is cleared from the screen", events.contains(TurnEvent.TextReset))
        assertEquals(listOf(Json.parseToJsonElement("""{"a":1}""")), tool.calls)
        assertEquals(2, ollama.requests.size)
        assertEquals(listOf("get_x"), ollama.requests[0].tools!!.map { it.function.name })
        val second = ollama.requests[1].messages
        assertEquals(start, second.take(2))
        assertEquals("assistant message exactly as received", ollama.sentCalls[0], second[2])
        assertEquals(OllamaMessage("tool", "noon", toolName = "get_x"), second[3])
        assertEquals(4, second.size)
    }

    @Test fun t02_twoToolsInOneRound_runInOrder() = runBlocking {
        val ollama = ScriptedToolOllama(ScriptedToolOllama.Calls(listOf("tool_a" to "{}", "tool_b" to "{}")), ScriptedToolOllama.Text("Both."))
        val a = RecordingTool("tool_a")
        val b = RecordingTool("tool_b")
        val done = finished(runner(ollama, a, b).run("http://x", model, start).toList())
        assertEquals(listOf("tool_a", "tool_b"), done.toolsUsed)
        assertEquals(listOf("tool_a", "tool_b"), toolMessages(ollama.requests[1]).map { it.toolName })
        assertEquals(listOf("result of tool_a", "result of tool_b"), toolMessages(ollama.requests[1]).map { it.content })
    }

    @Test fun t03_aSecondRoundAskingForAnotherToolIsFollowed() = runBlocking {   // v1 ignored this (L4)
        val ollama = ScriptedToolOllama(
            ScriptedToolOllama.Calls(listOf("tool_a" to "{}")), ScriptedToolOllama.Calls(listOf("tool_b" to "{}")), ScriptedToolOllama.Text("Done both."),
        )
        val a = RecordingTool("tool_a")
        val b = RecordingTool("tool_b")
        assertEquals("Done both.", finished(runner(ollama, a, b).run("http://x", model, start).toList()).text)
        assertEquals(1, a.calls.size)
        assertEquals(1, b.calls.size)
        assertEquals(3, ollama.requests.size)
        assertEquals(2, toolMessages(ollama.requests[2]).size)
    }

    @Test fun t04_afterFiveRounds_aFinalRequestWithoutTools() = runBlocking {
        // Five rounds of tool calls; the sixth request offers no tools, so the model answers.
        val ollama = ScriptedToolOllama(*Array(ToolTurnRunner.MAX_ROUNDS) { ScriptedToolOllama.Calls(listOf("tool_a" to "{}")) }, ScriptedToolOllama.Text("Here's what I found."))
        val a = RecordingTool("tool_a")
        val done = finished(runner(ollama, a).run("http://x", model, start).toList())
        assertEquals(ToolTurnRunner.MAX_ROUNDS, a.calls.size)
        assertEquals(ToolTurnRunner.MAX_ROUNDS + 1, ollama.requests.size)
        assertTrue(ollama.requests.take(ToolTurnRunner.MAX_ROUNDS).all { it.tools != null })
        assertNull("the final request offers no tools", ollama.requests.last().tools)
        assertEquals("Here's what I found.", done.text)
    }

    @Test fun t05_unknownTool_getsAnErrorAndTheLoopContinues() = runBlocking {
        val ollama = ScriptedToolOllama(ScriptedToolOllama.Calls(listOf("nope" to "{}")), ScriptedToolOllama.Text("Sorry."))
        val done = finished(runner(ollama, RecordingTool("tool_a")).run("http://x", model, start).toList())
        assertEquals("Sorry.", done.text)
        assertTrue(done.toolsUsed.isEmpty())
        val result = toolMessages(ollama.requests[1]).single()
        assertEquals("nope", result.toolName)
        assertEquals("Error: there's no tool called 'nope'. Available: tool_a.", result.content)
    }

    @Test fun t06_misspelledNamesAreRepaired() = runBlocking {
        val ollama = ScriptedToolOllama(ScriptedToolOllama.Calls(listOf("functions.Get-Weather" to "{}")), ScriptedToolOllama.Text("ok"))
        val weather = RecordingTool("get_weather")
        runner(ollama, weather).run("http://x", model, start).toList()
        assertEquals(1, weather.calls.size)
        assertEquals("get_weather", toolMessages(ollama.requests[1]).single().toolName)
    }

    @Test fun t07_aMissingRequiredArgumentIsAnErrorForTheModel() = runBlocking {
        val ollama = ScriptedToolOllama(ScriptedToolOllama.Calls(listOf("get_weather" to """{"city":"Boston"}""")), ScriptedToolOllama.Text("Which city?"))
        val weather = RecordingTool("get_weather", params = listOf(ToolParam("location", ParamType.String, "City", required = true)))
        runner(ollama, weather).run("http://x", model, start).toList()
        assertEquals("Error: missing location for get_weather.", toolMessages(ollama.requests[1]).single().content)
    }

    @Test fun t08_aFailingOrSlowToolBecomesAnErrorAndTheTurnFinishes() = runBlocking {
        val ollama = ScriptedToolOllama(ScriptedToolOllama.Calls(listOf("boom" to "{}", "slow" to "{}")), ScriptedToolOllama.Text("Couldn't."))
        val done = finished(runner(ollama, RecordingTool("boom", failWith = IllegalStateException("kaput")), RecordingTool("slow", delayMs = 5_000), timeoutMs = 200)
            .run("http://x", model, start).toList())
        assertEquals("Couldn't.", done.text)
        assertEquals(
            listOf("Error: boom failed (kaput).", "Error: slow took too long and was stopped."),
            toolMessages(ollama.requests[1]).map { it.content },
        )
    }

    @Test fun t09_confirmation_approveRuns_denyDoesNot_cancelWhileWaitingRunsNothing() = runBlocking {
        // Approve
        var ollama = ScriptedToolOllama(ScriptedToolOllama.Calls(listOf("add_event" to "{}")), ScriptedToolOllama.Text("Added."))
        var tool = RecordingTool("add_event", confirm = true)
        val seen = mutableListOf<ConfirmationRequest>()
        runner(ollama, tool).run("http://x", model, start).collect { e ->
            if (e is TurnEvent.NeedsConfirmation) { seen += e.request; confirmations.resolve(e.request.id, approved = true, by = ApprovalChannel.Tap) }
        }
        assertEquals(1, tool.calls.size)
        assertEquals("Do add_event?", seen.single().title)
        assertEquals("add_event", seen.single().toolName)
        assertTrue(seen.single().id.isNotEmpty())

        // Deny
        ollama = ScriptedToolOllama(ScriptedToolOllama.Calls(listOf("add_event" to "{}")), ScriptedToolOllama.Text("Okay, I won't."))
        tool = RecordingTool("add_event", confirm = true)
        runner(ollama, tool).run("http://x", model, start).collect { e ->
            if (e is TurnEvent.NeedsConfirmation) confirmations.resolve(e.request.id, approved = false, by = ApprovalChannel.Tap)
        }
        assertTrue(tool.calls.isEmpty())
        assertEquals("The user declined: Do add_event?. Don't retry unless they ask.", toolMessages(ollama.requests[1]).single().content)

        // Cancel while waiting
        ollama = ScriptedToolOllama(ScriptedToolOllama.Calls(listOf("add_event" to "{}")))
        tool = RecordingTool("add_event", confirm = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch { runner(ollama, tool).run("http://x", model, start).collect { } }
        withTimeout(5_000) { while (ollama.requests.isEmpty()) delay(10) }
        delay(200)
        job.cancel()
        job.join()
        assertTrue("nothing executed after cancelling", tool.calls.isEmpty())
        assertEquals(1, ollama.requests.size)
        scope.cancel()
    }

    @Test fun t13_permission_grantedRuns_askedThenAllowedRuns_deniedDoesNot_slowAnswerIsNotATimeout() = runBlocking {
        // Already granted: runs, nothing asked
        var granted = true
        val permissions = ToolPermissions { granted }
        var ollama = ScriptedToolOllama(ScriptedToolOllama.Calls(listOf("weather_here" to "{}")), ScriptedToolOllama.Text("Sunny."))
        var tool = RecordingTool("weather_here", needs = "LOCATION")
        var events = runner(ollama, tool, permissions = permissions).run("http://x", model, start).toList()
        assertEquals(1, tool.calls.size)
        assertFalse(events.contains(TurnEvent.Status(ToolTurnRunner.WAITING_FOR_PERMISSION)))

        // Not granted: asked; allowed after longer than the tool timeout (the dialog isn't timed) → runs
        granted = false
        ollama = ScriptedToolOllama(ScriptedToolOllama.Calls(listOf("weather_here" to "{}")), ScriptedToolOllama.Text("Sunny."))
        tool = RecordingTool("weather_here", needs = "LOCATION")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            val request = permissions.pending.first { it != null }!!
            assertEquals("LOCATION", request.permission)
            delay(700)
            permissions.resolve(request.id, granted = true)
        }
        events = runner(ollama, tool, timeoutMs = 500, permissions = permissions).run("http://x", model, start).toList()
        assertEquals(1, tool.calls.size)
        assertTrue(events.contains(TurnEvent.Status(ToolTurnRunner.WAITING_FOR_PERMISSION)))
        assertNull("the request is cleared", permissions.pending.value)

        // Denied → not run, the model gets the tool's message
        ollama = ScriptedToolOllama(ScriptedToolOllama.Calls(listOf("weather_here" to "{}")), ScriptedToolOllama.Text("Which city?"))
        tool = RecordingTool("weather_here", needs = "LOCATION")
        scope.launch { permissions.resolve(permissions.pending.first { it != null }!!.id, granted = false) }
        runner(ollama, tool, permissions = permissions).run("http://x", model, start).toList()
        assertTrue(tool.calls.isEmpty())
        assertEquals("No LOCATION.", toolMessages(ollama.requests[1]).single().content)

        // Stopping the reply while the dialog is up runs nothing and clears the request
        ollama = ScriptedToolOllama(ScriptedToolOllama.Calls(listOf("weather_here" to "{}")))
        tool = RecordingTool("weather_here", needs = "LOCATION")
        val job = scope.launch { runner(ollama, tool, permissions = permissions).run("http://x", model, start).collect { } }
        withTimeout(5_000) { permissions.pending.first { it != null } }
        job.cancel()
        job.join()
        assertTrue(tool.calls.isEmpty())
        assertNull(permissions.pending.value)
        scope.cancel()
    }

    @Test fun t10_aModelWithoutToolsIsOfferedNone() = runBlocking {
        val ollama = ScriptedToolOllama(ScriptedToolOllama.Text("I can't check that right now."))
        runner(ollama, RecordingTool("tool_a")).run("http://x", ModelInfo("tiny", setOf("completion")), start).toList()
        assertNull(ollama.requests.single().tools)
    }

    @Test fun t11_onlyTheUserMessageAndTheFinalReplyAreStored() = runBlocking {
        val db = inMemoryTestDatabase()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = ChatRepository(db, clock, dispatchers)
            val ollama = ScriptedToolOllama(
                ScriptedToolOllama.Calls(listOf("get_current_time" to "{}"), text = "<tool_call>junk</tool_call> Let me look."),
                ScriptedToolOllama.Text("It's Saturday afternoon."),
            )
            val engine = ChatEngine(
                repo, settings, ModelSelection(settings, ModelCatalog(ollama)), PromptBuilder(clock) { emptyList() }, ContextBuilder(),
                runner(ollama, CurrentTimeTool()), emptyList(), scope,
                object : LocalNetworkAccess { override fun isGranted() = true }, confirmations,
            )
            val cid = repo.createConversation()
            engine.send(cid, "What time is it?", MessageSource.Typed)
            withTimeout(10_000) { engine.state.first { it is EngineState.Idle || it is EngineState.Failed } }

            val rows = repo.recent(cid, 50).map { it.message }
            assertEquals(listOf("user" to "What time is it?", "assistant" to "It's Saturday afternoon."), rows.map { it.role.name.lowercase() to it.content })
            assertEquals(listOf("get_current_time"), rows.last().toolsUsed)
            assertFalse("no tool text stored", rows.any { "tool_call" in it.content || "Let me look" in it.content || "PM" in it.content })
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @Test fun t12_aToolCallWrittenAsTextIsParsedAndRun() = runBlocking {
        val ollama = ScriptedToolOllama(
            ScriptedToolOllama.Text("<tool_call>get_weather{location:<|\"|>Boston<|\"|>}</tool_call>"),
            ScriptedToolOllama.Text("Sunny in Boston."),
        )
        val weather = RecordingTool("get_weather", params = listOf(ToolParam("location", ParamType.String, "City", required = true)))
        val done = finished(runner(ollama, weather).run("http://x", model, start).toList())
        assertEquals("Sunny in Boston.", done.text)
        assertEquals(listOf(Json.parseToJsonElement("""{"location":"Boston"}""")), weather.calls)
        val resent = ollama.requests[1].messages[2]
        assertEquals("sent back as a real tool call", "get_weather", resent.toolCalls!!.single().function.name)
    }

    @Test fun t12b_textCallFormats_andPlainProseIsLeftAlone() {
        fun parse(t: String) = TextToolCallParser.extract(t).map { it.name to it.arguments.toString() }
        assertEquals(listOf("get_weather" to """{"location":"Boston"}"""), parse("<tool_call>get_weather{location:<|\"|>Boston<|\"|>}</tool_call>"))
        assertEquals(listOf("search_web" to """{"query":"Pistons"}"""), parse("""<tool_call>{"name":"search_web","arguments":{"query":"Pistons"}}</tool_call>"""))
        assertEquals(listOf("a" to "{}", "b" to """{"x":1}"""), parse("""[TOOL_CALLS][{"name":"a","arguments":{}},{"name":"b","arguments":{"x":1}}]"""))
        assertEquals(listOf("get_current_time" to "{}"), parse("""{"name":"get_current_time","parameters":{}}"""))
        assertEquals(listOf("t" to """{"k":"v"}"""), parse("""{"name":"t","arguments":"{\"k\":\"v\"}"}"""))
        assertTrue(parse("It's 72°F and sunny {mostly}. Enjoy!").isEmpty())
        assertTrue(parse("""{"weather":"sunny"}""").isEmpty())
        assertTrue(parse("").isEmpty())
    }
}
