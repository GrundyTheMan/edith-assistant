package com.edith.app.utils

/** A piece of one of Edith's replies, in the order it appears. */
sealed interface ReplyPart {
    data class Text(val text: String) : ReplyPart
    /** A file she wrote, still in memory. */
    data class File(val file: WrittenFile) : ReplyPart
    /** One of the user's attachments she's sending back. */
    data class SentAttachment(val number: Int) : ReplyPart
    /** A file from an earlier session: only its name was saved. */
    data class FileNotKept(val name: String) : ReplyPart
}

/**
 * Edith marks attachments in her replies with tags (taught in the system prompt):
 *   <file name="shopping-list.md">…</file>   a file she wrote
 *   <attachment number="3"/>                  one of the user's attachments, sent back
 */
object ReplyFormat {
    // An unclosed <file> (a reply cut short) runs to the end of the reply
    private val FILE = """<file\s+name="([^"]+)"\s*>\n?([\s\S]*?)(?:\n?</file>|$)"""
    private val ATTACHMENT = """<attachment\s+number="(\d+)"\s*/?>"""
    private val NOT_KEPT = """\[Attached file: ([^\]]+)]"""
    private val ANY = Regex("$FILE|$ATTACHMENT|$NOT_KEPT")

    fun parse(reply: String): List<ReplyPart> {
        val parts = mutableListOf<ReplyPart>()
        var last = 0
        for (match in ANY.findAll(reply)) {
            addText(parts, reply.substring(last, match.range.first))
            val (fileName, fileContent, number, notKept) = match.destructured
            parts += when {
                fileName.isNotEmpty() -> ReplyPart.File(WrittenFile(fileName, fileContent.trimEnd()))
                number.isNotEmpty() -> ReplyPart.SentAttachment(number.toInt())
                else -> ReplyPart.FileNotKept(notKept)
            }
            last = match.range.last + 1
        }
        addText(parts, reply.substring(last))
        return parts
    }

    /** What's saved in the chat history: files she wrote are reduced to their names. */
    fun forStorage(reply: String): String =
        Regex(FILE).replace(reply) { "[Attached file: ${it.groupValues[1]}]" }

    /** What's read aloud: the text only. */
    fun forSpeech(reply: String): String = ANY.replace(reply, " ")

    private fun addText(parts: MutableList<ReplyPart>, text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) parts += ReplyPart.Text(trimmed)
    }
}
