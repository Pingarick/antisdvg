package com.antisdvg.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A package the blocker watches for. Defaults seeded for YouTube + TikTok;
 * the user can add more from Settings.
 */
@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey val packageName: String,
    val label: String = "",
    val enabled: Boolean = true
)
