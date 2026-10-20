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

    /** Null when the mirror was never written (fresh app data with a restored
     * database): the caller must bootstrap FROM Room instead of wiping it to zero. */
    fun readRemainingMinutesOrNull(): Int? =
        if (prefs.contains(KEY_REMAINING)) prefs.getInt(KEY_REMAINING, 0) else null

    fun writeRemainingMinutes(value: Int) = prefs.edit().putInt(KEY_REMAINING, value).apply()

    /**
     * Epoch millis when the running focus block ends; 0 = no session.
     * Lives in prefs, not in memory: the accessibility service restarts on its
     * own and the wall must survive that.
     */
    fun readFocusUntilMs(): Long = prefs.getLong(KEY_FOCUS_UNTIL, 0L)

    fun writeFocusUntilMs(value: Long) = prefs.edit().putLong(KEY_FOCUS_UNTIL, value).apply()

    fun readEmergencyTokens(): Int = prefs.getInt(KEY_TOKENS, 3)
    fun writeEmergencyTokens(value: Int) = prefs.edit().putInt(KEY_TOKENS, value).apply()

    fun tickDownRemaining(by: Int): Int {
        val newValue = (readRemainingMinutes() - by).coerceAtLeast(0)
        writeRemainingMinutes(newValue)
        return newValue
    }

    fun hasAccess(): Boolean = readRemainingMinutes() > 0 || readEmergencyTokens() > 0

    // Minutes spent today (fast path for the daily-limit gate in the blocker).
    // Day rollover resets the counter: the value is always "today's" spend.
    fun currentSpentToday(): Int {
        val today = TimeCalculator.startOfToday()
        val day = prefs.getLong(KEY_SPEND_DAY, 0L)
        if (day != today) {
            prefs.edit().putLong(KEY_SPEND_DAY, today).putInt(KEY_SPENT_TODAY, 0).apply()
            return 0
        }
        return prefs.getInt(KEY_SPENT_TODAY, 0)
    }

    fun addSpentToday(delta: Int) {
        if (delta <= 0) return
        val today = TimeCalculator.startOfToday()
        val day = prefs.getLong(KEY_SPEND_DAY, 0L)
        val base = if (day == today) prefs.getInt(KEY_SPENT_TODAY, 0) else 0
        prefs.edit().putLong(KEY_SPEND_DAY, today).putInt(KEY_SPENT_TODAY, base + delta).apply()
    }

    companion object {
        private const val KEY_REMAINING = "minutes_remaining"
        private const val KEY_FOCUS_UNTIL = "focus_until_ms"
        private const val KEY_TOKENS = "emergency_tokens"
        private const val KEY_SPENT_TODAY = "minutes_spent_today"
        private const val KEY_SPEND_DAY = "spend_day_start"
    }
}
