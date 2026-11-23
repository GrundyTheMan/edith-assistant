package com.edith.app.ring

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.home.CameraSnapshotTool
import com.edith.app.home.Home
import com.edith.app.home.HomeDevicesTool
import com.edith.app.home.MemoryCommandLog
import com.edith.app.home.MemoryHomePrefs
import com.edith.app.home.RecentEventsTool
import com.edith.app.settings.Settings
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import com.edith.app.tools.framework.IntegrationStatus
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** C27 acceptance without a network: Edith's tools → Home → Ring: "Show me the front door camera", "Did anyone ring today?". */
class RingToolsTest {
    private val ring = FakeRing()
    private val clock = FakeClock(Instant.parse("2026-11-14T18:00:00Z"))
    private val service = RingService(InMemorySecretStore(), ring.api(), clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel(); ring.shutdown() }

    private fun args(tool: Tool, vararg pairs: Pair<String, String>) =
        ToolArgs(buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }, tool.spec)

    private val ctx get() = ToolContext(clock, Settings(), AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined))

    private suspend fun home(): Home {
        ring.devicesJson = """{"doorbots":[${ring.camera(1, "Front Door", "doorbell_v4", battery = "90")}],"stickup_cams":[${ring.camera(2, "Driveway")}]}"""
        service.signIn(mapOf("email" to "a@b.c", "password" to "hunter2"))
        return Home(MemoryHomePrefs(), MemoryCommandLog(), scope, clock).also {
            it.register(service)
            withTimeout(3_000) { it.status.first { s -> s == IntegrationStatus.Connected } }
        }
    }

    @Test fun `show me the front door camera - a picture comes back with the reply`() = runBlocking {
        val tool = CameraSnapshotTool(home())
        val r = tool.execute(args(tool, "device" to "the front door"), ctx) as ToolResult.Ok
        assertEquals(listOf<Byte>(1, 2, 3), r.images.single().jpeg.toList())
        assertTrue(r.text, "Front Door" in r.text)
    }

    @Test fun `a camera that gives no picture is said plainly, not as a crash`() = runBlocking {
        val tool = CameraSnapshotTool(home())
        ring.snapshotBytes = null; ring.snapshotCode = 504
        val r = tool.execute(args(tool, "device" to "driveway"), ctx)
        assertTrue(r.text, r is ToolResult.Error && "No picture from Driveway" in r.text)
    }

    @Test fun `did anyone ring today - events with times, only what the owner asked about`() = runBlocking {
        val tool = RecentEventsTool(home())
        ring.eventsJson = """{"events":[${ring.event("ding", "2026-11-14T17:30:00Z")},${ring.event("motion", "2026-11-14T16:00:00Z", person = true)},${ring.event("motion", "2026-11-12T16:00:00Z")}]}"""
        val all = tool.execute(args(tool), ctx).text
        assertTrue(all, "Someone rang the doorbell" in all && "A person" in all)
        assertEquals("the two day old motion is outside 24 hours: $all", 0, all.lines().count { it.startsWith("- Motion") })
        assertTrue("both cameras are asked: $all", "Front Door:" in all && "Driveway:" in all)
        val one = tool.execute(args(tool, "device" to "front door", "hours" to "72"), ctx).text
        assertTrue(one, "Front Door:" in one && "Driveway" !in one && "- Motion" in one)
        ring.eventsJson = """{"events":[]}"""
        assertTrue(tool.execute(args(tool, "device" to "driveway"), ctx).text.contains("nothing reported"))
    }

    @Test fun `home_devices lists the cameras`() = runBlocking {
        val tool = HomeDevicesTool(home()) { clock.now().toEpochMilliseconds() }
        val text = tool.execute(args(tool), ctx).text
        assertTrue(text, "Front Door (Doorbell)" in text && "Driveway (Camera)" in text)
    }

    @Test fun `a dead Ring sign-in shows as a problem line, and the tools say so`() = runBlocking {
        val tool = HomeDevicesTool(home()) { clock.now().toEpochMilliseconds() }
        ring.validRefresh.clear(); ring.validAccess.clear()
        val text = tool.execute(args(tool), ctx).text
        assertTrue(text, "Ring:" in text && "signing in again" in text)
    }
}
