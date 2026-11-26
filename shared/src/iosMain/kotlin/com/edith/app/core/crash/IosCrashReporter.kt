@file:OptIn(ExperimentalNativeApi::class, BetaInteropApi::class, ExperimentalForeignApi::class)

package com.edith.app.core.crash

import com.edith.app.core.log.LogRingBuffer
import com.edith.app.core.time.SystemClock
import com.edith.app.di.applicationSupportDir
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSThread
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIDevice
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

/**
 * Crash reports on the iPhone (D58), as Android's `CrashReporter` does: an uncaught Kotlin exception writes `crash/last-crash.txt` (what, when, build, device, stack, the last log
 * lines), and the next launch shows "Edith closed unexpectedly last time." with View, Copy and Dismiss, and Settings → About keeps "View last crash report".
 *
 * Kotlin's hook sees only Kotlin exceptions. A crash in Swift (`fatalError`, a bad memory access) or in a library leaves no hook call, so a **running marker** catches those: it is
 * written when Edith comes to the front and removed when she goes to the background; a marker still there at the next launch means she was closed while in front, and the report
 * says so and where the system's own crash log is. Written without a Mac to try it on: CI compiles it; a real crash on the owner's phone is the first real run.
 */
class IosCrashReporter(private val dir: String, private val version: String, private val device: String) {
    private val reportPath = "$dir/last-crash.txt"
    private val runningPath = "$dir/running"
    private val files = NSFileManager.defaultManager

    init {
        files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    }

    /** Installs the hook; call first thing at launch. The hook returns, then the runtime ends the process as it would have. */
    fun install() {
        setUnhandledExceptionHook { t ->
            runCatching {
                val thread = if (NSThread.isMainThread) "main" else "background"
                write(reportPath, CrashReportText.build(SystemClock.now().toString(), version, device, thread, t.stackTraceToString(), LogRingBuffer.tail(50)))
            }
        }
    }

    /**
     * What to show at launch, read **before** [markFront]: the written report if there is one, else the "no report" text when the running marker was left behind, else null.
     * A report the hook wrote wins over the marker (a Kotlin crash leaves both).
     */
    fun pending(): String? {
        read(reportPath)?.let { return it }
        if (!files.fileExistsAtPath(runningPath)) return null
        // Kept as a report until dismissed, so Settings → About can show it after this launch took the marker away.
        return CrashReportText.withoutReport(version, device).also { write(reportPath, it) }
    }

    /** Edith came to the front. */
    fun markFront() = write(runningPath, "running")

    /** Edith went to the background (a clean place to be ended from). */
    fun markBackground() { files.removeItemAtPath(runningPath, error = null) }

    /** The owner dismissed the report; the marker goes too, so one crash is told once. */
    fun dismiss() {
        files.removeItemAtPath(reportPath, error = null)
        files.removeItemAtPath(runningPath, error = null)
    }

    private fun write(path: String, text: String) {
        NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private fun read(path: String): String? =
        if (files.fileExistsAtPath(path)) NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) else null

    companion object {
        /** The reporter for this app: its folder under Application Support, the bundle's version and build, and the phone's model and system. */
        fun create(): IosCrashReporter {
            val info = NSBundle.mainBundle
            val shortVersion = info.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown"
            val build = info.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "?"
            val phone = UIDevice.currentDevice
            return IosCrashReporter(applicationSupportDir() + "/crash", "$shortVersion ($build)", "${phone.model} (iOS ${phone.systemVersion})")
        }
    }
}
