package com.antisdvg.ui.reading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.BookFormat
import com.antisdvg.data.model.QuizQuestion
import com.antisdvg.data.repository.AIRepository
import com.antisdvg.data.repository.DataRepository
import com.antisdvg.data.remote.VerifyResult
import com.antisdvg.util.TimeCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

/** Reading modes */
enum class ReadingMode { RETELL, QUIZ }

/** Everything the reading screen shows, now includes mode and quiz data. */
data class ReadingUiState(
    val book: Book? = null,
    val tokenCount: Int = 0,
    val verify: VerifyState = VerifyState.Idle,
    val mode: ReadingMode = ReadingMode.RETELL,
    val quizQuestions: List<QuizQuestion> = emptyList(),
    val selectedAnswers: List<Int> = emptyList()
)

class ReadingViewModel(
    private val repo: DataRepository,
    private val ai: AIRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ReadingUiState())
    val state: StateFlow<ReadingUiState> = _state.asStateFlow()

    private var apiKey: String = ""

    fun load(bookId: Long) {
        viewModelScope.launch { apiKey = repo.getSettings().apiKey }
        viewModelScope.launch {
            repo.observeTokens().collect { token ->
                _state.update { it.copy(tokenCount = token?.count ?: 0) }
            }
        }
        if (bookId < 0) return
        viewModelScope.launch {
            repo.observeBook(bookId).collect { book ->
                if (book != null) _state.update { it.copy(book = book) }
            }
        }
    }

    /** Switch between retelling and quiz mode */
    fun setMode(mode: ReadingMode) {
        _state.update {
            it.copy(
                mode = mode,
                verify = VerifyState.Idle,
                quizQuestions = emptyList(),
                selectedAnswers = emptyList()
            )
        }
    }

    /** Verify reading or generate quiz depending on mode */
    fun verify(startPageInput: String, endPageInput: String, userText: String, quote: String = "") {
        val current = _state.value
        val book = current.book ?: return
        val start = startPageInput.toIntOrNull()
        val end = endPageInput.toIntOrNull()
        if (start == null || end == null || end < start) {
            _state.update { it.copy(verify = VerifyState.Failure("Укажи корректный диапазон страниц.")) }
            return
        }
        if (start < 1 || end < start || start > book.totalPages || end > book.totalPages) {
            _state.update { it.copy(verify = VerifyState.Failure("Страницы вне диапазона 1…${book.totalPages}.")) }
            return
        }
        val effectiveKey = apiKey.ifBlank { com.antisdvg.BuildConfig.DEEPSEEK_API_KEY }
        if (effectiveKey.isBlank()) {
            _state.update { it.copy(verify = VerifyState.NoKey) }
            return
        }
        // For quiz mode we only generate questions
        if (current.mode == ReadingMode.QUIZ) {
            _state.update { it.copy(verify = VerifyState.Loading) }
            viewModelScope.launch {
                val quiz = try { ai.generateQuiz(book, start, end, effectiveKey) } catch (e: Exception) { emptyList() }
                if (quiz.isEmpty()) {
                    _state.update { it.copy(verify = VerifyState.Failure("Не удалось сгенерировать квиз.")) }
                } else {
                    _state.update { it.copy(quizQuestions = quiz, verify = VerifyState.Idle) }
                }
            }
            return
        }
        // Retelling path requires non‑empty text
        if (userText.isBlank()) {
            _state.update { it.copy(verify = VerifyState.Failure("Напиши пересказ прочитанного.")) }
            return
        }
        // Proceed with normal verification
        _state.update { it.copy(verify = VerifyState.Loading) }
        viewModelScope.launch {
            val settings = repo.getSettings()
            val strictness = settings.verificationStrictness
            val result = try {
                ai.verifyReading(
                    book = book,
                    startPage = start,
                    endPage = end,
                    userText = userText,
                    quote = quote.trim(),
                    taskType = VerifyResult.TASK_RETELLING,
                    strictness = strictness,
                    apiKey = effectiveKey
                )
            } catch (e: Exception) {
                _state.update { it.copy(verify = VerifyState.NoNetwork) }
                return@launch
            }
            handleResult(result, start, end)
        }
    }

    /** Submit answers for a generated quiz */
    fun submitQuizAnswers(start: Int, end: Int, answers: List<Int>) {
        val questions = _state.value.quizQuestions
        if (questions.isEmpty()) {
            _state.update { it.copy(verify = VerifyState.Failure("Нет вопросов для проверки.")) }
            return
        }
        var correct = 0
        for (i in answers.indices) {
            if (i < questions.size && answers[i] == questions[i].correctIndex) correct++
        }
        if (correct >= 2) { // award minutes when at least 2 of 3 correct
            viewModelScope.launch {
                val book = _state.value.book ?: return@launch
                val pages = (end - start + 1).coerceAtLeast(1)
                val settings = repo.getSettings()
                val base = TimeCalculator.minutesForReading(
                    pages,
                    settings.minutesPerPage,
                    useProgressiveBonus = true,
                    streak = repo.getStats().currentStreak
                )
                val earned = (base * BookFormat.factor(book.format)).toInt()
                repo.recordSession(
                    book = book,
                    startPage = start,
                    endPage = end,
                    taskType = VerifyResult.TASK_QUIZ,
                    earnedMinutes = earned
                )
                _state.update { it.copy(verify = VerifyState.Success(earned), quizQuestions = emptyList(), selectedAnswers = emptyList()) }
            }
        } else {
            _state.update { it.copy(verify = VerifyState.Failure("Недостаточно правильных ответов: $correct из ${questions.size}.")) }
        }
    }

    private suspend fun handleResult(result: VerifyResult, start: Int, end: Int) {
        val book = _state.value.book ?: return
        if (!result.ok) {
            _state.update { it.copy(verify = VerifyState.Failure(result.feedback)) }
            return
        }
        val settings = repo.getSettings()
        val pages = (end - start + 1).coerceAtLeast(1)
        val currentStreak = repo.getStats().currentStreak
        val base = TimeCalculator.minutesForReading(
            pages,
            settings.minutesPerPage,
            useProgressiveBonus = true,
            streak = currentStreak
        )
        val earned = (base * BookFormat.factor(book.format)).toInt()
        repo.recordSession(
            book = book,
            startPage = start,
            endPage = end,
            taskType = result.taskType,
            earnedMinutes = earned
        )
        _state.update { it.copy(verify = VerifyState.Success(earned)) }
    }

    fun resetVerify() {
        _state.update { it.copy(verify = VerifyState.Idle) }
    }

    /** Spend one emergency token for a grace period of the settings' emergency minutes. */
    fun useEmergencyToken() {
        if (_state.value.tokenCount <= 0) return
        viewModelScope.launch {
            val minutes = repo.getSettings().emergencyMinutes.coerceAtLeast(0)
            val spent = repo.spendTokenForMinutes(minutes)
            if (spent) {
                _state.update { it.copy(tokenCount = _state.value.tokenCount - 1) }
            }
        }
    }
}
