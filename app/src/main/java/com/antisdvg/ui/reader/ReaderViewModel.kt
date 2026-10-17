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

/**
 * Reading palette of the built-in reader.
 *
 * The ordinal is stored in preferences, so new themes are only ever appended —
 * reordering would silently repaint everyone's saved choice.
 */
enum class ReaderTheme { PAPER, SEPIA, NIGHT, BLACK }

/** Typeface of the reader; [css] goes straight into the page style. */
enum class ReaderFont(val css: String) {
    SERIF("Georgia,'Noto Serif','Times New Roman',serif"),
    SANS("'Roboto','Helvetica Neue',Arial,sans-serif"),
    MONO("'Noto Sans Mono','Droid Sans Mono','Courier New',monospace")
}

/** PAGED turns pages sideways, SCROLL is the endless ribbon. */
enum class ReaderMode { PAGED, SCROLL }

data class ReaderUiState(
    val book: Book? = null,
    val html: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    /** Seconds the reader was on screen, awake and not idle. */
    val activeSeconds: Int = 0,
    /** Furthest progress reached during this session, 0..1. */
    val progress: Double = 0.0,
    /** Exact page the paged layout is on, 1-based; 0 while it is unknown. */
    val page: Int = 0,
    /** Exact page count of the paged layout; 0 while it is unknown. */
    val pages: Int = 0,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val fontPercent: Int = 100,
    val font: ReaderFont = ReaderFont.SERIF,
    val mode: ReaderMode = ReaderMode.PAGED
)

/** What the session actually produced, shown before the light test. */
data class SessionSummary(
    val minutesRead: Int,
    val pagesRead: Int,
    val fromPage: Int,
    val toPage: Int
)

/**
 * The pages the paged layout really put on screen during a session, plus their
 * text. [pages] is the number of *distinct* screens (flipping back and forth
 * never counts a page twice), [fromPage]/[toPage] are the outermost ones, and
 * [text] is the joined text of those screens — the only text a session's test
 * is allowed to ask about.
 */
data class ReadExcerpt(
    val text: String,
    val fromPage: Int,
    val toPage: Int,
    val pages: Int
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

    /**
     * Pages the paged layout reported as seen this session. Set before the
     * session is frozen, so the summary and the test both describe the same
     * screens. Empty in ribbon mode, where only progress is known.
     */
    private var excerpt: ReadExcerpt? = null

    /** Last progress written to the database, so autosave never writes twice. */
    private var lastPersisted = -1.0

    /**
     * Set when the user ends the session: the reward must reflect reading time
     * only, not the seconds spent staring at the summary and the test.
     */
    private var frozen: SessionSummary? = null

    fun load(
        bookId: Long,
        theme: ReaderTheme,
        fontPercent: Int,
        font: ReaderFont,
        mode: ReaderMode
    ) {
        viewModelScope.launch {
            val book = repo.getBook(bookId)
            if (book == null || book.filePath.isBlank()) {
                _state.update { it.copy(loading = false, error = FILE_MISSING) }
                return@launch
            }
            sessionStartProgress = book.progress.coerceIn(0.0, 1.0)
            lastPersisted = sessionStartProgress
            _state.update {
                it.copy(
                    book = book,
                    progress = sessionStartProgress,
                    theme = theme,
                    fontPercent = fontPercent,
                    font = font,
                    mode = mode
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
            // Opening a book in the reader makes it the current one on the home screen.
            repo.markActive(book)
        }
    }

    /** One honest reading second: only the Activity decides when to call this. */
    fun onTick() {
        if (frozen != null) return
        _state.update { it.copy(activeSeconds = it.activeSeconds + 1) }
    }

    /** The user ended the session: lock in what was read before any dialog. */
    fun freezeSession() {
        if (frozen == null) frozen = computeSummary()
    }

    /** Back to reading without ending the session (dialog cancelled). */
    fun unfreeze() {
        frozen = null
    }

    fun noteProgress(progress: Double) {
        val value = progress.coerceIn(0.0, 1.0)
        if (value > _state.value.progress) _state.update { it.copy(progress = value) }
    }

    /**
     * The paged layout reports where it really is. This is what the reader shows
     * and what the session counts, so "стр. 5 из 6" means the five pages the
     * reader actually turned, not a guess from the character count.
     *
     * The page count of a layout depends on font size and screen, so the book
     * keeps advertising the rough estimate until the reader has a real number —
     * then the book is corrected, and the library card agrees with the reader.
     */
    fun notePage(page: Int, pages: Int) {
        val before = _state.value
        if (before.page == page && before.pages == pages) return
        _state.update { it.copy(page = page, pages = pages) }

        val book = before.book ?: return
        if (pages <= 0 || pages == book.totalPages) return
        viewModelScope.launch {
            repo.setBookTotalPages(book.id, pages)
            _state.update { state ->
                state.copy(book = state.book?.copy(totalPages = pages))
            }
        }
    }

    /**
     * The layout's report of which screens were on screen, taken when the user
     * ends the session. Only used to describe and to test the session, never to
     * decide the minutes: time is what pays.
     */
    fun noteExcerpt(excerpt: ReadExcerpt) {
        this.excerpt = excerpt
    }

    fun setTheme(theme: ReaderTheme) = _state.update { it.copy(theme = theme) }

    fun cycleTheme() = setTheme(
        when (_state.value.theme) {
            ReaderTheme.PAPER -> ReaderTheme.SEPIA
            ReaderTheme.SEPIA -> ReaderTheme.NIGHT
            ReaderTheme.NIGHT -> ReaderTheme.BLACK
            ReaderTheme.BLACK -> ReaderTheme.PAPER
        }
    )

    /** Serif -> sans -> mono, then back. */
    fun cycleFont() = _state.update {
        it.copy(
            font = when (it.font) {
                ReaderFont.SERIF -> ReaderFont.SANS
                ReaderFont.SANS -> ReaderFont.MONO
                ReaderFont.MONO -> ReaderFont.SERIF
            }
        )
    }

    /** Pages sideways <-> endless ribbon. */
    fun cycleMode() = _state.update {
        it.copy(mode = if (it.mode == ReaderMode.PAGED) ReaderMode.SCROLL else ReaderMode.PAGED)
    }

    fun adjustFont(deltaPercent: Int) {
        _state.update {
            it.copy(fontPercent = (it.fontPercent + deltaPercent).coerceIn(80, 200))
        }
    }

    /** Persists the position so closing the reader never loses the bookmark. */
    fun persistProgress() {
        val state = _state.value
        val book = state.book ?: return
        // Writing on every tick would be silly; a real move is worth a save.
        if (Math.abs(state.progress - lastPersisted) < 0.005) return
        lastPersisted = state.progress
        viewModelScope.launch {
            repo.saveReaderProgress(book.id, state.progress, ReaderProgress.pageAt(state.progress, book.totalPages))
        }
    }

    /**
     * Pages of the book as the reader really sees them: the exact count of the
     * current paged layout when it is known, otherwise the character estimate.
     */
    fun totalPages(): Int {
        val state = _state.value
        return state.pages.takeIf { it > 0 }
            ?: state.book?.totalPages?.takeIf { it > 0 }
            ?: 1
    }

    /** 1-based page the reader is on right now. */
    fun currentPage(): Int =
        _state.value.page.takeIf { it > 0 } ?: pageAt(_state.value.progress)

    /** 1-based page equivalent of a 0..1 progress on the current book. */
    fun pageAt(progress: Double): Int = ReaderProgress.pageAt(progress, totalPages())

    fun summary(): SessionSummary = frozen ?: computeSummary()

    private fun computeSummary(): SessionSummary {
        val state = _state.value
        val total = totalPages()
        // With a page report the summary describes the screens the reader saw;
        // without one (ribbon mode) it falls back to progress on the text.
        val seen = excerpt
        val from = seen?.fromPage ?: ReaderProgress.pageAt(sessionStartProgress, total)
        val to = seen?.toPage ?: ReaderProgress.pageAt(state.progress, total)
        val pages = seen?.pages ?: ReaderProgress.pagesAdvanced(sessionStartProgress, state.progress, total)
        return SessionSummary(state.activeSeconds / 60, pages, from, to)
    }

    /** Minutes this session is worth: real reading time, electronic factor applied. */
    private fun earnedMinutes(summary: SessionSummary, format: String): Int =
        ReaderProgress.earnedMinutes(summary.minutesRead, format)

    /**
     * Builds the session's light test.
     *
     * The text comes from the screens the paged layout says it showed, so the
     * questions can only ask about what was in front of the reader — no wide
     * window around the position, no text from pages never opened. The number
     * of questions follows the amount read (see [ReaderProgress.questionsForPages]).
     */
    fun buildQuiz(): List<QuizQuestion> {
        val seen = excerpt
        // With a page report the test comes from those pages and nothing else.
        // Ribbon mode has no pages, so there the progress range stands in.
        val text = seen?.text?.takeIf { it.isNotBlank() } ?: fallbackExcerpt()
        val pages = seen?.pages ?: summary().pagesRead
        questions = ClozeQuiz.generate(
            text,
            count = ReaderProgress.questionsForPages(pages),
            rnd = Random(System.currentTimeMillis())
        )
        return questions
    }

    /** How many questions the current test has (0 when none could be built). */
    fun questionCount(): Int = questions.size

    /**
     * Ribbon mode has no pages to report, so there the test still comes from the
     * character range the progress covers, widened when the session was short.
     */
    private fun fallbackExcerpt(): String {
        val state = _state.value
        val total = plainText.length
        val start = (sessionStartProgress * total).toInt().coerceIn(0, total)
        val end = (state.progress * total).toInt().coerceIn(0, total)
        var excerpt = if (end > start) plainText.substring(start, end) else ""
        if (excerpt.length < WIDE_EXCERPT_CHARS && total > WIDE_EXCERPT_CHARS) {
            val wideEnd = (end + 4000).coerceAtMost(total)
            val wideStart = (wideEnd - 6500).coerceAtLeast(0)
            excerpt = plainText.substring(wideStart, wideEnd)
        }
        return excerpt
    }

    /**
     * Grades the answers and, when the light test is passed, awards minutes for
     * the honest reading time.
     */
    suspend fun gradeAndGrant(answers: List<Int>): QuizOutcome {
        val state = _state.value
        val book = state.book ?: return QuizOutcome(0, 0, false, 0)
        val correct = questions.indices.count { i ->
            answers.getOrNull(i) == questions[i].correctIndex
        }
        val required = ReaderProgress.passThreshold(questions.size)
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
        val state = _state.value
        // Count in the pages the reader saw, and correct the book's page count
        // in the same write, or "the end of the book" would be judged by the
        // rough estimate and the book would never be marked as finished.
        val total = totalPages()
        val endPage = maxOf(summary.toPage, book.currentPage)
        repo.recordSession(
            book = book.copy(progress = state.progress, totalPages = total),
            startPage = summary.fromPage,
            endPage = endPage,
            pagesRead = summary.pagesRead,
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

        /** Ribbon mode widens a short read to this much text before quizzing. */
        private const val WIDE_EXCERPT_CHARS = 1500
    }
}
