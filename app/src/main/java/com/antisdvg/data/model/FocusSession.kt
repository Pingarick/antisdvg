package com.antisdvg.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One completed pomodoro-style focus block (default 25 min).
 * Completing a block earns entertainment minutes 1:1 via
 * [com.antisdvg.data.repository.DataRepository.recordFocusSession].
 */
@Entity(tableName = "focus_sessions")
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val durationMin: Int = 25,
    val completed: Boolean = true
)
