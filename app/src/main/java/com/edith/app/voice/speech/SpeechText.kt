package com.edith.app.voice.speech

/**
 * Turns a streaming reply into sentences to speak (C13 §1, L14–L15). Feed it deltas with [add]; each call returns the
 * sentences that are now complete. [flush] returns what's left when the reply ends.
 *
 * - A sentence ends at `.`, `!`, `?` or `…` followed by a space or newline, or at the end of a non-empty line (list
 *   items often have no full stop). Not inside numbers (3.5, no space follows) or after common abbreviations.
 * - A long unfinished sentence (over [LONG_CHARS]) is cut at the first comma, semicolon, colon or dash after
 *   [MIN_CUT_CHARS], so a long opening sentence doesn't delay speech.
 * - A piece of one word ("Sure.", "1.") is joined to the next, so the voice doesn't stop after every word.
 * - Edith's files (`<file name="…">…</file>`, C16) are skipped, even when a tag is split across deltas.
 *
 * Written without regexes on purpose: Android's regex engine differs from the JVM's (L25).
 */
class SentenceChunker {
    private val buffer = StringBuilder()
    /** Text not yet checked for file tags: a possible start of a tag waits here for the next delta. */
    private val raw = StringBuilder()
    private var inFile = false
    /** A one-word piece waiting to be joined to the next sentence. */
    private var carry = ""

    fun add(delta: String): List<String> {
        raw.append(delta)
        filterFiles(final = false)
        return take(final = false)
    }

    /** Everything left. The chunker is empty afterwards. */
    fun flush(): List<String> {
        filterFiles(final = true)
        val out = take(final = true)
        reset()
        return out
    }

    fun reset() {
        buffer.clear(); raw.clear(); inFile = false; carry = ""
    }

    private fun filterFiles(final: Boolean) {
        while (raw.isNotEmpty()) {
            if (inFile) {
                val end = raw.indexOf(FILE_CLOSE)
                if (end < 0) {
                    // Keep only what could be the start of the closing tag.
                    val keep = if (final) 0 else partialSuffix(raw, FILE_CLOSE)
                    raw.delete(0, raw.length - keep)
                    return
                }
                raw.delete(0, end + FILE_CLOSE.length)
                inFile = false
            } else {
                val start = raw.indexOf(FILE_OPEN)
                if (start < 0) {
                    val keep = if (final) 0 else partialSuffix(raw, FILE_OPEN)
                    buffer.append(raw, 0, raw.length - keep)
                    raw.delete(0, raw.length - keep)
                    return
                }
                buffer.append(raw, 0, start).append(' ')
                raw.delete(0, start + FILE_OPEN.length)
                inFile = true
            }
        }
    }

    private fun take(final: Boolean): List<String> {
        val out = mutableListOf<String>()
        fun emit(piece: String) {
            val text = (if (carry.isEmpty()) piece.trim() else "$carry ${piece.trim()}").trim()
            if (text.isEmpty()) return
            if (wordCount(text) < MIN_WORDS && !final) carry = text else { carry = ""; out += text }
        }
        while (true) {
            val end = sentenceEnd(buffer)
            if (end >= 0) {
                emit(buffer.substring(0, end))
                buffer.delete(0, end)
                continue
            }
            val cut = if (buffer.length > LONG_CHARS) pauseCut(buffer) else -1
            if (cut >= 0) {
                emit(buffer.substring(0, cut))
                buffer.delete(0, cut)
                continue
            }
            break
        }
        if (final) {
            val rest = buffer.toString()
            buffer.clear()
            if (rest.isNotBlank() || carry.isNotEmpty()) emit(rest)
        }
        return out
    }

    companion object {
        const val LONG_CHARS = 100
        const val MIN_CUT_CHARS = 25
        const val MIN_WORDS = 2
        private const val FILE_OPEN = "<file"
        private const val FILE_CLOSE = "</file>"
        private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "vs", "e.g", "i.e", "a.m", "p.m", "approx")

        /** Index just after a sentence's end (including its terminator), or -1 if no sentence is complete yet. */
        internal fun sentenceEnd(text: CharSequence): Int {
            for (i in text.indices) {
                val c = text[i]
                if (c == '\n') {
                    if (text.substring(0, i).isNotBlank()) return i + 1
                    continue
                }
                if (c != '.' && c != '!' && c != '?' && c != '…') continue
                // Swallow runs like "...", "?!" and a closing quote or bracket.
                var j = i + 1
                while (j < text.length && text[j] in TRAILING) j++
                if (j >= text.length) return -1   // wait: the next delta says whether it's an ending
                if (!text[j].isWhitespace()) continue
                if (c == '.' && j == i + 1 && isAbbreviation(text, i)) continue
                return j
            }
            return -1
        }

        /** In a long sentence, the index just after the first pause (`,;:` or a dash) past [MIN_CUT_CHARS], or -1. */
        internal fun pauseCut(text: CharSequence): Int {
            for (i in MIN_CUT_CHARS until text.length - 1) {
                val c = text[i]
                val pause = c == ',' || c == ';' || c == ':' ||
                    ((c == '—' || c == '–' || c == '-') && text[i - 1] == ' ')
                if (pause && text[i + 1] == ' ') return i + 1
            }
            return -1
        }

        private fun isAbbreviation(text: CharSequence, dot: Int): Boolean {
            var start = dot
            while (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] != '(') start--
            val word = text.substring(start, dot).lowercase()
            return word in ABBREVIATIONS
        }

        /** How many characters at the end of [text] could be the start of [tag]. */
        private fun partialSuffix(text: CharSequence, tag: String): Int {
            for (n in minOf(tag.length - 1, text.length) downTo 1) {
                if (text.regionMatchesAt(text.length - n, tag, n)) return n
            }
            return 0
        }

        private fun CharSequence.regionMatchesAt(offset: Int, tag: String, n: Int): Boolean {
            for (k in 0 until n) if (this[offset + k] != tag[k]) return false
            return true
        }

        private fun wordCount(text: String): Int = text.split(' ', '\n', '\t').count { it.isNotBlank() }

        private const val TRAILING = ".!?…\"'”’)]*_"
    }
}

/**
 * Makes one piece of a reply fit to be read aloud (C13 §1): no Markdown symbols, link text without its address, bare
 * addresses as "a link", no emoji, and no file tags (C16). Whitespace is collapsed.
 */
object SpeechText {
    // Every `{` and `}` is escaped and each pattern is compiled on the device in a test (L25).
    private val FILE_BLOCK = Regex("<file\\b[^>]*>.*?(</file>|$)", RegexOption.DOT_MATCHES_ALL)
    private val MARKDOWN_LINK = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
    /** Not ending in punctuation, so a sentence's full stop after an address stays. */
    private val URL = Regex("(https?://|www\\.)[^\\s)\\]]*[^\\s)\\].,!?;:'\"]")
    private val HEADING = Regex("(?m)^\\s*#+\\s*")
    private val QUOTE = Regex("(?m)^\\s*>+\\s?")
    private val BULLET = Regex("(?m)^\\s*[-*•]\\s+")
    /** `_` only at a word's edge, so snake_case names keep theirs. */
    private val EMPHASIS = Regex("\\*\\*|__|\\*|`+|~~|\\b_|_\\b")
    /** Symbols and modifiers (skin tones), plus the joiners that build emoji. */
    private val EMOJI = Regex("[\\p{So}\\p{Sk}\\x{FE0F}\\x{200D}]")
    private val SPACE = Regex("\\s+")

    fun clean(text: String): String = text
        .replace(FILE_BLOCK, " ")
        .replace(MARKDOWN_LINK, "$1")
        .replace(URL, "a link")
        .replace(HEADING, "")
        .replace(QUOTE, "")
        .replace(BULLET, "")
        .replace(EMPHASIS, "")
        .replace(EMOJI, "")
        .replace(SPACE, " ")
        .trim()
}
