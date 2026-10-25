package com.antisdvg.ui.catalog

import com.antisdvg.data.remote.CatalogBook
import com.antisdvg.data.remote.CatalogSource

/**
 * Short-lived memory of catalogue answers: repeating a query must not hit a
 * slow server again. Keyed by source and the normalized query; entries live
 * [TTL_MS] and the whole cache holds at most [MAX_ENTRIES] queries.
 */
class SearchCache(
    private val now: () -> Long = System::currentTimeMillis
) {

    private data class Entry(val at: Long, val books: List<CatalogBook>)

    private val entries = LinkedHashMap<String, Entry>()

    fun get(source: CatalogSource, query: String): List<CatalogBook>? {
        val key = key(source, query)
        val entry = entries[key] ?: return null
        if (now() - entry.at > TTL_MS) {
            entries.remove(key)
            return null
        }
        return entry.books
    }

    fun put(source: CatalogSource, query: String, books: List<CatalogBook>) {
        entries[key(source, query)] = Entry(now(), books)
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
    }

    internal fun key(source: CatalogSource, query: String): String =
        source.name + "|" + query.trim().lowercase()

    companion object {
        const val TTL_MS = 10 * 60 * 1000L
        const val MAX_ENTRIES = 20
    }
}
