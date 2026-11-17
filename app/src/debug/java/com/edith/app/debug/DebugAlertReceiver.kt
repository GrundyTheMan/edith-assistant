package com.edith.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.edith.app.EdithApp
import com.edith.app.alerts.AlertKind
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.home.FakeHomeBackend
import kotlinx.coroutines.launch

/**
 * Debug builds only (C33): tries camera alerts on the emulator with the Demo home's doorbell. Only the shell can send it (DUMP).
 *
 *     adb shell am broadcast -n com.edith.app.debug/com.edith.app.debug.DebugAlertReceiver --es mode on     (Demo home on, doorbell alerts on)
 *     adb shell am broadcast -n com.edith.app.debug/com.edith.app.debug.DebugAlertReceiver --es mode ring   (the doorbell rings now)
 *     adb shell am broadcast -n com.edith.app.debug/com.edith.app.debug.DebugAlertReceiver --es mode off    (doorbell alerts off)
 */
class DebugAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as EdithApp).container
        val mode = intent.getStringExtra("mode").orEmpty()
        container.appScope.launch {
            when (mode) {
                "on" -> {
                    container.home.setDemo(true)
                    container.alertPrefs.set(FakeHomeBackend.ID, AlertKind.Doorbell, true)
                }
                "off" -> container.alertPrefs.set(FakeHomeBackend.ID, AlertKind.Doorbell, false)
                "ring" -> (container.home.backends.value.firstOrNull { it is FakeHomeBackend } as? FakeHomeBackend)?.ring()
            }
            Log.i(LogTag.Home, "DEBUG_ALERT: $mode done")
        }
    }
}
