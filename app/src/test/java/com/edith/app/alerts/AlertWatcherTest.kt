package com.edith.app.alerts

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.time.Clock
import com.edith.app.home.EventKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** C33: which camera events become alerts, and when the next look is due. */
class AlertWatcherTest {
    private class FakeClock(var ms: Long = 1_000_000_000L) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(ms)
        override fun zone(): TimeZone = TimeZone.UTC
    }

    private class FakeSource : ActivitySource {
        val answers = HashMap<String, Outcome<List<Activity>>>()
        val asked = mutableListOf<Pair<String, Long>>()
        override suspend fun activity(backendId: String, sinceMs: Long): Outcome<List<Activity>> {
            asked += backendId to sinceMs
            return answers[backendId] ?: Outcome.Success(emptyList())
        }
    }

    private val clock = FakeClock()
    private val source = FakeSource()
    private fun watcher(max: Int = 3) = AlertWatcher(source, clock, maxPerCheck = max)
    private fun act(key: String, kind: EventKind = EventKind.Motion, at: Long = clock.ms, backend: String = "blink", device: String = "Porch") =
        Activity(key, backend, device, at, kind)

    private fun give(backend: String, vararg a: Activity) { source.answers[backend] = Outcome.Success(a.toList()) }
    private val fail = Outcome.Failure(AppError.Timeout)
    private fun blinkMotion() = AlertChoices(setOf(AlertChoices.key("blink", AlertKind.Motion)))

    @Test fun `the first look only learns what is already there`() = runTest {
        give("blink", act("old1", at = clock.ms - 60_000))
        val w = watcher()
        assertTrue(w.check(blinkMotion()).alerts.isEmpty())
        clock.ms += 45_000
        assertTrue("the old clip isn't announced later either", w.check(blinkMotion()).alerts.isEmpty())
    }

    @Test fun `a new event is announced once and not again`() = runTest {
        val w = watcher()
        w.check(blinkMotion())
        clock.ms += 45_000
        give("blink", act("c1", at = clock.ms - 5_000))
        val first = w.check(blinkMotion()).alerts
        assertEquals(listOf("c1"), first.map { it.key })
        assertEquals(AlertKind.Motion, first.single().kind)
        clock.ms += 45_000
        assertTrue(w.check(blinkMotion()).alerts.isEmpty())
    }

    @Test fun `at most three alerts per look, the newest, shown oldest first`() = runTest {
        val w = watcher()
        w.check(blinkMotion())
        clock.ms += 45_000
        give("blink", *(1..5).map { act("c$it", at = clock.ms - (6 - it) * 1_000L) }.toTypedArray())
        assertEquals(listOf("c3", "c4", "c5"), w.check(blinkMotion()).alerts.map { it.key })
        clock.ms += 45_000
        assertTrue("the dropped ones are not queued for later", w.check(blinkMotion()).alerts.isEmpty())
    }

    @Test fun `a kind that is off is not announced, and switching it on later does not announce the past`() = runTest {
        val ringPerson = AlertChoices(setOf(AlertChoices.key("ring", AlertKind.Person)))
        val w = watcher()
        w.check(ringPerson)
        clock.ms += 45_000
        give("ring", act("d1", EventKind.Ring, backend = "ring"), act("p1", EventKind.Person, backend = "ring"))
        assertEquals(listOf("p1"), w.check(ringPerson).alerts.map { it.key })
        clock.ms += 45_000
        val both = AlertChoices(setOf(AlertChoices.key("ring", AlertKind.Person), AlertChoices.key("ring", AlertKind.Doorbell)))
        assertTrue(w.check(both).alerts.isEmpty())
    }

    @Test fun `the owner's own live view is never an alert`() = runTest {
        val w = watcher()
        w.check(blinkMotion())
        clock.ms += 45_000
        give("blink", act("lv", EventKind.LiveView))
        assertTrue(w.check(blinkMotion()).alerts.isEmpty())
    }

    @Test fun `unknown activity counts as motion`() = runTest {
        val w = watcher()
        w.check(blinkMotion())
        clock.ms += 45_000
        give("blink", act("o1", EventKind.Other))
        assertEquals(AlertKind.Motion, w.check(blinkMotion()).alerts.single().kind)
    }

    @Test fun `a failed look waits twice as long each time, up to five minutes, and a good one resets it`() = runTest {
        val w = watcher()
        source.answers["blink"] = fail
        assertEquals(90_000L, w.check(blinkMotion()).nextInMs)
        clock.ms += 90_000
        assertEquals(180_000L, w.check(blinkMotion()).nextInMs)
        clock.ms += 180_000
        assertEquals(300_000L, w.check(blinkMotion()).nextInMs)
        clock.ms += 300_000
        assertEquals(300_000L, w.check(blinkMotion()).nextInMs)
        clock.ms += 300_000
        source.answers["blink"] = Outcome.Success(emptyList())
        assertEquals(45_000L, w.check(blinkMotion()).nextInMs)
    }

    @Test fun `a service is not asked again before it is due`() = runTest {
        val w = watcher()
        w.check(blinkMotion())
        clock.ms += 10_000
        val r = w.check(blinkMotion())
        assertEquals(1, source.asked.size)
        assertEquals(35_000L, r.nextInMs)
    }

    @Test fun `one service failing does not stop the other`() = runTest {
        val both = AlertChoices(setOf(AlertChoices.key("blink", AlertKind.Motion), AlertChoices.key("ring", AlertKind.Doorbell)))
        val w = watcher()
        w.check(both)
        clock.ms += 90_000
        source.answers["blink"] = fail
        give("ring", act("d1", EventKind.Ring, backend = "ring", device = "Front door"))
        val alerts = w.check(both).alerts
        assertEquals(listOf("Front door"), alerts.map { it.device })
        assertEquals(AlertKind.Doorbell, alerts.single().kind)
    }

    @Test fun `switching a service off and on again learns afresh`() = runTest {
        val w = watcher()
        w.check(blinkMotion())
        clock.ms += 45_000
        w.check(AlertChoices())
        clock.ms += 45_000
        give("blink", act("c9", at = clock.ms - 20_000))
        assertTrue("the first look after switching on only learns", w.check(blinkMotion()).alerts.isEmpty())
    }

    @Test fun `every look reaches back ten minutes`() = runTest {
        val w = watcher()
        w.check(blinkMotion())
        assertEquals(clock.ms - AlertWatcher.WINDOW_MS, source.asked.single().second)
    }

    @Test fun `with nothing switched on there is nothing to ask and a calm wait`() = runTest {
        val r = watcher().check(AlertChoices())
        assertTrue(source.asked.isEmpty())
        assertEquals(AlertWatcher.INTERVAL_MS, r.nextInMs)
    }
}
