package com.edith.app.core.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrashReportTextTest {
    @Test fun theReportHasTheSameSectionsAsAndroidsInOrder() {
        val text = CrashReportText.build("2026-11-26T09:00:00Z", "0.1 (118)", "iPhone (iOS 26.1)", "main", "java.lang.IllegalStateException: boom\n\tat a.b.C.d", listOf("line one", "line two"))
        val lines = text.lines()
        assertEquals("Edith crash report", lines[0])
        assertEquals("Time (UTC): 2026-11-26T09:00:00Z", lines[1])
        assertEquals("App version: 0.1 (118)", lines[2])
        assertEquals("Device: iPhone (iOS 26.1)", lines[3])
        assertEquals("Thread: main", lines[4])
        assertTrue(text.indexOf("IllegalStateException") < text.indexOf("Last log lines:"))
        assertTrue(text.trimEnd().endsWith("line two"))
    }

    @Test fun aMissingReportSaysWhereToLookAndInventsNoStack() {
        val text = CrashReportText.withoutReport("0.1 (118)", "iPhone (iOS 26.1)")
        assertTrue("no report was written" in text, text)
        assertTrue("TestFlight" in text, text)
        assertTrue("Last log lines" !in text, text)
    }
}
