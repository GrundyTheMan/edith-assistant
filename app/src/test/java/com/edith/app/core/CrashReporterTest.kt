package com.edith.app.core

import com.edith.app.core.log.LogRingBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class CrashReporterTest {
    @get:Rule val temp = TemporaryFolder()
    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var file: File

    @Before fun setUp() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        file = File(temp.root, "crash/last-crash.txt")
        LogRingBuffer.clear()
    }

    @After fun restore() = Thread.setDefaultUncaughtExceptionHandler(originalHandler)

    @Test fun `report has time, version, device, thread, stack and recent log lines`() {
        LogRingBuffer.append("Edith/Chat", "sent a message")
        CrashReporter.writeReport(
            file, Thread.currentThread(), IllegalStateException("boom"),
            "2.0.0-dev", "Google Pixel 9", "17", Instant.parse("2026-10-29T18:00:00Z"),
        )
        val text = CrashReporter.read(file)!!.text
        assertTrue(text.contains("Time (UTC): 2026-10-29T18:00:00Z"))
        assertTrue(text.contains("App version: 2.0.0-dev"))
        assertTrue(text.contains("Device: Google Pixel 9 (Android 17)"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(text.contains("sent a message"))
    }

    @Test fun `handler writes the report and delegates to the previous handler exactly once`() {
        val delegated = mutableListOf<Throwable>()
        val previous = Thread.UncaughtExceptionHandler { _, e -> delegated += e }
        Thread.setDefaultUncaughtExceptionHandler(previous)

        CrashReporter.install(file, "2.0.0-dev", "test", "17")
        val crash = RuntimeException("uncaught")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)

        assertEquals(1, delegated.size)          // the old version called itself until the stack overflowed
        assertSame(crash, delegated.single())
        assertTrue(CrashReporter.read(file)!!.text.contains("RuntimeException: uncaught"))
    }

    @Test fun `a failure while writing the report still delegates the original crash`() {
        val delegated = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> delegated += e }
        val unwritable = File(temp.newFile("not-a-folder"), "last-crash.txt")   // parent is a file

        CrashReporter.install(unwritable, "v", "d", "a")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), Error("x"))

        assertEquals(1, delegated.size)
    }

    @Test fun `no report means nothing pending`() {
        assertNull(CrashReporter.read(file))
    }
}
