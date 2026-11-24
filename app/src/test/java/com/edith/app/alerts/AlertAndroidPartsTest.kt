package com.edith.app.alerts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C33: the Android side of alerts: when watching starts, the remembered tap and the notification titles. */
class AlertAndroidPartsTest {
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
