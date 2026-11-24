package com.edith.app.chat.engine

import com.edith.app.data.db.MessageEntity
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.MessageStatus
import com.edith.app.data.db.MessageWithAttachments
import com.edith.app.ollama.ModelInfo
import com.edith.app.core.time.Clock
import kotlinx.datetime.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.time.Instant

class PromptAndContextTest {
    private val model = ModelInfo("gemma4:12b-it-q8_0", setOf("completion", "tools"))
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-10-29T18:30:00Z")
        override fun zone() = TimeZone.of("America/Detroit")
    }

    // --- PromptBuilder ------------------------------------------------------------------------------------------

    @Test fun `system prompt is the persona verbatim a blank line then the date line`() {
        val prompt = PromptBuilder(clock) { listOf(DateTimeSection { "en-US" }) }.system(model)
        assertEquals(PERSONA + "\n\nCurrent date and time: Thursday, October 29, 2026, 2:30 PM (America/Detroit, UTC-04:00).", prompt)
        assertTrue(PERSONA.startsWith("You are Edith, a highly sophisticated, warm, and genuinely complimentary personal assistant."))
        assertTrue(PERSONA.endsWith("Let your warmth and wit come through in your word choice, not in extra length."))
        assertEquals(8, PERSONA.lines().size)
    }

    @Test fun `sections that return null or blank are left out`() {
        val prompt = PromptBuilder(clock) {
            listOf(PromptSection { null }, PromptSection { "Tools: weather." }, PromptSection { " " }, PromptSection { "Google: off." })
        }.system(model)
        assertEquals(PERSONA + "\n\nTools: weather.\nGoogle: off.", prompt)
        assertEquals(PERSONA, PromptBuilder(clock) { listOf(PromptSection { null }) }.system(model))
    }

    // --- ContextBuilder -----------------------------------------------------------------------------------------

    private var nextId = 1L
    private fun msg(role: MessageRole, text: String, status: MessageStatus = MessageStatus.Complete) = MessageWithAttachments(
        MessageEntity(id = nextId++, conversationId = 1, role = role, content = text, createdAt = nextId, status = status,
            source = MessageSource.Typed),
        emptyList(),
    )
    private fun user(text: String) = msg(MessageRole.User, text)
    private fun edith(text: String, status: MessageStatus = MessageStatus.Complete) = msg(MessageRole.Assistant, text, status)
    private fun build(vararg history: MessageWithAttachments) =
        ContextBuilder().build(history.toList(), "SYSTEM", model).map { it.role to it.content }

    @Test fun `system first then completed user and assistant messages in order`() {
        assertEquals(
            listOf("system" to "SYSTEM", "user" to "Hi", "assistant" to "Hello!", "user" to "Weather?"),
            build(user("Hi"), edith("Hello!"), user("Weather?")),
        )
    }

    @Test fun `failed and cancelled replies are never sent back to the model`() {
        assertEquals(
            listOf("system" to "SYSTEM", "user" to "Hi", "user" to "Again"),
            build(user("Hi"), edith("", MessageStatus.Failed), edith("Half a sen", MessageStatus.Cancelled), user("Again")),
        )
    }

    @Test fun `tool-call text is stripped unclosed too and empty results are dropped`() {
        assertEquals(
            listOf("system" to "SYSTEM", "user" to "Weather?", "assistant" to "Let me check.", "user" to "And?"),
            build(user("Weather?"), edith("Let me check.<tool_call>{\"name\":\"get_weather\"}</tool_call>"),
                edith("<tool_call>{\"name\":\"get_weather\""), user("And?")),
        )
    }

    @Test fun `a leading assistant message is dropped so the model sees a user turn first`() {
        // Newest first: Q3, Reply two, Q2 (tiny) + Reply one (~4,000 tokens) fit; the 30,000-char user message
        // (~8,572 tokens) doesn't, so the budget cut leaves "Reply one" first — and it must be dropped.
        val result = build(user("x".repeat(30_000)), edith("r".repeat(14_000)), user("Q2"), edith("Reply two"), user("Q3"))
        assertEquals(listOf("system", "user", "assistant", "user"), result.map { it.first })
        assertEquals(listOf("Q2", "Reply two", "Q3"), result.drop(1).map { it.second })
    }

    @Test fun `the token budget keeps the newest messages`() {
        val chunk = "y".repeat(7_000)   // 2,000 tokens each
        val history = (1..8).flatMap { listOf(user("u$it $chunk"), edith("a$it $chunk")) } + user("latest")
        val kept = ContextBuilder().build(history, "S", model).drop(1)
        val tokens = kept.sumOf { ContextBuilder.estimateTokens(it.content) }
        assertTrue(tokens <= ContextBuilder.TOKEN_BUDGET, "within budget: $tokens")
        assertEquals("latest", kept.last().content)
        assertTrue(kept.first().role == "user" && kept.first().content.startsWith("u"))
        assertTrue(kept.none { it.content.startsWith("u1 ") }, "older ones dropped")
    }

    @Test fun `a huge latest user message is kept with its middle truncated`() {
        val huge = "START" + "m".repeat(60_000) + "END"
        val kept = ContextBuilder().build(listOf(user("old"), edith("older reply"), user(huge)), "S", model).drop(1)
        assertEquals(1, kept.size)
        val text = kept.single().content
        assertTrue(text.startsWith("START") && text.endsWith("END") && "[…]" in text)
        assertTrue(ContextBuilder.estimateTokens(text) <= ContextBuilder.TOKEN_BUDGET)
    }
}
