package com.antisdvg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.antisdvg.R
import com.antisdvg.util.BlockerStatus

/**
 * "The fence is down", said once and in one place.
 *
 * Without the accessibility service this app blocks exactly nothing, and the
 * service can be switched off without the app being told: a package update kills
 * the process before `onUnbind` runs, and on the phone this was written for that
 * is precisely what had happened — the fence was down and nothing said a word.
 *
 * So the message is deliberately hard to miss and always ends in one tap on the
 * screen that fixes it.
 */
object BlockerWarning {

    /** Ongoing: while the fence is down this has to stay put. */
    const val NOTIFICATION_ID = 1003
    const val CHANNEL_ID = "blocker_warning"

    fun show(context: Context) {
        val app = context.applicationContext
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    app.getString(R.string.blocker_warning_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val open = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pending = PendingIntent.getActivity(
            app,
            2,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(app, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(app)
        }
        runCatching {
            nm.notify(
                NOTIFICATION_ID,
                builder
                    .setContentTitle(app.getString(R.string.blocker_warning_title))
                    .setContentText(app.getString(R.string.blocker_warning_text))
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentIntent(pending)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .build()
            )
        }
    }

    fun cancel(context: Context) {
        runCatching {
            val nm = context.applicationContext
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        }
    }

    /**
     * Whether the *service* is gone — the switch in the app is deliberately not
     * part of this: turning blocking off on purpose is a decision, not a fault,
     * and there is no need to nag about it every day.
     */
    fun isServiceGone(context: Context): Boolean =
        !BlockerStatus.isServiceEnabled(context, BlockerService::class.java)

    /** Says the current truth: warns when the service is gone, clears the warning when it is back. */
    fun sync(context: Context) {
        if (isServiceGone(context)) show(context) else cancel(context)
    }
}
