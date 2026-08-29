package com.antisdvg.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antisdvg.data.model.AppSettings
import com.antisdvg.data.model.BlockedApp
import com.antisdvg.data.repository.DataRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A snapshot of everything the settings screen shows. */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val blockedApps: List<BlockedApp> = emptyList()
)

class SettingsViewModel(
    private val repo: DataRepository
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        repo.observeSettings(),
        repo.observeBlockedApps()
    ) { settings, blockedApps ->
        SettingsUiState(
            settings = settings ?: AppSettings(),
            blockedApps = blockedApps
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setBlockerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repo.updateSettings(
                state.value.settings.copy(blockerEnabled = enabled)
            )
        }
    }

    fun saveRewards(minutesPerPage: Int, emergencyMinutes: Int) {
        viewModelScope.launch {
            repo.updateSettings(
                state.value.settings.copy(
                    minutesPerPage = minutesPerPage.coerceAtLeast(1),
                    emergencyMinutes = emergencyMinutes.coerceAtLeast(1)
                )
            )
        }
    }

    fun saveApiKey(apiKey: String) {
        viewModelScope.launch {
            repo.updateSettings(
                state.value.settings.copy(apiKey = apiKey.trim())
            )
        }
    }

    fun addBlockedApp(packageName: String, label: String) {
        val cleaned = packageName.trim()
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            repo.upsertBlockedApp(BlockedApp(packageName = cleaned, label = label.trim()))
        }
    }

    fun removeBlockedApp(packageName: String) {
        viewModelScope.launch {
            repo.removeBlockedApp(packageName)
        }
    }
}
