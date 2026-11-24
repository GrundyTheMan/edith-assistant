package com.edith.app.printer.feed

import com.edith.app.jarvis.FeedResult

/** Plain-English words for everything that can go wrong asking Jarvis about the printer (C39 part 7.5). These are the strings. */
object PrinterWords {
    const val UNREACHABLE = "Edith can't reach Jarvis. Is the PC on, Jarvis running, and Tailscale connected (away from home)?"
    const val UNAUTHORIZED = "Jarvis removed this phone: pair it again."
    const val NOT_SHARED = "Jarvis isn't sharing the printer with this phone."
    const val CONTROL_OFF = "Jarvis lets this phone look at the printer, not control it."
    const val CONTROLS_OFF = "Controls turned off. Turn them on to continue."
    const val PRINTER_UNREACHABLE = "Jarvis can't reach the printer right now."
    const val TIMEOUT = "The printer took too long to answer."
    const val TOO_LARGE = "That file is too big to send from the phone."
    const val NOT_FOUND = "Jarvis doesn't have that any more. Refresh the list."
    const val TOO_MANY = "Too many requests at once. Wait a moment and try again."
    const val BAD_REQUEST = "Jarvis didn't understand that. (It may be an older version.)"
    const val UNEXPECTED = "Jarvis answered something Edith didn't expect."

    /** The words for an error answer. A sentence from Jarvis itself is used where the table says "the server's message". */
    fun forError(e: FeedResult.Err): String = when (e.code) {
        "PRINTER_NOT_SHARED" -> NOT_SHARED
        "CONTROL_OFF" -> CONTROL_OFF
        "CONTROLS_OFF" -> CONTROLS_OFF
        "REFUSED", "AWAY_LIMITED" -> sentence(e.message) ?: UNEXPECTED
        "PRINTER_UNREACHABLE" -> PRINTER_UNREACHABLE
        "TIMEOUT" -> TIMEOUT
        "TOO_LARGE" -> TOO_LARGE
        "NOT_FOUND" -> NOT_FOUND
        "TOO_MANY" -> TOO_MANY
        "BAD_REQUEST" -> BAD_REQUEST
        else -> sentence(e.message) ?: UNEXPECTED
    }

    /** The words for any result that isn't `Ok`. */
    fun forFailure(r: FeedResult<*>): String = when (r) {
        is FeedResult.Ok -> ""
        is FeedResult.Err -> forError(r)
        FeedResult.Unreachable -> UNREACHABLE
        FeedResult.Unauthorized -> UNAUTHORIZED
    }

    /** The server's message when it reads as a sentence (it has spaces and isn't a long dump), else null. */
    private fun sentence(message: String): String? {
        val t = message.trim()
        return t.takeIf { it.contains(' ') && it.length <= 300 }
    }
}
