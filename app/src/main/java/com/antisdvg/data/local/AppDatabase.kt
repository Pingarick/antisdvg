package com.antisdvg.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.antisdvg.data.model.Achievement
import com.antisdvg.data.model.AppSettings
import com.antisdvg.data.model.BlockedApp
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.Bookmark
import com.antisdvg.data.model.EmergencyTokens
import com.antisdvg.data.model.FocusSession
import com.antisdvg.data.model.ReadingSession
import com.antisdvg.data.model.UserStats
import com.antisdvg.data.model.Wallet

@Database(
    entities = [
        Book::class,
        ReadingSession::class,
        FocusSession::class,
        Wallet::class,
        EmergencyTokens::class,
        Achievement::class,
        UserStats::class,
        AppSettings::class,
        BlockedApp::class,
        Bookmark::class
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "anti_sdvg.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11).build().also { INSTANCE = it }
            }

        /** v1→v2: add the paper/electronic [format] column to books, defaulting to paper. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'PAPER'")
            }
        }

        /** v2→v3: add the [isbn] column to books, defaulting to empty. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN isbn TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3→v4: add the [format] column to reading_sessions so page totals can be
         * broken down by paper vs electronic. Existing sessions default to paper. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN format TEXT NOT NULL DEFAULT 'PAPER'")
            }
        }

        /** v4→v5: add the cover-image URL to books, defaulting to empty. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN coverUrl TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5→v6: add the AI check strictness to app_settings (0 мягко / 1 норма / 2 строго). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN verificationStrictness INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v6→v7: focus sessions table + lifetime focus minutes on user_stats. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS focus_sessions (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "startedAt INTEGER NOT NULL, " +
                        "durationMin INTEGER NOT NULL, " +
                        "completed INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE user_stats ADD COLUMN totalFocusMinutes INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v7→v8: daily spend tracking + savings goal ("копилка"). */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_stats ADD COLUMN spentTodayMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_stats ADD COLUMN lastSpendDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN dailyLimitMin INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN goalTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN goalTargetMin INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN goalSavedMin INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v8→v9: built-in reader — imported file path/kind + furthest scroll progress. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN filePath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN fileKind TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN progress REAL NOT NULL DEFAULT 0")
            }
        }

        /**
         * v9→v10: paper books pay three minutes a page instead of five.
         *
         * The default lives in a stored row, so changing the Kotlin value alone
         * would leave every existing install on the old rate. Rows still carrying
         * the old default move over; a page rate the user typed themselves is
         * indistinguishable from it and moves too — which is what was asked for.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE app_settings SET minutesPerPage = 3 WHERE minutesPerPage = 5")
            }
        }

        /**
         * v10→v11: bookmarks in imported books. Positions are progress, not page
         * numbers, so a bookmark survives a font or screen change.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS bookmarks (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "bookId INTEGER NOT NULL, " +
                        "progress REAL NOT NULL, " +
                        "page INTEGER NOT NULL, " +
                        "label TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        /**
         * The old default awarded three minutes a page, which on top of the time
         * paid fourfold. A page is worth one minute now, so anyone still sitting on
         * that default is moved to it; a value changed on purpose is left alone.
         */
        /**
         * The old default awarded three minutes a page, which on top of the time
         * paid fourfold. A page is worth one minute now, so anyone still sitting on
         * that default is moved to it; a value changed on purpose is left alone.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE app_settings SET minutesPerPage = 1 WHERE minutesPerPage = 3")
            }
        }
    }
}
