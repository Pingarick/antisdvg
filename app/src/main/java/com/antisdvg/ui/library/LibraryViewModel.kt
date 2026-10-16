package com.antisdvg.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.BookFormat
import com.antisdvg.data.model.BookStatus
import com.antisdvg.data.repository.DataRepository
import com.antisdvg.util.BookImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of a book import, consumed once by the library screen. */
sealed interface ImportEvent {
    data class Imported(val bookId: Long, val title: String) : ImportEvent
    data object Unsupported : ImportEvent
    data object Failed : ImportEvent
}

class LibraryViewModel(
    private val repo: DataRepository,
    private val app: Application
) : ViewModel() {

    /** Books ordered so active/in-progress come first, finished last. */
    val books: StateFlow<List<Book>> = repo.observeBooks()
        .map { list ->
            list.sortedWith(
                compareBy<Book> { it.status == BookStatus.READ }
                    .thenBy { it.startDate }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether there is a book the reader is currently working on. */
    val hasActiveBook: StateFlow<Boolean> = repo.observeBooks()
        .map { list -> list.any { it.status == BookStatus.ACTIVE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _importEvents = Channel<ImportEvent>(Channel.BUFFERED)
    val importEvents = _importEvents.receiveAsFlow()

    /** Marks a newly opened book as active, demoting any other active book. */
    fun setActive(book: Book) {
        viewModelScope.launch {
            val currentActive = repo.getActiveBook()
            if (currentActive != null && currentActive.id != book.id) {
                repo.updateBook(currentActive.copy(status = BookStatus.IN_PROGRESS))
            }
            if (book.status != BookStatus.ACTIVE) {
                repo.updateBook(book.copy(status = BookStatus.ACTIVE))
            }
        }
    }

    /**
     * Imports an FB2/TXT document into app storage and adds it to the shelf as
     * an electronic book, ready to open in the built-in reader.
     */
    fun importBook(uri: Uri) {
        viewModelScope.launch {
            val imported = try {
                withContext(Dispatchers.IO) { BookImporter.import(app, uri) }
            } catch (_: BookImporter.UnsupportedFormatException) {
                _importEvents.send(ImportEvent.Unsupported)
                return@launch
            } catch (_: Exception) {
                null
            }
            if (imported == null) {
                _importEvents.send(ImportEvent.Failed)
                return@launch
            }
            val book = Book(
                title = imported.title,
                author = imported.author,
                coverUrl = imported.coverPath,
                totalPages = imported.estimatedPages,
                currentPage = 0,
                status = BookStatus.IN_PROGRESS,
                format = BookFormat.ELECTRONIC,
                filePath = imported.filePath,
                fileKind = imported.kind
            )
            val id = repo.addBook(book)
            _importEvents.send(ImportEvent.Imported(id, book.title))
        }
    }
}
