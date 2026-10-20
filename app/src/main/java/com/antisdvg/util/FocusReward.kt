package com.antisdvg.util

/**
 * Focus pays rest at 2:1: half an hour of focus buys a quarter hour of
 * entertainment. Deliberately stricter than reading, because focus is the
 * activity the app exists to protect.
 */
object FocusReward {

    const val FOCUS_MINUTES_PER_EARNED = 2

    /** Entertainment minutes earned by [focusMinutes] of completed focus. */
    fun earnedMinutes(focusMinutes: Int): Int =
        (focusMinutes.coerceAtLeast(0) / FOCUS_MINUTES_PER_EARNED)
}
