package com.antisdvg.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Real-world shapes of imported books: namespaced FB2, windows-1251 FB2 with a
 * cover, and the `.fb2.zip` container RU libraries ship.
 */
class BookImporterRealFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The exact namespace real FB2 files declare. */
    private val namespacedFb2 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
                     xmlns:l="http://www.w3.org/1999/xlink">
          <description>
            <title-info>
              <author><first-name>Лев</first-name><last-name>Толстой</last-name></author>
              <book-title>Анна Каренина</book-title>
              <coverpage><image l:href="#cover.jpg"/></coverpage>
            </title-info>
          </description>
          <body>
            <section>
              <title><p>Часть первая</p></title>
              <p>Все счастливые семьи похожи друг на друга.</p>
            </section>
          </body>
          <binary id="cover.jpg" content-type="image/jpeg">/9j/4AAQSkZJRgABAQ==</binary>
        </FictionBook>
    """.trimIndent()

    @Test
    fun namespacedFb2_parsesMetadataAndCover() {
        val book = BookText.parseFb2(ByteArrayInputStream(namespacedFb2.toByteArray(Charsets.UTF_8)))
        assertEquals("Анна Каренина", book.title)
        assertTrue(book.author.contains("Толстой"))
        assertTrue(book.html.contains("Все счастливые семьи"))
        assertTrue("Cover should be embedded as a data URI", book.coverDataUri.startsWith("data:image"))
    }

    @Test
    fun windows1251Fb2_decodesCyrillicCorrectly() {
        val xml = namespacedFb2.replaceFirst("UTF-8", "windows-1251")
        val bytes = xml.toByteArray(Charset.forName("windows-1251"))
        val book = BookText.parseFb2(ByteArrayInputStream(bytes))
        assertEquals("Анна Каренина", book.title)
        assertTrue(book.plainText.contains("счастливые"))
    }

    @Test
    fun fb2Zip_archiveIsUnpackedToFb2() {
        val archive = tmp.newFile("anna.fb2.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("anna.fb2"))
            zip.write(namespacedFb2.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val extracted = BookImporter.extractFb2(archive, tmp.root)
        assertTrue("Expected an extracted .fb2", extracted != null && extracted.extension == "fb2")
        val parsed = extracted!!.inputStream().use { BookText.parse(it, "FB2", "anna") }
        assertEquals("Анна Каренина", parsed.title)
    }

    @Test
    fun zipWithoutFb2_isRejected() {
        val archive = tmp.newFile("photos.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("photo.jpg"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        assertNull(BookImporter.extractFb2(archive, tmp.root))
    }

    @Test
    fun corruptedArchive_returnsNullInsteadOfCrashing() {
        val broken = tmp.newFile("broken.zip")
        broken.writeText("this is definitely not a zip")
        assertNull(BookImporter.extractFb2(broken, tmp.root))
    }

    @Test
    fun txtWithoutParagraphBreaks_stillProducesText() {
        val raw = "Одна длинная строка без пустых строк, которая должна стать абзацем."
        val book = BookText.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)), "TXT", "notes")
        assertTrue(book.plainText.contains("длинная строка"))
        assertTrue(book.estimatedPages >= 1)
    }

    @Test
    fun emptyFile_doesNotCrashAndReportsOnePage() {
        val blank = tmp.newFile("blank.txt")
        val book = BookText.parse(blank.inputStream(), "TXT", "blank")
        assertEquals(1, book.estimatedPages)
        assertTrue(book.html.isEmpty())
    }
}
