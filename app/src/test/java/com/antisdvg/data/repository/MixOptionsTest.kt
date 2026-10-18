package com.antisdvg.data.repository

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model reliably returns the right answer in slot one, so the order is mixed
 * before a question reaches the reader. What must survive that is the answer
 * itself — a shuffle that loses it would turn a real test into an unanswerable one.
 */
class MixOptionsTest {

    private val options = listOf("RIGHT", "wrong a", "wrong b", "wrong c")

    @Test
    fun `the right answer survives the shuffle`() {
        var moved = 0
        for (seed in 0L until 50L) {
            val (mixed, index) = mixOptions(options, 0, Random(seed))
            assertEquals(options.size, mixed.size)
            assertEquals(options.toSet(), mixed.toSet())
            assertEquals("RIGHT", mixed[index])
            if (index != 0) moved++
        }
        // Fifty seeds and the answer never once moved means nothing is shuffled.
        assertTrue("the right answer never left its place: moved=$moved", moved > 0)
    }

    @Test
    fun `an index that points nowhere is left alone`() {
        val (mixed, index) = mixOptions(options, 9, Random(1))
        assertEquals(options, mixed)
        assertEquals(9, index)
    }
}
