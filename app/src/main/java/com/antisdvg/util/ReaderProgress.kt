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
     * What a session pays: real reading minutes, scaled by the electronic-book
     * factor. Zero below a full minute — there is nothing honest to pay yet.
     */
    fun earnedMinutes(minutesRead: Int, format: String): Int {
        if (minutesRead <= 0) return 0
        return maxOf(1, (minutesRead * BookFormat.factor(format)).toInt())
    }

    /** Seconds of a session that count as "a minute at least". */
    const val MIN_SESSION_SECONDS = 60

    private const val EPSILON = 0.0005
}
