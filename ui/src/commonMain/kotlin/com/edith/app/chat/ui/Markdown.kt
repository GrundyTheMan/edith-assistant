package com.edith.app.chat.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * The small Markdown subset Edith's replies use (C07 §3): **bold**, *italics*, `inline code`, bullet and numbered
 * lists, headings (shown bold), fenced code blocks, and links — `[text](url)` or a bare https URL — which open in
 * the browser when tapped. Written here rather than adding a library: it's ~100 lines, has no dependencies, and
 * anything it doesn't recognise is shown as plain text, so nothing is ever lost.
 *
 * Returns one [RenderedBlock] per paragraph, list item, heading or code block; the bubble lays them out with its
 * own spacing (a single string with paragraph styles gets extra blank lines between list items).
 */
fun renderMarkdown(markdown: String, linkColor: Color, codeBackground: Color): List<RenderedBlock> {
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
    val link = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))

    fun AnnotatedString.Builder.inline(text: String) {
        var at = 0
        for (m in INLINE.findAll(text)) {
            append(text.substring(at, m.range.first))
            val g = m.groups
            val codeText = g[1]
            val linkText = g[2]
            val linkUrl = g[3]
            val bold = g[4] ?: g[5]
            val italic = g[6] ?: g[7]
            val bareUrl = g[8]
            when {
                codeText != null -> withStyle(code) { append(codeText.value) }
                linkText != null && linkUrl != null -> withLink(LinkAnnotation.Url(linkUrl.value, link)) { inline(linkText.value) }
                bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(bold.value) }
                italic != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(italic.value) }
                bareUrl != null -> withLink(LinkAnnotation.Url(bareUrl.value, link)) { append(bareUrl.value) }
            }
            at = m.range.last + 1
        }
        append(text.substring(at))
    }

    return parseBlocks(markdown).map { block ->
        when (block) {
            is Block.Paragraph -> RenderedBlock(buildAnnotatedString { inline(block.lines.joinToString("\n")) })
            is Block.Heading -> RenderedBlock(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(block.text) } })
            is Block.Item -> RenderedBlock(buildAnnotatedString { inline(block.text) }, marker = block.marker, tight = block.joinsPrevious)
            is Block.Code -> RenderedBlock(buildAnnotatedString { withStyle(code) { append(block.text) } })
        }
    }
}

/** A laid-out block: [marker] ("•", "2.") for list items; [tight] when it directly follows another list item. */
data class RenderedBlock(val text: AnnotatedString, val marker: String? = null, val tight: Boolean = false)

/** One block of a reply, before styling. */
sealed interface Block {
    data class Paragraph(val lines: List<String>) : Block
    data class Heading(val text: String) : Block
    data class Item(val marker: String, val text: String, val joinsPrevious: Boolean) : Block
    data class Code(val text: String) : Block
}

fun parseBlocks(markdown: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = mutableListOf<String>()
    fun endParagraph() { if (paragraph.isNotEmpty()) blocks += Block.Paragraph(paragraph.toList()); paragraph.clear() }

    val lines = markdown.trim().lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        when {
            line.trimStart().startsWith("```") -> {
                endParagraph()
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) body += lines[i++]
                blocks += Block.Code(body.joinToString("\n"))
            }
            line.isBlank() -> endParagraph()
            HEADING.matches(line) -> { endParagraph(); blocks += Block.Heading(line.trimStart().trimStart('#').trim()) }
            bullet != null || numbered != null -> {
                endParagraph()
                val follows = blocks.lastOrNull() is Block.Item   // a blank line between items doesn't split the list
                val item = if (bullet != null) Block.Item("•", bullet.groupValues[1], follows)
                else numbered?.let { Block.Item("${it.groupValues[1]}.", it.groupValues[2], follows) }
                if (item != null) blocks += item
            }
            else -> paragraph += line.trim()
        }
        i++
    }
    endParagraph()
    return blocks
}

private val BULLET = Regex("""^\s*[-*•+]\s+(.*)$""")
private val NUMBERED = Regex("""^\s*(\d{1,3})[.)]\s+(.*)$""")
private val HEADING = Regex("""^\s*#{1,6}\s+.*$""")

/** Groups: 1 code · 2,3 link text/url · 4,5 bold · 6,7 italic · 8 bare URL. */
private val INLINE = Regex(
    """`([^`\n]+)`""" +
        """|\[([^\]\n]+)]\((https?://[^)\s]+)\)""" +
        """|\*\*(?=\S)(.+?)(?<=\S)\*\*|__(?=\S)(.+?)(?<=\S)__""" +
        """|(?<![\w*])\*(?=\S)(.+?)(?<=\S)\*(?![\w*])|(?<![\w_])_(?=\S)(.+?)(?<=\S)_(?![\w_])""" +
        """|(https?://[^\s<>()\[\]]*[^\s<>()\[\].,;:!?'"])""",
)

/** A one-line, marker-free version of a reply for the conversation list's preview ("**Tip:** water less" → "Tip: water less"). */
fun markdownToPlainLine(markdown: String, maxChars: Int): String =
    renderMarkdown(markdown.take(maxChars * 4), Color.Unspecified, Color.Unspecified)
        .joinToString(" ") { block -> listOfNotNull(block.marker, block.text.text).joinToString(" ") }
        .replace(Regex("""\s+"""), " ").trim().take(maxChars)
