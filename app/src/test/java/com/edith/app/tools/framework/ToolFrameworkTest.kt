package com.edith.app.tools.framework

import com.edith.app.chat.engine.PromptContext
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.ollama.ModelInfo
import com.edith.app.settings.Settings
import com.edith.app.testing.FakeClock
import com.edith.app.tools.context.CurrentTimeTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** C08 tests 6, 7, 10 (prompt), 13 and the tool JSON shape: the parts that don't need a model or a device. */
class ToolFrameworkTest {
    private fun tool(name: String, integration: String? = null, params: List<ToolParam> = emptyList()) = object : Tool {
        override val spec = ToolSpec(name, "Does $name.", params)
        override val statusLabel = "Working…"
        override val displayName = name
        override val integrationId = integration
        override suspend fun execute(args: ToolArgs, ctx: ToolContext) = ToolResult.Ok("ok")
    }
    private val withTools = ModelInfo("gemma4:12b-it-q8_0", setOf("completion", "tools"))
    private val withoutTools = ModelInfo("tiny", setOf("completion"))
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    // --- §1 tool JSON ----------------------------------------------------------------------------------------

    @Test fun `tool spec becomes Ollama's function JSON`() {
        val spec = ToolSpec("get_weather", "Current weather for a place.", listOf(
            ToolParam("location", ParamType.String, "City", required = true),
            ToolParam("when", ParamType.DateTime, "Time"),
            ToolParam("units", ParamType.String, "Units", enumValues = listOf("metric", "imperial")),
        ))
        val encoded = com.edith.app.ollama.OllamaDtos.json.encodeToString(com.edith.app.ollama.OllamaTool.serializer(), spec.toOllama())   // the encoder requests use
        assertEquals(
            json("""{"type":"function","function":{"name":"get_weather","description":"Current weather for a place.",
              "parameters":{"type":"object","properties":{
                "location":{"type":"string","description":"City"},
                "when":{"type":"string","description":"Time (ISO-8601 local time, e.g. 2026-10-30T12:00:00)"},
                "units":{"type":"string","description":"Units","enum":["metric","imperial"]}},
              "required":["location"]}}}"""),
            json(encoded),
        )
    }

    // --- §2 ToolArgs (test 7) --------------------------------------------------------------------------------

    private val spec = ToolSpec("t", "T.", listOf(
        ToolParam("location", ParamType.String, "", required = true, aliases = listOf("place")),
        ToolParam("days", ParamType.Integer, ""),
        ToolParam("detailed", ParamType.Boolean, ""),
        ToolParam("lat", ParamType.Number, ""),
        ToolParam("at", ParamType.DateTime, ""),
    ))

    @Test fun `arguments are coerced and keys repaired only as declared`() {
        val args = ToolArgs(json("""{"Place":"Boston","days":"5","detailed":"yes","lat":"42.36","at":"2026-10-30T12:00"}"""), spec)
        assertEquals("Boston", args.string("location"))   // alias, any case
        assertEquals(5, args.int("days"))
        assertEquals(true, args.bool("detailed"))
        assertEquals(42.36, args.double("lat")!!, 0.0001)
        val zone = kotlinx.datetime.TimeZone.of("America/Detroit")
        assertEquals(kotlin.time.Instant.parse("2026-10-30T16:00:00Z"), args.dateTime("at", zone))   // no offset → device zone
        assertEquals(kotlin.time.Instant.parse("2026-10-30T12:00:00Z"),
            ToolArgs(json("""{"location":"x","at":"2026-10-30T08:00:00-04:00"}"""), spec).dateTime("at", zone))
        assertEquals("Boston", ToolArgs(json("""{"LOCATION":"Boston"}"""), spec).string("location"))
    }

    @Test fun `missing optional is null, missing required and bad values are argument errors`() {
        val args = ToolArgs(json("""{"city":"Boston","days":"2.5","detailed":"maybe"}"""), spec)
        assertNull(args.double("lat"))
        expectArgumentError("location") { args.string("location") }   // "city" isn't a declared alias: not guessed
        expectArgumentError("days") { args.int("days") }
        expectArgumentError("detailed") { args.bool("detailed") }
    }

    private fun expectArgumentError(name: String, block: () -> Unit) {
        try { block(); fail("expected an ArgumentError for $name") } catch (e: ArgumentError) { assertEquals(name, e.argument) }
    }

    // --- §3 ToolRegistry (tests 6, 10, 13) -------------------------------------------------------------------

    @Test fun `names are repaired - prefix, case, dashes, one typo on long names`() {
        val weather = tool("get_weather")
        val time = tool("get_current_time")
        val reg = ToolRegistry(listOf(weather, time))
        val offered = reg.available(withTools)
        assertSame(weather, reg.resolve("get_weather", offered))
        assertSame(weather, reg.resolve("functions.get_weather", offered))
        assertSame(weather, reg.resolve("Get-Weather", offered))
        assertSame(weather, reg.resolve("get_wether", offered))           // one missing letter, name ≥ 8 chars
        assertNull(reg.resolve("get_wthr", offered))                      // two edits: not guessed
        assertNull(reg.resolve("search_web", offered))
        assertNull("short names get no typo repair", ToolRegistry(listOf(tool("time"))).let { it.resolve("tme", it.available(withTools)) })
    }

    @Test fun `only connected integrations' tools are offered, and none to a model without tools`() {
        val google = object : Integration {
            override val id = "google"
            override val status = MutableStateFlow(IntegrationStatus.NotConnected)
        }
        val reg = ToolRegistry(listOf(tool("get_current_time"), tool("list_calendar_events", integration = "google")), listOf(google))
        assertEquals(listOf("get_current_time"), reg.available(withTools).map { it.spec.name })
        google.status.value = IntegrationStatus.Connected
        assertEquals(listOf("get_current_time", "list_calendar_events"), reg.available(withTools).map { it.spec.name })
        assertTrue(reg.available(withoutTools).isEmpty())
        assertEquals("list_calendar_events", reg.displayName("list_calendar_events"))
        assertEquals("unknown_tool", reg.displayName("unknown_tool"))
    }

    // --- §4 prompt section (test 10) -------------------------------------------------------------------------

    @Test fun `tool guidance lists the offered tools, or says she can't look things up`() {
        val reg = ToolRegistry(listOf(CurrentTimeTool()))
        val section = ToolGuidanceSection { reg }
        val now = kotlin.time.Instant.parse("2026-10-31T16:00:00Z")
        assertEquals(
            "You can use these tools when they help: get_current_time (get the current date and time, here or in another time zone).\n" +
                ToolGuidanceSection.HOW_TO_USE,
            section.text(PromptContext(now, withTools)),
        )
        assertEquals(ToolGuidanceSection.NO_TOOLS, section.text(PromptContext(now, withoutTools)))
        assertNull("no tools registered: nothing", ToolGuidanceSection { ToolRegistry(emptyList()) }.text(PromptContext(now, withTools)))
    }

    // --- §7a get_current_time --------------------------------------------------------------------------------

    @Test fun `current time here, elsewhere, and an unknown zone`() = runBlocking {
        val ctx = ToolContext(FakeClock(Instant.parse("2026-10-29T18:30:00Z"), ZoneId.of("America/Detroit")), Settings(),
            AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined))
        val t = CurrentTimeTool()
        fun args(o: String) = ToolArgs(json(o), t.spec)
        assertEquals(ToolResult.Ok("Thursday, October 29, 2026, 2:30 PM, America/Detroit (UTC-4)"), t.execute(args("{}"), ctx))
        assertEquals(ToolResult.Ok("Friday, October 30, 2026, 12:00 AM, Asia/Kolkata (UTC+5:30)"), t.execute(args("""{"tz":"Asia/Kolkata"}"""), ctx))
        assertTrue(t.execute(args("""{"timezone":"Mars/Olympus"}"""), ctx) is ToolResult.Error)
    }
}
