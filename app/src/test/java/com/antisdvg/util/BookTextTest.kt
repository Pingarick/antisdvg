package com.antisdvg.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

class BookTextTest {

    private val fb2 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <FictionBook>
          <description>
            <title-info>
              <genre>prose</genre>
              <author><first-name>Джек</first-name><last-name>Лондон</last-name></author>
              <book-title>Мартин Иден</book-title>
            </title-info>
          </description>
          <body>
            <section>
              <title><p>Глава первая</p></title>
              <p>Море шумело всю ночь, и корабли стояли у причала.</p>
              <empty-line/>
              <p>Утром Мартин вышел на палубу и посмотрел на город.</p>
            </section>
          </body>
        </FictionBook>
    """.trimIndent()

    @Test
    fun fb2_extractsMetadataAndHtml() {
        val book = BookText.parseFb2(ByteArrayInputStream(fb2.toByteArray(Charsets.UTF_8)))
        assertEquals("Мартин Иден", book.title)
        assertTrue(book.author.contains("Лондон"))
        assertTrue(book.html.contains("<h2>"))
        assertTrue(book.html.contains("<p>"))
        assertTrue(book.plainText.contains("Море шумело"))
        assertTrue(book.estimatedPages >= 1)
    }

    @Test
    fun txt_readsUtf8AndSplitsParagraphs() {
        val raw = "Первый абзац текста.\n\nВторой абзац текста."
        val book = BookText.parseTxt(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)), "Проба")
        assertEquals("Проба", book.title)
        assertTrue(book.plainText.contains("Первый абзац"))
        assertTrue(book.html.contains("<p>Второй абзац текста.</p>"))
    }

    @Test
    fun txt_fallsBackToWindows1251() {
        val raw = "Привет мир, как дела сегодня".toByteArray(Charset.forName("windows-1251"))
        val book = BookText.parseTxt(ByteArrayInputStream(raw), "Закодированная")
        assertTrue(book.plainText.contains("Привет мир"))
    }

    @Test
    fun parse_dispatchesByKind() {
        val book = BookText.parse(ByteArrayInputStream(fb2.toByteArray(Charsets.UTF_8)), "FB2", "x")
        assertEquals("Мартин Иден", book.title)
    }

    @Test
    fun txt_headingsBecomeChapters() {
        val txt = "Глава первая\n\nОн вышел из дома и пошёл по улице, размышляя о своём будущем.\n\n" +
            "Глава вторая\n\nУтро было холодным, и город казался серым и пустым, как всегда осенью."

        val book = BookText.parseTxt(ByteArrayInputStream(txt.toByteArray(Charsets.UTF_8)), "Тест")

        assertTrue(book.html.contains("<h2>Глава первая</h2>"))
        assertTrue(book.html.contains("<h2>Глава вторая</h2>"))
        assertTrue(book.html.contains("<p>Он вышел из дома"))
    }

    @Test
    fun txt_onlyObviousLinesLookLikeHeadings() {
        assertTrue(BookText.looksLikeHeading("Глава 3"))
        assertTrue(BookText.looksLikeHeading("ЧАСТЬ ВТОРАЯ"))
        assertTrue(BookText.looksLikeHeading("Пролог"))
        assertTrue(BookText.looksLikeHeading("12"))
        // Sentences are not headings, however short they are.
        assertFalse(BookText.looksLikeHeading("Он ушёл домой."))
        assertFalse(BookText.looksLikeHeading("И это было только начало, о котором он ещё не знал"))
        // A long paragraph is a paragraph.
        assertFalse(
            BookText.looksLikeHeading(
                "Мартин Иден был простым моряком и зарабатывал тяжёлым трудом на кораблях торгового флота"
            )
        )
    }
}
