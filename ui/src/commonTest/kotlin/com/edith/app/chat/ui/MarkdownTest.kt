package com.edith.app.chat.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals

/** C07 §3: the Markdown subset. Moved from the app's ChatFormattingTest in K3.1; now runs on the desktop and the iPhone simulator too. */
class MarkdownTest {
    @Test fun blocksAreParagraphsHeadingsListsThatSurviveBlankLinesAndCode() {
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

    @Test fun inlineStylesAreAppliedAndTheirMarkersRemoved() {
        val text = renderMarkdown("A **bold** and *italic* `code` [site](https://example.com) or https://edith.test/x.", Color.Blue, Color.Gray)
            .single().text
        assertEquals("A bold and italic code site or https://edith.test/x.", text.text)
        val links = text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://example.com", "https://edith.test/x"), links)   // the full stop is not part of the URL
        assertEquals(3, text.spanStyles.size)   // bold, italic, code
    }

    @Test fun thingsThatAreNotMarkdownStayAsWritten() {
        val plain = "2 * 3 = 6, snake_case_name, a lone ** and file_name.txt"
        assertEquals(plain, renderMarkdown(plain, Color.Blue, Color.Gray).single().text.text)
    }
}
