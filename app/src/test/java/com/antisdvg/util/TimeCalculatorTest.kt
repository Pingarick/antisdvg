package com.antisdvg.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeCalculatorTest {

    @Test
    fun `minutesForReading calculates linear minutes`() {
        val minutes = TimeCalculator.minutesForReading(
            pages = 10,
            minutesPerPage = 5,
            useProgressiveBonus = false,
            streak = 0
        )
        assertEquals(50, minutes)
    }

    @Test
    fun `minutesForReading returns zero for zero or negative pages`() {
        assertEquals(0, TimeCalculator.minutesForReading(0, 5))
        assertEquals(0, TimeCalculator.minutesForReading(-3, 5))
    }

    @Test
    fun `minutesForReading with progressive bonus grants extra minutes`() {
        // pages = 10, bonus = 10*10*0.05 = 5 -> total 55
        val withBonus = TimeCalculator.minutesForReading(
            pages = 10,
            minutesPerPage = 5,
            useProgressiveBonus = true,
            streak = 0
        )
        assertEquals(55, withBonus)
    }

    @Test
    fun `minutesForReading with streak applies bonus`() {
        // streak = 2 -> +10% -> 50 * 1.10 = 55
        val withStreak = TimeCalculator.minutesForReading(
            pages = 10,
            minutesPerPage = 5,
            useProgressiveBonus = false,
            streak = 2
        )
        assertEquals(55, withStreak)
    }

    @Test
    fun `advanceStreak starts at 1 for first session`() {
        val today = TimeCalculator.startOfDay(System.currentTimeMillis())
        val streak = TimeCalculator.advanceStreak(lastActiveDay = 0L, currentStreak = 0, todayStart = today)
        assertEquals(1, streak)
    }

    @Test
    fun `advanceStreak preserves streak on the same calendar day`() {
        val today = TimeCalculator.startOfDay(System.currentTimeMillis())
        val streak = TimeCalculator.advanceStreak(lastActiveDay = today, currentStreak = 4, todayStart = today)
        assertEquals(4, streak)
    }

    @Test
    fun `advanceStreak increments on consecutive calendar day`() {
        val today = TimeCalculator.startOfDay(System.currentTimeMillis())
        val yesterday = today - TimeCalculator.DAY_MILLIS
        val streak = TimeCalculator.advanceStreak(lastActiveDay = yesterday, currentStreak = 4, todayStart = today)
        assertEquals(5, streak)
    }

    @Test
    fun `advanceStreak resets to 1 after a day gap`() {
        val today = TimeCalculator.startOfDay(System.currentTimeMillis())
        val twoDaysAgo = today - 2 * TimeCalculator.DAY_MILLIS
        val streak = TimeCalculator.advanceStreak(lastActiveDay = twoDaysAgo, currentStreak = 10, todayStart = today)
        assertEquals(1, streak)
    }
}
