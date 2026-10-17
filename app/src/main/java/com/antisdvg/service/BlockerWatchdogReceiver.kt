package com.antisdvg.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Checks, on its own, that the fence is still standing.
 *
 * The warning in `onUnbind` only covers the polite way of switching the service
 * off. The way that actually happens is ruder: a package update kills the app's
 * process, the callback never runs, and the blocker quietly stops existing. That
 * is not a hypothetical — it is the state the phone this was written for was
 * found in.
 *
 * So the app wakes up once a day, looks, and leaves the warning in the shade
 * where it will be seen even if the app is never opened. The alarm is inexact on
 * purpose: the hour does not matter, and this way no special permission is
 * needed.
 */
class BlockerWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return
        val app = context.applicationContext
        val gone = BlockerWarning.isServiceGone(app)
        Log.d("BlockerWatchdog", "check: service gone = $gone")
        BlockerWarning.sync(app)
        // Re-arm: an update can wipe pending alarms, and this is cheap.
        schedule(app)
    }

    companion object {
        const val ACTION_CHECK = "com.antisdvg.action.BLOCKER_WATCHDOG"

        private const val TAG = "BlockerWatchdog"
        private const val INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val REQUEST_CODE = 3

        /** Arms the daily check; safe to call from anywhere, any number of times. */
        fun schedule(context: Context) {
            val app = context.applicationContext
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = PendingIntent.getBroadcast(
                app,
                REQUEST_CODE,
                Intent(app, BlockerWatchdogReceiver::class.java).setAction(ACTION_CHECK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching {
                am.setInexactRepeating(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + INTERVAL_MS,
                    INTERVAL_MS,
                    pending
                )
                Log.d(TAG, "daily blocker check armed")
            }
        }
    }
}
