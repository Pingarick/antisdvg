package com.antisdvg.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.antisdvg.AntiSdvgApp
import com.antisdvg.data.local.AppDatabase
import com.antisdvg.data.repository.AIRepository
import com.antisdvg.data.repository.DataRepository
import com.antisdvg.util.PreferencesManager

/**
 * App-wide ViewModel factory. Every fragment ViewModel is built on top of a
 * single [DataRepository] (Room + a SharedPreferences mirror) and an
 * [AIRepository] (DeepSeek verification), which are wired here.
 */
class AppViewModelFactory(
    private val app: Application
) : ViewModelProvider.Factory {

    private val db: AppDatabase by lazy { AppDatabase.getInstance(app) }
    private val prefs: PreferencesManager by lazy { PreferencesManager(app) }
    private val repo: DataRepository by lazy { DataRepository(db.appDao(), prefs) }
    private val ai: AIRepository by lazy { AIRepository() }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass == com.antisdvg.ui.home.HomeViewModel::class.java ->
                com.antisdvg.ui.home.HomeViewModel(repo)
            modelClass == com.antisdvg.ui.library.LibraryViewModel::class.java ->
                com.antisdvg.ui.library.LibraryViewModel(repo)
            modelClass == com.antisdvg.ui.reading.ReadingViewModel::class.java ->
                com.antisdvg.ui.reading.ReadingViewModel(repo, ai)
            modelClass == com.antisdvg.ui.stats.StatsViewModel::class.java ->
                com.antisdvg.ui.stats.StatsViewModel(repo, prefs)
            modelClass == com.antisdvg.ui.settings.SettingsViewModel::class.java ->
                com.antisdvg.ui.settings.SettingsViewModel(repo)
            modelClass == com.antisdvg.ui.activities.BookEditorViewModel::class.java ->
                com.antisdvg.ui.activities.BookEditorViewModel(repo)
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        } as T
    }

    companion object {
        fun repository(app: AntiSdvgApp): DataRepository {
            val prefs = PreferencesManager(app)
            return DataRepository(AppDatabase.getInstance(app).appDao(), prefs)
        }
    }
}
