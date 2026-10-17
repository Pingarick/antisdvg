package com.antisdvg.util

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Converts imported e-book files (FB2, TXT) into renderable HTML plus a plain-text
 * mirror used for page estimation and the offline cloze quiz.
 *
 * FB2 is XML, so a plain DOM parse is enough; TXT may come in UTF-8 or the legacy
 * windows-1251 encoding popular in RU libraries — we detect by strict-decoding.
 */
object BookText {

    /** Publisher norm: ~1800 chars per printed page. */
    const val CHARS_PER_PAGE = 1800

    data class ParsedBook(
        val title: String,
        val author: String,
        /** data: URI of the embedded cover, empty when the file has none. */
        val coverDataUri: String,
        val plainText: String,
        val html: String,
        val estimatedPages: Int
    )

    fun parse(input: InputStream, kind: String, fallbackTitle: String): ParsedBook =
        when (kind.uppercase()) {
            "FB2" -> parseFb2(input)
            else -> parseTxt(input, fallbackTitle)
        }

    fun parseFb2(input: InputStream): ParsedBook {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val doc = factory.newDocumentBuilder().parse(input)
        doc.documentElement.normalize()
        val root = doc.documentElement

        val titleInfo = root.firstTag("description")?.firstTag("title-info")
        val title = titleInfo?.firstTag("book-title")?.textContent.orEmpty().trim()
        val author = titleInfo?.getTags("author")?.joinToString(", ") { a ->
            listOf("first-name", "middle-name", "last-name")
                .mapNotNull { a.firstTag(it)?.textContent?.trim()?.takeIf(String::isNotEmpty) }
                .joinToString(" ")
        }.orEmpty()

        var coverUri = ""
        val coverHref = titleInfo?.firstTag("coverpage")
            ?.firstTag("image")?.hrefAttr()?.removePrefix("#")
        if (!coverHref.isNullOrBlank()) {
            val binary = root.getTags("binary").firstOrNull { it.getAttribute("id") == coverHref }
            if (binary != null) {
                val type = binary.getAttribute("content-type").ifBlank { "image/jpeg" }
                coverUri = "data:$type;base64," + binary.textContent.replace(Regex("\\s"), "")
            }
        }

        val html = StringBuilder()
        val text = StringBuilder()
        root.getTags("body").forEach { walk(it, html, text) }
        return finish(title.ifBlank { "Без названия" }, author, coverUri, html, text)
    }

    fun parseTxt(input: InputStream, fallbackTitle: String): ParsedBook {
        val bytes = input.readBytes()
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val raw = try {
            strict.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, Charset.forName("windows-1251"))
        }.removePrefix("\uFEFF")
        val paragraphs = raw.split(Regex("\\r?\\n[ \\t]*\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val html = StringBuilder()
        val text = StringBuilder()
        paragraphs.forEach { p ->
            // Plain text has no markup, so headings are guessed: otherwise an
            // imported .txt has no table of contents at all, just paragraphs.
            val tag = if (looksLikeHeading(p)) "h2" else "p"
            html.append("<").append(tag).append(">")
                .append(esc(p.replace(Regex("\\r?\\n"), "<br>")))
                .append("</").append(tag).append(">")
            text.append(p.replace(Regex("\\r?\\n"), " ")).append("\n\n")
        }
        return finish(fallbackTitle, "", "", html, text)
    }

    /**
     * Does this paragraph read like a chapter title?
     *
     * Deliberately conservative — a wrong [h2] in the middle of a page is worse
     * than a missing one in the table of contents: a paragraph has to be a single
     * short line, with no sentence punctuation, and either name itself a chapter
     * ("Глава 3", "Part II", "Пролог") or be written in caps.
     */
    internal fun looksLikeHeading(paragraph: String): Boolean {
        val line = paragraph.trim()
        if (line.isEmpty() || line.length > 60 || line.contains('\n')) return false
        // A sentence ends with punctuation; a title usually does not.
        if (line.last() in ".,;:!?…)") return false
        if (line.split(HEADING_SPLIT).firstOrNull()?.lowercase() in HEADING_WORDS) return true
        // Bare chapter numbers ("12") are common in plain-text books.
        if (line.length <= 3 && line.all { it.isDigit() }) return true
        val letters = line.filter { it.isLetter() }
        return letters.length >= 3 && letters.all { it.isUpperCase() }
    }

    private val HEADING_SPLIT = Regex("[\\s]+")
    private val HEADING_WORDS = setOf(
        "глава", "часть", "книга", "том", "пролог", "эпилог", "вступление",
        "chapter", "part", "book", "prologue", "epilogue"
    )

    private fun finish(
        title: String,
        author: String,
        coverUri: String,
        html: StringBuilder,
        text: StringBuilder
    ): ParsedBook {
        val plain = text.toString().trim()
        val pages = maxOf(1, Math.round(plain.length.toDouble() / CHARS_PER_PAGE).toInt())
        return ParsedBook(title, author, coverUri, plain, html.toString(), pages)
    }

    /** Recursively renders FB2 body elements into simple semantic HTML. */
    private fun walk(el: Element, html: StringBuilder, text: StringBuilder) {
        for (i in 0 until el.childNodes.length) {
            val node = el.childNodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val child = node as Element
            when (child.tagName) {
                "title" -> block(child, "h2", html, text)
                "subtitle" -> block(child, "h3", html, text)
                "p" -> block(child, "p", html, text)
                "empty-line" -> html.append("<div class=\"gap\"></div>")
                "cite", "epigraph", "section", "poem", "stanza", "v", "text" ->
                    walk(child, html, text)
                else -> walk(child, html, text)
            }
        }
    }

    private fun block(el: Element, tag: String, html: StringBuilder, text: StringBuilder) {
        val value = el.textContent.trim()
        if (value.isEmpty()) return
        html.append('<').append(tag).append('>').append(esc(value))
            .append("</").append(tag).append('>')
        text.append(value).append("\n\n")
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * FB2 refers to embedded images with a namespaced attribute (`xlink:href`,
     * or `l:href` in the wild). With namespace processing disabled the qualified
     * name is kept literal, so match on the local name instead of "href".
     */
    private fun Element.hrefAttr(): String {
        for (i in 0 until attributes.length) {
            val name = attributes.item(i).nodeName
            if (name == "href" || name.endsWith(":href")) {
                return attributes.item(i).nodeValue.orEmpty()
            }
        }
        return ""
    }

    private fun Element.firstTag(name: String): Element? {
        val list = getElementsByTagName(name)
        for (i in 0 until list.length) {
            val n = list.item(i)
            if (n is Element && isDescendant(n, this)) return n
        }
        return null
    }

    private fun Element.getTags(name: String): List<Element> {
        val list = getElementsByTagName(name)
        val out = mutableListOf<Element>()
        for (i in 0 until list.length) {
            val n = list.item(i)
            if (n is Element && isDescendant(n, this)) out.add(n)
        }
        return out
    }

    private fun isDescendant(node: Node, ancestor: Node): Boolean {
        var p: Node? = node.parentNode
        while (p != null) {
            if (p == ancestor) return true
            p = p.parentNode
        }
        return false
    }
}
