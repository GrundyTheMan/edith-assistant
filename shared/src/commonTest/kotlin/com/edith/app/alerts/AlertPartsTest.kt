package com.edith.app.alerts

import com.edith.app.home.EventKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertFalse
import com.edith.app.testing.assertNull
import com.edith.app.testing.assertTrue
import kotlin.test.Test

/** C33: the alert switches, which kinds a service can offer and how events map to alerts (watching, taps and titles are Android's, in app/). */
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

    @Test fun `ring tells three kinds apart blink offers motion others offer nothing`() {
        assertEquals(listOf(AlertKind.Doorbell, AlertKind.Person, AlertKind.Motion), AlertCatalog.kindsOf("ring"))
        assertEquals(listOf(AlertKind.Motion), AlertCatalog.kindsOf("blink"))
        assertTrue(AlertCatalog.kindsOf("alexa").isEmpty())
        assertTrue(AlertCatalog.services.containsAll(setOf("ring", "blink")))   // debug builds add the Demo home
    }

    @Test fun `each kind has its own notification channel`() {
        assertEquals(3, AlertKind.entries.map { it.channelId }.toSet().size)
    }

    @Test fun `events map to alert kinds and live view is never one`() {
        assertEquals(AlertKind.Doorbell, AlertKind.of(EventKind.Ring))
        assertEquals(AlertKind.Person, AlertKind.of(EventKind.Person))
        assertEquals(AlertKind.Motion, AlertKind.of(EventKind.Motion))
        assertEquals(AlertKind.Motion, AlertKind.of(EventKind.Other))
        assertNull(AlertKind.of(EventKind.LiveView))
    }
}
