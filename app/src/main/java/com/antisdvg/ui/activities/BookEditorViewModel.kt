package com.antisdvg.ui.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antisdvg.data.model.Book
import com.antisdvg.data.remote.GoogleBooksClient
import com.antisdvg.data.remote.GoogleBookVolumeInfo
import com.antisdvg.data.remote.YandexBooksParser
import com.antisdvg.data.repository.DataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Result of a Google Books ISBN lookup, used to pre-fill the editor form. */
data class IsbnLookupResult(
    val title: String = "",
    val author: String = "",
    val publisher: String = "",
    val totalPages: Int = 0,
    val coverUrl: String = "",
    val found: Boolean = false
)

enum class IsbnLookupState { IDLE, LOADING, FOUND, NOT_FOUND, ERROR }

/** Loads a [Book] for editing (or sets up a blank one) and saves changes. */
class BookEditorViewModel(
    private val repo: DataRepository
) : ViewModel() {

    /** The book being edited; null marks a brand-new book until saved. */
    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book

    private val _lookupState = MutableStateFlow(IsbnLookupState.IDLE)
    val lookupState: StateFlow<IsbnLookupState> = _lookupState

    private val _lookupResult = MutableStateFlow<IsbnLookupResult?>(null)
    val lookupResult: StateFlow<IsbnLookupResult?> = _lookupResult

    /** Looks up an ISBN via Google Books and pre-fills the editor with its metadata. */
    fun lookupIsbn(isbn: String) {
        val cleanIsbn = isbn.trim()
        if (cleanIsbn.isEmpty()) return
        _lookupState.value = IsbnLookupState.LOADING
        viewModelScope.launch {
            // Try Google Books first; if it rate-limits (429) or finds nothing,
            // fall back to Open Library (keyless, reachable from any region).
            val result = try {
                lookupGoogle(cleanIsbn)
            } catch (e: Exception) {
                android.util.Log.w("IsbnLookup", "Google Books failed for $cleanIsbn: ${e.javaClass.name}: ${e.message}")
                lookupOpenLibrary(cleanIsbn)
            }
            if (result == null) {
                _lookupState.value = IsbnLookupState.NOT_FOUND
            } else {
                _lookupResult.value = result
                _lookupState.value = IsbnLookupState.FOUND
            }
        }
    }

    private suspend fun lookupGoogle(isbn: String): IsbnLookupResult? {
        val response = GoogleBooksClient.api.search("isbn:$isbn")
        val info = response.items?.firstOrNull()?.volumeInfo ?: return null
        return IsbnLookupResult(
            title = info.title.orEmpty(),
            author = info.authors?.joinToString(", ").orEmpty(),
            publisher = info.publisher.orEmpty(),
            totalPages = info.pageCount ?: 0,
            coverUrl = normalizeCover(info.imageLinks?.thumbnail),
            found = true
        )
    }

    private suspend fun lookupOpenLibrary(isbn: String): IsbnLookupResult? {
        val book = GoogleBooksClient.openLibrary.byIsbn(
            bibkeys = "ISBN:$isbn",
            format = "json",
            jscmd = "data"
        )["ISBN:$isbn"] ?: return null
        val cover = book.cover
        return IsbnLookupResult(
            title = book.title.orEmpty(),
            author = book.authors?.mapNotNull { it.name }?.joinToString(", ").orEmpty(),
            publisher = book.publishers?.mapNotNull { it.name }?.joinToString(", ").orEmpty(),
            totalPages = book.number_of_pages ?: 0,
            coverUrl = cover?.medium?.ifBlank { null }?.let { it }
                ?: cover?.large.orEmpty().let { if (it.isBlank()) openLibraryCoverUrl(isbn) else it },
            found = true
        )
    }

    /** Open Library serves standard cover images by ISBN at a predictable URL. */
    private fun openLibraryCoverUrl(isbn: String): String =
        "https://covers.openlibrary.org/b/isbn/${isbn}-M.jpg"

    /** Google Books thumbnails are often "http://"; force https so images load over the
     * default network policy, and keep the params that make them smaller & stable. */
    private fun normalizeCover(url: String?): String = when {
        url.isNullOrBlank() -> ""
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }

    /** Looks up a Yandex Books share link and pre-fills title/author/publisher. */
    fun lookupYandex(url: String) {
        var cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        // Tolerate a bare share link (no scheme) pasted into the field.
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "https://$cleanUrl"
        }
        _lookupState.value = IsbnLookupState.LOADING
        viewModelScope.launch {
            val meta = try {
                YandexBooksParser.parse(cleanUrl)
            } catch (e: Exception) {
                android.util.Log.w("YandexLookup", "Yandex Books parse failed: ${e.javaClass.name}: ${e.message}")
                null
            }
            if (meta == null || !meta.found) {
                _lookupState.value = IsbnLookupState.NOT_FOUND
            } else {
                _lookupResult.value = IsbnLookupResult(
                    title = meta.title,
                    author = meta.author,
                    publisher = meta.publisher,
                    coverUrl = meta.coverUrl,
                    found = true
                )
                _lookupState.value = IsbnLookupState.FOUND
            }
        }
    }

    fun consumeLookupResult(): IsbnLookupResult? {
        val r = _lookupResult.value ?: return null
        _lookupResult.value = null
        _lookupState.value = IsbnLookupState.IDLE
        return r
    }

    fun load(bookId: Long) {
        if (bookId < 0) {
            _book.value = Book()
            return
        }
        viewModelScope.launch {
            repo.observeBook(bookId).collect { loaded ->
                if (loaded != null) _book.value = loaded
            }
        }
    }

    /** Whether the underlying row exists (update) or must be inserted (insert). */
    fun isNew(): Boolean = _book.value?.id ?: 0L <= 0L

    /** Permanently deletes the loaded book and its sessions. No-op for a new (unsaved) book. */
    fun delete(onDone: () -> Unit) {
        val book = _book.value ?: return
        if (book.id <= 0L) return
        viewModelScope.launch {
            repo.deleteBook(book.id)
            onDone()
        }
    }

    /** Saves the edited fields, returning the resulting book id. */
    fun save(
        title: String,
        author: String,
        publisher: String,
        isbn: String,
        totalPages: Int,
        currentPage: Int,
        format: String,
        coverUrl: String = "",
        onDone: () -> Unit
    ) {
        val current = _book.value ?: return
        if (title.isBlank()) return
        viewModelScope.launch {
            val updated = current.copy(
                title = title.trim(),
                author = author.trim(),
                publisher = publisher.trim(),
                isbn = isbn.trim(),
                totalPages = totalPages.coerceAtLeast(0),
                currentPage = currentPage.coerceIn(0, totalPages.coerceAtLeast(0)),
                readPages = if (currentPage > current.readPages) currentPage else current.readPages,
                format = format,
                coverUrl = coverUrl.ifBlank { current.coverUrl }
            )
            if (isNew()) {
                repo.addBook(updated)
            } else {
                repo.updateBook(updated)
            }
            onDone()
        }
    }
}
