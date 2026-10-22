package com.antisdvg.ui.catalog

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.BookFormat
import com.antisdvg.data.model.BookStatus
import com.antisdvg.data.remote.CatalogBook
import com.antisdvg.data.remote.CatalogClient
import com.antisdvg.data.repository.DataRepository
import com.antisdvg.util.BookImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Project Gutenberg catalogue: search, download, and hand the file to the same
 * importer the "add book" flow uses, so downloaded books are ordinary shelf books.
 */
class CatalogViewModel(
    private val repo: DataRepository,
    private val app: Application
) : ViewModel() {

    enum class DownloadState { IDLE, DOWNLOADING, DONE, FAILED }

    data class Item(
        val book: CatalogBook,
        val state: DownloadState = DownloadState.IDLE,
        val bookId: Long = 0L
    )

    data class UiState(
        val query: String = "",
        val loading: Boolean = false,
        val error: Boolean = false,
        val items: List<Item> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        search("")
    }

    /** Typing should not fire a request per keystroke. */
    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(450)
            search(query)
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        _state.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { CatalogClient.search(query.trim()) }
            }
            outcome.onSuccess { books ->
                _state.update {
                    it.copy(loading = false, error = false, items = books.map { book -> Item(book) })
                }
            }.onFailure {
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

    fun download(book: CatalogBook) {
        val current = _state.value.items.firstOrNull { it.book.id == book.id } ?: return
        if (current.state == DownloadState.DOWNLOADING || current.state == DownloadState.DONE) return
        setState(book.id, DownloadState.DOWNLOADING)

        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { fetch(book) }
            if (file == null) {
                setState(book.id, DownloadState.FAILED)
                return@launch
            }
            val imported = withContext(Dispatchers.IO) {
                runCatching { BookImporter.import(app, file, book.fileName) }.getOrNull()
            }
            file.delete()
            if (imported == null) {
                setState(book.id, DownloadState.FAILED)
                return@launch
            }
            val id = withContext(Dispatchers.IO) {
                repo.addBook(
                    Book(
                        title = imported.title,
                        author = imported.author,
                        coverUrl = if (imported.coverPath.isBlank()) "" else "file://${imported.coverPath}",
                        totalPages = imported.estimatedPages,
                        currentPage = 0,
                        status = BookStatus.IN_PROGRESS,
                        format = BookFormat.ELECTRONIC,
                        filePath = imported.filePath,
                        fileKind = imported.kind
                    )
                )
            }
            setState(book.id, DownloadState.DONE, id)
        }
    }

    private fun fetch(book: CatalogBook): File? {
        val url = book.downloadUrl ?: return null
        val extension = if (book.epubUrl != null) "epub" else "txt"
        val target = File(app.cacheDir, "catalog-${book.id}.$extension")
        val ok = runCatching { CatalogClient.download(url, target) }.getOrDefault(false)
        return target.takeIf { ok }
    }

    private fun setState(id: Int, state: DownloadState, bookId: Long = 0L) {
        _state.update { ui ->
            ui.copy(
                items = ui.items.map {
                    if (it.book.id == id) it.copy(state = state, bookId = bookId) else it
                }
            )
        }
    }
}
