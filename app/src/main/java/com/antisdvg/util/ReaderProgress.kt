package com.antisdvg.util

import com.antisdvg.data.model.BookFormat

/**
 * Pure math of the built-in reader: maps scroll progress onto page numbers and
 * decides what an honest session pays. Kept free of Android types so it can be
 * unit-tested.
 */
object ReaderProgress {

    /** Page equivalents are estimated from ~1800 chars per page (see BookText). */
    fun pageAt(progress: Double, totalPages: Int): Int {
        val total = if (totalPages > 0) totalPages else 1
        return (progress.coerceIn(0.0, 1.0) * total).toInt().plus(1).coerceIn(1, total)
    }

    /**
     * Pages advanced between two positions. Returns 0 when the reader did not
     * move forward, so re-reading the same page never inflates the counter.
     */
    fun pagesAdvanced(startProgress: Double, endProgress: Double, totalPages: Int): Int {
        if (endProgress <= startProgress + EPSILON) return 0
        val from = pageAt(startProgress, totalPages)
        val to = pageAt(endProgress, totalPages)
        return (to - from + 1).coerceAtLeast(1)
    }

    /**
     * How many questions a session deserves. One screen of text is one question;
     * someone who read twenty screens should not pass on a single one. Grows with
     * the reading, capped at [MAX_QUESTIONS].
     */
    fun questionsForPages(pages: Int): Int = when {
        pages <= 1 -> 1
        pages <= 3 -> 2
        pages <= 6 -> 3
        pages <= 10 -> 4
        else -> MAX_QUESTIONS
    }

    /**
     * Right answers a test of [total] questions needs: two thirds, but a test of
     * one or two questions lets a single slip through.
     */
    /**
     * Right answers a test of [total] questions needs.
     *
     * [strictness] is the setting's level — 0 = мягко, 1 = обычно, 2 = строго:
     * half, two thirds, or three quarters. A test of one or two questions lets a
     * single slip through at any level.
     */
    fun passThreshold(total: Int, strictness: Int = 1): Int = when {
        total <= 0 -> 0
        total <= 2 -> 1
        strictness <= 0 -> (total + 1) / 2
        strictness >= 2 -> (total * 3 + 3) / 4
        else -> (total * 2 + 2) / 3
    }

    /**
     * What a session pays: a fixed reward for every page that was really on screen,
     * plus one minute for every minute read.
     *
     * Pages are capped by the clock — at most two per minute — because fifty pages
     * flipped through in ten seconds are not fifty pages read. Zero below a full
     * minute: there is nothing honest to pay yet.
     *
     * @param minutesPerPage the reader's own setting, so the page reward is theirs
     *   to tune; the time part is always one for one.
     */
    fun earnedMinutes(pagesRead: Int, minutesRead: Int, minutesPerPage: Int): Int {
        if (minutesRead <= 0) return 0
        val paidPages = pagesRead.coerceIn(0, minutesRead * 2 + 1)
        return maxOf(1, paidPages * minutesPerPage.coerceAtLeast(0) + minutesRead)
    }

    /** Seconds of a session that count as "a minute at least". */
    const val MIN_SESSION_SECONDS = 60

    /** Longest test anyone gets, however much was read. */
    const val MAX_QUESTIONS = 5

    private const val EPSILON = 0.0005
}
