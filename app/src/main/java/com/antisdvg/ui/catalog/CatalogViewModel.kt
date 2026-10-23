package com.antisdvg.ui.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.antisdvg.data.local.AppDatabase
import com.antisdvg.data.model.Book
import com.antisdvg.data.model.BookFormat
import com.antisdvg.data.model.BookStatus
import com.antisdvg.data.remote.CatalogBook
import com.antisdvg.data.remote.CatalogClient
import com.antisdvg.data.remote.CatalogSource
import com.antisdvg.data.remote.FlibustaClient
import com.antisdvg.data.remote.WikisourceClient
import com.antisdvg.data.repository.DataRepository
import com.antisdvg.util.BookImporter
import com.antisdvg.util.PreferencesManager
import kotlinx.coroutines.CancellationException
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

class CatalogViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repo = DataRepository(db.appDao(), PreferencesManager(application))

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        FlibustaClient.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(loading = false, error = false, items = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(450)
            performSearch()
        }
    }

    fun setSource(source: CatalogSource) {
        _state.update { it.copy(source = source) }
        if (_state.value.query.isNotBlank()) {
            search()
        }
    }

    fun search() {
        searchJob?.cancel()
        FlibustaClient.cancel()
        searchJob = viewModelScope.launch { performSearch() }
    }

    private suspend fun performSearch() {
        val query = _state.value.query.trim()
        if (query.isBlank()) return
        val source = _state.value.source

        _state.update { it.copy(loading = true, error = false, items = emptyList()) }
        val result = withContext(Dispatchers.IO) {
            try {
                Result.success(searchWithRetry(source, query))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        result.onSuccess { books ->
            _state.update { it.copy(loading = false, error = false, items = books.map { Item(book = it) }) }
        }.onFailure {
            _state.update { it.copy(loading = false, error = true) }
        }
    }

    /** Flibusta regularly answers in 20-40 s; one quick retry saves the query. */
    private suspend fun searchWithRetry(source: CatalogSource, query: String): List<CatalogBook> {
        var last: Exception? = null
        repeat(2) { attempt ->
            try {
                return when (source) {
                    CatalogSource.WIKISOURCE -> WikisourceClient.search(query)
                    CatalogSource.GUTENBERG -> CatalogClient.search(query)
                    CatalogSource.FLIBUSTA -> FlibustaClient.search(query)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (attempt == 0) delay(700)
            }
        }
        throw last ?: IllegalStateException("search failed")
    }

    fun download(book: CatalogBook) {
        val index = _state.value.items.indexOfFirst { it.book.id == book.id }
        if (index == -1) return
        
        val currentItem = _state.value.items[index]
        if (currentItem.state == DownloadState.DOWNLOADING || currentItem.state == DownloadState.DONE) return

        updateItemState(index, DownloadState.DOWNLOADING)

        viewModelScope.launch {
            val app = getApplication<Application>()
            val file: File? = withContext(Dispatchers.IO) {
                try {
                    when (book.source) {
                        CatalogSource.GUTENBERG -> {
                            // Unique on-disk name: two editions may share a title.
                            val target = File(app.cacheDir, "gutenberg-${book.id.hashCode()}.${book.format.lowercase()}")
                            if (CatalogClient.download(book.downloadUrl!!, target)) target else null
                        }
                        CatalogSource.WIKISOURCE -> {
                            val text = WikisourceClient.fetch(book.pageTitle) ?: return@withContext null
                            val target = File(app.cacheDir, "catalog-${book.id.hashCode()}.txt")
                            target.writeText(text.text)
                            target
                        }
                        CatalogSource.FLIBUSTA -> {
                            // Unique on-disk name: two editions may share a title.
                            FlibustaClient.downloadBook(
                                book.downloadUrl!!,
                                "flibusta-${book.id.hashCode()}.fb2",
                                app.cacheDir
                            )
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }

            if (file != null) {
                try {
                    val newBookId = withContext(Dispatchers.IO) {
                        val imported = BookImporter.import(app, file, book.fileName)
                            ?: return@withContext null
                        try {
                            repo.addBook(
                                Book(
                                    title = book.title,
                                    author = book.author.ifBlank { imported.author },
                                    coverUrl = if (imported.coverPath.isBlank()) "" else "file://${imported.coverPath}",
                                    filePath = imported.filePath,
                                    fileKind = imported.kind,
                                    format = BookFormat.ELECTRONIC,
                                    status = BookStatus.IN_PROGRESS,
                                    totalPages = imported.estimatedPages.coerceAtLeast(1)
                                )
                            )
                        } catch (e: Exception) {
                            // The row never appeared, so its files are garbage.
                            File(imported.filePath).delete()
                            if (imported.coverPath.isNotBlank()) File(imported.coverPath).delete()
                            throw e
                        }
                    }

                    if (newBookId == null) {
                        updateItemState(index, DownloadState.FAILED)
                    } else {
                        _state.update { current ->
                            val newList = current.items.toMutableList()
                            newList[index] = newList[index].copy(state = DownloadState.DONE, bookId = newBookId)
                            current.copy(items = newList)
                        }
                    }
                } catch (e: Exception) {
                    updateItemState(index, DownloadState.FAILED)
                } finally {
                    file.delete()
                }
            } else {
                updateItemState(index, DownloadState.FAILED)
            }
        }
    }

    private fun updateItemState(index: Int, downloadState: DownloadState) {
        _state.update { current ->
            val newList = current.items.toMutableList()
            if (index in newList.indices) {
                newList[index] = newList[index].copy(state = downloadState)
            }
            current.copy(items = newList)
        }
    }

    data class UiState(
        val query: String = "",
        val source: CatalogSource = CatalogSource.WIKISOURCE,
        val loading: Boolean = false,
        val error: Boolean = false,
        val items: List<Item> = emptyList()
    )

    data class Item(
        val book: CatalogBook,
        val state: DownloadState = DownloadState.IDLE,
        val bookId: Long = 0L
    )

    enum class DownloadState { IDLE, DOWNLOADING, DONE, FAILED }
}