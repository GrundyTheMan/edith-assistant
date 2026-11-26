package com.edith.app.core.crash

/**
 * The text of a crash report (C02 §5, D58), the same layout as Android's `CrashReporter`: what, when, which build and device, the stack and the last log lines.
 * Pure, so it is tested on the desktop; the platform writes it to a file and shows it in the crash banner and Settings → About.
 */
object CrashReportText {
    fun build(time: String, version: String, device: String, thread: String, stack: String, logTail: List<String>): String = buildString {
        appendLine("Edith crash report")
        appendLine("Time (UTC): $time")
        appendLine("App version: $version")
        appendLine("Device: $device")
        appendLine("Thread: $thread")
        appendLine()
        appendLine(stack)
        appendLine("Last log lines:")
        logTail.forEach { appendLine(it) }
    }

    /**
     * What the owner sees when the app was gone without writing a report (the previous run never reached the background and left no file): a crash in
     * native code (a Swift `fatalError`, a bad memory access), or the system ending the app. There is no stack; the words say where to look.
     */
    fun withoutReport(version: String, device: String): String = buildString {
        appendLine("Edith crash report")
        appendLine("App version: $version")
        appendLine("Device: $device")
        appendLine()
        appendLine("Edith was closed while it was open, and no report was written. That usually means a crash in the phone's own code or in a library (not in Edith's Kotlin code),")
        appendLine("or the system ending the app, for example for using too much memory. It can also be a force-quit while Edith was in front.")
        appendLine("The system's own crash log has the details: in the TestFlight app, open Edith, then Crashes; or iPhone Settings → Privacy & Security → Analytics & Improvements → Analytics Data.")
    }
}
