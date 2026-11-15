package com.edith.app.home

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.testing.FakeClock
import com.edith.app.tools.framework.IntegrationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Found on the owner's phone: signing in to Alexa crashed the whole app. A service that throws (a bug, or an answer it didn't expect)
 * must fail on its own, in words, and never take the app or the other services down.
 */
class HomeGuardTest {
    private val clock = FakeClock(Instant.parse("2026-11-15T15:00:00Z"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() = scope.cancel()

    /** A service whose every call throws, with text in the exception that must never be shown or logged (a cookie, say). */
    private class Throwing : HomeBackend {
        override val id = "boom"
        override val displayName = "Boom"
        override val status = MutableStateFlow(IntegrationStatus.Connected)
        private fun explode(): Nothing = throw IllegalArgumentException("Unexpected char 0x0a in Cookie value: SECRET-COOKIE")
        override suspend fun devices(): Outcome<List<HomeDevice>> = explode()
        override suspend fun state(deviceId: String): Outcome<DeviceState> = explode()
        override suspend fun command(deviceId: String, command: DeviceCommand): Outcome<DeviceState> = explode()
        override suspend fun snapshot(deviceId: String): Outcome<ByteArray> = explode()
        override val hasClips = true
        override suspend fun events(deviceId: String, sinceMs: Long): Outcome<List<DeviceEvent>> = explode()
        override suspend fun clips(sinceMs: Long, untilMs: Long): Outcome<ClipsPage> = explode()
        override suspend fun clipUrl(clipId: String): Outcome<String> = explode()
    }

    private suspend fun home(vararg backends: HomeBackend): Home = Home(MemoryHomePrefs(), MemoryCommandLog(), scope, clock).also { h ->
        backends.forEach { h.register(it) }
        // Home publishes its backends asynchronously: wait for all of them before asking.
        withTimeout(3_000) { h.backends.first { it.size == backends.size } }
        withTimeout(3_000) { h.status.first { it == IntegrationStatus.Connected } }
    }


    /** Records which thread each call arrives on. */
    private class Spy : HomeBackend {
        override val id = "spy"
        override val displayName = "Spy"
        override val status = MutableStateFlow(IntegrationStatus.Connected)
        val threads = java.util.concurrent.CopyOnWriteArrayList<String>()
        private fun seen() { threads += Thread.currentThread().name }
        override suspend fun devices(): Outcome<List<HomeDevice>> { seen(); return Outcome.Success(emptyList()) }
        override suspend fun state(deviceId: String): Outcome<DeviceState> { seen(); return Outcome.Success(DeviceState()) }
        override suspend fun command(deviceId: String, command: DeviceCommand): Outcome<DeviceState> { seen(); return Outcome.Success(DeviceState()) }
        override suspend fun snapshot(deviceId: String): Outcome<ByteArray> { seen(); return Outcome.Success(ByteArray(1)) }
    }

    @Test fun `a service is never called on the thread that asked, so the main thread never reads the network`() = runBlocking {
        // Found on the owner's phone: Alexa's answer was read on the main thread, and Android killed the app (NetworkOnMainThreadException).
        val main = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        try {
            val spy = Spy()
            val h = home(spy)
            val asking = main.asCoroutineDispatcher()
            kotlinx.coroutines.withContext(asking) {
                h.refresh()
                h.command(device.copy(backend = "spy"), DeviceCommand.TurnOn, CommandSource.HomeTab)
                h.snapshot(device.copy(backend = "spy"))
            }
            assertTrue("it was called", spy.threads.size >= 3)
            assertFalse("never on the asking thread: ${spy.threads}", spy.threads.any { it.startsWith("fake-main") })
        } finally { main.shutdown() }
    }

    private val device = HomeDevice("d1", "Thing", null, DeviceType.Light, setOf(Capability.OnOff, Capability.Snapshot, Capability.Events), "boom")

    @Test fun `a service that throws while the home is read fails alone, and the others still answer`() = runBlocking {
        val demo = FakeHomeBackend(clock, { ByteArray(1) })
        val h = home(Throwing(), demo)
        val view = h.refresh()
        assertTrue("the other service's devices are listed", view.devices.any { it.backend == "demo" })
        val problem = view.problems.single { it.backend == "boom" }
        assertTrue(problem.line, "Boom" in problem.line && "unexpected problem" in problem.line)
        assertFalse("the exception's text (a cookie, say) is never shown", "SECRET" in problem.line)
    }

    @Test fun `commands, pictures, events and clips from a service that throws are failures in words`() = runBlocking {
        val h = home(Throwing())
        fun why(o: Outcome<*>) = ((o as Outcome.Failure).error as AppError.ToolFailed).detail
        assertTrue(why(h.command(device, DeviceCommand.TurnOn, CommandSource.HomeTab)).contains("unexpected problem"))
        assertTrue(why(h.snapshot(device)).contains("unexpected problem"))
        assertTrue(why(h.events(device, 0)).contains("unexpected problem"))
        assertTrue(h.clips(0, 1_000).problems.joinToString().contains("unexpected problem"))
        assertEquals(1, h.refresh().problems.size)
    }
}
