package com.antisdvg.util

/**
 * Pure daily-spend accounting shared by [DataRepository.syncRemainingFromPrefs]
 * and the prefs mirror, so the day-rollover math is unit-testable.
 *
 * All day values are start-of-day millis from [TimeCalculator.startOfDay].
 */
object SpendTracker {

    /**
     * Applies a newly observed [spentDelta] (minutes spent since the last sync)
     * to ([lastSpendDay], [spentToday]) for [todayStart].
     *
     * On a new calendar day the counter restarts; non-positive deltas are ignored.
     * @return the new (lastSpendDay, spentToday) pair.
     */
    fun apply(
        lastSpendDay: Long,
        spentToday: Int,
        spentDelta: Int,
        todayStart: Long = TimeCalculator.startOfToday()
    ): Pair<Long, Int> {
        if (spentDelta <= 0) {
            return if (lastSpendDay == todayStart) lastSpendDay to spentToday
            else todayStart to 0
        }
        val base = if (lastSpendDay == todayStart) spentToday else 0
        return todayStart to (base + spentDelta)
    }

    /** True when [spentToday] hits a positive [dailyLimitMin]. */
    fun limitReached(spentToday: Int, dailyLimitMin: Int): Boolean =
        dailyLimitMin > 0 && spentToday >= dailyLimitMin
}
