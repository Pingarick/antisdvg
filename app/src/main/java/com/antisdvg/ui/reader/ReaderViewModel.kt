package com.antisdvg.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.QuizQuestion
import com.antisdvg.data.remote.VerifyResult
import com.antisdvg.data.repository.DataRepository
import com.antisdvg.util.BookText
import com.antisdvg.util.ClozeQuiz
import com.antisdvg.util.ReaderProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/** Reading palette of the built-in reader. */
enum class ReaderTheme { PAPER, SEPIA, NIGHT }

data class ReaderUiState(
    val book: Book? = null,
    val html: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    /** Seconds the reader was on screen, awake and not idle. */
    val activeSeconds: Int = 0,
    /** Furthest progress reached during this session, 0..1. */
    val progress: Double = 0.0,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val fontPercent: Int = 100
)

/** What the session actually produced, shown before the light test. */
data class SessionSummary(
    val minutesRead: Int,
    val pagesRead: Int,
    val fromPage: Int,
    val toPage: Int
)

/** Outcome of the gate: how many answers were right and what it paid. */
data class QuizOutcome(
    val correct: Int,
    val total: Int,
    val passed: Boolean,
    val grantedMinutes: Int
)

/**
 * Drives the built-in reader: loads an imported FB2/TXT file, tracks honest
 * reading time (only while the screen is awake and the reader is not idle),
 * remembers the position, and runs the offline cloze gate at the end.
 */
class ReaderViewModel(
    private val repo: DataRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var plainText: String = ""
    private var sessionStartProgress = 0.0
    private var questions: List<QuizQuestion> = emptyList()

    fun load(bookId: Long, theme: ReaderTheme, fontPercent: Int) {
        viewModelScope.launch {
            val book = repo.getBook(bookId)
            if (book == null || book.filePath.isBlank()) {
                _state.update { it.copy(loading = false, error = FILE_MISSING) }
                return@launch
            }
            sessionStartProgress = book.progress.coerceIn(0.0, 1.0)
            _state.update {
                it.copy(
                    book = book,
                    progress = sessionStartProgress,
                    theme = theme,
                    fontPercent = fontPercent
                )
            }
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    File(book.filePath).inputStream().use { stream ->
                        BookText.parse(stream, book.fileKind, book.title)
                    }
                }.getOrNull()
            }
            if (parsed == null) {
                _state.update { it.copy(loading = false, error = FILE_BROKEN) }
                return@launch
            }
            plainText = parsed.plainText
            _state.update { it.copy(html = parsed.html, loading = false) }
        }
    }

    /** One honest reading second: only the Activity decides when to call this. */
    fun onTick() {
        _state.update { it.copy(activeSeconds = it.activeSeconds + 1) }
    }

    fun noteProgress(progress: Double) {
        val value = progress.coerceIn(0.0, 1.0)
        if (value > _state.value.progress) _state.update { it.copy(progress = value) }
    }

    fun setTheme(theme: ReaderTheme) = _state.update { it.copy(theme = theme) }

    fun cycleTheme() = setTheme(
        when (_state.value.theme) {
            ReaderTheme.PAPER -> ReaderTheme.SEPIA
            ReaderTheme.SEPIA -> ReaderTheme.NIGHT
            ReaderTheme.NIGHT -> ReaderTheme.PAPER
        }
    )

    fun adjustFont(deltaPercent: Int) {
        _state.update {
            it.copy(fontPercent = (it.fontPercent + deltaPercent).coerceIn(80, 200))
        }
    }

    /** Persists the position so closing the reader never loses the bookmark. */
    fun persistProgress() {
        val state = _state.value
        val book = state.book ?: return
        viewModelScope.launch {
            repo.saveReaderProgress(book.id, state.progress, ReaderProgress.pageAt(state.progress, book.totalPages))
        }
    }

    fun totalPages(): Int = _state.value.book?.totalPages?.takeIf { it > 0 } ?: 1

    /** 1-based page equivalent of a 0..1 progress on the current book. */
    fun pageAt(progress: Double): Int = ReaderProgress.pageAt(progress, totalPages())

    fun summary(): SessionSummary {
        val state = _state.value
        val total = totalPages()
        val from = ReaderProgress.pageAt(sessionStartProgress, total)
        val to = ReaderProgress.pageAt(state.progress, total)
        val pages = ReaderProgress.pagesAdvanced(sessionStartProgress, state.progress, total)
        return SessionSummary(state.activeSeconds / 60, pages, from, to)
    }

    /** Minutes this session is worth: real reading time, electronic factor applied. */
    private fun earnedMinutes(summary: SessionSummary, format: String): Int =
        ReaderProgress.earnedMinutes(summary.minutesRead, format)

    /**
     * Builds a light cloze test from the text that was actually scrolled during
     * this session (widened a little when the session was short).
     */
    fun buildQuiz(): List<QuizQuestion> {
        val state = _state.value
        val total = plainText.length
        val start = (sessionStartProgress * total).toInt().coerceIn(0, total)
        val end = (state.progress * total).toInt().coerceIn(0, total)
        var excerpt = if (end > start) plainText.substring(start, end) else ""
        if (excerpt.length < 1500 && total > 1500) {
            val wideEnd = (end + 4000).coerceAtMost(total)
            val wideStart = (wideEnd - 6500).coerceAtLeast(0)
            excerpt = plainText.substring(wideStart, wideEnd)
        }
        questions = ClozeQuiz.generate(excerpt, count = 3, rnd = Random(System.currentTimeMillis()))
        return questions
    }

    /**
     * Grades the answers and, when the light test is passed, awards minutes for
     * the honest reading time. Two of three right already passes.
     */
    suspend fun gradeAndGrant(answers: List<Int>): QuizOutcome {
        val state = _state.value
        val book = state.book ?: return QuizOutcome(0, 0, false, 0)
        val correct = questions.indices.count { i ->
            answers.getOrNull(i) == questions[i].correctIndex
        }
        val required = when {
            questions.isEmpty() -> 0
            questions.size <= 2 -> 1
            else -> (questions.size * 2 + 2) / 3
        }
        val passed = questions.isNotEmpty() && correct >= required
        if (!passed) return QuizOutcome(correct, questions.size, false, 0)

        val summary = summary()
        val earned = awardSession(book, summary)
        return QuizOutcome(correct, questions.size, true, earned)
    }

    /**
     * Fallback when the read passage is too short to build a cloze test: the
     * honest reading time already proves the session, so minutes are granted.
     */
    suspend fun grantWithoutQuiz(): QuizOutcome {
        val book = _state.value.book ?: return QuizOutcome(0, 0, false, 0)
        val earned = awardSession(book, summary())
        return QuizOutcome(0, 0, true, earned)
    }

    private suspend fun awardSession(book: Book, summary: SessionSummary): Int {
        val earned = earnedMinutes(summary, book.format)
        val endPage = ReaderProgress.pageAt(_state.value.progress, book.totalPages)
        repo.recordSession(
            book = book.copy(progress = _state.value.progress),
            startPage = summary.fromPage,
            endPage = maxOf(endPage, book.currentPage),
            taskType = VerifyResult.TASK_QUIZ,
            earnedMinutes = earned
        )
        return earned
    }

    /** True when the session is too short to pay anything honestly. */
    fun isTooShort(): Boolean = _state.value.activeSeconds < ReaderProgress.MIN_SESSION_SECONDS

    companion object {
        const val FILE_MISSING = "Файл книги не найден."
        const val FILE_BROKEN = "Не удалось открыть файл книги."
    }
}
