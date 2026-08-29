package com.antisdvg.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A book the user is reading.
 *
 * @param status one of BookStatus ordinal: ACTIVE, IN_PROGRESS, READ
 */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val author: String = "",
    val publisher: String = "",
    val isbn: String = "",
    /** HTTPS URL of the cover image (Google Books / Open Library), empty when unknown. */
    val coverUrl: String = "",
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    val readPages: Int = 0,
    val status: String = BookStatus.IN_PROGRESS,
    val coverEmoji: String = "📖",
    val startDate: Long = System.currentTimeMillis(),
    val finishDate: Long = 0L,
    val format: String = BookFormat.PAPER
)

object BookStatus {
    const val ACTIVE = "ACTIVE"
    const val IN_PROGRESS = "IN_PROGRESS"
    const val READ = "READ"
}

/**
 * Whether the book is paper or electronic. Electronic reading earns fewer
 * minutes per page because it's easier to fake / less deliberate.
 */
object BookFormat {
    const val PAPER = "PAPER"
    const val ELECTRONIC = "ELECTRONIC"

    /** Multipliers applied to earned minutes per page. */
    fun factor(format: String): Double = when (format) {
        ELECTRONIC -> 0.5
        else -> 1.0
    }
}

/** Progress percentage 0..100, clamped. */
val Book.progressPercent: Int
    get() = if (totalPages <= 0) 0 else ((currentPage.toDouble() / totalPages) * 100).toInt().coerceIn(0, 100)
