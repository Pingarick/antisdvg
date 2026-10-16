package com.antisdvg.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.antisdvg.R
import com.antisdvg.service.BlockerService
import com.antisdvg.ui.activities.MainActivity
import com.antisdvg.util.PreferencesManager

/**
 * Home-screen wallet widget: remaining minutes + emergency tokens.
 *
 * Reads the fast prefs mirror (never Room — providers run on the main
 * thread) and refreshes on the system schedule plus every
 * [BlockerService] settings/blocked-apps refresh broadcast.
 */
class WalletWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == BlockerService.ACTION_REFRESH_BLOCKED) {
            updateAll(context)
        }
    }

    private fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, WalletWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val prefs = PreferencesManager(context.applicationContext)
        val views = RemoteViews(context.packageName, R.layout.widget_wallet).apply {
            setTextViewText(R.id.widget_minutes, prefs.readRemainingMinutes().toString())
            setTextViewText(
                R.id.widget_tokens,
                context.getString(R.string.widget_tokens, prefs.readEmergencyTokens())
            )
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setOnClickPendingIntent(R.id.widget_root, open)
        }
        ids.forEach { id -> manager.updateAppWidget(id, views) }
    }
}
