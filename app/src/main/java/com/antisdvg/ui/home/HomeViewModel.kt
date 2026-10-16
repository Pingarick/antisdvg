package com.antisdvg.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.BookStatus
import com.antisdvg.data.model.AppSettings
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
    val week: List<DaySummary> = emptyList(),
    val settings: AppSettings = AppSettings()
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
        viewModelScope.launch {
            repo.observeSettings().collect { settings ->
                _state.update { it.copy(settings = settings ?: AppSettings()) }
            }
        }
    }

    private suspend fun refreshTodayAndWeek() {
        val today = repo.todaySummary()
        val week = repo.lastSevenDays().map { day ->
            day.copy(label = weekdayShort(day.dayStartMillis))
        }
        _state.update {
            it.copy(todayMinutes = today.first, todayPages = today.second, week = week)
        }
    }

    /** Moves 10 minutes from the wallet into the savings goal. */
    fun depositGoal() {
        viewModelScope.launch {
            repo.depositToGoal(10)
        }
    }

    /** Sets the savings goal title and target. */
    fun saveGoal(title: String, targetMin: Int) {
        viewModelScope.launch {
            repo.setGoal(title, targetMin)
        }
    }

    /** Returns everything stashed back to the wallet. */
    fun breakGoal() {
        viewModelScope.launch {
            repo.breakGoal()
        }
    }

    private fun weekdayShort(millis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> "Пн"
            java.util.Calendar.TUESDAY -> "Вт"
            java.util.Calendar.WEDNESDAY -> "Ср"
            java.util.Calendar.THURSDAY -> "Чт"
            java.util.Calendar.FRIDAY -> "Пт"
            java.util.Calendar.SATURDAY -> "Сб"
            else -> "Вс"
        }
    }
}
