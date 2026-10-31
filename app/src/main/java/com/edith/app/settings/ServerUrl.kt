package com.edith.app.settings

import java.net.URI
import java.net.URISyntaxException

/** Turns what the owner types into a usable Ollama address, or says what's wrong with it (C04 §1). */
object ServerUrl {
    const val DEFAULT_PORT = 11434

    sealed interface Result {
        data class Valid(val url: String) : Result
        data class Invalid(val message: String) : Result
    }

    /** `192.168.4.82` → `http://192.168.4.82:11434`; `http://x:1234/` → `http://x:1234`. */
    fun normalize(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid("Enter your Ollama server's address.")
        if (trimmed.any { it.isWhitespace() }) return Result.Invalid("The address can't contain spaces.")

        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val uri = try {
            URI(withScheme.trimEnd('/'))
        } catch (e: URISyntaxException) {
            return Result.Invalid("That doesn't look like an address, e.g. http://192.168.4.82:11434.")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid("The address must start with http:// or https://.")
        val host = uri.host
        if (host.isNullOrEmpty() || !host.matches(Regex("""[A-Za-z0-9.-]+"""))) {
            return Result.Invalid("That doesn't look like an address, e.g. http://192.168.4.82:11434.")
        }
        if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null) {
            return Result.Invalid("Use just the server address and port, without a path.")
        }
        val port = if (uri.port == -1) DEFAULT_PORT else uri.port
        return Result.Valid("$scheme://$host:$port")
    }
}
