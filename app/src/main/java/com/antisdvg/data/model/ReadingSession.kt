package com.antisdvg.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single completed reading session that awarded fun time.
 * Kept to drive statistics (total pages, minutes) and achievements.
 */
@Entity(tableName = "reading_sessions")
data class ReadingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long = 0,
    val pagesRead: Int = 0,
    val startPage: Int = 0,
    val endPage: Int = 0,
    val taskType: String = "",
    val earnedMinutes: Int = 0,
    val format: String = BookFormat.PAPER,
    val createdAt: Long = System.currentTimeMillis()
)
