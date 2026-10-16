package com.antisdvg.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Aggregated user statistics, single-row (id = STATS_ROW).
 * Updated whenever a reading session completes.
 */
@Entity(tableName = "user_stats")
data class UserStats(
    @PrimaryKey val id: Int = STATS_ROW,
    var totalBooksRead: Int = 0,
    var totalPagesRead: Int = 0,
    var totalReadingTimeMinutes: Int = 0,
    var currentStreak: Int = 0,
    var highestStreak: Int = 0,
    var lastActiveDay: Long = 0L,
    var totalFocusMinutes: Int = 0,
    // Entertainment minutes spent on the current calendar day (start-of-day + sum).
    var spentTodayMinutes: Int = 0,
    var lastSpendDay: Long = 0L
) {
    companion object {
        const val STATS_ROW = 1
    }
}
