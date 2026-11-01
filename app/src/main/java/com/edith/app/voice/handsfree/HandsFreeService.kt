package com.edith.app.voice.handsfree

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.edith.app.EdithApp
import com.edith.app.MainActivity
import com.edith.app.R
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps hands-free running (C14 §2): a microphone foreground service with an ongoing notification, so the mic is
 * never live without one. [HandsFree] does the work; this service shows it and stops it for:
 * - a phone call (the audio mode changes to a call or ringing)
 * - another app taking the microphone (Android silences Edith's recording)
 * - the notification's Stop
 *
 * It must be started while the app is on screen (Android 14+ rule for the microphone), from the hands-free button.
 * It stops itself when hands-free turns off.
 */
class HandsFreeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as EdithApp).container
    private val handsFree get() = container.handsFree
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { handsFree.stop(); return START_NOT_STICKY }
            ACTION_PAUSE -> { handsFree.pause(); return START_NOT_STICKY }
            ACTION_RESUME -> { handsFree.resume(); return START_NOT_STICKY }
        }
        if (!goForeground(HandsFree.State.Listening)) {
            handsFree.stop("Hands-free couldn't start. Open Edith and try again.")
            return START_NOT_STICKY
        }
        handsFree.start()
        if (!watching) watch()
        return START_NOT_STICKY
    }

    private fun goForeground(state: HandsFree.State): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(state), type)
        true
    } catch (e: Exception) {
        Log.w(LogTag.Voice, "Couldn't start the hands-free service", e)
        stopSelf()
        false
    }

    private fun watch() {
        watching = true
        scope.launch {
            handsFree.state.map { it.notificationGroup() }.distinctUntilChanged().collect { state ->
                if (state == HandsFree.State.Off) {
                    finish()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.addOnModeChangedListener(ContextCompat.getMainExecutor(this), modeListener)
        } else {
            scope.launch {   // no mode listener before Android 12: check every 2 s
                while (isActive) {
                    if (isCall(audio.mode)) handsFree.stop(HandsFree.CALL)
                    delay(2_000)
                }
            }
        }
        audio.registerAudioRecordingCallback(recordingCallback, Handler(Looper.getMainLooper()))
    }

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        if (isCall(mode)) handsFree.stop(HandsFree.CALL)
    }

    private fun isCall(mode: Int) = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
        mode == AudioManager.MODE_RINGTONE || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING)

    /** Android silences an app's recording when another app takes the microphone (Android 10+). */
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val session = container.wakeListener.audioSessionId
            if (configs.any { it.clientAudioSessionId == session && it.isClientSilenced } &&
                handsFree.state.value == HandsFree.State.Listening
            ) {
                handsFree.stop(HandsFree.MIC_TAKEN)
            }
        }
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (watching) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.removeOnModeChangedListener(modeListener)
            audio.unregisterAudioRecordingCallback(recordingCallback)
        }
        scope.cancel()
        handsFree.stop()   // never a live microphone without this service's notification
        super.onDestroy()
    }

    private fun notification(state: HandsFree.State): android.app.Notification {
        val paused = state == HandsFree.State.Paused
        val text = when (state) {
            HandsFree.State.Paused -> "Paused. The microphone is off."
            else -> "Listening for “${handsFree.phrase}”"
        }
        fun action(action: String, code: Int) =
            PendingIntent.getService(this, code, Intent(this, HandsFreeService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, ensureChannel(this))
            .setSmallIcon(R.drawable.ic_stat_hands_free)
            .setContentTitle("Edith hands-free")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(0, if (paused) "Resume" else "Pause", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "Stop", action(ACTION_STOP, 2))
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 7002
        private const val CHANNEL_ID = "hands_free"
        const val ACTION_STOP = "com.edith.app.HANDS_FREE_STOP"
        const val ACTION_PAUSE = "com.edith.app.HANDS_FREE_PAUSE"
        const val ACTION_RESUME = "com.edith.app.HANDS_FREE_RESUME"

        /** The notification only changes between listening, paused and off. */
        private fun HandsFree.State.notificationGroup(): HandsFree.State = when (this) {
            HandsFree.State.Off, HandsFree.State.Paused -> this
            else -> HandsFree.State.Listening
        }

        private fun ensureChannel(context: Context): String {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Hands-free", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Edith listens for her wake word."
                    setShowBadge(false)
                },
            )
            return CHANNEL_ID
        }

        /** Turns hands-free on. Only works while the app is on screen (the owner tapped the button). */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, HandsFreeService::class.java))
            true
        } catch (e: Exception) {
            val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                "the app wasn't on screen"
            } else e::class.simpleName
            Log.w(LogTag.Voice, "Couldn't start hands-free ($reason)", e)
            false
        }
    }
}
