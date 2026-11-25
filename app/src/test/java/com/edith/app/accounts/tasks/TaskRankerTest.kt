package com.edith.app.accounts.tasks

import com.edith.app.core.error.Outcome
import com.edith.app.ollama.ChatCall
import com.edith.app.ollama.OllamaMessage
import com.edith.app.testing.FakeClock
import com.edith.app.testing.FakeOllamaClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import java.time.ZoneId

/** C35 §4: the saved rankings and the ranker's model call (the pure rules are in the shared TaskRankingTest). */
class TaskRankerTest {
    @get:Rule val temp = TemporaryFolder()

    private val today = LocalDate(2026, 11, 13)
    private fun t(id: String, title: String, due: LocalDate? = null, notes: String? = null, parent: String? = null, done: Boolean = false) =
        TaskItem(id, "L1", title, notes, due, done, parent = parent, position = id)

    private val tasks = TaskOrder.sort(listOf(
        t("a", "Water the plants"),
        t("b", "Pay the electric bill", due = today.minus(2, DateTimeUnit.DAY)),
        t("c", "Pack for the trip", due = today.plus(1, DateTimeUnit.DAY), notes = "x".repeat(400)),
        t("c1", "Socks", parent = "c"),
        t("d", "Old thing", done = true),
        t("e", "Call Mom", due = today),
    ))
    private val items = TaskRanking.rankable(tasks)

    @Test fun `saved rankings - per slot, the 50 newest, kept on disk`() = runBlocking {
        val file = temp.newFile("task-ranks.json")
        val store = RankStore(file.path)
        val ranking = Ranking("m", listOf(RankedTask("a", 1, "x")), 1, 0)
        repeat(55) { store.put("acct|list$it|m", "k$it", ranking) }
        assertEquals(50, store.slots().size)
        assertEquals("the oldest went first", "acct|list5|m", store.slots().first())
        assertEquals(ranking, store.get("acct|list54|m", "k54"))
        assertNull("a stale key is no ranking", store.get("acct|list54|m", "other"))
        assertEquals("read back from the file", ranking, RankStore(file.path).get("acct|list54|m", "k54"))
        assertTrue("no temporary file left", temp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun `the ranker asks once, with the schema and temperature 0, and uses the saved ranking while the list is the same`() = runBlocking {
        val calls = mutableListOf<ChatCall>()
        var reply = """{"order":[{"n":2,"reason":"overdue"},{"n":1,"reason":"kept"},{"n":3,"reason":"soon"},{"n":4,"reason":"today"}]}"""
        val ollama = object : FakeOllamaClient() {
            override suspend fun chat(baseUrl: String, request: ChatCall): Outcome<OllamaMessage> { calls += request; return Outcome.Success(OllamaMessage("assistant", reply)) }
        }
        val clock = FakeClock(Instant.parse("2026-11-13T15:00:00Z"), ZoneId.of("America/Detroit"))
        val ranker = TaskRanker(ollama, RankStore(temp.newFile("r.json").path), clock)
        val r1 = ranker.rank("p1|L1", tasks, "http://ollama", "gemma4:12b") as TaskRanker.Result.Ranked
        assertEquals(listOf("b", "a", "c", "e"), r1.ranking.order.map { it.taskId })
        assertEquals(1, calls.size)
        assertEquals(TaskRanking.SCHEMA, calls[0].format)
        assertEquals(0.0, calls[0].temperature)
        assertEquals(TaskRanking.SYSTEM, calls[0].messages[0].content)
        val r2 = ranker.rank("p1|L1", tasks, "http://ollama", "gemma4:12b") as TaskRanker.Result.Ranked
        assertTrue(r2.cached)
        assertEquals("no second model call", 1, calls.size)
        reply = "sorry"
        val changed = tasks.map { if (it.id == "a") it.copy(title = "Water all the plants") else it }
        assertEquals(TaskRanker.Result.Unusable, ranker.rank("p1|L1", changed, "http://ollama", "gemma4:12b"))
        assertNull("nothing saved for an unusable reply", ranker.saved("p1|L1", changed, "gemma4:12b"))
    }
}
