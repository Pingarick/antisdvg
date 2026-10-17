package com.antisdvg.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB is the format the reader gets from the open internet, so it is tested
 * against a real zip rather than a mock: container → OPF → spine → XHTML, with
 * an entity-happy chapter, a chapter without a heading, and a malformed one.
 */
class EpubBookTest {

    private val nav = """
        <?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        <body>
          <nav epub:type="toc"><ol>
            <li><a href="ch1.xhtml">Первая глава</a></li>
            <li><a href="ch2.xhtml">Вторая глава</a></li>
          </ol></nav>
        </body></html>
    """.trimIndent()

    private val opf = """
        <?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>Тестовая книга</dc:title>
            <dc:creator>Иван Петров</dc:creator>
            <meta name="cover" content="pic"/>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
            <item id="ch3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
            <item id="pic" href="images/pic.png" media-type="image/png"/>
          </manifest>
          <spine>
            <itemref idref="ch1"/>
            <itemref idref="ch2"/>
            <itemref idref="ch3"/>
          </spine>
        </package>
    """.trimIndent()

    /** First chapter: DOCTYPE, a declared entity, a heading and a picture. */
    private val chapterOne = """
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>гл. 1</title>
        <style>p { color: red; }</style></head>
        <body>
          <h1>Глава первая</h1>
          <p>Мартин Иден был простым моряком&nbsp;и зарабатывал тяжёлым трудом.</p>
          <p>Каждую свободную минуту он проводил за книгами в городской библиотеке.</p>
          <div><img src="images/pic.png" alt=""/></div>
        </body></html>
    """.trimIndent()

    /** Second chapter: no heading of its own, so the nav label must stand in. */
    private val chapterTwo = """
        <html xmlns="http://www.w3.org/1999/xhtml"><body>
          <p>Руфь смотрела на него с удивлением и скрытым восхищением.</p>
        </body></html>
    """.trimIndent()

    /** Third chapter: broken markup, which must still yield its words. */
    private val chapterBroken = """
        <html><body><p>Он писал рассказы ночами <b>и переписывал страницы
    """.trimIndent()

    private fun buildEpub(withCover: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            fun putText(name: String, text: String) = put(name, text.toByteArray(Charsets.UTF_8))

            putText("mimetype", "application/epub+zip")
            putText(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                   <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                     <rootfiles>
                       <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                     </rootfiles>
                   </container>"""
            )
            putText("OEBPS/content.opf", if (withCover) opf else opf.replace("<meta name=\"cover\" content=\"pic\"/>", ""))
            putText("OEBPS/nav.xhtml", nav)
            putText("OEBPS/ch1.xhtml", chapterOne)
            putText("OEBPS/ch2.xhtml", chapterTwo)
            putText("OEBPS/ch3.xhtml", chapterBroken)
            put("OEBPS/images/pic.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4))
        }
        return out.toByteArray()
    }

    @Test
    fun `reads metadata, chapters and text`() {
        val book = EpubBook.parse(buildEpub(), "Файл")

        assertEquals("Тестовая книга", book.title)
        assertEquals("Иван Петров", book.author)
        // The heading the chapter brought itself.
        assertTrue(book.html.contains("<h2>Глава первая</h2>"))
        // The chapter that had none takes the book's own nav label.
        assertTrue(book.html.contains("<h2>Вторая глава</h2>"))
        assertTrue(book.plainText.contains("Мартин Иден был простым моряком и зарабатывал"))
        assertTrue(book.plainText.contains("Руфь смотрела на него с удивлением"))
        assertTrue(book.estimatedPages >= 1)
    }

    @Test
    fun `declared entities and styles do not leak into the text`() {
        val book = EpubBook.parse(buildEpub(), "Файл")

        assertTrue(book.plainText.contains("моряком и зарабатывал"))
        assertFalse(book.html.contains("&nbsp;"))
        assertFalse(book.html.contains("color: red"))
    }

    @Test
    fun `a picture becomes an inline data uri and the book cover`() {
        val book = EpubBook.parse(buildEpub(), "Файл")

        assertTrue(book.html.contains("<img src=\"data:image/png;base64,"))
        assertTrue(book.coverDataUri.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `a book without a cover still parses`() {
        val book = EpubBook.parse(buildEpub(withCover = false), "Файл")

        assertEquals("Тестовая книга", book.title)
        assertEquals("", book.coverDataUri)
    }

    @Test
    fun `broken xhtml still yields its words`() {
        val book = EpubBook.parse(buildEpub(), "Файл")

        assertTrue(book.plainText.contains("Он писал рассказы ночами"))
    }

    @Test
    fun `a file that is not an epub fails loudly instead of silently empty`() {
        assertNull(runCatching { EpubBook.parse("не epub".toByteArray(), "Файл") }.getOrNull())
    }

    @Test
    fun `href resolution walks inside the archive`() {
        assertEquals("oebps/ch1.xhtml", EpubBook.resolve("oebps", "ch1.xhtml"))
        assertEquals("oebps/ch1.xhtml", EpubBook.resolve("oebps", "ch1.xhtml#part2"))
        assertEquals("oebps/img/pic.png", EpubBook.resolve("oebps/text", "../img/pic.png"))
        assertEquals("pic.png", EpubBook.resolve("oebps", "/pic.png"))
        assertNull(EpubBook.resolve("oebps", "https://example.com/pic.png"))
    }
}
