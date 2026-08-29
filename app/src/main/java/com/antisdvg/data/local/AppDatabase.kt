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
import com.antisdvg.data.model.EmergencyTokens
import com.antisdvg.data.model.ReadingSession
import com.antisdvg.data.model.UserStats
import com.antisdvg.data.model.Wallet

@Database(
    entities = [
        Book::class,
        ReadingSession::class,
        Wallet::class,
        EmergencyTokens::class,
        Achievement::class,
        UserStats::class,
        AppSettings::class,
        BlockedApp::class
    ],
    version = 5,
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { INSTANCE = it }
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
    }
}
