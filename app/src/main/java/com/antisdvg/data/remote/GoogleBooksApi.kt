package com.antisdvg.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Google Books API — used to look up a book by ISBN so the app knows the exact
 * edition (title, author, publisher, page count) DeepSeek should verify against.
 */
interface GoogleBooksApi {

    @GET("volumes")
    suspend fun search(
        @Query("q") query: String,
        @Query("maxResults") maxResults: Int = 1
    ): GoogleBooksResponse
}

data class GoogleBooksResponse(
    val items: List<GoogleBookItem>? = null
)

data class GoogleBookItem(
    val volumeInfo: GoogleBookVolumeInfo? = null
)

data class GoogleBookVolumeInfo(
    val title: String? = null,
    val authors: List<String>? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val pageCount: Int? = null,
    val imageLinks: GoogleBookImageLinks? = null
)

/** Author-supplied cover thumbnails from Google Books (may be absent for some titles). */
data class GoogleBookImageLinks(
    val thumbnail: String? = null
)

/**
 * Open Library — a free, keyless fallback to Google Books. Works from regions
 * where Google Books rate-limits (HTTP 429) or the book is missing. Returns
 * title/authors/publishers/page count for an ISBN. Compact JSON via jscmd=data.
 */
interface OpenLibraryApi {

    @GET("api/books")
    suspend fun byIsbn(
        @Query("bibkeys") bibkeys: String,
        @Query("format", encoded = true) format: String,
        @Query("jscmd", encoded = true) jscmd: String
    ): Map<String, OpenLibraryBook>
}

data class OpenLibraryBook(
    val title: String? = null,
    // jscmd=data returns "authors":[{name}] and "publishers":[{name}]
    val authors: List<OpenLibraryName>? = null,
    val publishers: List<OpenLibraryName>? = null,
    val number_of_pages: Int? = null,
    val publish_date: String? = null,
    val cover: OpenLibraryCover? = null
)

data class OpenLibraryCover(
    val medium: String? = null,
    val large: String? = null
)

data class OpenLibraryName(
    val name: String? = null
)
