package com.edith.app.core

import android.content.Context
import android.os.Build
import com.edith.app.EdithApp
import com.edith.app.core.log.LogRingBuffer
import com.edith.app.di.AppContainer
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Installs a default uncaught exception handler that writes crash reports (C02 §5). */
object CrashReporter {

    /** Writes `filesDir/crash/last-crash.txt` on any uncaught exception, then lets Android handle the crash. */
    fun install(context: Context) {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (e: Exception) {
            "unknown"
        }
        install(reportFile(context), versionName, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE.orEmpty())
    }

    /** Testable core of [install]. */
    internal fun install(file: File, versionName: String, device: String, androidVersion: String) {
        // Capture the previous handler BEFORE replacing it, so we delegate to it (and never to ourselves).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(file, thread, throwable, versionName, device, androidVersion, Instant.now())
            } catch (e: Throwable) {
                // A failure while reporting must never hide the original crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Reads the last crash report. Returns null if no report or unreadable. */
    fun pending(context: Context): CrashReport? = read(reportFile(context))

    /** Deletes the last crash report. */
    fun dismiss(context: Context) {
        reportFile(context).delete()
    }

    internal fun read(file: File): CrashReport? =
        if (!file.exists()) null else try { CrashReport(file.readText()) } catch (e: Exception) { null }

    internal fun writeReport(
        file: File, thread: Thread, throwable: Throwable,
        versionName: String, device: String, androidVersion: String, now: Instant,
    ) {
        file.parentFile?.mkdirs()
        val report = buildString {
            appendLine("Edith crash report")
            appendLine("Time (UTC): ${DateTimeFormatter.ISO_INSTANT.format(now)}")
            appendLine("App version: $versionName")
            appendLine("Device: $device (Android $androidVersion)")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine(throwable.stackTraceToString())
            appendLine("Last log lines:")
            LogRingBuffer.tail(50).forEach { appendLine(it) }
        }
        file.writeText(report)
    }

    private fun reportFile(context: Context) = File(context.filesDir, "crash/last-crash.txt")

    /** Crash report text for UI display. */
    data class CrashReport(val text: String)
}

/** The app's [AppContainer], from any Context. */
val Context.container: AppContainer
    get() = (this.applicationContext as EdithApp).container
