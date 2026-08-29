package com.antisdvg.data.model

/**
 * Aggregated reading stats for a single calendar day, used to render the
 * weekly graph on the stats screen.
 *
 * [dayStartMillis] is the UTC midnight (in the device calendar) of that day;
 * minutes/pages are the sums earned that day.
 */
data class DaySummary(
    val dayStartMillis: Long = 0,
    val minutes: Int = 0,
    val pages: Int = 0,
    /**
     * Optional short label drawn under the bar on the X axis. For a weekly
     * chart this is the day of the week; for month / all-time buckets it is a
     * range like "1–4" or "Jan–Feb". Null hides the label.
     */
    val label: String? = null
)
