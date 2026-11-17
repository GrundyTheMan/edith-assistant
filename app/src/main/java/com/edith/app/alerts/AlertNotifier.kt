package com.edith.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.edith.app.MainActivity
import com.edith.app.R
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag

/** Shows camera alerts (C33): one notification channel per kind, so the owner can silence one in Android's settings. */
class AlertNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        ensureChannels()
    }

    /** [picture] (a JPEG's bitmap) is added when there is one; showing the same alert again with a picture replaces it quietly. */
    fun show(alert: Alert, picture: Bitmap? = null) {
        if (!canNotify()) {
            Log.w(LogTag.App, "An alert couldn't be shown: notifications aren't allowed")
            return
        }
        val builder = NotificationCompat.Builder(context, alert.kind.channelId)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(title(alert))
            .setContentText(alert.device)
            .setWhen(alert.at)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openClips())
        if (picture != null) builder.setLargeIcon(picture).setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?))
        manager.notify(alert.key.hashCode(), builder.build())
    }

    /** The notification [AlertService] shows while it watches (Android requires one for a service that keeps running). */
    fun watching(): android.app.Notification = NotificationCompat.Builder(context, WATCHING_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_alert)
        .setContentTitle("Edith is watching your cameras")
        .setContentText("Alerts are on. Turn them off in Settings → Home.")
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openClips(): PendingIntent = PendingIntent.getActivity(
        context, 2,
        Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_CLIPS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannels() {
        fun channel(kind: AlertKind, importance: Int, about: String) = NotificationChannel(kind.channelId, kind.label, importance).apply { description = about }
        manager.createNotificationChannel(channel(AlertKind.Doorbell, NotificationManager.IMPORTANCE_HIGH, "Someone rang a doorbell."))
        manager.createNotificationChannel(channel(AlertKind.Person, NotificationManager.IMPORTANCE_DEFAULT, "A camera saw a person."))
        manager.createNotificationChannel(channel(AlertKind.Motion, NotificationManager.IMPORTANCE_DEFAULT, "A camera saw motion."))
        manager.createNotificationChannel(
            NotificationChannel(WATCHING_CHANNEL, "Watching your cameras", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while camera alerts are on, so Android lets Edith keep checking in the background."
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val WATCHING_CHANNEL = "alerts_watching"

        /** MainActivity opens the Home tab's Clips list when it is started with this action. */
        const val ACTION_OPEN_CLIPS = "com.edith.app.OPEN_CLIPS"

        fun title(alert: Alert): String = when (alert.kind) {
            AlertKind.Doorbell -> "Someone rang the doorbell"
            AlertKind.Person -> "A person was seen"
            AlertKind.Motion -> "Motion"
        }
    }
}
