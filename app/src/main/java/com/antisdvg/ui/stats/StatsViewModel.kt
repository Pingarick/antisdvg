package com.antisdvg.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antisdvg.data.model.Achievement
import com.antisdvg.data.model.DaySummary
import com.antisdvg.data.model.EmergencyTokens
import com.antisdvg.data.model.UserStats
import com.antisdvg.data.model.Wallet
import com.antisdvg.data.repository.DataRepository
import com.antisdvg.util.PreferencesManager
import com.antisdvg.util.TimeCalculator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Period scoping for the reading statistics grid. */
enum class StatsPeriod { WEEK, MONTH, ALL }

/** A snapshot of everything the stats screen shows. */
data class StatsUiState(
    val wallet: Wallet = Wallet(),
    val tokens: EmergencyTokens = EmergencyTokens(),
    val stats: UserStats = UserStats(),
    val achievements: List<Achievement> = emptyList(),
    val liveRemainingMinutes: Int = 0,
    val todayMinutes: Int = 0,
    val todayPages: Int = 0,
    val paperPages: Int = 0,
    val electronicPages: Int = 0,
    val week: List<DaySummary> = emptyList(),
    val period: StatsPeriod = StatsPeriod.ALL,
    val periodMinutes: Int = 0,
    val periodPages: Int = 0
)

class StatsViewModel(
    private val repo: DataRepository,
    private val prefs: PreferencesManager
) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init {
        // Live "remaining now" from the fast prefs mirror. The BlockerService
        // spends minutes here in real time, so a modest poll keeps the number
        // honest while the screen is open.
        viewModelScope.launch {
            _state.update { it.copy(liveRemainingMinutes = prefs.readRemainingMinutes()) }
            while (true) {
                delay(10_000)
                _state.update { it.copy(liveRemainingMinutes = prefs.readRemainingMinutes()) }
                refreshTodayAndWeek()
            }
        }

        // Room-backed numbers; fires on every wallet/token/stats/achievement change.
        viewModelScope.launch {
            repo.observeWallet().collect { wallet ->
                _state.update { it.copy(wallet = wallet ?: Wallet()) }
            }
        }
        viewModelScope.launch {
            repo.observeTokens().collect { tokens ->
                _state.update { it.copy(tokens = tokens ?: EmergencyTokens()) }
            }
        }
        viewModelScope.launch {
            repo.observeStats().collect { stats ->
                _state.update { it.copy(stats = stats ?: UserStats()) }
                refreshTodayAndWeek()
                refreshPeriod()
            }
        }
        viewModelScope.launch {
            repo.observeAchievements().collect { achievements ->
                _state.update { it.copy(achievements = achievements) }
            }
        }
    }

    private suspend fun refreshTodayAndWeek() {
        val today = repo.todaySummary()
        val week = buildChart(_state.value.period)
        _state.update {
            it.copy(
                todayMinutes = today.first,
                todayPages = today.second,
                week = week
            )
        }
    }

    /**
     * Build the 7-bar chart for the active period. Every bucket is a calendar-day
     * aligned portion of the range; bars are always 7 so switching period reshapes
     * what each bar means (day, week, or a slice of all history) instead of the
     * graph silently staying the same.
     */
    private suspend fun buildChart(period: StatsPeriod): List<DaySummary> {
        val today = TimeCalculator.startOfToday()
        val (start, end, labeler) = when (period) {
            StatsPeriod.WEEK -> {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                Triple(cal.timeInMillis, today, DAY_LABELER)
            }
            StatsPeriod.MONTH -> {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                Triple(cal.timeInMillis, today, DAY_NUMBER_LABELER)
            }
            StatsPeriod.ALL -> {
                val first = repo.firstReadingDay() ?: today
                Triple(first, today, MONTH_LABELER)
            }
        }
        val buckets = repo.barsBetween(start, end, 7)
        // Label each bucket from its own start day so labels travel with the data.
        return buckets.map { b ->
            b.copy(label = labeler(b.dayStartMillis))
        }
    }

    /** Re-pull pages by format and the scoped reading totals for the active period. */
    private suspend fun refreshPeriod() {
        val period = _state.value.period
        val (minutes, pages) = repo.aggregateSince(periodStart(period))
        val paper = repo.totalPagesByFormatSince(periodStart(period), com.antisdvg.data.model.BookFormat.PAPER)
        val electronic = repo.totalPagesByFormatSince(periodStart(period), com.antisdvg.data.model.BookFormat.ELECTRONIC)
        _state.update {
            it.copy(
                periodMinutes = minutes,
                periodPages = pages,
                paperPages = paper,
                electronicPages = electronic
            )
        }
    }

    /** Start-of-period millis; ALL uses 0 so every recorded session counts. */
    private fun periodStart(period: StatsPeriod): Long {
        val cal = Calendar.getInstance()
        return when (period) {
            StatsPeriod.WEEK -> {
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            StatsPeriod.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            StatsPeriod.ALL -> 0L
        }
    }

    /** Switch the scoped reading stats to a different period. */
    fun setPeriod(period: StatsPeriod) {
        if (_state.value.period == period) return
        _state.update { it.copy(period = period) }
        viewModelScope.launch {
            refreshPeriod()
            refreshTodayAndWeek() // reshapes the chart for the new period
        }
    }

    /**
     * Spend one emergency token for a grace period of the settings' emergency
     * minutes (mirrors the Reading/block-overlay flow).
     */
    fun useEmergencyToken() {
        val current = _state.value.tokens
        if (current.count <= 0) return
        viewModelScope.launch {
            val spent = repo.spendEmergencyToken()
            if (spent) {
                repo.grantEmergencyMinutes(repo.getSettings().emergencyMinutes.coerceAtLeast(0))
            }
        }
    }
}

/** Russian short day-of-week names, indexed by Calendar.DAY_OF_WEEK - 1. */
private val DAY_OF_WEEK_SHORT = arrayOf(
    "Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"
)

private val DATE_FMT = SimpleDateFormat("d", Locale.getDefault())
private val MONTH_FMT = SimpleDateFormat("MMM", Locale.getDefault())

/** Labels are chosen per bucket by the period they belong to. */
private typealias Labeler = (Long) -> String

/** Week chart: label each bar with the day of the week it starts on. */
private val DAY_LABELER: Labeler = { millis ->
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    DAY_OF_WEEK_SHORT[cal.get(Calendar.DAY_OF_WEEK) - 1]
}

/** Month chart: label bars with the start day number (1, 5, 9, …). */
private val DAY_NUMBER_LABELER: Labeler = { millis ->
    DATE_FMT.format(millis)
}

/** All-time chart: label bars with the start month (янв., фев., …). */
private val MONTH_LABELER: Labeler = { millis ->
    MONTH_FMT.format(millis)
}
