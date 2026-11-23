@file:OptIn(ExperimentalAtomicApi::class)

package com.edith.app.core.log

import com.edith.app.core.time.SystemClock
import com.edith.app.core.time.formatPattern
import kotlinx.datetime.TimeZone
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Fixed log tags (C02 §4). Filter with e.g. `adb logcat -s Edith/Ollama:*`. */
enum class LogTag(val value: String) {
    App("Edith/App"),
    Chat("Edith/Chat"),
    Ollama("Edith/Ollama"),
    Tools("Edith/Tools"),
    Voice("Edith/Voice"),
    Attachments("Edith/Attachments"),
    Google("Edith/Google"),
    Accounts("Edith/Accounts"),
    Home("Edith/Home"),
}

/** What the app tells the log about itself, once, at startup. */
object LogConfig {
    /** A debug build: personal content is shown in the log ([Log.redact]) instead of only its length. */
    @kotlin.concurrent.Volatile
    var debug: Boolean = false
}

/** How serious a log line is. */
enum class LogLevel { Debug, Info, Warn, Error }

/** In-memory ring buffer for the last [CAPACITY] log lines, used by the crash reporter. Thread-safe (the list is swapped whole). */
object LogRingBuffer {
    const val CAPACITY = 200
    private val lines = AtomicReference<List<String>>(emptyList())

    fun append(tag: String, msg: String) {
        val line = "${formatPattern(SystemClock.now(), TimeZone.currentSystemDefault(), "yyyy-MM-dd HH:mm:ss.SSS", "en-US")} [$tag] $msg"
        while (true) {
            val old = lines.load()
            if (lines.compareAndSet(old, (old + line).takeLast(CAPACITY))) return
        }
    }

    /** The last [count] lines, oldest first. */
    fun tail(count: Int = 50): List<String> = lines.load().takeLast(count)

    fun clear() = lines.store(emptyList())
}

/**
 * Logging wrapper (C02 §4): the platform's log plus the crash-report ring buffer. Messages are logged as given, so
 * wrap anything personal (message text, email bodies, transcripts) in [redact]; never log secrets at all.
 */
object Log {
    fun d(tag: LogTag, msg: String, t: Throwable? = null) = write(LogLevel.Debug, tag, msg, t)
    fun i(tag: LogTag, msg: String, t: Throwable? = null) = write(LogLevel.Info, tag, msg, t)
    fun w(tag: LogTag, msg: String, t: Throwable? = null) = write(LogLevel.Warn, tag, msg, t)
    fun e(tag: LogTag, msg: String, t: Throwable? = null) = write(LogLevel.Error, tag, msg, t)

    /** Personal content: shown in debug builds, only its length in release builds. */
    fun redact(text: String): String = redact(text, LogConfig.debug)

    internal fun redact(text: String, debug: Boolean): String = if (debug) text else "<${text.length} chars>"

    private fun write(level: LogLevel, tag: LogTag, msg: String, t: Throwable?) {
        val debug = LogConfig.debug
        val full = if (t != null) "$msg\n${t.logTrace(debug)}" else msg
        platformLog(level, tag.value, full)
        LogRingBuffer.append(tag.value, if (t != null) "$msg (${t.logSummary(debug)})" else msg)
    }

    /**
     * A parse error's message quotes the text it couldn't read (an email, an event, a reply), so in release builds
     * it shows only the type. Other messages (a host, an HTTP status) are kept: they're what makes a log useful.
     */
    internal fun summary(t: Throwable, debug: Boolean): String = t.logSummary(debug)

    /** Like the platform's stack trace string, with [summary] for the throwable and each cause. */
    internal fun stackTrace(t: Throwable, debug: Boolean): String = t.logTrace(debug)
}

/** Writes one line to the platform's log (logcat on Android). */
internal expect fun platformLog(level: LogLevel, tag: String, message: String)

/** [Log.summary]. */
internal expect fun Throwable.logSummary(debug: Boolean): String

/** [Log.stackTrace]. */
internal expect fun Throwable.logTrace(debug: Boolean): String
