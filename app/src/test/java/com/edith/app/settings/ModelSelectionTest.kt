package com.edith.app.settings

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.ollama.ModelCatalog
import com.edith.app.ollama.ModelInfo
import com.edith.app.testing.FakeOllamaClient
import com.edith.app.testing.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSelectionTest {
    private fun info(name: String, vararg caps: String) = ModelInfo(name, setOf("completion", *caps))

    @Test fun `prefers gemma4 12b, then any gemma4, then tools, then the first`() {
        val tools = info("qwen3.5:9b", "tools")
        val plain = info("ornith:9b")
        assertEquals("gemma4:12b-it-q8_0", ModelSelection.chooseDefault(listOf(plain, info("gemma4:e4b"), info("gemma4:12b-it-q8_0")))?.name)
        assertEquals("gemma4:e4b", ModelSelection.chooseDefault(listOf(plain, tools, info("gemma4:e4b")))?.name)
        assertEquals("qwen3.5:9b", ModelSelection.chooseDefault(listOf(plain, tools))?.name)
        assertEquals("ornith:9b", ModelSelection.chooseDefault(listOf(plain))?.name)
        assertNull(ModelSelection.chooseDefault(emptyList()))
    }

    @Test fun `missing saved model is replaced, saved, and announced once`() = runTest {
        val settings = FakeSettingsRepository(Settings(model = "gemma4:12b"))   // old name, no longer installed (L21)
        val selection = ModelSelection(settings, ModelCatalog(FakeOllamaClient()))

        val chosen = (selection.ensureModel() as Outcome.Success).value

        assertEquals("gemma4:12b-it-q8_0", chosen.name)
        assertEquals("gemma4:12b-it-q8_0", settings.current().model)
        assertEquals("Your model gemma4:12b is no longer installed; switched to gemma4:12b-it-q8_0.", selection.takeNotice())
        assertNull("the notice is shown once", selection.takeNotice())
    }

    @Test fun `no saved model picks a default silently`() = runTest {
        val settings = FakeSettingsRepository()
        val selection = ModelSelection(settings, ModelCatalog(FakeOllamaClient()))
        assertEquals("gemma4:12b-it-q8_0", (selection.ensureModel() as Outcome.Success).value.name)
        assertNull(selection.takeNotice())
    }

    @Test fun `installed saved model is kept`() = runTest {
        val settings = FakeSettingsRepository(Settings(model = "qwen3.5:9b-q8_0"))
        assertEquals("qwen3.5:9b-q8_0", (ModelSelection(settings, ModelCatalog(FakeOllamaClient())).ensureModel() as Outcome.Success).value.name)
    }

    @Test fun `no chat models is an error, and an unreachable server stays that error`() = runTest {
        val onlyEmbedding = FakeOllamaClient(models = mapOf("nomic-embed-text:latest" to listOf("embedding")))
        assertEquals(Outcome.Failure(AppError.NoChatModels), ModelSelection(FakeSettingsRepository(), ModelCatalog(onlyEmbedding)).ensureModel())

        val down = FakeOllamaClient(failure = AppError.Timeout)
        assertEquals(Outcome.Failure(AppError.Timeout), ModelSelection(FakeSettingsRepository(), ModelCatalog(down)).ensureModel())
    }
}
