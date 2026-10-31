package com.edith.app.debug

import android.app.Activity
import android.os.Bundle
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag

/** Debug builds only. Crashes on purpose so crash reports can be tested: `adb shell am start -a com.edith.app.DEBUG_CRASH`. */
class DebugCrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.w(LogTag.App, "Crashing on purpose (DEBUG_CRASH)")
        throw DebugCrashException()
    }
}

class DebugCrashException : RuntimeException("Deliberate crash requested with adb (com.edith.app.DEBUG_CRASH)")
