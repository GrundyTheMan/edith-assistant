package com.edith.app.accounts.sources

import com.edith.app.tools.web.PageReader

/**
 * The provider-neutral part of an email's text (C19 §1, C23 §3): the length cap, quoted history removed, HTML made readable. Gmail's MIME decoding
 * and its outgoing format stay with Gmail (`MailText`); Outlook and iCloud use these directly.
 */
object MailBody {
    const val MAX_BODY = 6_000

    /** Plain text with the quoted history removed, capped at [MAX_BODY] (Outlook's text bodies too, C23 §3). */
    fun clean(text: String): String {
        val clean = stripQuoted(text.replace("\r\n", "\n")).trim()
        return if (clean.length <= MAX_BODY) clean else clean.take(MAX_BODY) + "\n[…the rest of the email is cut]"
    }

    fun htmlToText(html: String): String = PageReader.readableHtml(html).content

    /**
     * Removes the quoted earlier messages where it's obvious: from an "On … wrote:" line (Gmail, Apple Mail) or an
     * "-----Original Message-----" line (Outlook) on, and lines starting with ">".
     */
    fun stripQuoted(text: String): String {
        val lines = text.lines()
        val cut = lines.indices.firstOrNull { i ->
            val l = lines[i].trim()
            l.startsWith("-----Original Message-----", ignoreCase = true) ||
                (l.startsWith("On ") && (l.endsWith("wrote:") || (i + 1 < lines.size && lines[i + 1].trim().endsWith("wrote:") && l.length < 120)))
        } ?: lines.size
        return lines.take(cut).filterNot { it.trimStart().startsWith(">") }.joinToString("\n").trimEnd()
    }
}
