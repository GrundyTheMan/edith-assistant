package com.edith.app.voice.handsfree

/**
 * Finds "Hey Edith" in what a speech recognizer has heard so far (the iPhone's wake word, K6.1). Recognizers hear a name they don't know as the nearest
 * word ("Edit", "Eddie"), so a higher sensitivity accepts those too; the lowest accepts only "Edith". Pure text, tested on the desktop.
 */
object WakePhrase {
    private val greetings = setOf("hey", "hay", "hi", "hei", "okay", "ok")
    private val exact = setOf("edith", "edyth", "edyth's")
    private val loose = setOf("edit", "eddie", "edie", "edith's", "eadith", "adith", "idith", "eden", "edis", "ediths")

    /**
     * Where the wake word ends in [transcript] (the number of words up to and including it), or null. Words before [after] are ignored, so one "Hey Edith"
     * that is still in a growing transcript does not fire twice. [loose] false: only "Edith" after the greeting.
     */
    fun find(transcript: String, after: Int = 0, loose: Boolean = true): Int? {
        val words = words(transcript)
        for (i in maxOf(after, 0) until words.size) {
            val w = words[i]
            // "heyedith" run together
            if (w.startsWith("hey") && w.length > 3 && isName(w.removePrefix("hey"), loose)) return i + 1
            if (w in greetings && i + 1 < words.size && isName(words[i + 1], loose)) return i + 2
        }
        return null
    }

    /** How many words the transcript holds (what to pass as `after` once a wake word was taken). */
    fun wordCount(transcript: String): Int = words(transcript).size

    /** Sensitivity (Settings, 0..1) to "accept the loose spellings": from the middle up. */
    fun isLoose(sensitivity: Float): Boolean = sensitivity >= 0.4f

    private fun isName(word: String, loose: Boolean) = word in exact || (loose && word in this.loose)

    private fun words(text: String): List<String> =
        text.lowercase().map { if (it.isLetterOrDigit() || it == '\'' || it == '’') it else ' ' }.joinToString("").replace('’', '\'').split(' ').filter { it.isNotEmpty() }
}
