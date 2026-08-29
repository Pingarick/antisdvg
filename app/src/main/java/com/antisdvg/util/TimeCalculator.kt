package com.antisdvg.util

import java.util.Calendar

/**
 * Economry of reading -> fun time.
 *
 * Simple rule seeded by the user's design:
 *   1 page read  ->  [minutesPerPage] minutes of access (default 5).
 *
 * A progressive bonus is also provided (the "read more at once, earn more" idea):
 *   bonusMinutes = pages^2 * 0.05
 * It is OFF by default to keep numbers predictable; flip [useProgressiveBonus]
 * in Settings to turn it on.
 */
object TimeCalculator {

    fun minutesForReading(
        pages: Int,
        minutesPerPage: Int,
        useProgressiveBonus: Boolean = false,
        streak: Int = 0
    ): Int {
        if (pages <= 0) return 0
        var minutes = pages * minutesPerPage
        if (useProgressiveBonus) {
            minutes += (pages * pages * 0.05).toInt()
        }
        // Streak reward: up to +5% per consecutive day, capped at +100% (20+ days)
        // so regular readers earn more than one-off bursts.
        if (streak > 0) {
            minutes = (minutes * (1.0 + 0.05 * minOf(streak, 20))).toInt()
        }
        return minutes
    }

    // ---- Streak calculation ----
    const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val DAY_MS = DAY_MILLIS

    /**
     * Advance the current streak after a fresh reading session.
     *
     * [lastActiveDay] is the previous active day's start-of-day millis (0 if the
     * user has never read). [currentStreak] is the streak value held before this
     * session. The new day is taken as start-of-today at call time.
     *
     * Rules:
     *  - first-ever session                      -> 1
     *  - reading again on the same calendar day  -> keep the existing value
     *  - reading on the next consecutive day     -> previous + 1
     *  - any gap longer than one day             -> restart at 1
     *  - future-dated lastActiveDay (clock skew) -> keep the existing value
     */
    fun advanceStreak(
        lastActiveDay: Long,
        currentStreak: Int,
        todayStart: Long = startOfToday()
    ): Int {
        if (lastActiveDay <= 0L) return 1
        val diffDays = (todayStart - lastActiveDay) / DAY_MS
        return when {
            diffDays < 0L -> currentStreak.coerceAtLeast(1) // clock skew: don't reset
            diffDays == 0L -> currentStreak.coerceAtLeast(1) // same day: no double count
            diffDays == 1L -> currentStreak + 1              // consecutive day: continue run
            else -> 1                                        // gap: restart
        }
    }

    fun startOfDay(epochMillis: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = epochMillis
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun startOfToday(): Long = startOfDay(System.currentTimeMillis())

    /** Number of whole days between a past millis and today's start (negative if in the future). */
    fun daysSince(epochMillis: Long, todayStart: Long = startOfToday()): Long =
        (todayStart - epochMillis) / DAY_MS
}
