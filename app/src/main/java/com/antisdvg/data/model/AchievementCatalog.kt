package com.antisdvg.data.model

/**
 * Static catalog of achievements and their unlock conditions.
 * Evaluated against aggregate stats whenever stats change.
 */
data class AchievementDef(
    val key: String,
    val title: String,
    val condition: (StatsView) -> Boolean
)

data class StatsView(
    val totalBooksRead: Int,
    val totalPagesRead: Int,
    val currentStreak: Int
)

object AchievementCatalog {

    const val FIRST_BOOK = "first_book"
    const val BOOKWORM = "bookworm"
    const val HUNDRED_PAGES = "hundred_pages"
    const val THOUSAND_PAGES = "thousand_pages"
    const val SEVEN_DAYS = "seven_days"
    const val THIRTY_DAYS = "thirty_days"

    val all: List<AchievementDef> = listOf(
        AchievementDef(FIRST_BOOK, "Первая книга", { it.totalBooksRead >= 1 }),
        AchievementDef(BOOKWORM, "Книжный червь (10 книг)", { it.totalBooksRead >= 10 }),
        AchievementDef(HUNDRED_PAGES, "100 страниц", { it.totalPagesRead >= 100 }),
        AchievementDef(THOUSAND_PAGES, "1000 страниц", { it.totalPagesRead >= 1000 }),
        AchievementDef(SEVEN_DAYS, "7 дней чтения", { it.currentStreak >= 7 }),
        AchievementDef(THIRTY_DAYS, "30 дней чтения", { it.currentStreak >= 30 })
    )
}
