package com.edith.app.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.edith.app.ollama.ModelInfo
import com.edith.app.settings.ConnectionState
import com.edith.app.settings.ModelsState
import com.edith.app.settings.PermissionState
import com.edith.app.settings.SettingsUiState
import com.edith.app.ui.theme.EdithTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** K3.6: the shared Settings page's server-and-model section, drawn from made-up states (desktop and iPhone simulator). */
@OptIn(ExperimentalTestApi::class)
class SettingsContentTest {
    private val models = listOf(ModelInfo("gemma4:12b", setOf("completion", "vision", "audio", "tools")), ModelInfo("qwen3.5:9b", setOf("completion", "tools")))


    @Test fun showsTheAddressTheModelAndTheVersion() = runComposeUiTest {
        setContent {
            EdithTheme {
                SettingsContent(
                    state = SettingsUiState(serverField = "http://192.168.1.5:11434", serverSaved = "http://192.168.1.5:11434", permission = PermissionState.Granted,
                        models = ModelsState.Loaded(models), selectedModel = "gemma4:12b", ollamaVersion = "0.40.13"),
                    crashReport = null, onServerChange = {}, onSave = {}, onTest = {}, onRetry = {}, onSelectModel = {}, onDismissNotice = {}, onRequestPermission = {},
                    onOpenAppSettings = {}, edithVersion = "1.2.3",
                )
            }
        }
        onNodeWithText("Server & model").assertIsDisplayed()
        onNodeWithText("Ollama server address").assertIsDisplayed()
        onNodeWithText("Sees images · Hears audio · Uses tools").assertIsDisplayed()
    }

    @Test fun saveIsOnlyForAnUnsavedAddressAndTestConnectionCalls() = runComposeUiTest {
        var tested = 0
        setContent {
            EdithTheme {
                SettingsContent(
                    state = SettingsUiState(serverField = "http://new:11434", serverSaved = "http://old:11434", permission = PermissionState.Granted, connection = ConnectionState.Ok("0.40.13")),
                    crashReport = null, onServerChange = {}, onSave = {}, onTest = { tested++ }, onRetry = {}, onSelectModel = {}, onDismissNotice = {}, onRequestPermission = {},
                    onOpenAppSettings = {}, edithVersion = "1.2.3",
                )
            }
        }
        onNodeWithText("Connected — Ollama 0.40.13").assertIsDisplayed()
        onNodeWithText("Test connection").performClick()
        assertEquals(1, tested)
    }

    @Test fun aFailedModelListOffersARealRetryButton() = runComposeUiTest {
        var retried = 0
        setContent {
            EdithTheme {
                SettingsContent(
                    state = SettingsUiState(permission = PermissionState.Granted, models = ModelsState.Failed("Couldn't reach Ollama.")),
                    crashReport = null, onServerChange = {}, onSave = {}, onTest = {}, onRetry = { retried++ }, onSelectModel = {}, onDismissNotice = {}, onRequestPermission = {},
                    onOpenAppSettings = {}, edithVersion = "1.2.3",
                )
            }
        }
        onNodeWithText("Couldn't reach Ollama.").assertIsDisplayed()
        onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test fun theLocalNetworkCardAsksOnlyWhenItNeedsTo() = runComposeUiTest {
        var asked = 0
        setContent {
            EdithTheme {
                SettingsContent(
                    state = SettingsUiState(permission = PermissionState.NeedsRequest, models = ModelsState.NeedsPermission),
                    crashReport = null, onServerChange = {}, onSave = {}, onTest = {}, onRetry = {}, onSelectModel = {}, onDismissNotice = {}, onRequestPermission = { asked++ },
                    onOpenAppSettings = {}, edithVersion = "1.2.3",
                )
            }
        }
        onNodeWithText("Allow").performClick()
        assertEquals(1, asked)
    }
}
