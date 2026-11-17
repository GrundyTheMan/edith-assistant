package com.edith.app.alerts

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.edith.app.EdithApp
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches the owner's cameras while any alert is switched on (C33). A foreground service, because Android cuts an app's
 * network a few seconds after it leaves the screen unless one is running (L24); WorkManager's 15-minute floor is too slow
 * for a doorbell. It stops itself when the last alert is switched off.
 */
class AlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as EdithApp).container
        val notifier = container.alertNotifier
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notifier.watching(), type)
        } catch (e: Exception) {
            Log.w(LogTag.App, "Couldn't start watching the cameras in the background", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            val watcher = AlertWatcher(container.activitySource, container.clock)
            scope.launch {
                container.alertPrefs.choices.map { it.any }.distinctUntilChanged().collect { if (!it) stop() }
            }
            scope.launch {
                while (isActive) {
                    val choices = container.alertPrefs.choices.first()
                    if (!choices.any) { stop(); return@launch }
                    val round = try {
                        watcher.check(choices)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(LogTag.App, "An alert check failed (${e::class.simpleName})")   // a surprise from a service: carry on
                        AlertWatcher.Round(emptyList(), AlertWatcher.MAX_BACKOFF_MS)
                    }
                    for (alert in round.alerts) {
                        notifier.show(alert)
                        if (alert.kind == AlertKind.Doorbell) scope.launch { addPicture(alert) }
                    }
                    delay(round.nextInMs)
                }
            }
        }
        return START_STICKY
    }

    /** A doorbell ring gets its camera's picture a few seconds later (a battery doorbell must wake first); the alert is already showing. */
    private suspend fun addPicture(alert: Alert) {
        val container = (application as EdithApp).container
        val camera = container.home.alertCamera(alert.backend, alert.device) ?: return
        val bytes = (container.home.snapshot(camera) as? Outcome.Success)?.value ?: return
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        container.alertNotifier.show(alert, bitmap)
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 7003

        /** Starts the service. It works while the app is on screen or just after boot; otherwise Android refuses and it is logged. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AlertService::class.java))
            } catch (e: Exception) {
                val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) "the app wasn't on screen" else e::class.simpleName
                Log.w(LogTag.App, "Couldn't start the camera alerts ($reason)", e)
            }
        }
    }
}

/**
 * Starts [AlertService] when the owner switches the first alert on, and when Edith opens with alerts already on. Kept
 * apart from the service so the rule is testable.
 */
class AlertKeepAlive(private val choices: kotlinx.coroutines.flow.Flow<AlertChoices>, private val startService: () -> Unit) {
    fun watch(scope: CoroutineScope) {
        scope.launch { choices.map { it.any }.distinctUntilChanged().collect { if (it) startService() } }
    }
}

/** After the phone restarts, watching starts again if alerts are on (the boot is one of the few times Android lets a service start in the background). */
class AlertBootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val container = (context.applicationContext as EdithApp).container
                if (container.alertPrefs.choices.first().any) AlertService.start(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
