package com.edith.app.alerts

import com.edith.app.home.EventKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C33: the alert switches, which kinds a service can offer, how events map to alerts, and when watching starts. */
class AlertPartsTest {
    @Test fun `everything is off until the owner switches it on`() {
        val c = AlertChoices()
        assertFalse(c.any)
        assertFalse(c.enabled("ring", AlertKind.Doorbell))
        assertTrue(c.backends.isEmpty())
    }

    @Test fun `switches are kept per service and kind`() = runTest {
        val prefs = MemoryAlertPrefs()
        prefs.set("ring", AlertKind.Doorbell, true)
        prefs.set("blink", AlertKind.Motion, true)
        prefs.set("ring", AlertKind.Doorbell, false)
        val c = prefs.choices.first()
        assertFalse(c.enabled("ring", AlertKind.Doorbell))
        assertTrue(c.enabled("blink", AlertKind.Motion))
        assertEquals(setOf("blink"), c.backends)
    }

    @Test fun `ring tells three kinds apart, blink offers motion, others offer nothing`() {
        assertEquals(listOf(AlertKind.Doorbell, AlertKind.Person, AlertKind.Motion), AlertCatalog.kindsOf("ring"))
        assertEquals(listOf(AlertKind.Motion), AlertCatalog.kindsOf("blink"))
        assertTrue(AlertCatalog.kindsOf("alexa").isEmpty())
        assertTrue(AlertCatalog.services.containsAll(setOf("ring", "blink")))   // debug builds add the Demo home
    }

    @Test fun `each kind has its own notification channel`() {
        assertEquals(3, AlertKind.entries.map { it.channelId }.toSet().size)
    }

    @Test fun `events map to alert kinds, and live view is never one`() {
        assertEquals(AlertKind.Doorbell, AlertKind.of(EventKind.Ring))
        assertEquals(AlertKind.Person, AlertKind.of(EventKind.Person))
        assertEquals(AlertKind.Motion, AlertKind.of(EventKind.Motion))
        assertEquals(AlertKind.Motion, AlertKind.of(EventKind.Other))
        assertNull(AlertKind.of(EventKind.LiveView))
    }

    @Test fun `watching starts when the first alert is switched on, and not when it stays on`() = runTest {
        val choices = MutableStateFlow(AlertChoices())
        var starts = 0
        AlertKeepAlive(choices) { starts++ }.watch(backgroundScope)
        runCurrent()
        assertEquals("nothing on, nothing to watch", 0, starts)
        choices.value = AlertChoices(setOf(AlertChoices.key("ring", AlertKind.Doorbell)))
        runCurrent()
        assertEquals(1, starts)
        choices.value = AlertChoices(setOf(AlertChoices.key("ring", AlertKind.Doorbell), AlertChoices.key("blink", AlertKind.Motion)))
        runCurrent()
        assertEquals("a second switch doesn't restart it", 1, starts)
    }

    @Test fun `watching starts at launch when alerts were already on`() = runTest {
        var starts = 0
        AlertKeepAlive(MutableStateFlow(AlertChoices(setOf(AlertChoices.key("blink", AlertKind.Motion))))) { starts++ }.watch(backgroundScope)
        runCurrent()
        assertEquals(1, starts)
    }

    @Test fun `a tap on an alert is remembered until the Home tab takes it`() {
        AlertTaps.consume()
        assertFalse(AlertTaps.openClips.value)
        AlertTaps.request()
        assertTrue("a tap before the screens exist is not lost", AlertTaps.openClips.value)
        AlertTaps.consume()
        assertFalse(AlertTaps.openClips.value)
    }

    @Test fun `the notification titles say what happened`() {
        fun alert(kind: AlertKind) = Alert("ring", "Front door", kind, 1L, "k")
        assertEquals("Someone rang the doorbell", AlertNotifier.title(alert(AlertKind.Doorbell)))
        assertEquals("A person was seen", AlertNotifier.title(alert(AlertKind.Person)))
        assertEquals("Motion", AlertNotifier.title(alert(AlertKind.Motion)))
    }
}
