package com.edith.app.chat.ui

import com.edith.app.core.time.formatListTime
import com.edith.app.core.time.formatMessageTime
import com.edith.app.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.time.toKotlinInstant
import java.util.Locale

/** C07 §3: the time formats (the Markdown tests moved to ui/ in K3.1). */
class ChatFormattingTest {

    // --- Times ---------------------------------------------------------------------------------------------------

    private val detroit = ZoneId.of("America/Detroit")
    private val now = FakeClock(Instant.parse("2026-10-31T18:30:00Z"), detroit)   // Sat Oct 31, 2:30 PM local
    private fun local(text: String) = java.time.LocalDateTime.parse(text).atZone(detroit).toInstant().toKotlinInstant()

    @Test fun `message times go by calendar day in the owner's zone`() {
        assertEquals("2:06 PM", formatMessageTime(local("2026-10-31T14:06"), now, "en-US"))
        assertEquals("12:05 AM", formatMessageTime(local("2026-10-31T00:05"), now, "en-US"))   // today, though 14 h ago
        assertEquals("Fri 11:50 PM", formatMessageTime(local("2026-10-30T23:50"), now, "en-US"))   // yesterday, 3 h ago
        assertEquals("Sun 9:00 AM", formatMessageTime(local("2026-10-25T09:00"), now, "en-US"))   // 6 days ago
        assertEquals("Oct 24, 9:00 AM", formatMessageTime(local("2026-10-24T09:00"), now, "en-US"))   // a week ago
    }

    @Test fun `list times are shorter`() {
        assertEquals("2:06 PM", formatListTime(local("2026-10-31T14:06"), now, "en-US"))
        assertEquals("Fri", formatListTime(local("2026-10-30T23:50"), now, "en-US"))
        assertEquals("Oct 12", formatListTime(local("2026-10-12T10:00"), now, "en-US"))
    }
}
