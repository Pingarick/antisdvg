package com.antisdvg.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row wallet for earned "fun time" awarded by reading.
 * Row is always id = WALLET_ROW.
 */
@Entity(tableName = "wallet")
data class Wallet(
    @PrimaryKey val id: Int = WALLET_ROW,
    val minutesEarned: Int = 0,
    val minutesUsed: Int = 0,
    var minutesRemaining: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    companion object {
        const val WALLET_ROW = 1
    }
}
