package com.antisdvg.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the blocker cache after a reboot.
 *
 * The AccessibilityService itself is re-bound by the system when enabled,
 * but our in-memory blocked-packages/settings snapshot is empty until the
 * service refreshes it. This nudge covers the window before
 * onServiceConnected() runs; it is a no-op when the service is disabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        try {
            BlockerService.broadcastRefresh(context.applicationContext)
        } catch (_: Exception) {
        }
    }
}
