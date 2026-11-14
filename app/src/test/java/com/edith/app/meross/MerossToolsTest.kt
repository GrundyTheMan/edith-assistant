package com.edith.app.meross

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.home.CommandSource
import com.edith.app.home.Home
import com.edith.app.home.HomeControlTool
import com.edith.app.home.HomeDevicesTool
import com.edith.app.home.MemoryCommandLog
import com.edith.app.home.MemoryHomePrefs
import com.edith.app.settings.Settings
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** C26 acceptance, end to end without a network: Edith's tools → Home → the Meross service → a command, and the card rules. */
class MerossToolsTest {
    private val cloud = FakeMerossCloud()
    private val bus = FakeMerossBus()
    private val clock = FakeClock(Instant.parse("2026-11-14T15:00:00Z"))
    private val service = MerossService(InMemorySecretStore(), cloud.api(clock), bus, clock)
    private val log = MemoryCommandLog()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() { scope.cancel(); cloud.shutdown() }

    private fun args(tool: Tool, vararg pairs: Pair<String, String>) =
        ToolArgs(buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }, tool.spec)

    private val ctx get() = ToolContext(clock, Settings(), AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined))

    @Test fun `opening the garage by name is a screen-only card, then goes to the opener and is logged`() = runBlocking {
        cloud.answers["/v1/Auth/signIn"] = { cloud.loginOk() }
        cloud.answers["/v1/Device/devList"] = { cloud.devList(cloud.device("u1", "Garage Door")) }
        bus.replies[Triple("u1", "GET", "Appliance.System.All")] = MerossReply.Ok(systemAll(Triple(0, 0, 1_794_000_000)))
        bus.replies[Triple("u1", "SET", "Appliance.GarageDoor.State")] = MerossReply.Ok(JsonObject(emptyMap()))
        service.signIn(mapOf("email" to "a@b.c", "password" to "pw"))
        val home = Home(MemoryHomePrefs(), log, scope, clock)
        home.register(service)
        withTimeout(3_000) { home.status.first { it == com.edith.app.tools.framework.IntegrationStatus.Connected } }

        val list = HomeDevicesTool(home) { clock.now().toEpochMilli() }
        val text = list.execute(args(list), ctx).text
        assertTrue(text, "Garage Door (Garage door): Closed, last moved" in text)

        val control = HomeControlTool(home)
        val open = args(control, "device" to "the garage", "action" to "open")
        val card = control.confirmation(open)!!
        assertTrue("never by voice alone (D10)", card.screenOnly)
        assertEquals("Open: Garage Door?", card.title)
        assertTrue("nothing is sent before the tap", bus.seen.none { it.method == "SET" })

        val result = control.execute(open, ctx)
        assertTrue(result.text, result is ToolResult.Ok && "Done: open" in result.text)
        assertEquals("""{"state":{"channel":0,"open":1,"uuid":"u1"}}""", bus.seen.last { it.method == "SET" }.payload.toString())
        val entry = log.all.single()
        assertEquals(listOf("Garage Door", "meross", "open", CommandSource.Chat, true), listOf(entry.deviceName, entry.backend, entry.action, entry.source, entry.ok))

        val close = args(control, "device" to "garage door", "action" to "close")
        assertTrue("closing: a card, but a voice may approve it", !control.confirmation(close)!!.screenOnly)
        assertNull("a light's action isn't a door's", control.confirmation(args(control, "device" to "garage door", "action" to "turn_on")))
    }

    @Test fun `a refused token shows as a problem line for the model, not as an empty home`() = runBlocking {
        cloud.answers["/v1/Auth/signIn"] = { cloud.loginOk() }
        service.signIn(mapOf("email" to "a@b.c", "password" to "pw"))
        cloud.answers["/v1/Device/devList"] = { cloud.status(1200) }
        val home = Home(MemoryHomePrefs(), log, scope, clock)
        home.register(service)
        withTimeout(3_000) { home.status.first { it == com.edith.app.tools.framework.IntegrationStatus.Connected } }
        val list = HomeDevicesTool(home)
        val text = list.execute(args(list), ctx).text
        assertTrue(text, "Meross: it needs signing in again — Settings → Home." in text)
    }
}
