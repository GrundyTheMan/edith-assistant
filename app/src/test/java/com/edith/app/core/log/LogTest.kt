package com.edith.app.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LogTest {
    @Before fun clear() = LogRingBuffer.clear()

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

    @Test fun `redact hides content in release and keeps it in debug`() {
        assertEquals("<11 chars>", Log.redact("hello there", debug = false))
        assertEquals("hello there", Log.redact("hello there", debug = true))
    }
}
