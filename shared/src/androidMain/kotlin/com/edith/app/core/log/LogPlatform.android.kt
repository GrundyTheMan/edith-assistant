package com.edith.app.core.log

import android.util.Log as AndroidLog

internal actual fun platformLog(level: LogLevel, tag: String, message: String) {
    val priority = when (level) {
        LogLevel.Debug -> AndroidLog.DEBUG
        LogLevel.Info -> AndroidLog.INFO
        LogLevel.Warn -> AndroidLog.WARN
        LogLevel.Error -> AndroidLog.ERROR
    }
    AndroidLog.println(priority, tag, message)
}

internal actual fun Throwable.logSummary(debug: Boolean): String =
    if (debug || !quotesInput(this)) toString() else "${javaClass.name}: <message hidden>"

internal actual fun Throwable.logTrace(debug: Boolean): String = buildString {
    var current: Throwable? = this@logTrace
    var first = true
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        if (!first) append("Caused by: ")
        append(current.logSummary(debug)).append('\n')
        current.stackTrace.forEach { append("\tat ").append(it).append('\n') }
        current = current.cause
        first = false
    }
}.trimEnd()

private fun quotesInput(t: Throwable): Boolean =
    t is kotlinx.serialization.SerializationException ||
        t.stackTrace.take(8).any { it.className.startsWith("kotlinx.serialization.") }
