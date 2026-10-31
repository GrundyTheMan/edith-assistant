package com.edith.app.ollama

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edith.app.EdithApp
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C03 acceptance: the real client against the owner's real Ollama, from the emulator. Opt-in, because it depends
 * on a running server, installed models and model behaviour (02-standards §3), so CI and check.sh skip it:
 *
 *   adb shell am instrument -w -e live true -e class com.edith.app.ollama.OllamaLiveCheckTest \
 *       com.edith.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results are logged under Edith/Ollama: `adb logcat -d -s Edith/Ollama:*`.
 */
@RunWith(AndroidJUnit4::class)
class OllamaLiveCheckTest {
    private val baseUrl = InstrumentationRegistry.getArguments().getString("url") ?: "http://10.0.2.2:11434"
    private val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EdithApp).container
    private val chatModel = InstrumentationRegistry.getArguments().getString("model") ?: "gemma4:e4b-it-q8_0"

    @Before fun onlyWhenAsked() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("live checks run only with -e live true", args.getString("live") == "true")
        // Android 17 blocks LAN access without this runtime permission (04-lessons L23). `-e grant false` skips the
        // grant, to show what happens without it.
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (args.getString("grant") != "false") ui.grantRuntimePermission("com.edith.app", LOCAL_NETWORK)
        else ui.revokeRuntimePermission("com.edith.app", LOCAL_NETWORK)

        val version = runBlocking { container.ollama.version(baseUrl) }
        Log.i(LogTag.Ollama, "LIVE version check at $baseUrl: $version")
        assertTrue("Ollama not reachable at $baseUrl: $version", version is Outcome.Success)
    }

    private companion object {
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }

    @Test fun listsRealModelsWithCapabilities() = runBlocking {
        val models = (container.modelCatalog.chatModels(baseUrl) as Outcome.Success).value
        models.forEach { Log.i(LogTag.Ollama, "LIVE model ${it.name}: ${it.capabilities.sorted().joinToString()}") }

        assertTrue("no chat models", models.isNotEmpty())
        assertFalse("embedding-only models must be hidden", models.any { "embed" in it.name })
        val gemma = models.firstOrNull { it.name == chatModel }
        if (gemma != null) assertTrue("$chatModel should use tools", gemma.canUseTools)
    }

    @Test fun streamsARealReply() = runBlocking {
        val events = container.ollama.chatStream(
            baseUrl, ChatCall(chatModel, listOf(OllamaMessage("user", "Reply with exactly one word: hello"))),
        ).toList()
        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        val done = events.last() as ChatEvent.Done
        Log.i(LogTag.Ollama, "LIVE reply from $chatModel: \"$text\" (${events.size} events, ${done.stats.totalMs} ms)")
        assertTrue("empty reply", text.isNotBlank())
    }

    @Test fun makesARealToolCall() = runBlocking {
        val weather = OllamaTool(
            function = OllamaFunctionSpec(
                name = "get_weather",
                description = "Get the current weather for a place",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("location") { put("type", "string"); put("description", "City name") }
                    }
                    putJsonArray("required") { add("location") }
                },
            ),
        )
        val events = container.ollama.chatStream(
            baseUrl, ChatCall(chatModel, listOf(OllamaMessage("user", "What's the weather in Boston?")), listOf(weather)),
        ).toList()
        val call = events.filterIsInstance<ChatEvent.ToolCalls>().singleOrNull()?.assistantMessage?.toolCalls?.firstOrNull()
        Log.i(LogTag.Ollama, "LIVE tool call from $chatModel: ${call?.function?.name}(${call?.function?.arguments})")
        assertEquals("get_weather", call?.function?.name)
        assertTrue((call!!.function.arguments as JsonObject)["location"]!!.jsonPrimitive.content.contains("Boston"))
    }
}
