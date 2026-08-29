package com.antisdvg.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.BookStatus
import com.antisdvg.data.repository.DataRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repo: DataRepository
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
}
