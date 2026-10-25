package com.antisdvg.ui.catalog

import com.antisdvg.data.remote.CatalogBook
import com.antisdvg.data.remote.CatalogSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchCacheTest {

    private var clock = 0L
    private val cache = SearchCache(now = { clock })

    private fun book(id: String) =
        CatalogBook(id, CatalogSource.FLIBUSTA, "T", "A", "рус", "FB2", "https://x/$id")

    @Test
    fun `repeated query comes from memory`() {
        cache.put(CatalogSource.FLIBUSTA, "1984", listOf(book("1")))

        assertEquals(listOf(book("1")), cache.get(CatalogSource.FLIBUSTA, "1984"))
    }

    @Test
    fun `key ignores case and spaces`() {
        cache.put(CatalogSource.FLIBUSTA, "Крёстный отец", listOf(book("1")))

        assertEquals(listOf(book("1")), cache.get(CatalogSource.FLIBUSTA, "крёстный отец "))
    }

    @Test
    fun `sources do not share entries`() {
        cache.put(CatalogSource.FLIBUSTA, "1984", listOf(book("1")))

        assertNull(cache.get(CatalogSource.GUTENBERG, "1984"))
    }

    @Test
    fun `entries expire after ten minutes`() {
        cache.put(CatalogSource.FLIBUSTA, "1984", listOf(book("1")))
        clock += SearchCache.TTL_MS + 1

        assertNull(cache.get(CatalogSource.FLIBUSTA, "1984"))
    }

    @Test
    fun `oldest entries leave when the cache is full`() {
        repeat(SearchCache.MAX_ENTRIES + 1) { i ->
            cache.put(CatalogSource.FLIBUSTA, "q$i", listOf(book("$i")))
        }

        assertNull(cache.get(CatalogSource.FLIBUSTA, "q0"))
        assertEquals(listOf(book("20")), cache.get(CatalogSource.FLIBUSTA, "q20"))
    }
}
