package com.antisdvg.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusRewardTest {

    @Test
    fun `thirty minutes of focus buys fifteen minutes of rest`() {
        assertEquals(15, FocusReward.earnedMinutes(30))
    }

    @Test
    fun `rate is two to one`() {
        assertEquals(30, FocusReward.earnedMinutes(60))
        assertEquals(2, FocusReward.earnedMinutes(5))
    }

    @Test
    fun `partial minutes round down`() {
        assertEquals(14, FocusReward.earnedMinutes(29))
        assertEquals(0, FocusReward.earnedMinutes(1))
    }

    @Test
    fun `nothing and garbage earn nothing`() {
        assertEquals(0, FocusReward.earnedMinutes(0))
        assertEquals(0, FocusReward.earnedMinutes(-30))
    }
}
