package com.edith.app.core.log

import com.edith.app.BuildConfig
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import android.util.Log as AndroidLog

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

/** In-memory ring buffer for the last [CAPACITY] log lines, used by [com.edith.app.core.CrashReporter]. Thread-safe. */
object LogRingBuffer {
    const val CAPACITY = 200
    private val buffer = ArrayDeque<String>(CAPACITY)
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun append(tag: String, msg: String) {
        val line = "${LocalDateTime.now().format(timeFormat)} [$tag] $msg"
        synchronized(buffer) {
            buffer.addLast(line)
            if (buffer.size > CAPACITY) buffer.removeFirst()
        }
    }

    /** The last [count] lines, oldest first. */
    fun tail(count: Int = 50): List<String> = synchronized(buffer) { buffer.toList().takeLast(count) }

    fun clear() = synchronized(buffer) { buffer.clear() }
}

/**
 * Logging wrapper (C02 §4): Android's log plus the crash-report ring buffer. Messages are logged as given, so
 * wrap anything personal (message text, email bodies, transcripts) in [redact]; never log secrets at all.
 */
object Log {
    fun d(tag: LogTag, msg: String, t: Throwable? = null) = write(AndroidLog.DEBUG, tag, msg, t)
    fun i(tag: LogTag, msg: String, t: Throwable? = null) = write(AndroidLog.INFO, tag, msg, t)
    fun w(tag: LogTag, msg: String, t: Throwable? = null) = write(AndroidLog.WARN, tag, msg, t)
    fun e(tag: LogTag, msg: String, t: Throwable? = null) = write(AndroidLog.ERROR, tag, msg, t)

    /** Personal content: shown in debug builds, only its length in release builds. */
    fun redact(text: String): String = redact(text, BuildConfig.DEBUG)

    internal fun redact(text: String, debug: Boolean): String = if (debug) text else "<${text.length} chars>"

    private fun write(priority: Int, tag: LogTag, msg: String, t: Throwable?) {
        val full = if (t != null) "$msg\n${stackTrace(t, BuildConfig.DEBUG)}" else msg
        AndroidLog.println(priority, tag.value, full)
        LogRingBuffer.append(tag.value, if (t != null) "$msg (${summary(t, BuildConfig.DEBUG)})" else msg)
    }

    /**
     * A parse error's message quotes the text it couldn't read (an email, an event, a reply), so in release builds
     * it shows only the type. Other messages (a host, an HTTP status) are kept: they're what makes a log useful.
     */
    internal fun summary(t: Throwable, debug: Boolean): String =
        if (debug || !quotesInput(t)) t.toString() else "${t.javaClass.name}: <message hidden>"

    /** Like Android's stack trace string, with [summary] for the throwable and each cause. */
    internal fun stackTrace(t: Throwable, debug: Boolean): String = buildString {
        var current: Throwable? = t
        var first = true
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (!first) append("Caused by: ")
            append(summary(current, debug)).append('\n')
            current.stackTrace.forEach { append("\tat ").append(it).append('\n') }
            current = current.cause
            first = false
        }
    }.trimEnd()

    private fun quotesInput(t: Throwable): Boolean =
        t is kotlinx.serialization.SerializationException ||
            t.stackTrace.take(8).any { it.className.startsWith("kotlinx.serialization.") }
}
