package com.antisdvg.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row application settings.
 */
@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = SETTINGS_ROW,
    var apiKey: String = "",
    // Minutes of access awarded per page read.
    var minutesPerPage: Int = 5,
    // Minutes of grace a single emergency token grants.
    var emergencyMinutes: Int = 10,
    // Whether the in-app toggle is on. (System service must still be enabled separately.)
    var blockerEnabled: Boolean = false,
    // AI check strictness: 0 = мягко, 1 = нормально, 2 = строго.
    var verificationStrictness: Int = 1,
    // Daily spend cap for entertainment minutes (0 = unlimited).
    var dailyLimitMin: Int = 0,
    // Savings goal ("копилка"): title + target, with already stashed minutes.
    var goalTitle: String = "",
    var goalTargetMin: Int = 0,
    var goalSavedMin: Int = 0
) {
    companion object {
        const val SETTINGS_ROW = 1
    }
}
