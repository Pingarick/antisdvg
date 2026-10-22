package com.antisdvg.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikisourceClientTest {

    @Test
    fun `search results become catalogue rows`() {
        val json = """
            {"query":{"search":[{"title":"Мартин Иден (Лондон; Заяицкий)"},
                               {"title":"Мартин Иден (Лондон; Пименова)"},
                               {"title":"Мартин Иден (Лондон; Заяицкий)"}]}}
        """.trimIndent()

        val books = WikisourceClient.parseSearch(json)

        assertEquals(2, books.size)
        assertEquals("Мартин Иден (Лондон; Заяицкий)", books[0].title)
        assertEquals(CatalogSource.WIKISOURCE, books[0].source)
        assertEquals("рус", books[0].language)
        assertEquals("TXT", books[0].format)
        assertEquals("Мартин Иден (Лондон; Заяицкий)", books[0].pageTitle)
    }

    @Test
    fun `html becomes readable text with paragraph breaks`() {
        val html = "<div class=\"mw-parser-output\"><p>Первая строка.</p>" +
            "<p>Вторая&nbsp;строка &amp; всё.</p><script>x()</script></div>"

        val text = WikisourceClient.htmlToText(html)

        assertTrue(text.contains("Первая строка."))
        assertTrue(text.contains("Вторая строка & всё."))
        assertTrue(text.contains("\n"))
        assertTrue(!text.contains("<"))
    }

    @Test
    fun `author is read from the page header`() {
        val text = "Мартин Иден автор Джек Лондон , пер. Сергей Сергеевич Заяицкий\nОригинал: англ."

        assertEquals("Джек Лондон", WikisourceClient.authorOf(text))
    }

    @Test
    fun `pages without an author header stay empty`() {
        assertEquals("", WikisourceClient.authorOf("Просто текст без шапки"))
    }
}
