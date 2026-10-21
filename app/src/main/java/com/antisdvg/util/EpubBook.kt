package com.antisdvg.util

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * EPUB: a zip of XHTML documents with a manifest, a spine and a table of contents.
 *
 * The reader needs a single HTML document, so this walks the spine in order and
 * flattens each XHTML file into the few tags the reader styles (`h2`, `h3`, `p`,
 * `img`). Two things matter beyond the text:
 *
 * - **Chapter headings survive.** The reader's table of contents is built from
 *   the `h2`/`h3` in the rendered page, so a heading is taken from the file
 *   itself, or from the book's own nav/NCX when a chapter starts without one.
 * - **Images are inlined** as `data:` URIs. The reader loads the book as a data
 *   URL, which cannot reach back into the zip for a picture.
 *
 * Real-world EPUBs are often misspelled XHTML, so a failed DOM parse falls back
 * to a tag strip rather than losing the book.
 */
internal object EpubBook {

    /** Entries bigger than this are skipped: a book is not a photo album. */
    private const val MAX_ENTRY_BYTES = 12 * 1024 * 1024

    /** One inline picture; bigger ones are dropped rather than bloating the page. */
    private const val MAX_IMAGE_BYTES = 1_500_000

    /** All pictures together, so a book full of illustrations still opens. */
    private const val MAX_TOTAL_IMAGE_BYTES = 6_000_000

    private const val MAX_ENTRIES = 2000

    fun parse(bytes: ByteArray, fallbackTitle: String): BookText.ParsedBook {
        val entries = readEntries(bytes)
        val opfPath = rootfilePath(entries)
            ?: throw IllegalArgumentException("EPUB has no META-INF/container.xml")
        val opfBytes = entries[opfPath] ?: throw IllegalArgumentException("EPUB has no package document")
        val opf = parseXml(opfBytes) ?: throw IllegalArgumentException("EPUB package document is broken")
        val baseDir = opfPath.substringBeforeLast('/', "")

        val title = local(opf.documentElement, "title").firstOrNull()?.textContent?.trim().orEmpty()
        val author = local(opf.documentElement, "creator").joinToString(", ") { it.textContent.trim() }
            .trim().trim(',', ' ')

        val manifest = manifestItems(opf, baseDir)
        val labels = tocLabels(entries, opf, manifest, baseDir)
        val images = InlineImages(entries)
        val cover = coverHref(opf, manifest)

        val html = StringBuilder()
        val text = StringBuilder()
        for (href in spineHrefs(opf, manifest)) {
            val entry = entries[href] ?: continue
            val blocks = mutableListOf<Block>()
            val doc = parseXhtml(entry)
            if (doc != null) {
                collect(doc.documentElement, blocks, images, href.substringBeforeLast('/', ""), manifest)
            } else {
                collectFromRaw(decodeText(entry), blocks)
            }
            // A chapter without its own heading still deserves a line in the
            // contents: take the book's own label for it, if it has one.
            val label = labels[href]
            val startsWithHeading = blocks.firstOrNull()?.tag?.startsWith("h") == true
            if (!startsWithHeading && !label.isNullOrBlank()) {
                blocks.add(0, Block("h2", label))
            }
            for (block in blocks) block.appendTo(html, text)
        }

        return BookText.finish(
            title.ifBlank { fallbackTitle },
            author,
            cover?.let { images.dataUri(it.first, it.second) }.orEmpty(),
            html,
            text
        )
    }

    // ---- Zip, OPF, TOC ----

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            while (entry != null && count < MAX_ENTRIES) {
                if (!entry.isDirectory && entry.size <= MAX_ENTRY_BYTES) {
                    val limit = if (entry.size in 0 until MAX_ENTRY_BYTES) entry.size.toInt() else MAX_ENTRY_BYTES
                    val buffer = java.io.ByteArrayOutputStream(limit.coerceAtMost(1 shl 20))
                    val chunk = ByteArray(16 * 1024)
                    var read = zip.read(chunk)
                    while (read > 0 && buffer.size() < MAX_ENTRY_BYTES) {
                        buffer.write(chunk, 0, read)
                        read = zip.read(chunk)
                    }
                    out[norm(entry.name)] = buffer.toByteArray()
                    count++
                }
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun rootfilePath(entries: Map<String, ByteArray>): String? {
        val container = entries["meta-inf/container.xml"] ?: return null
        val doc = parseXml(container) ?: return null
        val rootfile = local(doc.documentElement, "rootfile").firstOrNull() ?: return null
        val path = rootfile.getAttribute("full-path")
        return if (path.isBlank()) null else norm(path)
    }

    private class ManifestItem(val href: String, val type: String, val properties: String)

    private fun manifestItems(opf: Document, baseDir: String): Map<String, ManifestItem> {
        val out = mutableMapOf<String, ManifestItem>()
        for (item in local(opf.documentElement, "item")) {
            val id = item.getAttribute("id")
            if (id.isBlank()) continue
            val href = resolve(baseDir, item.getAttribute("href")) ?: continue
            out[id] = ManifestItem(href, item.getAttribute("media-type"), item.getAttribute("properties"))
        }
        return out
    }

    private fun spineHrefs(opf: Document, manifest: Map<String, ManifestItem>): List<String> =
        local(opf.documentElement, "itemref")
            .mapNotNull { manifest[it.getAttribute("idref")]?.href }

    /** Cover image href and media type, from the EPUB 3 property or the EPUB 2 meta. */
    private fun coverHref(opf: Document, manifest: Map<String, ManifestItem>): Pair<String, String>? {
        manifest.values.firstOrNull { it.properties.contains("cover-image") }?.let {
            return it.href to it.type
        }
        val id = local(opf.documentElement, "meta")
            .firstOrNull { it.getAttribute("name").equals("cover", ignoreCase = true) }
            ?.getAttribute("content")
            .orEmpty()
        return manifest[id]?.let { it.href to it.type }
    }

    /**
     * Chapter titles the book itself declares: the EPUB 3 nav document when it
     * has one, otherwise the EPUB 2 NCX. Keyed by the resolved file path.
     */
    private fun tocLabels(
        entries: Map<String, ByteArray>,
        opf: Document,
        manifest: Map<String, ManifestItem>,
        baseDir: String
    ): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val nav = manifest.values.firstOrNull { it.properties.contains("nav") }
        if (nav != null) {
            val navBytes = entries[nav.href]
            val doc = if (navBytes == null) null else parseXhtml(navBytes)
            if (doc != null) {
                for (anchor in local(doc.documentElement, "a")) {
                    val target = resolve(nav.href.substringBeforeLast('/', ""), anchor.getAttribute("href"))
                    val label = anchor.textContent.trim()
                    if (target != null && label.isNotEmpty()) out.putIfAbsent(target, label)
                }
            }
        }
        if (out.isEmpty()) {
            val ncx = manifest.values.firstOrNull { it.type.contains("dtbncx") }
            val ncxBytes = if (ncx == null) null else entries[ncx.href]
            if (ncx != null && ncxBytes != null) {
                val doc = parseXml(ncxBytes)
                if (doc != null) {
                    val points = local(doc.documentElement, "navPoint")
                    for (point in points) {
                        val src = local(point, "content").firstOrNull()?.getAttribute("src").orEmpty()
                        val label = local(point, "text").firstOrNull()?.textContent?.trim().orEmpty()
                        val target = resolve(ncx.href.substringBeforeLast('/', ""), src)
                        if (target != null && label.isNotEmpty()) out.putIfAbsent(target, label)
                    }
                }
            }
        }
        return out
    }

    // ---- XHTML -> blocks ----

    private class Block(val tag: String, val text: String) {
        fun appendTo(html: StringBuilder, text: StringBuilder) {
            if (this.text.isBlank()) return
            when (tag) {
                "img" -> html.append("<p><img src=\"").append(this.text).append("\"></p>")
                "h2", "h3" -> {
                    html.append('<').append(tag).append('>').append(esc(this.text)).append("</").append(tag).append('>')
                    text.append(this.text).append("\n\n")
                }
                else -> {
                    html.append("<p>").append(esc(this.text)).append("</p>")
                    text.append(this.text).append("\n\n")
                }
            }
        }
    }

    private val CONTAINER_TAGS = setOf(
        "div", "section", "article", "body", "main", "figure", "nav", "aside",
        "header", "footer", "ul", "ol", "dl", "table", "tbody", "thead", "tr", "a", "span"
    )

    private val SKIP_TAGS = setOf("script", "style", "svg", "head", "title", "meta", "link", "nav")

    private fun collect(
        el: Element,
        out: MutableList<Block>,
        images: InlineImages,
        dir: String,
        manifest: Map<String, ManifestItem>
    ) {
        var inline = StringBuilder()
        fun flush() {
            val value = BookText.squash(inline.toString())
            if (value.isNotEmpty()) out.add(Block("p", value))
            inline = StringBuilder()
        }
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            when (node.nodeType) {
                Node.TEXT_NODE -> inline.append(node.nodeValue.orEmpty())
                Node.ELEMENT_NODE -> {
                    val child = node as Element
                    val name = tagOf(child)
                    when {
                        name in SKIP_TAGS -> Unit
                        name == "br" -> inline.append(' ')
                        name == "img" -> {
                            flush()
                            val uri = imageDataUri(child, images, dir, manifest)
                            if (uri != null) out.add(Block("img", uri))
                        }
                        name == "li" -> {
                            flush()
                            val value = inlineText(child, StringBuilder(), images, dir, manifest)
                            out.add(Block("p", "• " + BookText.squash(value.toString())))
                        }
                        name.startsWith("h") && name.length == 2 && name[1].isDigit() -> {
                            flush()
                            val level = name[1] - '0'
                            val value = inlineText(child, StringBuilder(), images, dir, manifest)
                            out.add(Block(if (level <= 3) "h2" else "h3", BookText.squash(value.toString())))
                        }
                        name in CONTAINER_TAGS -> {
                            if (child.getElementsByTagNameNS("*", "img").length > 0) {
                                flush()
                                collect(child, out, images, dir, manifest)
                            } else {
                                inlineText(child, inline, images, dir, manifest)
                            }
                        }
                        else -> {
                            flush()
                            val value = inlineText(child, StringBuilder(), images, dir, manifest)
                            out.add(Block("p", BookText.squash(value.toString())))
                        }
                    }
                }
            }
        }
        flush()
    }

    /** Text of an inline run, resolving any picture inside it on the way. */
    private fun inlineText(
        el: Element,
        out: StringBuilder,
        images: InlineImages,
        dir: String,
        manifest: Map<String, ManifestItem>
    ): StringBuilder {
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            when (node.nodeType) {
                Node.TEXT_NODE -> out.append(node.nodeValue.orEmpty())
                Node.ELEMENT_NODE -> {
                    val child = node as Element
                    val name = tagOf(child)
                    when {
                        name == "img" -> {
                            val uri = imageDataUri(child, images, dir, manifest)
                            if (uri != null && out.isNotEmpty()) out.append(' ').append(uri).append(' ')
                        }
                        name in SKIP_TAGS -> Unit
                        name == "br" -> out.append(' ')
                        else -> inlineText(child, out, images, dir, manifest)
                    }
                }
            }
        }
        return out
    }

    /**
     * Last resort for XHTML the parser refuses: strip the tags, keep the words.
     * Losing the markup is fine; losing the book is not.
     */
    private fun collectFromRaw(raw: String, out: MutableList<Block>) {
        val withoutHead = raw
            .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<body[^>]*>"), "\n")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr)>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), " ")
        withoutHead.split('\n')
            .map { BookText.squash(unescape(it)) }
            .filter { it.isNotEmpty() }
            .forEach { out.add(Block("p", it)) }
    }

    // ---- Images ----

    private class InlineImages(private val entries: Map<String, ByteArray>) {
        private var spent = 0

        /** Turns an image href into a `data:` URI, or null when it is not worth it. */
        fun dataUri(href: String, mimeType: String): String? {
            val bytes = entries[href] ?: return null
            if (bytes.size > MAX_IMAGE_BYTES || spent + bytes.size > MAX_TOTAL_IMAGE_BYTES) return null
            spent += bytes.size
            // The media type comes from the book's own manifest: never interpolate it
            // raw into the reader's HTML (it lands inside a src attribute of a WebView
            // with a JS bridge). Only a plain image/* token is allowed through.
            val type = mimeType
                .takeIf { it.matches(Regex("image/[a-z0-9.+-]{1,30}")) }
                ?: mimeFor(href)
            return "data:$type;base64," + Base64Lite.encode(bytes)
        }
    }

    private fun imageDataUri(
        el: Element,
        images: InlineImages,
        dir: String,
        manifest: Map<String, ManifestItem>
    ): String? {
        val src = srcAttr(el) ?: return null
        val href = resolve(dir, src) ?: return null
        val type = manifest.values.firstOrNull { it.href == href }?.type.orEmpty()
        return images.dataUri(href, type)
    }

    private fun mimeFor(href: String): String = when (href.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun srcAttr(el: Element): String? {
        for (i in 0 until el.attributes.length) {
            val name = el.attributes.item(i).nodeName
            if (name.equals("src", ignoreCase = true) || name.endsWith(":src")) {
                return el.attributes.item(i).nodeValue
            }
        }
        return null
    }

    // ---- Paths, XML, text ----

    /** Resolves a relative href against a directory inside the zip, normalized. */
    internal fun resolve(dir: String, href: String): String? {
        val clean = href.substringBefore('#').trim()
        if (clean.isEmpty() || clean.startsWith("http:") || clean.startsWith("https:")) return null
        val parts = (if (clean.startsWith('/')) emptyList() else dir.split('/').filter { it.isNotEmpty() })
            .toMutableList()
        for (part in clean.trimStart('/').split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(part)
            }
        }
        return norm(parts.joinToString("/"))
    }

    private fun norm(path: String): String = path.replace('\\', '/').trimStart('/').lowercase()

    internal fun parseXml(bytes: ByteArray): Document? =
        runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isExpandEntityReferences = true
            // Not every parser knows this switch; not having it is not fatal.
            runCatching {
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }.getOrNull()

    /**
     * XHTML as a DOM. Declared entities (`&nbsp;` and friends) are replaced and
     * the DOCTYPE dropped first: the DTD is not in the zip, and a parser that
     * cannot resolve it gives up on the whole file.
     */
    private fun parseXhtml(bytes: ByteArray): Document? {
        val raw = decodeText(bytes)
        val prepared = raw
            .replace(Regex("(?is)<!DOCTYPE[^>]*>"), "")
            .replace(Regex("(?is)<\\?xml[^>]*\\?>"), "")
            .replace("&nbsp;", "&#160;")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&hellip;", "…")
            .replace("&lsquo;", "‘")
            .replace("&rsquo;", "’")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&copy;", "©")
            .replace("&deg;", "°")
        return parseXml(prepared.toByteArray(Charsets.UTF_8))
    }

    /** UTF-8 when it decodes cleanly, windows-1251 otherwise — same rule as TXT. */
    private fun decodeText(bytes: ByteArray): String {
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = runCatching { strict.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrNull() ?: String(bytes, Charset.forName("windows-1251"))
        return decoded.removePrefix("\uFEFF")
    }

    private fun unescape(text: String): String = text
        .replace("&nbsp;", " ").replace("&mdash;", "—").replace("&ndash;", "–")
        .replace("&laquo;", "«").replace("&raquo;", "»").replace("&hellip;", "…")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&amp;", "&")

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun tagOf(el: Element): String = (el.localName ?: el.tagName).lowercase()

    /** Elements with this local name under [root], in document order. */
    private fun local(root: Element, name: String): List<Element> {
        val nodes = root.getElementsByTagNameNS("*", name)
        val out = mutableListOf<Element>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element) out.add(node)
        }
        return out
    }
}
