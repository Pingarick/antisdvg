package com.antisdvg.util

import com.antisdvg.data.model.Book
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookStorageTest {

    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    @Test
    fun `orphaned book and cover files are deleted`() {
        val root = Files.createTempDirectory("book-storage").toFile()
        val booksDir = File(root, "books").apply { mkdirs() }
        val coversDir = File(root, "covers").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val now = System.currentTimeMillis()

        val referencedBook = file(booksDir, "book.fb2", now - 2 * hour)
        val orphanBook = file(booksDir, "orphan.fb2", now - 2 * hour)
        val referencedCover = file(coversDir, "cover.img", now - 2 * hour)
        val orphanCover = file(coversDir, "orphan.img", now - 2 * hour)
        val oldCache = file(cacheDir, "1984.fb2", now - 2 * day)
        val freshCache = file(cacheDir, "recent.fb2", now)

        val books = listOf(
            Book(
                title = "1984",
                filePath = referencedBook.absolutePath,
                coverUrl = "file://${referencedCover.absolutePath}"
            )
        )

        val deleted = BookStorage.sweep(booksDir, coversDir, cacheDir, books, now)

        assertEquals(3, deleted)
        assertTrue(referencedBook.exists())
        assertTrue(referencedCover.exists())
        assertTrue(freshCache.exists())
        assertFalse(orphanBook.exists())
        assertFalse(orphanCover.exists())
        assertFalse(oldCache.exists())
    }

    @Test
    fun `fresh files survive a sweep because an import may still be running`() {
        val root = Files.createTempDirectory("book-storage").toFile()
        val booksDir = File(root, "books").apply { mkdirs() }
        val coversDir = File(root, "covers").apply { mkdirs() }
        val now = System.currentTimeMillis()

        val temp = file(booksDir, "importing.tmp", now - 5 * 60 * 1000L)

        val deleted = BookStorage.sweep(booksDir, coversDir, null, emptyList(), now)

        assertEquals(0, deleted)
        assertTrue(temp.exists())
    }

    private fun file(dir: File, name: String, modified: Long): File =
        File(dir, name).apply {
            writeText("x")
            setLastModified(modified)
        }
}
