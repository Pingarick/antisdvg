package com.antisdvg.ui.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antisdvg.data.repository.DataRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pomodoro timer phase. */
enum class FocusPhase { IDLE, FOCUS, BREAK, DONE }

/** Everything the focus screen shows. */
data class FocusUiState(
    val phase: FocusPhase = FocusPhase.IDLE,
    val secondsLeft: Int = FOCUS_SECONDS,
    val cyclesDone: Int = 0,
    val running: Boolean = false,
    val lastEarned: Int = 0
) {
    companion object {
        const val FOCUS_SECONDS = 25 * 60
        const val BREAK_SECONDS = 5 * 60
    }
}

/**
 * Pomodoro-style focus timer. A finished 25-min block is recorded via
 * [DataRepository.recordFocusSession] and earns entertainment minutes 1:1.
 * The timer lives in the ViewModel, so rotation doesn't reset it, but leaving
 * the screen cancels the run (no background service yet).
 */
class FocusViewModel(
    private val repo: DataRepository
) : ViewModel() {

    private val _state = MutableStateFlow(FocusUiState())
    val state: StateFlow<FocusUiState> = _state.asStateFlow()

    private var ticker: Job? = null

    fun start() {
        if (_state.value.running) return
        val phase = if (_state.value.phase == FocusPhase.BREAK) FocusPhase.BREAK else FocusPhase.FOCUS
        val seconds = when {
            _state.value.secondsLeft > 0 && _state.value.secondsLeft < phaseSeconds(phase) -> _state.value.secondsLeft
            else -> phaseSeconds(phase)
        }
        _state.update { it.copy(phase = phase, secondsLeft = seconds, running = true, lastEarned = 0) }
        runTicker()
    }

    fun pause() {
        ticker?.cancel()
        ticker = null
        _state.update { it.copy(running = false) }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        _state.update { FocusUiState(cyclesDone = it.cyclesDone) }
    }

    /** Skips the break and returns to idle, keeping the cycle count. */
    fun skipBreak() {
        if (_state.value.phase != FocusPhase.BREAK) return
        ticker?.cancel()
        ticker = null
        _state.update { it.copy(phase = FocusPhase.IDLE, secondsLeft = FocusUiState.FOCUS_SECONDS, running = false) }
    }

    private fun phaseSeconds(phase: FocusPhase): Int = when (phase) {
        FocusPhase.BREAK -> FocusUiState.BREAK_SECONDS
        else -> FocusUiState.FOCUS_SECONDS
    }

    private fun runTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                delay(1_000)
                val left = _state.value.secondsLeft - 1
                if (left <= 0) {
                    onPhaseFinished()
                    return@launch
                }
                _state.update { it.copy(secondsLeft = left) }
            }
        }
    }

    private suspend fun onPhaseFinished() {
        when (_state.value.phase) {
            FocusPhase.FOCUS -> {
                val earned = repo.recordFocusSession(FocusUiState.FOCUS_SECONDS / 60)
                _state.update {
                    it.copy(
                        phase = FocusPhase.BREAK,
                        secondsLeft = FocusUiState.BREAK_SECONDS,
                        cyclesDone = it.cyclesDone + 1,
                        lastEarned = earned
                    )
                }
                runTicker()
            }
            else -> {
                ticker?.cancel()
                ticker = null
                _state.update {
                    it.copy(phase = FocusPhase.DONE, secondsLeft = 0, running = false)
                }
            }
        }
    }

    /** mm:ss for the countdown label. */
    fun formattedTime(): String {
        val s = _state.value.secondsLeft.coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
