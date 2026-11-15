package com.edith.app.home

import com.edith.app.core.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock on the test scheduler's virtual time, so delays and the fake's lag move together. */
class SchedulerClock(private val s: TestCoroutineScheduler) : Clock {
    override fun now(): Instant = Instant.ofEpochMilli(START + s.currentTime)
    override fun zone(): ZoneId = ZoneOffset.UTC

    companion object {
        const val START = 1_800_000_000_000L
    }
}

/** A [Home] with the Demo home on, plus any other backends, on the test's virtual time. */
class HomeKit(val test: TestScope, scope: CoroutineScope, vararg others: HomeBackend, demoOn: Boolean = true) {
    val clock = SchedulerClock(test.testScheduler)
    val fake = FakeHomeBackend(clock, picture = { byteArrayOf(1, 2, 3) })
    val prefs = MemoryHomePrefs(HomeChoices(demo = demoOn))
    val log = MemoryCommandLog()
    // Virtual time: no switch of thread, so delays and timeouts in these tests stay on the test clock.
    val home = Home(prefs, log, scope, clock, demo = fake, ioContext = kotlin.coroutines.EmptyCoroutineContext)

    init {
        others.forEach { home.register(it) }
        test.runCurrent()   // the state flows start collecting
    }

    fun device(name: String): HomeDevice {
        test.runCurrent()   // the view follows the read
        return home.view.value.devices.first { it.name == name }
    }
}
