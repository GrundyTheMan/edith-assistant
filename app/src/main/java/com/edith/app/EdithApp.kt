package com.edith.app

import android.app.Application
import android.os.StrictMode
import com.edith.app.core.CrashReporter
import com.edith.app.core.log.LogConfig
import com.edith.app.di.AppContainer

/** Application subclass that builds the dependency container and installs crash handler. */
class EdithApp : Application() {
    lateinit var container: AppContainer; private set

    override fun onCreate() {
        super.onCreate()
        LogConfig.debug = BuildConfig.DEBUG   // before anything logs
        com.edith.app.alerts.AlertCatalog.demoHome = BuildConfig.DEBUG   // the Demo home's doorbell is an alert source in debug builds only
        CrashReporter.install(this) // First, so later failures are captured
        if (BuildConfig.DEBUG) enableStrictMode()
        container = AppContainer(this)
        container.openDatabaseInBackground()   // C05: edith.db exists from first launch
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                // A release build already dies on network use on the main thread (NetworkOnMainThreadException); debug builds only logged
                // it, so the Alexa sign-in crashed on the owner's phone and nothing failed on the emulator. Fail the same way here.
                .penaltyDeathOnNetwork()
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
