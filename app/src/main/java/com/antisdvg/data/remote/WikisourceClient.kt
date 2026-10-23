package com.antisdvg.data.remote

import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Russian Wikisource (ru.wikisource.org): public-domain Russian literature.
 * Gutenberg is English-heavy, so this is where the Russian shelf actually
 * comes from. Pages are rendered to HTML by the MediaWiki API and flattened
 * to plain text, which the TXT importer already understands.
 */
object WikisourceClient {

    private const val API = "https://ru.wikisource.org/w/api.php"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    data class WsText(val title: String, val author: String, val text: String)

    /** Throws on network failure. */
    fun search(query: String): List<CatalogBook> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        // Titles first: somebody typing a book name wants the book, not a mention
        // of it in an encyclopedia article.
        val byTitle = runCatching { prefixSearch(q) }
        val byText = runCatching { textSearch(q) }
        // Both requests failing means the service is down; one empty answer is
        // just "nothing found" and must not be reported as an outage.
        if (byTitle.isFailure && byText.isFailure) {
            throw byText.exceptionOrNull() ?: IllegalStateException("wikisource unavailable")
        }
        return (byTitle.getOrDefault(emptyList()) + byText.getOrDefault(emptyList()))
            .mapNotNull(::toBook)
            .distinctBy { it.pageTitle }
            .take(30)
    }

    private fun prefixSearch(query: String): List<String> {
        val url = api()
            .addQueryParameter("action", "query")
            .addQueryParameter("list", "prefixsearch")
            .addQueryParameter("pssearch", query)
            .addQueryParameter("pslimit", "20")
            .build()
        val dto = gson.fromJson(request(url), PrefixDto::class.java)
        return dto?.query?.prefixsearch.orEmpty().map { it.title }
    }

    private fun textSearch(query: String): List<String> {
        val url = api()
            .addQueryParameter("action", "query")
            .addQueryParameter("list", "search")
            .addQueryParameter("srsearch", query)
            .addQueryParameter("srnamespace", "0")
            .addQueryParameter("srlimit", "20")
            .build()
        val dto = gson.fromJson(request(url), SearchDto::class.java)
        return dto?.query?.search.orEmpty().map { it.title }
    }

    /**
     * Wikisource is a whole library of reference material, not only fiction:
     * encyclopedia entries, laws and "Лентапедия" biographies would otherwise
     * fill the catalogue with things nobody wants to read as a book.
     */
    private fun toBook(titleRaw: String): CatalogBook? {
        val title = titleRaw.trim()
        if (title.isBlank()) return null
        // Chapter subpages are pieces of a work, not works.
        if (title.contains('/')) return null
        if (NotLiterature.any { title.startsWith(it) }) return null
        return CatalogBook(
            id = "ws:$title",
            source = CatalogSource.WIKISOURCE,
            title = title,
            author = "",
            language = "рус",
            format = "TXT",
            pageTitle = title
        )
    }

    /** Fetches the page text; joins chapter subpages when the page is only a table of contents. */
    fun fetch(pageTitle: String): WsText? {
        val html = parseHtml(pageTitle) ?: return null
        var text = htmlToText(html)

        // A page that has subpages is usually a table of contents ("Идиот" is 50
        // chapters). Fetch them in reading order and keep whichever text is
        // longer: a genuinely complete page beats the assembled one.
        val parts = subpages(pageTitle)
        if (parts.isNotEmpty() && parts.size <= 60) {
            val assembled = parts.asSequence()
                .sortedWith(NaturalOrder)
                .mapNotNull { part -> parseHtml(part)?.let(::htmlToText) }
                .joinToString("\n\n")
            if (assembled.length > text.length) text = assembled
        }

        if (text.length < 200) return null
        return WsText(pageTitle, authorOf(text), text)
    }

    internal fun parseSearch(json: String): List<CatalogBook> {
        val dto = gson.fromJson(json, SearchDto::class.java) ?: return emptyList()
        val hits = dto.query?.search.orEmpty()
        return hits.mapNotNull { hit -> toBook(hit.title) }.distinctBy { it.pageTitle }
    }

    private fun api(): okhttp3.HttpUrl.Builder =
        API.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("formatversion", "2")
            ?: error("bad wikisource url")

    /** Pages that share the main namespace but are not literature. */
    private val NotLiterature = listOf(
        "Лентапедия/",
        "ЭСБЕ/",
        "РБС/",
        "ПБЭ/",
        "ЕЭБЕ/",
        "ЕЭБЕ ",
        "Федеральный список",
        "Указ Президента",
        "Налоговый кодекс",
        "Гражданский кодекс",
        "Уголовный кодекс",
        "Конституция",
        "Пути сообщения"
    )

    /** Wikimedia pages start with a template that renders "автор Имя Фамилия". */
    internal fun authorOf(text: String): String =
        Regex("""автор\s+([^,\n]{3,60})""")
            .find(text.take(600))
            ?.groupValues?.get(1)?.trim()
            .orEmpty()

    internal fun htmlToText(html: String): String {
        var s = html
        s = Regex("(?is)<(script|style)[^>]*>.*?</\\1>").replace(s, " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|h[1-6]|li|tr)>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), "")
        s = SystemNetHtmlDecode(s)
        s = s.replace('\u00A0', ' ')
        s = s.lines().joinToString("\n") { it.trim().replace(Regex("[ \\t]+"), " ") }
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun SystemNetHtmlDecode(s: String): String {
        // The app has no Html-free decoding helper here, and android.text.Html is
        // only available on device: the handful of entities Wikisource emits is
        // enough to cover the texts.
        return s
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private fun parseHtml(pageTitle: String): String? {
        val url = API.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("action", "parse")
            ?.addQueryParameter("page", pageTitle)
            ?.addQueryParameter("prop", "text")
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("formatversion", "2")
            ?.addQueryParameter("disableeditsection", "1")
            ?.addQueryParameter("disabletoc", "1")
            ?.build() ?: return null
        return runCatching {
            val body = request(url)
            gson.fromJson(body, ParseDto::class.java)?.parse?.text
        }.getOrNull()
    }

    private fun subpages(pageTitle: String): List<String> {
        val url = API.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("action", "query")
            ?.addQueryParameter("list", "allpages")
            ?.addQueryParameter("apprefix", "$pageTitle/")
            ?.addQueryParameter("aplimit", "100")
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("formatversion", "2")
            ?.build() ?: return emptyList()
        return runCatching {
            val body = request(url)
            val dto = gson.fromJson(body, AllPagesDto::class.java)
            dto?.query?.allpages.orEmpty().map { it.title }.sorted()
        }.getOrDefault(emptyList())
    }

    private fun request(url: okhttp3.HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AntiSDVG/1.0 (personal reader)")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string() ?: error("empty body")
        }
    }

    private data class SearchDto(val query: SearchQuery? = null)
    private data class SearchQuery(val search: List<Hit> = emptyList())
    private data class Hit(val title: String = "")

    private data class PrefixDto(val query: PrefixQuery? = null)
    private data class PrefixQuery(val prefixsearch: List<Hit> = emptyList())

    private data class ParseDto(val parse: ParseBody? = null)
    private data class ParseBody(val text: String = "")

    private data class AllPagesDto(val query: AllPagesQuery? = null)
    private data class AllPagesQuery(val allpages: List<PageRef> = emptyList())
    private data class PageRef(val title: String = "")

    /** "Глава 2" before "Глава 10", unlike plain string order. */
    private object NaturalOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val pa = a.split(Regex("(\\d+)"))
            val pb = b.split(Regex("(\\d+)"))
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val cmp = if (x.toIntOrNull() != null && y.toIntOrNull() != null) {
                    x.toInt().compareTo(y.toInt())
                } else {
                    x.compareTo(y)
                }
                if (cmp != 0) return cmp
            }
            return pa.size.compareTo(pb.size)
        }
    }
}
