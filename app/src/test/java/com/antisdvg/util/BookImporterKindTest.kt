package com.antisdvg.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
