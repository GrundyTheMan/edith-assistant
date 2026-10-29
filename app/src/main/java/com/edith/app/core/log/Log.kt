package com.edith.app.core.log

import android.util.Log as AndroidLog
import java.time.Instant
import java.text.SimpleDateFormat
import java.util.*

/** In-memory ring buffer for the last 200 log lines, used by [CrashReporter]. Thread-safe: any thread logs. */
object LogRingBuffer {
    private val buffer = ArrayDeque<String>(200)

    fun append(tag: String, msg: String) {
        val line = formatLine(tag, msg)
        synchronized(buffer) {
            buffer.add(line)
            if (buffer.size > 200) buffer.removeFirst()
        }
    }

    /** Returns the last [count] lines in FIFO order. */
    fun tail(count: Int = 50): List<String> = synchronized(buffer) { buffer.toList().takeLast(count) }

    fun clear() = synchronized(buffer) { buffer.clear() }

    private fun formatLine(tag: String, msg: String): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.US).format(Instant.now().toEpochMilli())
        return "$time [$tag] $msg"
    }
}

/** Logging wrapper with fixed tags. Releases redact message content. */
object Log {
    enum class LogTag(val value: String) {
        App("Edith/App"),
        Chat("Edith/Chat"),
        Ollama("Edith/Ollama"),
        Tools("Edith/Tools"),
        Voice("Edith/Voice"),
        Attachments("Edith/Attachments"),
        Google("Edith/Google")
    }

    private fun debug(tag: String, msg: String) {
        AndroidLog.d(tag, msg)
        LogRingBuffer.append(tag, msg)
    }

    private fun info(tag: String, msg: String) {
        AndroidLog.i(tag, msg)
        LogRingBuffer.append(tag, msg)
    }

    private fun warn(tag: String, msg: String) {
        AndroidLog.w(tag, msg)
        LogRingBuffer.append(tag, msg)
    }

    private fun error(tag: String, msg: String) {
        AndroidLog.e(tag, msg)
        LogRingBuffer.append(tag, msg)
    }

    /** Debug log. Releases redact message content. */
    fun d(tag: LogTag, msg: String, t: Throwable? = null) {
        val redacted = redact(msg)
        debug(tag.value, "$redacted" + if (t != null) " ($t)" else "")
        t?.let { error -> AndroidLog.e(tag.value, "Exception in log.d: $redacted", error) }
    }

    /** Info log. */
    fun i(tag: LogTag, msg: String, t: Throwable? = null) {
        val redacted = redact(msg)
        info(tag.value, "$redacted" + if (t != null) " ($t)" else "")
        t?.let { error -> AndroidLog.e(tag.value, "Exception in log.i: $redacted", error) }
    }

    /** Warn log. */
    fun w(tag: LogTag, msg: String, t: Throwable? = null) {
        val redacted = redact(msg)
        warn(tag.value, "$redacted" + if (t != null) " ($t)" else "")
        t?.let { error -> AndroidLog.e(tag.value, "Exception in log.w: $redacted", error) }
    }

    /** Error log. */
    fun e(tag: LogTag, msg: String, t: Throwable? = null) {
        val redacted = redact(msg)
        error(tag.value, "$redacted" + if (t != null) " ($t)" else "")
        t?.let { AndroidLog.e(tag.value, "Exception in log.e: $redacted", it) }
    }

    /** Redacts message content - C02 uses debug mode only. */
    fun redact(text: String): String {
        return text
    }
}
