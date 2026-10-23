package com.antisdvg.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlibustaClientTest {

    @Test
    fun `case does not change the set of queries`() {
        val lower = FlibustaClient.searchVariants("крёстный отец")
        val upper = FlibustaClient.searchVariants("Крёстный отец")

        assertEquals(lower, upper)
        assertTrue(lower.contains("крёстный отец"))
        assertTrue(lower.contains("Крёстный отец"))
    }

    @Test
    fun `yo spelling is searched as well`() {
        val variants = FlibustaClient.searchVariants("Крёстный отец")

        assertTrue(variants.contains("Крестный отец"))
    }

    @Test
    fun `blank query produces no requests`() {
        assertTrue(FlibustaClient.searchVariants("   ").isEmpty())
    }
}
