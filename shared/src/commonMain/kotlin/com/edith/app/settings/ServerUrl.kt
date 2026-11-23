package com.edith.app.settings

import io.ktor.http.URLBuilder

/** Turns what the owner types into a usable Ollama address, or says what's wrong with it (C04 §1). */
object ServerUrl {
    const val DEFAULT_PORT = 11434

    sealed interface Result {
        data class Valid(val url: String) : Result
        data class Invalid(val message: String) : Result
    }

    private const val NOT_AN_ADDRESS = "That doesn't look like an address, e.g. http://192.168.4.82:11434."

    /** `192.168.4.82` → `http://192.168.4.82:11434`; `http://x:1234/` → `http://x:1234`. */
    fun normalize(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid("Enter your Ollama server's address.")
        if (trimmed.any { it.isWhitespace() }) return Result.Invalid("The address can't contain spaces.")

        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        // Ktor fills an empty host in with "localhost"; the owner typed nothing after the scheme.
        if (withScheme.substringAfter("://").trimEnd('/').isEmpty()) return Result.Invalid(NOT_AN_ADDRESS)
        val url = try {
            URLBuilder(withScheme.trimEnd('/')).build()
        } catch (e: Exception) {   // Ktor's parser says why in several ways; to the owner it is all "not an address"
            return Result.Invalid(NOT_AN_ADDRESS)
        }
        val scheme = url.protocol.name.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid("The address must start with http:// or https://.")
        val host = url.host
        if (host.isEmpty() || !host.matches(Regex("""[A-Za-z0-9.-]+"""))) return Result.Invalid(NOT_AN_ADDRESS)
        if ((url.encodedPath.isNotEmpty() && url.encodedPath != "/") || url.encodedQuery.isNotEmpty()) {
            return Result.Invalid("Use just the server address and port, without a path.")
        }
        val port = if (url.specifiedPort == 0) DEFAULT_PORT else url.specifiedPort
        return Result.Valid("$scheme://$host:$port")
    }
}
