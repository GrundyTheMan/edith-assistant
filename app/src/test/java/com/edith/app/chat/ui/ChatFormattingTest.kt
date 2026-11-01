package com.edith.app.chat.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import com.edith.app.core.time.formatListTime
import com.edith.app.core.time.formatMessageTime
import com.edith.app.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** C07 §3: the Markdown subset and the time formats. */
class ChatFormattingTest {

    // --- Markdown ------------------------------------------------------------------------------------------------

    @Test fun `blocks - paragraphs, headings, lists that survive blank lines, and code`() {
        val blocks = parseBlocks(
            """
            # Plan
            First line
            second line

            - one

            - two
            1. step
            ```
            val x = 1
            ```
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Block.Heading("Plan"),
                Block.Paragraph(listOf("First line", "second line")),
                Block.Item("•", "one", joinsPrevious = false),
                Block.Item("•", "two", joinsPrevious = true),
                Block.Item("1.", "step", joinsPrevious = true),
                Block.Code("val x = 1"),
            ),
            blocks,
        )
    }

    @Test fun `inline styles are applied and their markers removed`() {
        val text = renderMarkdown("A **bold** and *italic* `code` [site](https://example.com) or https://edith.test/x.", Color.Blue, Color.Gray)
            .single().text
        assertEquals("A bold and italic code site or https://edith.test/x.", text.text)
        val links = text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://example.com", "https://edith.test/x"), links)   // the full stop isn't part of the URL
        assertEquals(3, text.spanStyles.size)   // bold, italic, code
    }

    @Test fun `things that aren't Markdown stay as written`() {
        val plain = "2 * 3 = 6, snake_case_name, a lone ** and file_name.txt"
        assertEquals(plain, renderMarkdown(plain, Color.Blue, Color.Gray).single().text.text)
    }

    // --- Times ---------------------------------------------------------------------------------------------------

    private val detroit = ZoneId.of("America/Detroit")
    private val now = FakeClock(Instant.parse("2026-10-31T18:30:00Z"), detroit)   // Sat Oct 31, 2:30 PM local
    private fun local(text: String) = java.time.LocalDateTime.parse(text).atZone(detroit).toInstant()

    @Test fun `message times go by calendar day in the owner's zone`() {
        assertEquals("2:06 PM", formatMessageTime(local("2026-10-31T14:06"), now, Locale.US))
        assertEquals("12:05 AM", formatMessageTime(local("2026-10-31T00:05"), now, Locale.US))   // today, though 14 h ago
        assertEquals("Fri 11:50 PM", formatMessageTime(local("2026-10-30T23:50"), now, Locale.US))   // yesterday, 3 h ago
        assertEquals("Sun 9:00 AM", formatMessageTime(local("2026-10-25T09:00"), now, Locale.US))   // 6 days ago
        assertEquals("Oct 24, 9:00 AM", formatMessageTime(local("2026-10-24T09:00"), now, Locale.US))   // a week ago
    }

    @Test fun `list times are shorter`() {
        assertEquals("2:06 PM", formatListTime(local("2026-10-31T14:06"), now, Locale.US))
        assertEquals("Fri", formatListTime(local("2026-10-30T23:50"), now, Locale.US))
        assertEquals("Oct 12", formatListTime(local("2026-10-12T10:00"), now, Locale.US))
    }
}
