package com.edith.app.icloud.mail

import com.edith.app.accounts.sources.MailBody
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Reading an email's text (K4.10, for iCloud mail over IMAP): headers (with RFC 2047 encoded words), quoted-printable and base64 bodies, the common
 * charsets, and multipart messages. A message arrives as a string whose characters are the raw bytes (ISO-8859-1), so nothing is lost before the
 * charset is known; [bytesOf] turns it back. Plain text is preferred; HTML is made readable; attachments are skipped. Pure Kotlin, tested on every platform.
 */
object Mime {
    /** One header, name and value, unfolded. */
    class Entity(val headers: List<Pair<String, String>>, val body: String) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    /** Splits [raw] at its first blank line into headers and body. Folded header lines (a line starting with space or tab) are joined. */
    fun parse(raw: String): Entity {
        val split = Regex("\r?\n\r?\n").find(raw)
        val head = if (split == null) raw else raw.substring(0, split.range.first)
        val body = if (split == null) "" else raw.substring(split.range.last + 1)
        val headers = ArrayList<Pair<String, String>>()
        for (line in head.split(Regex("\r?\n"))) {
            if (line.isEmpty()) continue
            if ((line[0] == ' ' || line[0] == '\t') && headers.isNotEmpty()) {
                val last = headers.removeAt(headers.lastIndex)
                headers += last.first to (last.second + " " + line.trim())
            } else {
                val colon = line.indexOf(':')
                if (colon > 0) headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
        }
        return Entity(headers, body)
    }

    /** The bytes a raw (ISO-8859-1) string stands for. */
    fun bytesOf(raw: String): ByteArray = ByteArray(raw.length) { raw[it].code.toByte() }

    /** The raw string for [bytes]. */
    fun rawOf(bytes: ByteArray): String = buildString(bytes.size) { bytes.forEach { append((it.toInt() and 0xff).toChar()) } }

    // ---- Headers: RFC 2047 encoded words ----

    private val ENCODED_WORD = Regex("""=\?([^?\s]+)\?([bBqQ])\?([^?\s]*)\?=""")

    /** A header's text with its `=?charset?B?...?=` words decoded; the header arrives as a raw string. */
    @OptIn(ExperimentalEncodingApi::class)
    fun decodeWords(raw: String): String {
        if (!raw.contains("=?")) return raw
        // Spaces between two encoded words are dropped (RFC 2047 §6.2).
        val joined = raw.replace(Regex("""(\?=)\s+(=\?)"""), "$1$2")
        return ENCODED_WORD.replace(joined) { m ->
            val charset = m.groupValues[1].substringBefore('*')
            val bytes = try {
                if (m.groupValues[2].equals("b", true)) Base64.Mime.decode(m.groupValues[3]) else quotedPrintable(m.groupValues[3].replace('_', ' '), header = true)
            } catch (e: IllegalArgumentException) {
                return@replace m.value
            }
            decode(bytes, charset)
        }
    }

    // ---- Bodies ----

    /** Quoted-printable (RFC 2045 §6.7) to bytes. Soft line breaks (`=` at the end of a line) are removed. */
    fun quotedPrintable(text: String, header: Boolean = false): ByteArray {
        val out = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '=' && i + 1 < text.length) {
                if (text[i + 1] == '\r' && i + 2 < text.length && text[i + 2] == '\n') { i += 3; continue }
                if (text[i + 1] == '\n') { i += 2; continue }
                val hex = if (i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) { out += hex.toByte(); i += 3; continue }
            }
            out += (if (header && c == '_') ' ' else c).code.toByte()
            i++
        }
        return out.toByteArray()
    }

    /** [bytes] as text in [charset]. UTF-8 and the Latin family are handled; anything else is read as UTF-8, lossily. */
    fun decode(bytes: ByteArray, charset: String?): String = when (charset?.trim()?.lowercase()) {
        "iso-8859-1", "latin1", "iso8859-1", "us-ascii", "ascii", "ansi_x3.4-1968" -> rawOf(bytes)
        "windows-1252", "cp1252", "iso-8859-15" -> bytes.joinToString("") { b -> windows1252(b.toInt() and 0xff).toString() }
        else -> bytes.decodeToString()
    }

    private val CP1252_HIGH = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008dŽ\u008f\u0090‘’“”•–—˜™š›œ\u009džŸ"
    private fun windows1252(code: Int): Char = if (code in 0x80..0x9f) CP1252_HIGH[code - 0x80] else code.toChar()

    /** The body of [entity] decoded by its transfer encoding and charset. */
    @OptIn(ExperimentalEncodingApi::class)
    fun text(entity: Entity): String {
        val charset = param(entity.header("Content-Type"), "charset")
        val bytes = when (entity.header("Content-Transfer-Encoding")?.trim()?.lowercase()) {
            "base64" -> try { Base64.Mime.decode(entity.body.filter { !it.isWhitespace() }) } catch (e: IllegalArgumentException) { bytesOf(entity.body) }
            "quoted-printable" -> quotedPrintable(entity.body)
            else -> bytesOf(entity.body)
        }
        return decode(bytes, charset)
    }

    /** `name="value"` or `name=value` from a header such as `Content-Type`. */
    fun param(header: String?, name: String): String? {
        if (header == null) return null
        val m = Regex("""(?:^|[;\s])${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|([^;\s]*))""", RegexOption.IGNORE_CASE).find(header) ?: return null
        return (m.groups[1]?.value ?: m.groups[2]?.value)?.takeIf { it.isNotEmpty() }
    }

    /** The parts of a multipart [entity], or null when it is not multipart. */
    fun parts(entity: Entity): List<Entity>? {
        val type = entity.header("Content-Type") ?: return null
        if (!type.trim().lowercase().startsWith("multipart/")) return null
        val boundary = param(type, "boundary") ?: return null
        val marker = "--$boundary"
        val parts = ArrayList<Entity>()
        var current: StringBuilder? = null
        for (line in entity.body.split("\n")) {
            val l = line.trimEnd('\r')
            when {
                l == "$marker--" -> { current?.let { parts += parse(it.toString().removeSuffix("\r\n").removeSuffix("\n")) }; current = null; break }
                l == marker -> { current?.let { parts += parse(it.toString().removeSuffix("\r\n").removeSuffix("\n")) }; current = StringBuilder() }
                else -> current?.append(line)?.append('\n')
            }
        }
        current?.let { parts += parse(it.toString().removeSuffix("\n")) }
        return parts
    }

    /** The email's readable text: its plain part, else its HTML made readable, else empty. Attachments are skipped. */
    fun readableText(entity: Entity): String = find(entity, "text/plain") ?: find(entity, "text/html")?.let(MailBody::htmlToText) ?: ""

    private fun find(entity: Entity, mime: String): String? {
        if (entity.header("Content-Disposition")?.trim()?.lowercase()?.startsWith("attachment") == true) return null
        val sub = parts(entity)
        if (sub != null) return sub.firstNotNullOfOrNull { find(it, mime) }
        val type = entity.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase() ?: "text/plain"
        return if (type == mime) text(entity) else null
    }
}
