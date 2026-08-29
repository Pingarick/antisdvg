package com.antisdvg.ui.reading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.BookFormat
import com.antisdvg.data.repository.AIRepository
import com.antisdvg.data.repository.DataRepository
import com.antisdvg.data.remote.VerifyResult
import com.antisdvg.util.TimeCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Progress of the AI-backed reading verification. */
sealed interface VerifyState {
    data object Idle : VerifyState
    data object Loading : VerifyState
    data class Success(val earnedMinutes: Int) : VerifyState
    data class Failure(val feedback: String) : VerifyState
    object NoKey : VerifyState
    object NoNetwork : VerifyState
}

/** Everything the reading screen shows. */
data class ReadingUiState(
    val book: Book? = null,
    val tokenCount: Int = 0,
    val verify: VerifyState = VerifyState.Idle
)

class ReadingViewModel(
    private val repo: DataRepository,
    private val ai: AIRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ReadingUiState())
    val state: StateFlow<ReadingUiState> = _state.asStateFlow()

    private var apiKey: String = ""

    fun load(bookId: Long) {
        viewModelScope.launch {
            apiKey = repo.getSettings().apiKey
        }
        viewModelScope.launch {
            repo.observeTokens().collect {
                _state.value = _state.value.copy(tokenCount = it?.count ?: 0)
            }
        }
        if (bookId < 0) return
        viewModelScope.launch {
            repo.observeBook(bookId).collect { book ->
                if (book != null) _state.value = _state.value.copy(book = book)
            }
        }
    }

    /** Verify the user read the pages, and on success award minutes. */
    fun verify(startPageInput: String, endPageInput: String, userText: String) {
        val book = _state.value.book ?: return
        val start = startPageInput.toIntOrNull()
        val end = endPageInput.toIntOrNull()
        if (start == null || end == null || end < start) {
            _state.value = _state.value.copy(
                verify = VerifyState.Failure("Укажи корректный диапазон страниц.")
            )
            return
        }
        if (userText.isBlank()) {
            _state.value = _state.value.copy(
                verify = VerifyState.Failure("Напиши пересказ прочитанного.")
            )
            return
        }
        if (start < 1 || end < start || start > book.totalPages || end > book.totalPages) {
            _state.value = _state.value.copy(
                verify = VerifyState.Failure("Страницы вне диапазона 1…${book.totalPages}.")
            )
            return
        }

        if (apiKey.isBlank()) {
            _state.value = _state.value.copy(verify = VerifyState.NoKey)
            return
        }

        _state.value = _state.value.copy(verify = VerifyState.Loading)
        viewModelScope.launch {
            val result = try {
                ai.verifyReading(
                    book = book,
                    startPage = start,
                    endPage = end,
                    userText = userText,
                    apiKey = apiKey
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(verify = VerifyState.NoNetwork)
                return@launch
            }
            handleResult(result, start, end)
        }
    }

    private suspend fun handleResult(result: VerifyResult, start: Int, end: Int) {
        val book = _state.value.book ?: return
        if (!result.ok) {
            _state.value = _state.value.copy(
                verify = VerifyState.Failure(result.feedback)
            )
            return
        }

        val settings = repo.getSettings()
        val pages = (end - start + 1).coerceAtLeast(1)
        // Progressive bonus enabled: base minutes + bonus so that reading more
        // at once earns disproportionately more fun time.
        val currentStreak = repo.getStats().currentStreak
        val base = TimeCalculator.minutesForReading(
            pages,
            settings.minutesPerPage,
            useProgressiveBonus = true,
            streak = currentStreak
        )
        // Electronic books earn less per page than paper ones.
        val earned = (base * BookFormat.factor(book.format)).toInt()
        repo.recordSession(
            book = book,
            startPage = start,
            endPage = end,
            taskType = result.taskType,
            earnedMinutes = earned
        )
        _state.value = _state.value.copy(verify = VerifyState.Success(earned))
    }

    fun resetVerify() {
        _state.value = _state.value.copy(verify = VerifyState.Idle)
    }

    /** Spend one emergency token for a grace period of the settings' emergency minutes. */
    fun useEmergencyToken() {
        if (_state.value.tokenCount <= 0) return
        viewModelScope.launch {
            val spent = repo.spendEmergencyToken()
            if (spent) {
                repo.grantEmergencyMinutes(repo.getSettings().emergencyMinutes.coerceAtLeast(0))
                _state.value = _state.value.copy(
                    tokenCount = _state.value.tokenCount - 1
                )
            }
        }
    }
}
