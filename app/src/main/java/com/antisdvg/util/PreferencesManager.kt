package com.antisdvg.util

import android.content.Context

/**
 * Fast SharedPreferences mirror used by the BlockerService (which runs
 * synchronously on window-change events and cannot afford a Room query).
 *
 * The main app writes the authoritative values here after every read session /
 * emergency spend so the blocker always sees a recent snapshot.
 */
class PreferencesManager(context: Context) {

    private val prefs = context.getSharedPreferences("anti_sdvg_prefs", Context.MODE_PRIVATE)

    // Remaining access minutes (fast path for the blocker).
    fun readRemainingMinutes(): Int = prefs.getInt(KEY_REMAINING, 0)
    fun writeRemainingMinutes(value: Int) = prefs.edit().putInt(KEY_REMAINING, value).apply()

    fun readEmergencyTokens(): Int = prefs.getInt(KEY_TOKENS, 3)
    fun writeEmergencyTokens(value: Int) = prefs.edit().putInt(KEY_TOKENS, value).apply()

    fun tickDownRemaining(by: Int): Int {
        val newValue = (readRemainingMinutes() - by).coerceAtLeast(0)
        writeRemainingMinutes(newValue)
        return newValue
    }

    fun hasAccess(): Boolean = readRemainingMinutes() > 0 || readEmergencyTokens() > 0

    companion object {
        private const val KEY_REMAINING = "minutes_remaining"
        private const val KEY_TOKENS = "emergency_tokens"
    }
}
