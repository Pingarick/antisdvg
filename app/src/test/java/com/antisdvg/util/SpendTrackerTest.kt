package com.antisdvg.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendTrackerTest {

    @Test
    fun `apply accumulates within the same day`() {
        val today = TimeCalculator.startOfToday()
        val (day, total) = SpendTracker.apply(today, 3, 2, today)
        assertEquals(today, day)
        assertEquals(5, total)
    }

    @Test
    fun `apply restarts on a new day`() {
        val today = TimeCalculator.startOfToday()
        val yesterday = today - TimeCalculator.DAY_MILLIS
        val (day, total) = SpendTracker.apply(yesterday, 30, 2, today)
        assertEquals(today, day)
        assertEquals(2, total)
    }

    @Test
    fun `apply ignores non-positive delta but still rolls over`() {
        val today = TimeCalculator.startOfToday()
        val yesterday = today - TimeCalculator.DAY_MILLIS
        val (day, total) = SpendTracker.apply(yesterday, 30, 0, today)
        assertEquals(today, day)
        assertEquals(0, total)
    }

    @Test
    fun `limitReached only with positive limit`() {
        assertTrue(SpendTracker.limitReached(60, 60))
        assertTrue(SpendTracker.limitReached(61, 60))
        assertFalse(SpendTracker.limitReached(59, 60))
        assertFalse(SpendTracker.limitReached(999, 0))
    }
}
