package com.antisdvg.data.remote

import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** One book offered by the free catalogue. */
data class CatalogBook(
    val id: Int,
    val title: String,
    val author: String,
    val language: String,
    val epubUrl: String?,
    val textUrl: String?
) {
    val downloadUrl: String? get() = epubUrl ?: textUrl

    /** Name used by the importer to detect the format; the URL itself is not enough. */
    val fileName: String
        get() = title.take(80).ifBlank { "book" } + if (epubUrl != null) ".epub" else ".txt"
}

/**
 * Project Gutenberg catalogue through Gutendex (free, no key).
 * Only public-domain books are served, which is the whole point: the app can
 * hand them out without a licence.
 */
object CatalogClient {

    private const val BASE = "https://gutendex.com/books"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /** Throws on network or protocol failure; the caller turns that into a message. */
    fun search(query: String): List<CatalogBook> {
        val url = BASE.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("search", query)
            ?.build() ?: error("bad catalogue url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AntiSDVG/1.0 (personal reader)")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string() ?: error("empty body")
            return parse(body)
        }
    }

    /** Downloads a book file; false when the response is not a successful body. */
    fun download(url: String, target: File): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AntiSDVG/1.0 (personal reader)")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
            return target.length() > 0
        }
    }

    internal fun parse(json: String): List<CatalogBook> {
        val page = gson.fromJson(json, PageDto::class.java) ?: return emptyList()
        return page.results.mapNotNull { dto ->
            val title = dto.title.trim()
            if (title.isBlank()) return@mapNotNull null
            CatalogBook(
                id = dto.id,
                title = title,
                author = dto.authors.firstOrNull()?.name?.let(::authorName).orEmpty(),
                language = languageName(dto.languages.firstOrNull()),
                epubUrl = dto.formats["application/epub+zip"],
                textUrl = dto.formats.entries
                    .firstOrNull { it.key.startsWith("text/plain") }?.value
            )
        }
            // Gutenberg carries several files per title; keep one, and prefer EPUB.
            .sortedByDescending { it.epubUrl != null }
            .distinctBy { it.title.lowercase() + "|" + it.author.lowercase() }
    }

    /** Gutenberg writes "Austen, Jane"; people read "Jane Austen". */
    private fun authorName(raw: String): String {
        val parts = raw.split(",", limit = 2).map { it.trim() }
        return if (parts.size == 2 && parts[1].isNotBlank()) "${parts[1]} ${parts[0]}" else raw.trim()
    }

    private fun languageName(code: String?): String = when (code) {
        "ru" -> "рус"
        "en" -> "англ"
        "fr" -> "фр"
        "de" -> "нем"
        "es" -> "исп"
        "it" -> "ит"
        else -> code.orEmpty()
    }

    private data class PageDto(val results: List<BookDto> = emptyList())

    private data class BookDto(
        val id: Int = 0,
        val title: String = "",
        val authors: List<AuthorDto> = emptyList(),
        val languages: List<String> = emptyList(),
        val formats: Map<String, String> = emptyMap()
    )

    private data class AuthorDto(val name: String = "")
}
