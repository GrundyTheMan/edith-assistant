package com.edith.app

import android.app.Application
import android.os.StrictMode
import com.edith.app.core.CrashReporter
import com.edith.app.di.AppContainer

/** Application subclass that builds the dependency container and installs crash handler. */
class EdithApp : Application() {
    lateinit var container: AppContainer; private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this) // First, so later failures are captured
        if (BuildConfig.DEBUG) enableStrictMode()
        container = AppContainer(this)
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
    }
}
