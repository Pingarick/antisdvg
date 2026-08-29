package com.antisdvg.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.antisdvg.data.model.Achievement
import com.antisdvg.data.model.AppSettings
import com.antisdvg.data.model.BlockedApp
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.DayReading
import com.antisdvg.data.model.EmergencyTokens
import com.antisdvg.data.model.ReadingSession
import com.antisdvg.data.model.UserStats
import com.antisdvg.data.model.Wallet
import com.antisdvg.data.model.BookStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    // ---- Books ----
    @Query("SELECT * FROM books ORDER BY status = :activeStatus DESC, startDate DESC")
    fun observeBooks(activeStatus: String = BookStatus.ACTIVE): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBook(id: Long): Flow<Book?>

    @Query("SELECT * FROM books WHERE status = :status LIMIT 1")
    fun findActive(status: String = BookStatus.ACTIVE): Book?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long

    @Update
    suspend fun updateBook(book: Book)

    @Query("SELECT COUNT(*) FROM books WHERE status = :status")
    suspend fun countBooks(status: String): Int

    @Query("SELECT COALESCE(SUM(currentPage), 0) FROM books")
    suspend fun sumReadPages(): Int

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: Long)

    @Query("DELETE FROM reading_sessions WHERE bookId = :bookId")
    suspend fun deleteSessionsForBook(bookId: Long)

    // ---- Reading sessions ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ReadingSession): Long

    @Query("SELECT COALESCE(SUM(pagesRead), 0) FROM reading_sessions")
    fun observeTotalPagesRead(): Flow<Int>

    @Query("SELECT COALESCE(SUM(pagesRead), 0) FROM reading_sessions")
    suspend fun totalPagesRead(): Int

    @Query("SELECT COALESCE(SUM(earnedMinutes), 0) FROM reading_sessions")
    suspend fun totalEarnedMinutes(): Int

    @Query("SELECT COUNT(*) FROM reading_sessions")
    suspend fun countSessions(): Int

    @Query("SELECT createdAt FROM reading_sessions ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastSessionTime(): Long?

    @Query("SELECT MIN(createdAt) FROM reading_sessions")
    suspend fun firstSessionTime(): Long?

    @Query("SELECT COALESCE(SUM(earnedMinutes), 0) FROM reading_sessions WHERE createdAt >= :dayStart")
    suspend fun minutesSince(dayStart: Long): Int

    @Query("SELECT COALESCE(SUM(pagesRead), 0) FROM reading_sessions WHERE createdAt >= :dayStart")
    suspend fun pagesSince(dayStart: Long): Int

    @Query("SELECT createdAt, earnedMinutes, pagesRead FROM reading_sessions WHERE createdAt >= :weekStart")
    suspend fun sessionsSince(weekStart: Long): List<DayReading>

    @Query("SELECT COALESCE(SUM(pagesRead), 0) FROM reading_sessions WHERE format = :format")
    suspend fun totalPagesByFormat(format: String): Int

    @Query("SELECT COALESCE(SUM(pagesRead), 0) FROM reading_sessions WHERE createdAt >= :dayStart AND format = :format")
    suspend fun pagesByFormatSince(dayStart: Long, format: String): Int

    // ---- Wallet (fun time) ----
    @Query("SELECT * FROM wallet WHERE id = :row")
    suspend fun getWallet(row: Int = Wallet.WALLET_ROW): Wallet?

    @Query("SELECT * FROM wallet WHERE id = :row")
    fun observeWallet(row: Int = Wallet.WALLET_ROW): Flow<Wallet?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWallet(wallet: Wallet)

    // ---- Emergency tokens ----
    @Query("SELECT * FROM emergency_tokens WHERE id = :row")
    suspend fun getTokens(row: Int = EmergencyTokens.TOKEN_ROW): EmergencyTokens?

    @Query("SELECT * FROM emergency_tokens WHERE id = :row")
    fun observeTokens(row: Int = EmergencyTokens.TOKEN_ROW): Flow<EmergencyTokens?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTokens(tokens: EmergencyTokens)

    // ---- App settings ----
    @Query("SELECT * FROM app_settings WHERE id = :row")
    suspend fun getSettings(row: Int = AppSettings.SETTINGS_ROW): AppSettings?

    @Query("SELECT * FROM app_settings WHERE id = :row")
    fun observeSettings(row: Int = AppSettings.SETTINGS_ROW): Flow<AppSettings?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: AppSettings)

    // ---- Blocked apps ----
    @Query("SELECT * FROM blocked_apps WHERE enabled = 1")
    fun observeBlockedApps(): Flow<List<BlockedApp>>

    @Query("SELECT * FROM blocked_apps WHERE enabled = 1")
    suspend fun getBlockedApps(): List<BlockedApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlockedApp(app: BlockedApp)

    @Query("DELETE FROM blocked_apps WHERE packageName = :pkg")
    suspend fun deleteBlockedApp(pkg: String)

    // ---- Achievements ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAchievement(achievement: Achievement)

    @Query("SELECT * FROM achievements ORDER BY unlockedAt")
    fun observeAchievements(): Flow<List<Achievement>>

    @Query("SELECT * FROM achievements")
    suspend fun getAchievements(): List<Achievement>

    // ---- Stats ----
    @Query("SELECT * FROM user_stats WHERE id = :row")
    suspend fun getStats(row: Int = UserStats.STATS_ROW): UserStats?

    @Query("SELECT * FROM user_stats WHERE id = :row")
    fun observeStats(row: Int = UserStats.STATS_ROW): Flow<UserStats?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStats(stats: UserStats)
}
