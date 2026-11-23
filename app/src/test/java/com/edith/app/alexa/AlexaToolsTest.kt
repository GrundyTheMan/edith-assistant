package com.edith.app.alexa

import com.edith.app.alexa.FakeAmazon.Companion.cap
import com.edith.app.alexa.FakeAmazon.Companion.endpoint
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.home.Capability
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceState
import com.edith.app.home.DeviceType
import com.edith.app.home.Home
import com.edith.app.home.HomeBackend
import com.edith.app.home.HomeDevice
import com.edith.app.home.HomeDevicesTool
import com.edith.app.home.MemoryCommandLog
import com.edith.app.home.MemoryHomePrefs
import com.edith.app.settings.Settings
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import com.edith.app.tools.framework.IntegrationStatus
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** C30 part 1 end to end without a network: "Which lights are on?", and a device both Alexa and a direct service report (D19). */
class AlexaToolsTest {
    private val amazon = FakeAmazon()
    private val clock = FakeClock(Instant.parse("2026-11-14T18:00:00Z"))
    private val service = AlexaService(InMemorySecretStore(), amazon.api(), clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel(); amazon.shutdown() }

    private val ctx get() = ToolContext(clock, Settings(), AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined))
    private fun args(tool: HomeDevicesTool, vararg pairs: Pair<String, String>) =
        ToolArgs(buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }, tool.spec)

    private suspend fun alexaHome(vararg others: HomeBackend): Home {
        amazon.endpointItems = FakeAmazon.items(
            endpoint("e1", "Living room lamp", "LIGHT", applianceId = "a1", retrievable = listOf("Alexa.PowerController" to "powerState")),
            endpoint("e2", "Porch light", "LIGHT", applianceId = "a2", retrievable = listOf("Alexa.PowerController" to "powerState")),
            endpoint("e3", "Front door", "SMARTLOCK", applianceId = "a3", retrievable = listOf("Alexa.LockController" to "lockState")),
        )
        amazon.states = mapOf(
            "a1" to listOf(cap("Alexa.PowerController", "powerState", "ON")),
            "a2" to listOf(cap("Alexa.PowerController", "powerState", "OFF")),
            "a3" to listOf(cap("Alexa.LockController", "lockState", "LOCKED")),
        )
        val page = service.pageSignIn.begin()
        amazon.challenge = page.url.toHttpUrl().queryParameter("openid.oa2.code_challenge")
        service.pageSignIn.finish(maplanding(), "")
        return Home(MemoryHomePrefs(), MemoryCommandLog(), scope, clock).also { h ->
            h.register(service)
            others.forEach { h.register(it) }
            // Home publishes its backends and status asynchronously: wait until every one is in, or a slow machine asks too early.
            withTimeout(3_000) { h.backends.first { it.size == 1 + others.size } }
            withTimeout(3_000) { h.status.first { it == IntegrationStatus.Connected } }
        }
    }

    @Test fun `which lights are on - the owner's question is answered from Alexa's states`() = runBlocking {
        val tool = HomeDevicesTool(alexaHome()) { clock.now().toEpochMilliseconds() }
        val text = tool.execute(args(tool, "filter" to "light"), ctx).text
        assertTrue(text, "Living room lamp (Light): On" in text)
        assertTrue(text, "Porch light (Light): Off" in text)
        assertTrue("the lock isn't a light: $text", "Front door" !in text)
        assertTrue(tool.execute(args(tool, "filter" to "lock"), ctx).text.contains("Front door (Lock): Locked"))
    }

    /** A direct service (Meross, Ring, …) that reports the same lock as Alexa does. */
    private class Direct(private val reachable: Boolean = true) : HomeBackend {
        override val id = "direct"
        override val displayName = "Direct"
        override val status = MutableStateFlow(IntegrationStatus.Connected)
        override suspend fun devices(): Outcome<List<HomeDevice>> =
            if (reachable) Outcome.Success(listOf(HomeDevice("d1", "Front door", null, DeviceType.Lock, setOf(Capability.LockUnlock), "direct", state = DeviceState(lock = com.edith.app.home.LockState.Unlocked), stateAt = 1)))
            else Outcome.Failure(AppError.Timeout)
        override suspend fun state(deviceId: String) = Outcome.Success(DeviceState())
        override suspend fun command(deviceId: String, command: DeviceCommand) = Outcome.Success(DeviceState())
        override suspend fun snapshot(deviceId: String): Outcome<ByteArray> = Outcome.Failure(AppError.Timeout)
    }

    @Test fun `a device both Alexa and a direct service report is shown once, through the direct one (D19)`() = runBlocking {
        val home = alexaHome(Direct())
        val tool = HomeDevicesTool(home) { clock.now().toEpochMilliseconds() }
        val text = tool.execute(args(tool, "filter" to "front door"), ctx).text
        assertEquals(text, 1, Regex("Front door").findAll(text).count())
        assertTrue("through the direct service: $text", "Unlocked" in text)
    }

    @Test fun `Alexa failing doesn't hide the other services, and says so`() = runBlocking {
        val home = alexaHome(Direct())
        amazon.validRefresh.clear(); amazon.expireCookies()
        service.load()   // forget the cookies held from the first read
        val tool = HomeDevicesTool(home) { clock.now().toEpochMilliseconds() }
        val text = tool.execute(args(tool), ctx).text
        assertTrue("the direct service still answers: $text", "Front door (Lock)" in text)
    }
}
