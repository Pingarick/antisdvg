package com.antisdvg.data.repository

import com.antisdvg.data.local.AppDao
import com.antisdvg.data.model.Achievement
import com.antisdvg.data.model.AchievementCatalog
import com.antisdvg.data.model.AppSettings
import com.antisdvg.data.model.BlockedApp
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.BookStatus
import com.antisdvg.data.model.DayReading
import com.antisdvg.data.model.DaySummary
import com.antisdvg.data.model.EmergencyTokens
import com.antisdvg.data.model.ReadingSession
import com.antisdvg.data.model.StatsView
import com.antisdvg.data.model.UserStats
import com.antisdvg.data.model.Wallet
import com.antisdvg.util.PreferencesManager
import com.antisdvg.util.TimeCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

/**
 * Single entry point for all Room-backed data plus the time-widget
 * bookkeeping logic that ties a verified reading session to wallet/token/stats
 * updates. ViewModels depend on this class rather than on AppDao directly.
 */
class DataRepository(
    private val dao: AppDao,
    private val prefs: PreferencesManager
) {

    // ---- Books ----
    fun observeBooks(): Flow<List<Book>> = dao.observeBooks()
    fun observeBook(id: Long): Flow<Book?> = dao.observeBook(id)

    fun getActiveBook(): Book? = dao.findActive(BookStatus.ACTIVE)
    suspend fun addBook(book: Book): Long = dao.insertBook(book)
    suspend fun updateBook(book: Book) = dao.updateBook(book)

    /** Attaches a cover-image URL to a book (empty clears it). */
    suspend fun setBookCover(bookId: Long, coverUrl: String) {
        val current = dao.observeBook(bookId).firstOrNull() ?: return
        dao.updateBook(current.copy(coverUrl = coverUrl))
    }

    /**
     * Removes a book and its reading sessions permanently. The wallet,
     * tokens, and lifetime stats are left untouched.
     */
    suspend fun deleteBook(bookId: Long) {
        dao.deleteSessionsForBook(bookId)
        dao.deleteBook(bookId)
    }

    /**
     * Marks a book completed, closes its read status, and bumps the
     * total-books-read counter if this is the first time it finishes.
     */
    suspend fun finishBook(book: Book) {
        if (book.status == BookStatus.READ) return
        dao.updateBook(book.copy(status = BookStatus.READ, finishDate = System.currentTimeMillis()))
        val stats = dao.getStats() ?: UserStats()
        dao.upsertStats(stats.copy(totalBooksRead = stats.totalBooksRead + 1))
    }

    // ---- Reading flow ----
    /**
     * Records a successful reading session: updates the book progress, adds
     * the earned minutes to the wallet, refreshes the shared-prefs mirror,
     * updates streak, and awards an emergency token.
     */
    suspend fun recordSession(
        book: Book,
        startPage: Int,
        endPage: Int,
        taskType: String,
        earnedMinutes: Int
    ) {
        val pagesThisSession = (endPage - startPage + 1).coerceAtLeast(1)

        // 1. Advance the book.
        val nextPage = endPage.coerceAtMost(book.totalPages)
        val finished = nextPage >= book.totalPages
        dao.updateBook(
            book.copy(
                currentPage = nextPage,
                readPages = book.readPages + pagesThisSession,
                status = if (finished) BookStatus.READ else book.status,
                finishDate = if (finished) System.currentTimeMillis() else book.finishDate
            )
        )

        // 2. Persist the session row.
        dao.insertSession(
            ReadingSession(
                bookId = book.id,
                pagesRead = pagesThisSession,
                startPage = startPage,
                endPage = endPage,
                taskType = taskType,
                earnedMinutes = earnedMinutes,
                format = book.format
            )
        )

        // 3. Wallet + mirror: earn minutes.
        val wallet = dao.getWallet() ?: Wallet()
        val totalEarned = wallet.minutesEarned + earnedMinutes
        val newRemaining = (wallet.minutesRemaining + earnedMinutes)
        dao.upsertWallet(
            wallet.copy(
                minutesEarned = totalEarned,
                minutesRemaining = newRemaining,
                lastUpdated = System.currentTimeMillis()
            )
        )
        prefs.writeRemainingMinutes(newRemaining)

        // 4. Emergency token reward (+1 per successful session), capped at 99.
        val tokens = dao.getTokens() ?: EmergencyTokens()
        val newCount = (tokens.count + 1).coerceAtMost(99)
        dao.upsertTokens(tokens.copy(count = newCount, lastEarned = System.currentTimeMillis()))
        prefs.writeEmergencyTokens(newCount)

        // 5. Stats + streak.
        val stats = dao.getStats() ?: UserStats()
        val pagesTotal = dao.totalPagesRead()
        val streak = TimeCalculator.advanceStreak(stats.lastActiveDay, stats.currentStreak)
        val updatedStats = stats.copy(
            totalPagesRead = pagesTotal,
            totalReadingTimeMinutes = stats.totalReadingTimeMinutes + earnedMinutes,
            currentStreak = streak,
            highestStreak = maxOf(stats.highestStreak, streak),
            lastActiveDay = System.currentTimeMillis()
        )
        dao.upsertStats(updatedStats)

        // 6. Recompute achievements.
        evaluateAchievements(updatedStats)
    }

    // ---- Achievements ----
    fun observeAchievements(): Flow<List<Achievement>> = dao.observeAchievements()

    suspend fun evaluateAchievements(stats: UserStats) {
        val earned = dao.getAchievements().map { it.type }.toSet()
        AchievementCatalog.all.forEach { def ->
            if (def.key !in earned && def.condition(stats.toView())) {
                dao.insertAchievement(Achievement(type = def.key, title = def.title))
            }
        }
    }

    // ---- Wallet / tokens / time ----
    fun observeWallet(): Flow<Wallet?> = dao.observeWallet()
    fun observeTokens(): Flow<EmergencyTokens?> = dao.observeTokens()
    fun observeStats(): Flow<UserStats?> = dao.observeStats()
    fun observeSettings(): Flow<AppSettings?> = dao.observeSettings()

    suspend fun getWallet(): Wallet = dao.getWallet() ?: Wallet()
    suspend fun getTokens(): EmergencyTokens = dao.getTokens() ?: EmergencyTokens()
    suspend fun getStats(): UserStats = dao.getStats() ?: UserStats()

    /** Subtract minutes after an access period elapses; keeps mirror in sync. */
    suspend fun spendMinutes(minutes: Int) {
        val wallet = dao.getWallet() ?: Wallet()
        val spent = minutes.coerceAtMost(wallet.minutesRemaining)
        val updated = wallet.copy(
            minutesUsed = wallet.minutesUsed + spent,
            minutesRemaining = wallet.minutesRemaining - spent
        )
        dao.upsertWallet(updated)
        prefs.writeRemainingMinutes(updated.minutesRemaining)
    }

    /**
     * Pushes the authoritative remaining-minutes value from the fast prefs mirror
     * into the Room wallet, preserving how many minutes have been used in total.
     * Called (in small batches) by the BlockerService so real-time spent time is
     * reflected in the wallet and daily statistics.
     *
     * The value never goes up here: the service only ever spends time, so if prefs
     * reports more than the wallet we simply leave the wallet untouched (the main
     * app will sync it back down on its next normal write).
     */
    suspend fun syncRemainingFromPrefs() {
        val wallet = dao.getWallet() ?: Wallet()
        val prefsRemaining = prefs.readRemainingMinutes()
        // The blocker spends minutes on the fast prefs mirror. If prefs has gone
        // down, always reflect that in Room — otherwise the wallet on the main
        // screen sticks at an old value and never reaches zero even though the
        // user has spent their time.
        val newRemaining = minOf(prefsRemaining, wallet.minutesRemaining)
        if (newRemaining == wallet.minutesRemaining) return
        val used = wallet.minutesUsed + (wallet.minutesRemaining - newRemaining)
        dao.upsertWallet(wallet.copy(minutesUsed = used, minutesRemaining = newRemaining))
    }

    /**
     * Spend one emergency token for an emergency-minute grace period.
     * Returns true if a token was available and spent.
     */
    suspend fun spendEmergencyToken(): Boolean {
        val tokens = dao.getTokens() ?: EmergencyTokens()
        if (tokens.count <= 0) return false
        dao.upsertTokens(tokens.copy(count = tokens.count - 1, lastEarned = tokens.lastEarned))
        prefs.writeEmergencyTokens(tokens.count - 1)
        return true
    }

    /** Credit emergency-access minutes gained from spending a token. */
    suspend fun grantEmergencyMinutes(minutes: Int) {
        val wallet = dao.getWallet() ?: Wallet()
        val updated = wallet.copy(
            minutesRemaining = wallet.minutesRemaining + minutes,
            lastUpdated = System.currentTimeMillis()
        )
        dao.upsertWallet(updated)
        prefs.writeRemainingMinutes(updated.minutesRemaining)
    }

    // ---- Blocked apps ----
    fun observeBlockedApps(): Flow<List<BlockedApp>> = dao.observeBlockedApps()
    suspend fun getBlockedApps(): List<BlockedApp> = dao.getBlockedApps()
    suspend fun upsertBlockedApp(app: BlockedApp) = dao.upsertBlockedApp(app)
    suspend fun removeBlockedApp(pkg: String) = dao.deleteBlockedApp(pkg)

    // ---- Settings ----
    suspend fun getSettings(): AppSettings = dao.getSettings() ?: AppSettings()
    suspend fun updateSettings(settings: AppSettings) = dao.upsertSettings(settings)

    // ---- Counts for stats screens ----
    suspend fun countBooks(status: String): Int = dao.countBooks(status)
    suspend fun totalPagesRead(): Int = dao.totalPagesRead()
    suspend fun totalEarnedMinutes(): Int = dao.totalEarnedMinutes()
    suspend fun countSessions(): Int = dao.countSessions()
    suspend fun lastSessionTime(): Long? = dao.lastSessionTime()
    suspend fun totalPagesByFormat(format: String): Int = dao.totalPagesByFormat(format)

    /** Pages (by format) recorded at or after [startMillis]; 0 = all time. */
    suspend fun totalPagesByFormatSince(startMillis: Long, format: String): Int =
        dao.pagesByFormatSince(startMillis, format)

    /** Reading time (minutes) and pages recorded at or after [startMillis]; 0 = all time. */
    suspend fun aggregateSince(startMillis: Long): Pair<Int, Int> =
        dao.minutesSince(startMillis) to dao.pagesSince(startMillis)

    /**
     * Minutes and pages earned since the start of the current calendar day,
     * in the device's local timezone.
     */
    suspend fun todaySummary(): Pair<Int, Int> {
        val dayStart = TimeCalculator.startOfDay(System.currentTimeMillis())
        return dao.minutesSince(dayStart) to dao.pagesSince(dayStart)
    }

    /**
     * Per-day reading minutes over the last 7 calendar days (today being the
     * last entry), oldest day first. Empty days render as zero-height bars.
     */
    /** Start of the oldest recorded reading day, or null when there are no sessions yet. */
    suspend fun firstReadingDay(): Long? {
        val first = dao.firstSessionTime() ?: return null
        return TimeCalculator.startOfDay(first)
    }

    suspend fun lastSevenDays(): List<DaySummary> {
        val today = TimeCalculator.startOfDay(System.currentTimeMillis())
        return barsBetween(today - 6 * TimeCalculator.DAY_MILLIS, today, 7)
    }

    /**
     * Splits the inclusive millis window [start, end] into [count] equal buckets
     * (oldest first) and aggregates the reading minutes/pages that fell into
     * each one. Buckets map to whole calendar days so spikes line up with the
     * day they happened on; empty buckets render as zero-height bars. This is
     * what backs the stats chart regardless of the active period.
     */
    suspend fun barsBetween(start: Long, end: Long, count: Int): List<DaySummary> {
        if (count <= 0) return emptyList()
        val from = TimeCalculator.startOfDay(start)
        val to = TimeCalculator.startOfDay(end)
        val totalDays = ((to - from) / TimeCalculator.DAY_MILLIS).coerceAtLeast(1).toInt() + 1
        val buckets = Array(count) { MutableDayReading() }

        // Assign the last bucket's end day to its largest whole-day fraction so a
        // 12-bucket "year" still reads sensibly; otherwise mid-week boundaries get
        // lost. Bucket i covers whole days [bucketStart, bucketStart+size).
        for (s in dao.sessionsSince(from)) {
            val day = TimeCalculator.startOfDay(s.createdAt)
            if (day < from || day > to) continue
            val dayIndex = (day - from) / TimeCalculator.DAY_MILLIS
            val bucket = ((dayIndex * count) / totalDays).coerceAtMost<Long>((count - 1).toLong())
            buckets[bucket.toInt()].merge(s)
        }

        return buckets.mapIndexed { i, agg ->
            val bucketStart = from + (totalDays * i / count) * TimeCalculator.DAY_MILLIS
            DaySummary(
                dayStartMillis = bucketStart,
                minutes = agg.minutes,
                pages = agg.pages
            )
        }
    }
}

/** Mutable accumulator for [barsBetween] so buckets are merged in place. */
private class MutableDayReading(var minutes: Int = 0, var pages: Int = 0) {
    fun merge(s: DayReading) {
        minutes += s.earnedMinutes
        pages += s.pagesRead
    }
}

/** Small mapping so achievement conditions can read a plain [StatsView]. */
private fun UserStats.toView() = StatsView(totalBooksRead, totalPagesRead, currentStreak)
