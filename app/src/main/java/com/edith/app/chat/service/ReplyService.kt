package com.edith.app.chat.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.edith.app.EdithApp
import com.edith.app.MainActivity
import com.edith.app.R
import com.edith.app.chat.engine.EngineState
import com.edith.app.chat.engine.isRunning
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a reply alive when the owner leaves the app (decision D13, lesson L24): Android cuts the network of an app
 * with nothing on screen after ~5 s, so while Edith is writing a reply the app runs this short foreground service,
 * with a quiet "Edith is replying…" notification (Android shows it only if the reply takes more than a few seconds).
 *
 * It's started by [ReplyKeepAlive] when a turn begins and stops itself when the engine is no longer running a turn,
 * so it's never stopped before it has gone foreground (a known crash). A "short service" needs no extra permission;
 * Android allows it about 3 minutes, far longer than a reply takes today.
 */
class ReplyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private val engine get() = (application as EdithApp).container.chatEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_REPLY) {
            Log.i(LogTag.Chat, "Reply stopped from the notification")
            engine.cancel()   // the watcher below then stops the service
            return START_NOT_STICKY
        }
        if (!goForeground()) return START_NOT_STICKY
        if (watching == null) {
            watching = scope.launch {
                engine.state.first { !it.isRunning() }   // already idle (a very short reply) → stops at once
                finish("reply ended")
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        true
    } catch (e: Exception) {
        // Never worth a crash: without the service a reply still finishes while the app is on screen.
        Log.w(LogTag.Chat, "Couldn't keep the reply alive in the background", e)
        stopSelf()
        false
    }

    /** Android's limit for a short service (~3 minutes). The reply carries on until Android cuts the network. */
    override fun onTimeout(startId: Int) = finish("Android's 3-minute limit was reached")
    override fun onTimeout(startId: Int, fgsType: Int) = finish("Android's 3-minute limit was reached")

    private fun finish(why: String) {
        Log.d(LogTag.Chat, "Reply service stopping: $why")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, ensureChannel(this))
        .setSmallIcon(R.drawable.ic_stat_reply)
        .setContentTitle("Edith is replying…")
        .setContentText("Your reply keeps going while you're away.")
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0, "Stop",
            PendingIntent.getService(this, 1, Intent(this, ReplyService::class.java).setAction(ACTION_STOP_REPLY), PendingIntent.FLAG_IMMUTABLE),
        )
        .build()

    companion object {
        private const val NOTIFICATION_ID = 7001
        private const val CHANNEL_ID = "replies"
        const val ACTION_STOP_REPLY = "com.edith.app.STOP_REPLY"

        private fun ensureChannel(context: Context): String {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Replies in progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Edith finishes a reply after you leave the app."
                    setShowBadge(false)
                },
            )
            return CHANNEL_ID
        }

        /** Starts the service. Only works while the app is on screen, which is when turns start (the owner tapped). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ReplyService::class.java))
            } catch (e: Exception) {
                val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    "the app wasn't on screen"
                } else e::class.simpleName
                Log.w(LogTag.Chat, "Couldn't start the reply service ($reason); the reply needs the app open", e)
            }
        }
    }
}

/**
 * Starts [ReplyService] whenever the engine begins a turn (D13). Kept apart from the service so the rule is testable.
 */
class ReplyKeepAlive(private val engineState: kotlinx.coroutines.flow.StateFlow<EngineState>, private val startService: () -> Unit) {
    /** Collects for as long as [scope] lives (the app). */
    fun watch(scope: CoroutineScope) {
        scope.launch {
            engineState.map { it.isRunning() }.distinctUntilChanged().filter { it }.collect { startService() }
        }
    }
}
