package com.edith.app.ollama

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.AppErrorException
import com.edith.app.core.error.Outcome
import com.edith.app.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** C03 tests 1–9 (MockWebServer + the recorded fixtures from 03-ollama-reference). */
class OllamaClientTest {
    private val server = MockWebServer()
    private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.IO, Dispatchers.Default)
    private val callCancelled = AtomicBoolean(false)
    private val okHttp = OkHttpClient.Builder()
        .eventListener(object : EventListener() {
            override fun canceled(call: Call) = callCancelled.set(true)
        })
        .build()
    private val client = KtorOllamaClient(HttpClients.over(okHttp), OllamaDtos.json, dispatchers)
    private val baseUrl get() = server.url("/").toString()

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("ollama/$name")!!.readText()
    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
    private fun requestJson(): JsonObject = OllamaDtos.json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
    private val weatherTool = OllamaTool(function = OllamaFunctionSpec("get_weather", "Get the weather", JsonObject(emptyMap())))
    private fun chat(model: String = "gemma4:e4b", tools: List<OllamaTool>? = null) =
        ChatCall(model, listOf(OllamaMessage("user", "What's the weather in \"Boston\"?\nThanks")), tools)

    @Test fun `1 chat request carries think false, num_ctx, stream and tools, no keep_alive`() = runTest {
        server.enqueue(ok(fixture("text_stream.ndjson")))
        client.chatStream(baseUrl, chat(tools = listOf(weatherTool))).toList()

        val body = requestJson()
        assertEquals(JsonPrimitive(false), body["think"])
        assertEquals(16384, body["options"]!!.jsonObject["num_ctx"]!!.jsonPrimitive.int)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertEquals("get_weather", body["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertFalse("keep_alive must not be sent by default", "keep_alive" in body)
        // Quotes and newlines in messages survive (the request is real JSON, not hand-built text).
        assertEquals("What's the weather in \"Boston\"?\nThanks",
            body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun `2 gpt-oss asks for think low`() = runTest {
        server.enqueue(ok("""{"model":"gpt-oss:20b","message":{"role":"assistant","content":"hi"},"done":true}"""))
        client.chat(baseUrl, chat(model = "gpt-oss:20b"))
        val body = requestJson()
        assertEquals(JsonPrimitive("low"), body["think"])
        assertFalse(body["stream"]!!.jsonPrimitive.boolean)
    }

    @Test fun `3 text stream emits deltas then Done`() = runTest {
        server.enqueue(ok(fixture("text_stream.ndjson")))
        val events = client.chatStream(baseUrl, chat()).toList()

        val deltas = events.filterIsInstance<ChatEvent.Delta>()
        assertEquals("The", deltas.first().text)
        assertEquals(" weather", deltas[1].text)
        val expected = fixture("text_stream.ndjson").lines().filter { it.isNotBlank() }
            .mapNotNull { OllamaDtos.json.decodeFromString(OllamaChatChunk.serializer(), it).message?.content }.joinToString("")
        assertEquals(expected, deltas.joinToString("") { it.text })
        assertTrue(events.last() is ChatEvent.Done)
        assertTrue(events.none { it is ChatEvent.ToolCalls })
    }

    @Test fun `4 tool-call stream emits ToolCalls before Done`() = runTest {
        server.enqueue(ok(fixture("tool_call_stream.ndjson")))
        val events = client.chatStream(baseUrl, chat(tools = listOf(weatherTool))).toList()

        val toolCalls = events.filterIsInstance<ChatEvent.ToolCalls>().single().assistantMessage
        assertEquals("assistant", toolCalls.role)
        val call = toolCalls.toolCalls!!.single().function
        assertEquals("get_weather", call.name)
        assertEquals("Boston", call.arguments["location"]!!.jsonPrimitive.content)
        assertTrue(events.indexOfFirst { it is ChatEvent.ToolCalls } < events.indexOfFirst { it is ChatEvent.Done })
    }

    @Test fun `5 non-stream tool call parses function and argument objects`() = runTest {
        server.enqueue(ok(fixture("tool_call_nonstream.json")))
        val reply = (client.chat(baseUrl, chat()) as Outcome.Success).value
        assertEquals("get_weather", reply.toolCalls!!.single().function.name)

        server.enqueue(ok("""{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"plan",
            "arguments":{"title":"Lunch","minutes":45,"all_day":false,"where":{"city":"Boston","zip":"02101"}}}}]},"done":true}"""))
        val args = (client.chat(baseUrl, chat()) as Outcome.Success).value.toolCalls!!.single().function.arguments
        assertEquals("Lunch", args["title"]!!.jsonPrimitive.content)
        assertEquals(45, args["minutes"]!!.jsonPrimitive.int)
        assertFalse(args["all_day"]!!.jsonPrimitive.boolean)
        assertEquals("Boston", args["where"]!!.jsonObject["city"]!!.jsonPrimitive.content)
    }

    @Test fun `6 model not found maps to ModelNotFound`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body(fixture("error_model_not_found.json")).build())
        val result = client.chat(baseUrl, chat(model = "no-such-model"))
        assertEquals(Outcome.Failure(AppError.ModelNotFound("no-such-model")), result)
    }

    @Test fun `7 connection refused maps to ServerUnreachable`() = runTest {
        val url = baseUrl
        server.close()
        val result = client.listModels(url)
        assertTrue("got $result", result is Outcome.Failure && result.error is AppError.ServerUnreachable)
    }

    @Test fun `8 malformed NDJSON line maps to MalformedResponse`() = runTest {
        server.enqueue(ok("""{"message":{"role":"assistant","content":"Hi"},"done":false}""" + "\n{this is not json\n"))
        try {
            client.chatStream(baseUrl, chat()).toList()
            fail("Expected MalformedResponse")
        } catch (e: AppErrorException) {
            assertTrue("got ${e.error}", e.error is AppError.MalformedResponse)
        }
    }

    @Test fun `9 cancelling the stream cancels the HTTP call`() = runTest {
        val lines = (1..200).joinToString("\n") { """{"message":{"role":"assistant","content":"word$it "},"done":false}""" }
        server.enqueue(MockResponse.Builder().code(200).body(lines).throttleBody(256, 100, TimeUnit.MILLISECONDS).build())

        val first = client.chatStream(baseUrl, chat()).first()   // first() cancels the flow after one event

        assertEquals(ChatEvent.Delta("word1 "), first)
        assertTrue("OkHttp call should be cancelled so Ollama stops generating", callCancelled.get())
    }
}
