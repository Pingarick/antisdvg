package com.antisdvg.util

import com.antisdvg.data.model.Book
import java.io.File

/**
 * Removes book and cover files that no database row points at. Deleting a book
 * already removes its files; this sweep catches leftovers from failed imports,
 * interrupted downloads and older builds, so they cannot pile up forever.
 */
object BookStorage {

    /** Files younger than this may belong to an import that is still running. */
    private const val STALE_MS = 60 * 60 * 1000L

    /** Cache downloads older than this are never needed again. */
    private const val CACHE_STALE_MS = 24 * 60 * 60 * 1000L

    fun referencedPaths(books: List<Book>): Set<String> = buildSet {
        books.forEach { book ->
            if (book.filePath.isNotBlank()) add(File(book.filePath).absolutePath)
            if (book.coverUrl.startsWith("file://")) {
                add(File(book.coverUrl.removePrefix("file://")).absolutePath)
            }
        }
    }

    /**
     * @return number of deleted files.
     */
    fun sweep(
        booksDir: File,
        coversDir: File,
        cacheDir: File?,
        books: List<Book>,
        now: Long = System.currentTimeMillis()
    ): Int {
        val keep = referencedPaths(books)
        var deleted = 0
        deleted += sweepDir(booksDir, keep, now, STALE_MS)
        deleted += sweepDir(coversDir, keep, now, STALE_MS)
        if (cacheDir != null) deleted += sweepDir(cacheDir, emptySet(), now, CACHE_STALE_MS)
        return deleted
    }

    private fun sweepDir(dir: File, keep: Set<String>, now: Long, staleMs: Long): Int {
        val files = dir.listFiles() ?: return 0
        var deleted = 0
        files.forEach { file ->
            if (!file.isFile) return@forEach
            val old = now - file.lastModified() > staleMs
            if (file.absolutePath !in keep && old && file.delete()) deleted++
        }
        return deleted
    }
}
