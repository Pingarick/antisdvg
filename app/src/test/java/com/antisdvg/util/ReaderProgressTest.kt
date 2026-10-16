package com.antisdvg.util

import com.antisdvg.data.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderProgressTest {

    @Test
    fun pageAt_startsAtFirstPageAndEndsAtLast() {
        assertEquals(1, ReaderProgress.pageAt(0.0, 340))
        assertEquals(340, ReaderProgress.pageAt(1.0, 340))
        assertEquals(101, ReaderProgress.pageAt(0.295, 340))
    }

    @Test
    fun pageAt_toleratesUnknownPageCount() {
        assertEquals(1, ReaderProgress.pageAt(0.5, 0))
        assertEquals(1, ReaderProgress.pageAt(0.9, -3))
    }

    @Test
    fun pageAt_clampsOutOfRangeProgress() {
        assertEquals(1, ReaderProgress.pageAt(-1.0, 100))
        assertEquals(100, ReaderProgress.pageAt(5.0, 100))
    }

    @Test
    fun pagesAdvanced_countsInclusiveRange() {
        assertEquals(11, ReaderProgress.pagesAdvanced(0.10, 0.20, 100))
    }

    @Test
    fun pagesAdvanced_isZeroWhenNotMovingForward() {
        assertEquals(0, ReaderProgress.pagesAdvanced(0.50, 0.50, 100))
        assertEquals(0, ReaderProgress.pagesAdvanced(0.50, 0.40, 100))
    }

    @Test
    fun pagesAdvanced_isZeroForNegligibleScroll() {
        assertEquals(0, ReaderProgress.pagesAdvanced(0.5, 0.5001, 1000))
    }

    @Test
    fun earnedMinutes_paysForRealTimeOnly() {
        assertEquals(0, ReaderProgress.earnedMinutes(0, BookFormat.PAPER))
        assertEquals(10, ReaderProgress.earnedMinutes(10, BookFormat.PAPER))
    }

    @Test
    fun earnedMinutes_appliesElectronicFactorButNeverBelowOne() {
        assertEquals(6, ReaderProgress.earnedMinutes(10, BookFormat.ELECTRONIC))
        assertEquals(1, ReaderProgress.earnedMinutes(1, BookFormat.ELECTRONIC))
    }
}
