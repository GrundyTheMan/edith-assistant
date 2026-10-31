package com.edith.app.ollama

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** C03 test 10. */
class ModelCatalogTest {
    private class FakeOllama(private val caps: Map<String, List<String>?>) : OllamaClient {
        val showCalls = mutableListOf<String>()
        override fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent> = emptyFlow()
        override suspend fun chat(baseUrl: String, request: ChatCall) = error("not used")
        override suspend fun version(baseUrl: String) = error("not used")
        override suspend fun listModels(baseUrl: String) = Outcome.Success(caps.keys.map { OllamaModelTag(it) })
        override suspend fun show(baseUrl: String, model: String): Outcome<OllamaShow> {
            showCalls += model
            return caps[model]?.let { Outcome.Success(OllamaShow(it)) } ?: Outcome.Failure(AppError.OllamaHttp(500, "boom"))
        }
    }

    private val fake = FakeOllama(
        mapOf(
            "gemma4:e4b" to listOf("completion", "vision", "audio", "tools"),
            "nomic-embed-text" to listOf("embedding"),
            "broken" to null,
        )
    )
    private val catalog = ModelCatalog(fake)

    @Test fun `hides embedding-only models and keeps one whose details failed`() = runTest {
        val models = (catalog.chatModels("http://pc:11434") as Outcome.Success).value
        assertEquals(listOf("gemma4:e4b", "broken"), models.map { it.name })
        val gemma = models.first()
        assertEquals(true, gemma.canUseTools && gemma.canSee && gemma.canHear)
        assertEquals(emptySet<String>(), models.last().capabilities)
    }

    @Test fun `caches capabilities per server and model`() = runTest {
        catalog.capabilities("http://pc:11434", "gemma4:e4b")
        catalog.capabilities("http://pc:11434", "gemma4:e4b")
        assertEquals(1, fake.showCalls.count { it == "gemma4:e4b" })

        catalog.invalidate()
        catalog.capabilities("http://pc:11434", "gemma4:e4b")
        assertEquals(2, fake.showCalls.count { it == "gemma4:e4b" })
    }
}
