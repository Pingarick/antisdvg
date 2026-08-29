package com.antisdvg.data.model

/**
 * Minimal projection of a reading session, used to aggregate per-day reading
 * for the weekly graph without pulling whole [ReadingSession] rows.
 */
data class DayReading(
    val createdAt: Long = 0,
    val earnedMinutes: Int = 0,
    val pagesRead: Int = 0
)
