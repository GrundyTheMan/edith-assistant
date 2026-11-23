package com.edith.app.core.log

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogTest {
    @BeforeTest fun clear() = LogRingBuffer.clear()

    @Test fun `ring buffer keeps the last 200 lines in order`() {
        repeat(250) { LogRingBuffer.append("Edith/Test", "line $it") }
        val all = LogRingBuffer.tail(1000)
        assertEquals(200, all.size)
        assertTrue(all.first().endsWith("[Edith/Test] line 50"))
        assertTrue(all.last().endsWith("[Edith/Test] line 249"))
    }

    @Test fun `tail returns the newest lines, oldest first`() {
        repeat(5) { LogRingBuffer.append("Edith/Test", "line $it") }
        assertEquals(listOf("line 3", "line 4"), LogRingBuffer.tail(2).map { it.substringAfter("] ") })
    }

    @Test fun `log calls land in the ring buffer with their tag`() {
        Log.i(LogTag.Ollama, "Chat stream started")
        assertTrue(LogRingBuffer.tail(1).single().endsWith("[Edith/Ollama] Chat stream started"))
    }

    private val email = """{"id":"a1","payload":{"headers":[{"name":"Subject","value":"Biopsy results"}]},"snippet":"Your results are in"""

    private fun parseError(): Throwable = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(email)
    }.exceptionOrNull()!!

    @Test fun `a parse error quotes what it couldn't read`() {
        // Why release builds hide these messages: kotlinx puts the input in them.
        assertTrue(parseError().message!!.contains("Biopsy results"))
    }

    @Test fun `in release a parse error and its wrapper show only their types`() {
        val wrapped = RuntimeException("Unreadable reply", parseError())
        val trace = Log.stackTrace(wrapped, debug = false)
        assertFalse(trace.contains("Biopsy") || trace.contains("Your results"))
        assertTrue(trace.startsWith("java.lang.RuntimeException: Unreadable reply\n\tat "))
        assertTrue(Regex("""Caused by: kotlinx\.serialization\.[\w.]+: <message hidden>\n""").containsMatchIn(trace))
        assertTrue(trace.contains("\tat kotlinx.serialization."))
        assertFalse(Log.summary(parseError(), debug = false).contains("Biopsy"))
    }

    @Test fun `debug builds and other errors keep their messages`() {
        assertTrue(Log.stackTrace(parseError(), debug = true).contains("Biopsy results"))
        val io = java.io.IOException("failed to connect to /10.0.2.2 (port 11434)")
        assertEquals(io.toString(), Log.summary(io, debug = false))
    }

    @Test fun `redact hides content in release and keeps it in debug`() {
        assertEquals("<11 chars>", Log.redact("hello there", debug = false))
        assertEquals("hello there", Log.redact("hello there", debug = true))
    }
}
