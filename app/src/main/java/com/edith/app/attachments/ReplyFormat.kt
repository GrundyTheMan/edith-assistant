package com.edith.app.attachments

import com.edith.app.chat.engine.PromptContext
import com.edith.app.chat.engine.PromptSection

/**
 * Edith's replies can carry files and resends (C16 §3):
 * - `<file name="packing-list.md">…</file>`: a file she wrote. An unclosed block runs to the end of the reply.
 * - `<attachment number="3"/>`: one of the owner's attachments, sent back.
 *
 * Written without regexes over the whole reply, so a long reply with a stray `<` can't backtrack badly; the small
 * tag patterns are escaped for Android's regex engine (L25).
 */
object ReplyFormat {
    sealed interface Part {
        data class Text(val text: String) : Part
        data class File(val name: String, val content: String) : Part
        data class Resend(val number: Int) : Part
    }

    private const val FILE_CLOSE = "</file>"
    private val FILE_OPEN = Regex("<file\\s+name\\s*=\\s*\"([^\"]*)\"\\s*>")
    private val RESEND = Regex("<attachment\\s+number\\s*=\\s*\"?(\\d+)\"?\\s*/?>")
    private val PLACEHOLDER = Regex("\\[Attached file: ([^\\]]+)\\]")
    private val BLANK_LINES = Regex("\\n{3,}")

    fun parse(reply: String): List<Part> {
        val parts = mutableListOf<Part>()
        var at = 0
        val text = StringBuilder()
        fun flushText() {
            if (text.isNotBlank()) parts += Part.Text(text.toString())
            text.clear()
        }
        while (at < reply.length) {
            val file = FILE_OPEN.find(reply, at)
            val resend = RESEND.find(reply, at)
            val next = listOfNotNull(file, resend).minByOrNull { it.range.first }
            if (next == null) {
                text.append(reply, at, reply.length)
                break
            }
            text.append(reply, at, next.range.first)
            flushText()
            if (next === file) {
                val start = next.range.last + 1
                val end = reply.indexOf(FILE_CLOSE, start).let { if (it < 0) reply.length else it }
                parts += Part.File(sanitize(next.groupValues[1]), reply.substring(start, end).trim('\n', '\r'))
                at = if (end == reply.length) end else end + FILE_CLOSE.length
            } else {
                parts += Part.Resend(next.groupValues[1].toInt())
                at = next.range.last + 1
            }
        }
        flushText()
        return parts
    }

    /** `[A-Za-z0-9._-]`, at most 60 characters, never empty or hidden; `note.md` when nothing usable is left. */
    fun sanitize(name: String): String {
        val cleaned = name.trim().replace(' ', '-').filter { it.isLetterOrDigit() && it.code < 128 || it in "._-" }
            .trimStart('.', '-')
        if (cleaned.isEmpty() || cleaned.all { it == '.' }) return DEFAULT_NAME
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0) cleaned.substring(dot).take(MAX_EXTENSION) else ".md"
        val base = (if (dot > 0) cleaned.substring(0, dot) else cleaned).take(MAX_NAME - ext.length)   // the extension survives
        return base + ext
    }

    /** The placeholder that replaces a file block in the stored reply. */
    fun placeholder(name: String) = "[Attached file: $name]"

    /** The stored reply without its file placeholders: the cards show the files (C16 §4). */
    fun withoutPlaceholders(stored: String): String =
        stored.replace(PLACEHOLDER, "").replace(BLANK_LINES, "\n\n").trim()

    /** Swaps each placeholder for [replacement] (the file's own block, when the model should see it again). */
    fun replacePlaceholders(stored: String, replacement: (name: String) -> String?): String =
        stored.replace(PLACEHOLDER) { m -> replacement(m.groupValues[1]) ?: m.value }

    /** A reply as it streams: file blocks become "Writing name…" and resend tags disappear. */
    fun streaming(partial: String): String = parse(partial).joinToString("") { part ->
        when (part) {
            is Part.Text -> part.text
            is Part.File -> "\n\n_Writing ${part.name}…_\n\n"
            is Part.Resend -> ""
        }
    }.replace(BLANK_LINES, "\n\n").trim()

    fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "xml" -> "text/xml"
        else -> "text/plain"
    }

    const val MAX_NAME = 60
    private const val MAX_EXTENSION = 10
    const val DEFAULT_NAME = "note.md"
}

/** Teaches the file and resend conventions (C16 §3); always on. */
class EdithFilesSection : PromptSection {
    override fun text(ctx: PromptContext): String =
        "When your reply contains something worth keeping as a file (a list, note, table, plan or code), put it in a file block:\n" +
            "<file name=\"short-name.ext\">the content</file>\n" +
            "Use .md for notes and lists, .csv for tables, and the right extension for code. Keep the chat text around it to a sentence or two.\n" +
            "To send one of the user's attachments back to them, write <attachment number=\"3\"/> with its number. Only use numbers from labels you've been given.\n" +
            // Live, gemma4 once answered "I can't actually send files back" instead (C16 As built): say plainly that she can.
            "You can send attachments back: when the user asks to see, resend or get back an attachment, reply with a short sentence and the tag, " +
            "e.g. \"Here it is: <attachment number=\"2\"/>\". The app shows it to them. Never say you can't send it."
}
