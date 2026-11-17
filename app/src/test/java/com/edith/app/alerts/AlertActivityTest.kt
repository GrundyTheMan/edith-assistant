package com.edith.app.alerts

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.home.Capability
import com.edith.app.home.ClipsPage
import com.edith.app.home.DeviceClip
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceEvent
import com.edith.app.home.DeviceState
import com.edith.app.home.DeviceType
import com.edith.app.home.EventKind
import com.edith.app.home.HomeBackend
import com.edith.app.home.HomeDevice
import com.edith.app.home.HomeKit
import com.edith.app.tools.framework.IntegrationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C33: what [com.edith.app.home.Home.activity] hands the alerts: clips when a service has them, else each camera's events. */
class AlertActivityTest {
    private class Camera(
        override val id: String,
        var page: Outcome<ClipsPage>?,
        var events: Outcome<List<DeviceEvent>> = Outcome.Success(emptyList()),
        connected: Boolean = true,
    ) : HomeBackend {
        override val displayName = id.replaceFirstChar { it.uppercase() }
        override val status = MutableStateFlow(if (connected) IntegrationStatus.Connected else IntegrationStatus.NotConnected)
        override val hasClips: Boolean get() = page != null
        var deviceCalls = 0
        val eventCalls = mutableListOf<Pair<String, Long>>()
        val door = HomeDevice("d1", "Front door", null, DeviceType.Doorbell, setOf(Capability.Snapshot, Capability.Events), id)
        val porch = HomeDevice("d2", "Porch", null, DeviceType.Camera, setOf(Capability.Snapshot, Capability.Events), id)
        val plug = HomeDevice("d3", "Plug", null, DeviceType.Plug, emptySet(), id)

        override suspend fun devices(): Outcome<List<HomeDevice>> { deviceCalls++; return Outcome.Success(listOf(door, porch, plug)) }
        override suspend fun state(deviceId: String) = Outcome.Success(DeviceState())
        override suspend fun command(deviceId: String, command: DeviceCommand) = Outcome.Success(DeviceState())
        override suspend fun snapshot(deviceId: String): Outcome<ByteArray> = Outcome.Success(byteArrayOf(1))
        override suspend fun events(deviceId: String, sinceMs: Long): Outcome<List<DeviceEvent>> { eventCalls += deviceId to sinceMs; return events }
        override suspend fun clips(sinceMs: Long, untilMs: Long): Outcome<ClipsPage> = page ?: Outcome.Failure(AppError.Timeout)
    }

    private fun clip(id: String, at: Long, kind: EventKind = EventKind.Motion) = DeviceClip(id, "", "Porch", at, kind, null)

    @Test fun `a service with clips answers from its clips, keyed by service and clip`() = runTest {
        val blink = Camera("blink", Outcome.Success(ClipsPage(listOf(clip("c1", 5), clip("c2", 9, EventKind.Ring)))))
        val k = HomeKit(this, backgroundScope, blink, demoOn = false)
        val found = (k.home.activity("blink", 0) as Outcome.Success).value
        assertEquals(listOf("blink:c1", "blink:c2"), found.map { it.key })
        assertEquals(listOf("Porch", "Porch"), found.map { it.device })
        assertEquals(EventKind.Ring, found.last().kind)
        assertEquals("clips are one call, no device list", 0, blink.deviceCalls)
    }

    @Test fun `a service with no plan falls back to each camera's events`() = runTest {
        val ring = Camera("ring", Outcome.Success(ClipsPage(emptyList(), noPlan = true)), events = Outcome.Success(listOf(DeviceEvent(70, EventKind.Ring))))
        val k = HomeKit(this, backgroundScope, ring, demoOn = false)
        val found = (k.home.activity("ring", 50) as Outcome.Success).value
        assertEquals(listOf("Front door", "Porch"), found.map { it.device })
        assertEquals("only devices that report events are asked", listOf("d1" to 50L, "d2" to 50L), ring.eventCalls)
        assertEquals("ring:d1:70:Ring", found.first().key)
    }

    @Test fun `the camera list is kept, not asked for again on every look`() = runTest {
        val ring = Camera("ring", null)
        val k = HomeKit(this, backgroundScope, ring, demoOn = false)
        k.home.activity("ring", 0)
        k.home.activity("ring", 0)
        assertEquals(1, ring.deviceCalls)
    }

    @Test fun `a failing service says so`() = runTest {
        val blink = Camera("blink", Outcome.Failure(AppError.ToolFailed("Blink", "it needs signing in again")))
        val k = HomeKit(this, backgroundScope, blink, demoOn = false)
        assertTrue(k.home.activity("blink", 0) is Outcome.Failure)
    }

    @Test fun `a service that is not signed in is not asked`() = runTest {
        val ring = Camera("ring", Outcome.Success(ClipsPage(emptyList())), connected = false)
        val k = HomeKit(this, backgroundScope, ring, demoOn = false)
        assertTrue(k.home.activity("ring", 0) is Outcome.Failure)
        assertTrue(k.home.activity("nobody", 0) is Outcome.Failure)
    }

    @Test fun `an alert's camera is found by name, for its picture`() = runTest {
        val ring = Camera("ring", null)
        val k = HomeKit(this, backgroundScope, ring, demoOn = false)
        assertEquals("d1", k.home.alertCamera("ring", "Front door")?.id)
        assertNull(k.home.alertCamera("ring", "Garage"))
        assertNull(k.home.alertCamera("blink", "Front door"))
        assertNotNull(k.home.alertCamera("ring", "Porch"))
    }
}
