package com.antisdvg.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A place in an imported book worth coming back to.
 *
 * The position is kept as progress (0..1) rather than as a page number: page
 * numbers are what the current font size and screen make of the text, so they
 * move, while the place in the book does not. [page] is stored for the label
 * only, so a bookmark list still reads like a book ("стр. 42").
 */
@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val progress: Double,
    val page: Int,
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
