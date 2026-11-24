package com.edith.app.home

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertFalse
import com.edith.app.testing.assertNotNull
import com.edith.app.testing.assertNull
import com.edith.app.testing.assertTrue
import kotlin.test.Test

/** C25 §2, §6, §8: the registry, merging, last-known states, the command log and the re-checks. */
/** How many devices the Demo home has: C25's nine, and C34's 3D printer. */
private const val DEMO_DEVICES = 10

class HomeTest {
    /** A second backend, as a real service would be: a hub (Alexa) or a direct one. */
    private class Other(
        override val id: String,
        override val isHub: Boolean,
        val list: List<HomeDevice>,
        var hang: Boolean = false,
    ) : HomeBackend {
        override val displayName = id.replaceFirstChar { it.uppercase() }
        override val status = kotlinx.coroutines.flow.MutableStateFlow(com.edith.app.tools.framework.IntegrationStatus.Connected)
        override suspend fun devices(): Outcome<List<HomeDevice>> { if (hang) awaitCancellation(); return Outcome.Success(list) }
        override suspend fun state(deviceId: String) = Outcome.Success(DeviceState())
        override suspend fun command(deviceId: String, command: DeviceCommand) = Outcome.Success(DeviceState())
        override suspend fun snapshot(deviceId: String): Outcome<ByteArray> = Outcome.Failure(AppError.Timeout)
    }

    private fun alexaCopy(name: String, type: DeviceType, id: String = name) =
        HomeDevice(id, name, "Somewhere", type, setOf(Capability.OnOff), "alexa", state = DeviceState(on = true))

    @Test fun `the Demo home is read with every device and its state`() = runTest {
        val k = HomeKit(this, backgroundScope)
        val v = k.home.refresh()
        assertEquals(DEMO_DEVICES, v.devices.size)
        assertTrue(v.problems.isEmpty())
        assertEquals("Off, 100%".substringBefore(','), HomeActions.stateText(v.devices.first { it.name == "Porch light" }))
        assertTrue("states are stamped with the read's time", v.devices.all { it.stateAt == SchedulerClock.START })
    }

    @Test fun `no Demo home and no service - not connected nothing read`() = runTest {
        val k = HomeKit(this, backgroundScope, demoOn = false)
        assertEquals(com.edith.app.tools.framework.IntegrationStatus.NotConnected, k.home.status.value)
        assertTrue(k.home.refresh().devices.isEmpty())
    }

    @Test fun `one backend timing out doesn't hide the others`() = runTest {
        val slow = Other("ring", isHub = false, list = emptyList(), hang = true)
        val k = HomeKit(this, backgroundScope, slow)
        val started = testScheduler.currentTime
        val v = k.home.refresh()
        assertEquals("it waits only the limit", Home.TIMEOUT_MS, testScheduler.currentTime - started)
        assertEquals(DEMO_DEVICES, v.devices.size)
        assertEquals(listOf("Ring: it didn't answer in time."), v.problems.map { it.line })
    }

    @Test fun `a backend that stops answering keeps its last-known states marked stale`() = runTest {
        val k = HomeKit(this, backgroundScope)
        k.home.refresh()
        advanceTimeBy(120_000)
        k.fake.failWith = AppError.Timeout
        val v = k.home.refresh()
        assertEquals(DEMO_DEVICES, v.devices.size)
        assertEquals(setOf(FakeHomeBackend.ID), v.stale)
        assertEquals("the age is the old read's", SchedulerClock.START, v.devices.first().stateAt)
        assertEquals(1, v.problems.size)
    }

    @Test fun `a device both Alexa and a direct service report is shown once through the direct one while it answers`() = runTest {
        val alexa = Other("alexa", isHub = true, list = listOf(alexaCopy("Front door", DeviceType.Lock), alexaCopy("Hall lamp", DeviceType.Light)))
        val k = HomeKit(this, backgroundScope, alexa)
        var v = k.home.refresh()
        assertEquals(listOf("demo"), v.devices.filter { it.name == "Front door" }.map { it.backend })
        assertEquals("Alexa's own devices stay", 1, v.devices.count { it.name == "Hall lamp" })
        assertEquals(DEMO_DEVICES + 1, v.devices.size)

        k.fake.failWith = AppError.Timeout   // the direct service stops answering: Alexa's copy is used instead
        v = k.home.refresh()
        assertEquals(listOf("alexa"), v.devices.filter { it.name == "Front door" }.map { it.backend })
    }

    @Test fun `hidden devices are left out and listed for Settings`() = runTest {
        val k = HomeKit(this, backgroundScope)
        k.home.refresh()
        k.home.setHidden(k.device("Coffee maker").key, "Coffee maker", true)
        runCurrent()
        val v = k.home.refresh()
        assertFalse(v.devices.any { it.name == "Coffee maker" })
        assertEquals(listOf("Coffee maker"), v.hidden.map { it.name })
        assertFalse(k.home.view.value.devices.any { it.name == "Coffee maker" })
    }

    @Test fun `a command is logged with its source and result`() = runTest {
        val k = HomeKit(this, backgroundScope)
        k.home.refresh()
        val r = k.home.command(k.device("Porch light"), DeviceCommand.TurnOn, CommandSource.HomeTab)
        assertTrue(r is Outcome.Success)
        runCurrent()
        assertEquals(true, k.device("Porch light").state?.on)
        val e = k.log.all.single()
        assertEquals(listOf("Porch light", "turn_on", "On, 100%"), listOf(e.deviceName, e.action, e.result))
        assertEquals(CommandSource.HomeTab, e.source)
        assertTrue(e.ok)
    }

    @Test fun `a failed command is logged as failed`() = runTest {
        val k = HomeKit(this, backgroundScope)
        k.home.refresh()
        val porch = k.device("Porch light")
        k.fake.failWith = AppError.ToolFailed("demo", "the hub is offline")
        assertTrue(k.home.command(porch, DeviceCommand.TurnOn, CommandSource.Chat) is Outcome.Failure)
        val e = k.log.all.single()
        assertFalse(e.ok)
        assertEquals("the hub is offline", e.result)
        assertNull(e.value)
    }

    @Test fun `a lock's late report is picked up by the re-checks`() = runTest {
        val k = HomeKit(this, backgroundScope)
        k.home.refresh()
        val door = k.device("Front door")
        val r = k.home.command(door, DeviceCommand.Unlock, CommandSource.Chat) as Outcome.Success
        assertEquals("the cloud hasn't reported it yet", LockState.Locked, r.value.lock)
        assertEquals("the log doesn't read as if nothing happened", "Sent; it still reported Locked at first", k.log.all.single().result)
        advanceTimeBy(3_001)
        assertEquals("3 s: still the old state (the fake lags 4 s)", LockState.Locked, k.device("Front door").state?.lock)
        advanceTimeBy(7_000)
        assertEquals("10 s: picked up", LockState.Unlocked, k.device("Front door").state?.lock)
        assertNotNull(k.device("Front door").stateAt)
    }
}
