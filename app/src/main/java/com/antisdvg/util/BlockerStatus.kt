package com.antisdvg.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Is the blocker actually running?
 *
 * The app's whole promise is "these apps are closed until you read", and Android
 * lets that promise be switched off from a system screen — by the user, by a
 * cleanup tool, or by an update. Nothing told the app, so the fence could be down
 * for days while the app still said everything was fine.
 */
object BlockerStatus {

    /**
     * The system keeps enabled accessibility services as a colon-separated list,
     * each entry either fully qualified or in the short `pkg/.Class` form.
     */
    fun isListed(enabledSetting: String?, component: String, shortComponent: String): Boolean =
        enabledSetting.orEmpty()
            .split(':')
            .any { it.equals(component, ignoreCase = true) || it.equals(shortComponent, ignoreCase = true) }

    /** True when the system has our accessibility service switched on. */
    fun isServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val component = ComponentName(context, serviceClass)
        val setting = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull()
        return isListed(setting, component.flattenToString(), component.flattenToShortString())
    }
}
