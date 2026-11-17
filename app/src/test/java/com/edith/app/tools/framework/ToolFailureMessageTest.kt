package com.edith.app.tools.framework

import com.edith.app.chat.engine.TurnEvent
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
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
import com.edith.app.testing.FakeClock
import com.edith.app.testing.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * When a tool keeps failing and the model ends with nothing to say, the owner is told what failed, not just
 * "Edith didn't reply" (dev.16: the work-mail question failed five times in a row and that was all she said).
 */
class ToolFailureMessageTest {
    private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private val clock = FakeClock(Instant.parse("2026-11-17T12:00:00Z"), ZoneId.of("UTC"))

    private class FakeTool(private val answers: MutableList<ToolResult>) : Tool {
        override val spec = ToolSpec("search_email", "Search email")
        override val statusLabel = "Checking your email…"
        override val displayName = "email"
        override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult = if (answers.size > 1) answers.removeAt(0) else answers[0]
    }

    /** A model that calls the tool every round, and says [lastWords] when it is finally asked for an answer without tools. */
    private fun run(tool: Tool, callsBeforeAnswer: Int, lastWords: String): String = runBlocking {
        var round = 0
        val ollama = object : OllamaClient {
            override fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent> = flow {
                if (request.tools != null && round++ < callsBeforeAnswer) {
                    emit(ChatEvent.ToolCalls(OllamaMessage("assistant", "", toolCalls = listOf(OllamaToolCall(function = OllamaFunctionCall("search_email", JsonObject(emptyMap())))))))
                } else if (lastWords.isNotEmpty()) emit(ChatEvent.Delta(lastWords))
                emit(ChatEvent.Done(ReplyStats(1, 1, 1)))
            }
            override suspend fun chat(baseUrl: String, request: ChatCall) = Outcome.Failure(AppError.EmptyReply)
            override suspend fun listModels(baseUrl: String) = Outcome.Success(emptyList<OllamaModelTag>())
            override suspend fun show(baseUrl: String, model: String) = Outcome.Success(OllamaShow(listOf("tools")))
            override suspend fun version(baseUrl: String) = Outcome.Success("x")
        }
        val runner = ToolTurnRunner(ollama, ToolRegistry(listOf(tool), emptyList()), Confirmations(), FakeSettingsRepository(), clock, dispatchers)
        var finished = ""
        runner.run("http://ollama", ModelInfo("gemma4:12b", setOf("tools")), listOf(OllamaMessage("user", "any unread work email?"))).collect {
            if (it is TurnEvent.Finished) finished = it.text
        }
        finished
    }

    private val noAccount = ToolResult.Error("There's no account called 'Work (HomeGamingRig)'. The accounts are: a@gmail.com, Work (HomeGamingRig).")

    @Test fun `five failures in a row and then silence says what failed`() {
        val text = run(FakeTool(mutableListOf(noAccount)), callsBeforeAnswer = 99, lastWords = "")
        assertTrue(text, text.startsWith("I couldn't finish that: the email step failed."))
        assertTrue(text, text.contains("There's no account called 'Work (HomeGamingRig)'"))
    }

    @Test fun `a model that gives up with nothing right after a failure also says what failed`() {
        val text = run(FakeTool(mutableListOf(noAccount)), callsBeforeAnswer = 1, lastWords = "")
        assertTrue(text, text.contains("There's no account called"))
    }

    @Test fun `when the model does answer, its words are kept`() {
        assertEquals("You have no unread mail.", run(FakeTool(mutableListOf(noAccount)), callsBeforeAnswer = 99, lastWords = "You have no unread mail."))
    }

    @Test fun `a failure followed by a success is not reported`() {
        val tool = FakeTool(mutableListOf(noAccount, ToolResult.Ok("1. Mail")))
        assertEquals("", run(tool, callsBeforeAnswer = 2, lastWords = ""))   // a silent model after a success is still the old "didn't reply"
    }
}
