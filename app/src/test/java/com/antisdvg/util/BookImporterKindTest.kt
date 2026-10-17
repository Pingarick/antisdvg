package com.antisdvg.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookImporterKindTest {

    @Test
    fun detectsFb2AndTxtByExtension() {
        assertEquals("FB2", BookImporter.detectKindName("Martin Iden.fb2", null))
        assertEquals("TXT", BookImporter.detectKindName("notes.TXT", null))
    }

    @Test
    fun detectsZipArchiveAsZip() {
        assertEquals("ZIP", BookImporter.detectKindName("book.fb2.zip", null))
        assertEquals("ZIP", BookImporter.detectKindName("collection.zip", null))
    }

    @Test
    fun fallsBackToMimeType() {
        assertEquals("FB2", BookImporter.detectKindName("book", "application/fb2+xml"))
        assertEquals("TXT", BookImporter.detectKindName("book", "text/plain"))
        assertEquals("ZIP", BookImporter.detectKindName("book", "application/zip"))
    }

    @Test
    fun rejectsUnknownDocuments() {
        assertNull(BookImporter.detectKindName("scan.pdf", "application/pdf"))
        assertNull(BookImporter.detectKindName("photo.jpg", "image/jpeg"))
    }

    @Test
    fun detectsEpubByExtensionAndMime() {
        assertEquals("EPUB", BookImporter.detectKindName("Voina i mir.epub", null))
        assertEquals("EPUB", BookImporter.detectKindName("book", "application/epub+zip"))
    }

    @Test
    fun tellsAnEpubZipFromAnFb2Zip() {
        val dir = java.nio.file.Files.createTempDirectory("books").toFile()
        try {
            val epub = File(dir, "a.zip").apply {
                writeBytes(zipOf("mimetype" to "application/epub+zip", "OEBPS/content.opf" to "<package/>"))
            }
            val fb2zip = File(dir, "b.zip").apply {
                writeBytes(zipOf("book.fb2" to "<FictionBook/>"))
            }
            val empty = File(dir, "c.zip").apply { writeBytes(zipOf("readme.txt" to "ничего")) }

            assertTrue(BookImporter.looksLikeEpub(epub))
            assertFalse(BookImporter.looksLikeEpub(fb2zip))
            assertFalse(BookImporter.looksLikeEpub(empty))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
