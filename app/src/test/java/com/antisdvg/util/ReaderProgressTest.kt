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
        assertEquals(0, ReaderProgress.earnedMinutes(0, 0, 3))
        // With no pages to pay for, the time part still stands on its own.
        assertEquals(10, ReaderProgress.earnedMinutes(0, 10, 3))
    }

    @Test
    fun earnedMinutes_paysPagesAndTime() {
        // 5 minutes read, 10 pages, 2 per page: 20 + 5 = 25.
        assertEquals(25, ReaderProgress.earnedMinutes(10, 5, 2))
        // 3 minutes, 3 pages, 3 per page: 9 + 3 = 12.
        assertEquals(12, ReaderProgress.earnedMinutes(3, 3, 3))
        assertEquals(4, ReaderProgress.earnedMinutes(1, 1, 3))
    }

    @Test
    fun earnedMinutes_capsPagesByTheClock() {
        // Fifty pages flipped in one minute are not fifty pages read: the cap is
        // two pages per minute plus one, so the page part is 3 × 2.
        assertEquals(7, ReaderProgress.earnedMinutes(50, 1, 2))
        assertEquals(0, ReaderProgress.earnedMinutes(10, 0, 2))
    }

    @Test
    fun questionsForPages_growsWithTheReading() {
        assertEquals(1, ReaderProgress.questionsForPages(0))
        assertEquals(1, ReaderProgress.questionsForPages(1))
        assertEquals(2, ReaderProgress.questionsForPages(2))
        assertEquals(2, ReaderProgress.questionsForPages(3))
        assertEquals(3, ReaderProgress.questionsForPages(4))
        assertEquals(3, ReaderProgress.questionsForPages(6))
        assertEquals(4, ReaderProgress.questionsForPages(7))
        assertEquals(4, ReaderProgress.questionsForPages(10))
    }

    @Test
    fun questionsForPages_isCappedForVeryLongReads() {
        assertEquals(ReaderProgress.MAX_QUESTIONS, ReaderProgress.questionsForPages(11))
        assertEquals(ReaderProgress.MAX_QUESTIONS, ReaderProgress.questionsForPages(400))
    }

    @Test
    fun passThreshold_isTwoThirdsButForgivingOnTinyTests() {
        assertEquals(0, ReaderProgress.passThreshold(0))
        assertEquals(1, ReaderProgress.passThreshold(1))
        assertEquals(1, ReaderProgress.passThreshold(2))
        assertEquals(2, ReaderProgress.passThreshold(3))
        assertEquals(3, ReaderProgress.passThreshold(4))
        assertEquals(4, ReaderProgress.passThreshold(5))
    }

    @Test
    fun passThreshold_followsTheStrictnessSetting() {
        // 0 = мягко: половина, 1 = обычно: две трети, 2 = строго: три четверти.
        assertEquals(2, ReaderProgress.passThreshold(3, 0))
        assertEquals(2, ReaderProgress.passThreshold(3, 1))
        assertEquals(3, ReaderProgress.passThreshold(3, 2))
        assertEquals(3, ReaderProgress.passThreshold(5, 0))
        assertEquals(4, ReaderProgress.passThreshold(5, 1))
        assertEquals(4, ReaderProgress.passThreshold(5, 2))
    }
}
