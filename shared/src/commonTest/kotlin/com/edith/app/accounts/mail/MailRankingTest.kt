package com.edith.app.accounts.mail

import com.edith.app.accounts.sources.MailSummary
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.ollama.ChatCall
import com.edith.app.ollama.ChatEvent
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaMessage
import com.edith.app.ollama.OllamaModelTag
import com.edith.app.ollama.OllamaShow
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertNull
import com.edith.app.testing.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Instant

/** K7.1: ranking the inbox, ported from Jarvis's rank.ts and heuristics.ts; the pure part and the ranker over a scripted model. */
class MailRankingTest {
    private val now = Instant.parse("2026-11-27T12:00:00Z")
    private val own = "me@example.com"

    private fun mail(id: String, from: String = "Pat <pat@example.org>", subject: String = "Hello", to: String? = own, unread: Boolean = true, ageHours: Int = 2, snippet: String = "") =
        MailSummary(id, "t$id", from, to, subject, now - kotlin.time.Duration.parse("${ageHours}h"), snippet, null, unread, accountId = "a1")

    @Test fun `names and addresses are read from the from line`() {
        assertEquals("pat@example.org", MailRanking.addressOf("Pat <pat@example.org>"))
        assertEquals("pat@example.org", MailRanking.addressOf("pat@example.org"))
        assertEquals("Pat", MailRanking.nameOf("\"Pat\" <pat@example.org>"))
        assertEquals("pat@example.org", MailRanking.nameOf("<pat@example.org>"))
    }

    @Test fun `an automated sender is told by its address`() {
        assertTrue(MailRanking.automated("Shop <no-reply@shop.example>"))
        assertTrue(MailRanking.automated("news@example.org"))
        assertTrue(!MailRanking.automated("Pat <pat@example.org>"))
    }

    @Test fun `a new unread email to you with a question outscores an old automated one`() {
        val real = MailRanking.heuristicScore(mail("1", subject = "Can you review this today?"), own, now)
        val bulk = MailRanking.heuristicScore(mail("2", from = "Shop <no-reply@shop.example>", to = "list@shop.example", ageHours = 24 * 12), own, now)
        assertTrue(real > bulk)
        assertTrue(MailRanking.heuristicToTen(real) > MailRanking.heuristicToTen(bulk))
    }

    @Test fun `the scale is kept between 1 and 10`() {
        assertEquals(1, MailRanking.heuristicToTen(-50.0))
        assertEquals(10, MailRanking.heuristicToTen(50.0))
    }

    @Test fun `an estimate says it is one and why`() {
        val e = MailRanking.estimate(mail("1"), own, now)
        assertTrue(e.estimated)
        assertTrue("unread" in e.reason && "directly" in e.reason)
    }

    @Test fun `the prompt numbers the batch from 1 and marks what is sent to you`() {
        val p = MailRanking.prompt(listOf(mail("1"), mail("2", to = "list@x.example")), { own })
        assertTrue("\"n\":1" in p && "\"n\":2" in p)
        assertTrue("\"sent_to_me\":\"directly\"" in p && "\"sent_to_me\":\"list or bcc\"" in p)
    }

    @Test fun `a reply is read by position and scores are rounded and kept in range`() {
        val r = MailRanking.parse("""{"ratings":[{"n":1,"score":9,"reason":"asks you a question"},{"n":2,"score":14,"reason":"x"},{"n":3,"score":4.6,"reason":"too high n"},{"n":0,"score":5,"reason":"out"}]}""", 2, "m")!!
        assertEquals(setOf(0, 1), r.keys)
        assertEquals(9, r.getValue(0).score)
        assertEquals(10, r.getValue(1).score)
        assertEquals("asks you a question", r.getValue(0).reason)
        assertEquals("m", r.getValue(0).model)
    }

    @Test fun `a reply that is not usable gives nothing`() {
        assertNull(MailRanking.parse("not json", 3, "m"))
        assertNull(MailRanking.parse("""{"other":1}""", 3, "m"))
        assertNull(MailRanking.parse("""{"ratings":[]}""", 3, "m"))
    }

    private class Scripted(val replies: MutableList<Outcome<OllamaMessage>>) : OllamaClient {
        var calls = 0
        val prompts = mutableListOf<String>()
        override fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent> = emptyFlow()
        override suspend fun chat(baseUrl: String, request: ChatCall): Outcome<OllamaMessage> {
            calls++
            prompts += request.messages.last().content
            return replies.removeAt(0)
        }
        override suspend fun listModels(baseUrl: String): Outcome<List<OllamaModelTag>> = error("not used")
        override suspend fun show(baseUrl: String, model: String): Outcome<OllamaShow> = error("not used")
        override suspend fun version(baseUrl: String): Outcome<String> = error("not used")
    }

    private fun ok(json: String): Outcome<OllamaMessage> = Outcome.Success(OllamaMessage("assistant", json))

    @Test fun `the ranker rates in batches and reports each and does not ask again for a rated email`() = runTest {
        val twelve = (1..12).map { mail("m$it") }
        val ollama = Scripted(mutableListOf(
            ok("""{"ratings":[${(1..10).joinToString(",") { """{"n":$it,"score":7,"reason":"r$it"}""" }}]}"""),
            ok("""{"ratings":[{"n":1,"score":3,"reason":"late"},{"n":2,"score":2,"reason":"later"}]}"""),
        ))
        val ranker = MailRanker(ollama, MailRatingStore("build/mail-ratings-test-1.json"))
        val seen = mutableListOf<Int>()
        val failure = ranker.rate(twelve, { own }, "http://x", "model", { seen += it.size })
        assertNull(failure)
        assertEquals(listOf(10, 2), seen)
        assertEquals(2, ollama.calls)
        assertEquals(12, ranker.saved(twelve, "model").size)
        // Rated: no second round; another model: rated again.
        assertNull(ranker.rate(twelve, { own }, "http://x", "model", {}))
        assertEquals(2, ollama.calls)
        assertEquals(0, ranker.saved(twelve, "other").size)
    }

    @Test fun `an unusable batch stays estimated and the next one is still tried`() = runTest {
        val twenty = (1..20).map { mail("n$it") }
        val ollama = Scripted(mutableListOf(
            ok("nonsense"),
            ok("""{"ratings":[{"n":1,"score":8,"reason":"waiting on you"}]}"""),
        ))
        val ranker = MailRanker(ollama, MailRatingStore("build/mail-ratings-test-2.json"))
        assertNull(ranker.rate(twenty, { own }, "http://x", "model", {}))
        assertEquals(1, ranker.saved(twenty, "model").size)
    }

    @Test fun `a model that cannot be reached is reported and stops the run`() = runTest {
        val ollama = Scripted(mutableListOf(Outcome.Failure(AppError.Timeout)))
        val ranker = MailRanker(ollama, MailRatingStore("build/mail-ratings-test-3.json"))
        assertEquals(AppError.Timeout, ranker.rate((1..20).map { mail("o$it") }, { own }, "http://x", "model", {}))
        assertEquals(1, ollama.calls)
    }
}
