package com.antisdvg.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Emergency access "огонечки". Single-row, always id = TOKEN_ROW.
 * Spend one for a short browsing grace period (see AppSettings.emergencyMinutes).
 */
@Entity(tableName = "emergency_tokens")
data class EmergencyTokens(
    @PrimaryKey val id: Int = TOKEN_ROW,
    var count: Int = 3,
    val lastEarned: Long = System.currentTimeMillis()
) {
    companion object {
        const val TOKEN_ROW = 1
        const val STARTING_COUNT = 3
        const val MINUTES_PER_TOKEN = 10
    }
}
