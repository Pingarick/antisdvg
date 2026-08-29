package com.antisdvg.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Metadata pulled from a Yandex Books book page. */
data class YandexBookMeta(
    val title: String = "",
    val author: String = "",
    val publisher: String = "",
    val coverUrl: String = "",
    val found: Boolean = false
)

/**
 * Fetches a Yandex Books book page and extracts schema.org JSON-LD metadata
 * (name / author / publisher). Yandex renders book pages server-side, so a
 * plain GET of the share link is enough — no auth, no API key.
 */
object YandexBooksParser {

    private const val CONNECT_TIMEOUT = 15L
    private const val READ_TIMEOUT = 20L

    // Yandex serves a different (JS-only) page to bare OkHttp/curl clients;
    // a real browser user-agent gets the server-rendered HTML with JSON-LD.
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Extracts [YandexBookMeta] from [url]; null when the page can't be read. */
    suspend fun parse(url: String): YandexBookMeta? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val html = response.body?.string() ?: return@withContext null
                parseHtml(html)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseHtml(html: String): YandexBookMeta? {
        // Prefer the schema.org Book JSON-LD block; fall back to <title>.
        // Either way, Yandex serves the cover as an <img>, not in JSON-LD, so
        // attach it separately so the editor can show a real cover photo.
        val cover = findCover(html)
        val fromJsonLd = findBookJsonLd(html)
        if (fromJsonLd != null) return fromJsonLd.copy(coverUrl = cover)
        return titleFallback(html)?.copy(coverUrl = cover)
    }

    /**
     * Returns the current book's cover URL. Yandex renders the cover as the
     * first cover <img> (`Cover_image__ZAscW`) whose src is a real CDN URL;
     * the eager placeholder and related-book thumbs use a data URI / different
     * classes, so grabbing the first genuine `books-covers` src works.
     */
    private fun findCover(html: String): String {
        val imgRegex = Regex("<img[^>]*class=\"Cover_image__[^\"]*\"[^>]*src=\"(https://[^\"]*books-covers/[^\"]*?)\"")
        val match = imgRegex.find(html)
        val src = match?.groupValues?.get(1) ?: ""
        // Drop the signed image_hash query — the plain CDN path serves 200.
        return src.substringBefore("?image_hash=")
    }

    /** Looks for `<script type="application/ld+json">…</script>` containing a @type Book. */
    private fun findBookJsonLd(html: String): YandexBookMeta? {
        val scriptRegex = Regex("<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>")
        for (match in scriptRegex.findAll(html)) {
            val jsonText = match.groupValues[1].trim()
            if (jsonText.isEmpty()) continue
            try {
                val json = JSONObject(jsonText)
                if (json.optString("@type") != "Book") continue
                val title = json.optString("name", "").trim()
                val author = parseNames(json, "author")
                val publisher = parseNames(json, "publisher")
                if (title.isEmpty() && author.isEmpty() && publisher.isEmpty()) continue
                return YandexBookMeta(
                    title = title,
                    author = author,
                    publisher = publisher,
                    found = true
                )
            } catch (e: Exception) {
                // Malformed JSON in an unrelated block; keep scanning.
            }
        }
        return null
    }

    /** Reads a name-like field that may be a String, an object, or an array of either. */
    private fun parseNames(json: JSONObject, field: String): String {
        val value = json.opt(field) ?: return ""
        return when (value) {
            is JSONObject -> value.optString("name", "").trim()
            is String -> value.trim()
            is org.json.JSONArray -> {
                val names = mutableListOf<String>()
                for (i in 0 until value.length()) {
                    val item = value.opt(i) ?: continue
                    val name = when (item) {
                        is JSONObject -> item.optString("name", "")
                        is String -> item
                        else -> ""
                    }.trim()
                    if (name.isNotEmpty()) names.add(name)
                }
                names.joinToString(", ")
            }
            else -> ""
        }
    }

    /** Fallback: parse the page <title> ("Book — Author — читать книгу онлайн…"). */
    private fun titleFallback(html: String): YandexBookMeta? {
        val titleMatch = Regex("<title[^>]*>([^<]*?)</title>", RegexOption.IGNORE_CASE)
            .find(html) ?: return null
        var title = titleMatch.groupValues[1].trim()
        if (title.isEmpty()) return null
        // Strip the " — читать книгу онлайн на Яндекс Книгах" suffix Yandex appends.
        title = Regex("\\s+—\\s+читать.*", RegexOption.IGNORE_CASE).replace(title, "").trim()
        return YandexBookMeta(title = title, found = true)
    }
}
