package com.antisdvg.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing the system's list of enabled accessibility services is the difference
 * between "the app knows the fence is down" and "the app keeps saying all good",
 * so the shapes Android actually writes are pinned here.
 */
class BlockerStatusTest {

    private val full = "com.antisdvg/com.antisdvg.service.BlockerService"
    private val short = "com.antisdvg/.service.BlockerService"

    @Test
    fun `finds our service among others`() {
        val setting = "com.other.app/com.other.Service:$full:com.third/Thing"
        assertTrue(BlockerStatus.isListed(setting, full, short))
    }

    @Test
    fun `accepts the short component form`() {
        assertTrue(BlockerStatus.isListed("com.other/Thing:$short", full, short))
    }

    @Test
    fun `is false when the list is empty, missing or about someone else`() {
        assertFalse(BlockerStatus.isListed(null, full, short))
        assertFalse(BlockerStatus.isListed("", full, short))
        assertFalse(BlockerStatus.isListed("com.other.app/com.other.Service", full, short))
        // A prefix must not count as a match.
        assertFalse(BlockerStatus.isListed("com.antisdvg/com.antisdvg.service.BlockerService2", full, short))
    }

    @Test
    fun `ignores case, because the system is inconsistent about it`() {
        assertTrue(BlockerStatus.isListed(full.uppercase(), full, short))
    }
}
