package com.edith.app.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.ollama.ChatCall
import com.edith.app.ollama.ChatEvent
import com.edith.app.ollama.ModelCatalog
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaMessage
import com.edith.app.ollama.OllamaModelTag
import com.edith.app.ollama.OllamaShow
import com.edith.app.settings.ModelSelection
import com.edith.app.settings.Settings
import com.edith.app.settings.SettingsRepository
import com.edith.app.settings.SettingsViewModel
import com.edith.app.ui.theme.EdithTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C04: picking a model in the real Settings screen updates the selection and persists it. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val models = mapOf(
        "gemma4:12b-it-q8_0" to listOf("completion", "vision", "audio", "tools"),
        "qwen3.5:9b-q8_0" to listOf("completion", "tools"),
        "nomic-embed-text:latest" to listOf("embedding"),
    )

    private val client = object : OllamaClient {
        override fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent> = emptyFlow()
        override suspend fun chat(baseUrl: String, request: ChatCall): Outcome<OllamaMessage> = error("unused")
        override suspend fun listModels(baseUrl: String) = Outcome.Success(models.keys.map { OllamaModelTag(it) })
        override suspend fun show(baseUrl: String, model: String): Outcome<OllamaShow> =
            models[model]?.let { Outcome.Success(OllamaShow(it)) } ?: Outcome.Failure(AppError.ModelNotFound(model))
        override suspend fun version(baseUrl: String) = Outcome.Success("0.40.13")
    }

    private val settings = object : SettingsRepository {
        val state = MutableStateFlow(Settings())
        override val settings: Flow<Settings> = state
        override suspend fun current() = state.value
        override suspend fun update(transform: (Settings) -> Settings) { state.value = transform(state.value) }
    }

    @Test
    fun pickingAModelUpdatesTheSelectionAndPersists() {
        val catalog = ModelCatalog(client)
        val vm = SettingsViewModel(settings, catalog, client, ModelSelection(settings, catalog), object : LocalNetworkAccess {
            override fun isGranted() = true
        })
        compose.setContent { EdithTheme { SettingsScreen(vm = vm) } }

        compose.waitUntil(5_000) { runBlocking { settings.current().model } == "gemma4:12b-it-q8_0" }   // default chosen
        compose.onNodeWithText("gemma4:12b-it-q8_0").assertExists()
        compose.onNodeWithText("Sees images · Hears audio · Uses tools").assertExists()

        compose.onNodeWithText("gemma4:12b-it-q8_0").performClick()       // open the dropdown
        compose.onNodeWithText("nomic-embed-text:latest").assertDoesNotExist()
        compose.onNodeWithText("qwen3.5:9b-q8_0").performClick()

        compose.waitUntil(5_000) { runBlocking { settings.current().model } == "qwen3.5:9b-q8_0" }
        assertEquals("qwen3.5:9b-q8_0", vm.state.value.selectedModel)
        compose.onNodeWithText("Uses tools").assertExists()
    }
}
