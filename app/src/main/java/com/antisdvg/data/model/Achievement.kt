package com.antisdvg.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-owned achievement that has been unlocked.
 */
@Entity(tableName = "achievements")
data class Achievement(
    @PrimaryKey val type: String,
    val title: String,
    val unlockedAt: Long = System.currentTimeMillis()
)
