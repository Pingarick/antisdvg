package com.antisdvg.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.BookStatus
import com.antisdvg.data.model.DaySummary
import com.antisdvg.data.model.UserStats
import com.antisdvg.data.model.Wallet
import com.antisdvg.data.repository.DataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Snapshot of everything the Home dashboard shows. */
data class HomeUiState(
    val activeBook: Book? = null,
    val hasBooks: Boolean = false,
    val stats: UserStats = UserStats(),
    val wallet: Wallet = Wallet(),
    val todayMinutes: Int = 0,
    val todayPages: Int = 0,
    val week: List<DaySummary> = emptyList()
)

class HomeViewModel(
    private val repo: DataRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // The blocker spends time on the fast prefs mirror; the wallet in
            // Room lags behind until flushed. Pull the authoritative remaining
            // minutes down before the dashboard observes its value, so the
            // "minutes remaining" card can actually reach zero.
            repo.syncRemainingFromPrefs()
        }
        viewModelScope.launch {
            repo.observeBooks().collect { books ->
                // Prefer a manually-fixed "active" book, otherwise fall back to
                // the most recently added in-progress book so the continue tile
                // on the dashboard never shows an empty state once there is any.
                val active = books.firstOrNull { it.status == BookStatus.ACTIVE }
                    ?: books.firstOrNull { it.status == BookStatus.IN_PROGRESS }
                _state.update {
                    it.copy(activeBook = active, hasBooks = books.isNotEmpty())
                }
            }
        }
        viewModelScope.launch {
            repo.observeStats().collect { stats ->
                _state.update { it.copy(stats = stats ?: UserStats()) }
                refreshTodayAndWeek()
            }
        }
        viewModelScope.launch {
            repo.observeWallet().collect { wallet ->
                _state.update { it.copy(wallet = wallet ?: Wallet()) }
            }
        }
    }

    private suspend fun refreshTodayAndWeek() {
        val today = repo.todaySummary()
        val week = repo.lastSevenDays()
        _state.update {
            it.copy(todayMinutes = today.first, todayPages = today.second, week = week)
        }
    }
}
