package com.antisdvg.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogClientTest {

    private val json = """
        {
          "count": 2,
          "results": [
            {
              "id": 1342,
              "title": "Pride and Prejudice",
              "authors": [{"name": "Austen, Jane"}],
              "languages": ["en"],
              "formats": {
                "application/epub+zip": "https://example.org/1342.epub",
                "text/plain; charset=utf-8": "https://example.org/1342.txt"
              }
            },
            {
              "id": 2554,
              "title": "  Crime and Punishment  ",
              "authors": [],
              "languages": ["ru"],
              "formats": {
                "text/plain; charset=utf-8": "https://example.org/2554.txt"
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses books and prefers epub`() {
        val books = CatalogClient.parse(json)

        assertEquals(2, books.size)
        val first = books[0]
        assertEquals("g1342", first.id)
        assertEquals(CatalogSource.GUTENBERG, first.source)
        assertEquals("Pride and Prejudice", first.title)
        assertEquals("Jane Austen", first.author)
        assertEquals("англ", first.language)
        assertEquals("EPUB", first.format)
        assertEquals("https://example.org/1342.epub", first.downloadUrl)
        assertTrue(first.fileName.endsWith(".epub"))
    }

    @Test
    fun `falls back to plain text and trims titles`() {
        val second = CatalogClient.parse(json)[1]

        assertEquals("Crime and Punishment", second.title)
        assertEquals("", second.author)
        assertEquals("рус", second.language)
        assertEquals("TXT", second.format)
        assertEquals("https://example.org/2554.txt", second.downloadUrl)
        assertTrue(second.fileName.endsWith(".txt"))
    }

    @Test
    fun `a book without any file is not offered`() {
        val empty = CatalogClient.parse("""{"results":[{"id":1,"title":"X","formats":{}}]}""")
        assertEquals(0, empty.size)
    }

    @Test
    fun `duplicate editions collapse to one, epub wins`() {
        val books = CatalogClient.parse(
            """
            {"results":[
              {"id":1,"title":"Hamlet","authors":[{"name":"Shakespeare, William"}],
               "languages":["en"],"formats":{"text/plain; charset=utf-8":"https://x/1.txt"}},
              {"id":2,"title":"Hamlet","authors":[{"name":"Shakespeare, William"}],
               "languages":["en"],"formats":{"application/epub+zip":"https://x/2.epub"}}
            ]}
            """.trimIndent()
        )
        assertEquals(1, books.size)
        assertEquals("https://x/2.epub", books[0].downloadUrl)
    }
}
