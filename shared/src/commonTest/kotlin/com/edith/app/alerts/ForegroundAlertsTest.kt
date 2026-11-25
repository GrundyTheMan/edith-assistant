package com.edith.app.alerts

import com.edith.app.core.error.Outcome
import com.edith.app.home.EventKind
import com.edith.app.testing.FakeClock
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Instant

/** K4.16: "While you were away", and the alerts shown while the app is open. */
class ForegroundAlertsTest {
    private val now = Instant.parse("2026-11-26T12:00:00Z")
    private val clock = FakeClock(now)
    private val t = now.toEpochMilliseconds()
    private val hour = 60 * 60_000L

    private val events = listOf(
        Activity("old", "ring", "Porch", t - 30 * hour, EventKind.Ring),           // 30 hours ago: too old
        Activity("a", "ring", "Porch", t - 3 * hour, EventKind.Ring),              // 3 hours ago
        Activity("b", "ring", "Garage", t - hour, EventKind.Motion),               // 1 hour ago, motion
        Activity("c", "ring", "Porch", t - hour / 2, EventKind.Person),            // 30 minutes ago
        Activity("self", "ring", "Porch", t - hour / 3, EventKind.LiveView),       // the owner's own live view: never an alert
    )
    private val source = ActivitySource { _, since -> Outcome.Success(events.filter { it.at >= since }) }

    private fun alerts(prefs: AlertPrefs, scope: CoroutineScope) = ForegroundAlerts(source, prefs, clock, scope)

    @Test fun theFirstOpeningHasNothingToCatchUpOn() = runTest {
        val prefs = MemoryAlertPrefs(AlertChoices(setOf(AlertChoices.key("ring", AlertKind.Doorbell))))
        assertEquals(emptyList<Alert>(), alerts(prefs, this).catchUp())
        assertEquals(t, prefs.lastOpened.first())
    }

    @Test fun catchUpListsWhatHappenedSinceTheLastOpeningForTheKindsThatAreOn() = runTest {
        val prefs = MemoryAlertPrefs(AlertChoices(setOf(AlertChoices.key("ring", AlertKind.Doorbell), AlertChoices.key("ring", AlertKind.Person))))
        prefs.markOpened(t - 4 * hour)   // last opened four hours ago
        val list = alerts(prefs, this).catchUp()
        assertEquals(listOf("c", "a"), list.map { it.key })   // newest first; motion is off, the live view is never an alert
    }

    @Test fun catchUpNeverReachesBackMoreThanADay() = runTest {
        val prefs = MemoryAlertPrefs(AlertChoices(setOf(AlertChoices.key("ring", AlertKind.Doorbell))))
        prefs.markOpened(t - 100 * hour)   // last opened four days ago
        assertEquals(listOf("a"), alerts(prefs, this).catchUp().map { it.key })
    }

    @Test fun withEverythingOffThereIsNothingToCatchUpOn() = runTest {
        val prefs = MemoryAlertPrefs()
        prefs.markOpened(t - 4 * hour)
        assertTrue(alerts(prefs, this).catchUp().isEmpty())
    }
}
