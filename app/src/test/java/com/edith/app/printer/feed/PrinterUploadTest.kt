package com.edith.app.printer.feed

import okio.Buffer
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** C39 E11: what may be sent from the phone (the same rules as Jarvis's `files/upload`), and the streaming body. */
class PrinterUploadTest {
    // --- The gates --------------------------------------------------------------------------------------------------------

    @Test fun `a gcode and a 3mf name are accepted, and the capitals of the name are fine`() {
        assertNull(PrinterUpload.problem("benchy.gcode", 1000))
        assertNull(PrinterUpload.problem("Benchy v2 (final)+test-1.3mf", 1000))
        assertNull(PrinterUpload.problem("BENCHY.gcode", 1000))
    }

    @Test fun `the ending must be in lower case, as Jarvis's pattern has no case flag`() {
        assertEquals(PrinterUpload.NAME_WORDS, PrinterUpload.problem("BENCHY.GCODE", 1000))
        assertEquals(PrinterUpload.NAME_WORDS, PrinterUpload.problem("x.3MF", 1000))
    }

    @Test fun `any other extension is refused with the words for it`() {
        assertEquals(PrinterUpload.NAME_WORDS, PrinterUpload.problem("benchy.stl", 1000))
        assertEquals(PrinterUpload.NAME_WORDS, PrinterUpload.problem("benchy.gcode.txt", 1000))
        assertEquals(PrinterUpload.NAME_WORDS, PrinterUpload.problem(".gcode", 1000))
        assertEquals(PrinterUpload.NAME_WORDS, PrinterUpload.problem("", 1000))
    }

    @Test fun `characters Jarvis does not allow in a name are refused`() {
        for (bad in listOf("a/b.gcode", "a\\b.gcode", "a:b.gcode", "a[1].gcode", "ä.gcode", "a\nb.gcode", "a;b.gcode", "..\\x.gcode")) {
            assertEquals("$bad", PrinterUpload.NAME_WORDS, PrinterUpload.problem(bad, 1000))
        }
    }

    @Test fun `a name of 100 characters before the ending is the longest allowed`() {
        val ok = "a".repeat(100) + ".gcode"
        assertNull(PrinterUpload.problem(ok, 1000))
        assertEquals(PrinterUpload.NAME_WORDS, PrinterUpload.problem("a$ok", 1000))
    }

    @Test fun `the size gates are 64 MiB exactly, not empty, and known`() {
        assertNull(PrinterUpload.problem("a.gcode", PrinterUpload.MAX_BYTES))
        assertEquals(PrinterUpload.TOO_BIG_WORDS, PrinterUpload.problem("a.gcode", PrinterUpload.MAX_BYTES + 1))
        assertEquals(PrinterUpload.EMPTY_WORDS, PrinterUpload.problem("a.gcode", 0))
        assertEquals(PrinterUpload.SIZE_UNKNOWN_WORDS, PrinterUpload.problem("a.gcode", -1))
        assertEquals(64L * 1024 * 1024, PrinterUpload.MAX_BYTES)
    }

    @Test fun `the name is checked before the size`() {
        assertEquals(PrinterUpload.NAME_WORDS, PrinterUpload.problem("a.stl", PrinterUpload.MAX_BYTES + 1))
    }

    // --- The body -----------------------------------------------------------------------------------------------------------

    private fun write(source: InputStream, size: Long, progress: MutableList<Long> = mutableListOf()): Buffer {
        val sink = Buffer()
        PrinterUpload.body(source, size, null) { progress += it }.writeTo(sink)
        return sink
    }

    @Test fun `the body writes exactly the bytes of the file`() {
        val bytes = ByteArray(300_000) { (it * 7 % 251).toByte() }
        assertArrayEquals(bytes, write(ByteArrayInputStream(bytes), bytes.size.toLong()).readByteArray())
    }

    @Test fun `progress rises to the full size, in steps no bigger than one buffer`() {
        val bytes = ByteArray(200_000)
        val progress = mutableListOf<Long>()
        write(ByteArrayInputStream(bytes), bytes.size.toLong(), progress)
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b > a && b - a <= 64 * 1024 })
        assertTrue("progress is reported more than once", progress.size > 1)
    }

    @Test fun `a file that grew after it was measured is cut at the promised size`() {
        val bytes = ByteArray(10_000) { 7 }
        val out = write(ByteArrayInputStream(bytes), 6_000)
        assertEquals(6_000L, out.size)
    }

    @Test fun `the body never holds more than a buffer, even for a stream of the cap's size`() {
        // A stream that makes bytes up as it is read: if the body read it all first this would need 64 MiB at once.
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = if (served < PrinterUpload.MAX_BYTES) { served++; 1 } else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= PrinterUpload.MAX_BYTES) return -1
                val n = minOf(len.toLong(), PrinterUpload.MAX_BYTES - served).toInt()
                served += n
                return n
            }
        }
        var lastProgress = 0L
        var biggestBuffered = 0L
        val sink = okio.blackholeSink().buffer()
        PrinterUpload.body(endless, PrinterUpload.MAX_BYTES, null) { sent -> lastProgress = sent; biggestBuffered = maxOf(biggestBuffered, sink.buffer.size) }.writeTo(sink)
        assertEquals(PrinterUpload.MAX_BYTES, lastProgress)
        assertTrue("the sink was emptied as it went (most held: $biggestBuffered)", biggestBuffered <= 128 * 1024)
    }

    @Test fun `the body is one-shot and says how long it is`() {
        val body = PrinterUpload.body(ByteArrayInputStream(ByteArray(10)), 10, null) {}
        assertTrue(body.isOneShot())
        assertEquals(10L, body.contentLength())
    }
}
