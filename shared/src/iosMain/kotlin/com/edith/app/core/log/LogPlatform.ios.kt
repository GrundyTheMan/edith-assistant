package com.edith.app.core.log

internal actual fun platformLog(level: LogLevel, tag: String, message: String) {
    println("[$tag] $message")
}

internal actual fun Throwable.logSummary(debug: Boolean): String =
    if (debug || !quotesInput(this)) toString() else "${this::class.simpleName}: <message hidden>"

internal actual fun Throwable.logTrace(debug: Boolean): String =
    if (debug || !quotesInput(this)) stackTraceToString().trimEnd() else logSummary(false)

private fun quotesInput(t: Throwable): Boolean = t is kotlinx.serialization.SerializationException
