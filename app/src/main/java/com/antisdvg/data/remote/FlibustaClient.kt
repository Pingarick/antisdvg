package com.antisdvg.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object FlibustaClient {
    // flub.flibusta.is отвечает по DNS, но соединение висит: рабочий хост — flibusta.is
    private const val BASE_URL = "https://flibusta.is"

    // Флибуста ищет по SQLite, а SQLite сравнивает кириллицу с учётом
    // регистра: «крёстный» и «Крёстный» дают разные книги. Поэтому запрос
    // уходит в обоих регистрах, а выдача объединяется.
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val activeCalls: MutableSet<Call> = Collections.synchronizedSet(mutableSetOf())

    /** Aborts whatever is in flight; a new query must not wait for the old one. */
    fun cancel() {
        val calls = synchronized(activeCalls) { activeCalls.toList() }
        calls.forEach { it.cancel() }
        activeCalls.clear()
    }

    suspend fun search(query: String): List<CatalogBook> = coroutineScope {
        val variants = searchVariants(query)
        if (variants.isEmpty()) return@coroutineScope emptyList()

        val attempts = variants
            .map { variant -> async(Dispatchers.IO) { runCatching { searchOnce(variant) } } }
            .awaitAll()

        if (attempts.all { it.isFailure }) {
            throw attempts.first().exceptionOrNull() ?: IllegalStateException("Flibusta unavailable")
        }

        return@coroutineScope mergeResults(attempts.mapNotNull { it.getOrNull() })
    }

    /**
     * Merges the answers of the case variants into one list: the same book
     * found through "крёстный" and "Крёстный" appears once, different editions
     * (different file links) all stay.
     */
    internal fun mergeResults(results: List<List<CatalogBook>>): List<CatalogBook> {
        val seen = HashSet<String>()
        return results.flatten().filter { seen.add(it.downloadUrl ?: it.id) }
    }

    internal fun searchVariants(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val lower = trimmed.lowercase()
        val capitalized = lower.replaceFirstChar { it.uppercase() }
        // Canonical order: the same input in any case produces the same list,
        // otherwise the merged result order would still depend on capitalisation.
        val variants = mutableListOf(lower, capitalized, trimmed)
        if (trimmed.contains('ё') || trimmed.contains('Ё')) {
            val noYo = lower.replace('ё', 'е')
            variants += noYo
            variants += noYo.replaceFirstChar { it.uppercase() }
        }
        return variants.distinct()
    }

    private fun searchOnce(query: String): List<CatalogBook> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/opds/search?searchTerm=$encoded&searchType=books"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AntiSDVG/1.0 (personal reader)")
            .build()

        val call = http.newCall(request)
        activeCalls.add(call)
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val xml = response.body?.string() ?: error("empty body")
                parseOpds(xml)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Не глотаем сетевую ошибку: иначе «сервис недоступен» выглядит как
            // «ничего не нашлось» и непонятно, что чинить.
            throw IllegalStateException("Flibusta unavailable", e)
        } finally {
            activeCalls.remove(call)
        }
    }

    private fun parseOpds(xml: String): List<CatalogBook> {
        val books = mutableListOf<CatalogBook>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var currentId: String? = null
            var currentTitle: String? = null
            var currentAuthor: String? = null
            var currentUrl: String? = null
            var currentLanguage: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "entry" -> {
                                currentId = null
                                currentTitle = null
                                currentAuthor = null
                                currentUrl = null
                                currentLanguage = null
                            }
                            "id" -> currentId = parser.nextText()
                            "title" -> currentTitle = parser.nextText()
                            "name" -> currentAuthor = parser.nextText()
                            "dc:language", "language" -> currentLanguage = parser.nextText()
                            "link" -> {
                                val rel = parser.getAttributeValue(null, "rel")
                                val type = parser.getAttributeValue(null, "type")
                                // Ищем ссылку на fb2. Flibusta отдаёт
                                // rel=".../acquisition/open-access", а не просто
                                // ".../acquisition", поэтому сравниваем по префиксу.
                                if (rel != null &&
                                    rel.startsWith("http://opds-spec.org/acquisition") &&
                                    type?.contains("fb2") == true
                                ) {
                                    currentUrl = parser.getAttributeValue(null, "href")
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "entry" && currentTitle != null && currentUrl != null) {
                            books.add(
                                CatalogBook(
                                    // The entry id is unique; titles repeat across editions.
                                    id = "flibusta:${currentId ?: currentTitle}",
                                    source = CatalogSource.FLIBUSTA,
                                    title = currentTitle,
                                    author = currentAuthor ?: "Неизвестен",
                                    language = languageLabel(currentLanguage),
                                    format = "FB2",
                                    downloadUrl = if (currentUrl.startsWith("http")) currentUrl else "$BASE_URL$currentUrl"
                                )
                            )
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // Если XML битый, просто вернем пустой список
        }
        return books
    }

    private fun languageLabel(code: String?): String = when (code?.trim()?.lowercase()) {
        "ru" -> "рус"
        "be" -> "бел"
        "uk" -> "укр"
        "en" -> "англ"
        "de" -> "нем"
        "fr" -> "фр"
        "pl" -> "пол"
        null, "" -> "рус"
        else -> code.trim()
    }

    /**
     * Скачивает файл и, если он в ZIP, распаковывает его.
     * Возвращает чистый File (fb2), который можно отдать в BookImporter.
     */
    fun downloadBook(downloadUrl: String, fileName: String, dir: File): File? {
        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "AntiSDVG/1.0 (personal reader)")
            .build()

        val target = File(dir, fileName)
        return try {
            dir.mkdirs()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    target.delete()
                    return null
                }
                val bytes = response.body?.bytes()
                if (bytes == null) {
                    target.delete()
                    return null
                }

                // Проверяем ZIP-сигнатуру (PK)
                if (bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                    unzipFb2(bytes, target) ?: run {
                        target.delete()
                        null
                    }
                } else {
                    // Если не ZIP, сохраняем как есть
                    target.writeBytes(bytes)
                    target
                }
            }
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    private fun unzipFb2(zipBytes: ByteArray, target: File): File? {
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".fb2", ignoreCase = true)) {
                    target.outputStream().use { out -> zis.copyTo(out) }
                    return target
                }
                entry = zis.nextEntry
            }
        }
        return null
    }
}