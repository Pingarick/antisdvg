package com.antisdvg.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Copies an e-book picked through the Storage Access Framework into app-private
 * storage and extracts its metadata, so the built-in reader can open it later
 * without needing the original document permission.
 *
 * Supported: FB2 (XML), TXT (UTF-8 or windows-1251), EPUB (a zip of XHTML) and
 * ZIP archives that hold an FB2 — the `.fb2.zip` layout most RU libraries ship.
 */
object BookImporter {

    data class Imported(
        val filePath: String,
        val kind: String,
        val title: String,
        val author: String,
        /** Absolute path of the extracted cover image, empty when the file has none. */
        val coverPath: String,
        val estimatedPages: Int
    )

    /** Thrown when the picked document is not a supported book file. */
    class UnsupportedFormatException : Exception("Unsupported book format")

    /**
     * Returns null when the document could not be read at all.
     * @throws UnsupportedFormatException when the file type is unknown or a ZIP
     *         archive contains no FB2 file.
     */
    fun import(context: Context, uri: Uri): Imported? {
        val displayName = queryName(context, uri) ?: "book"
        val kind = detectKind(displayName, context.contentResolver.getType(uri))
            ?: throw UnsupportedFormatException()

        val stash = newStash(context)
        context.contentResolver.openInputStream(uri)?.use { input ->
            stash.outputStream().use { output -> input.copyTo(output) }
        } ?: return null

        return importStashed(context, stash, displayName, kind)
    }

    /**
     * Same pipeline for a file the app downloaded itself (the catalogue): there is
     * no content resolver, the bytes are already on disk.
     */
    fun import(context: Context, file: File, displayName: String): Imported? {
        val kind = detectKind(displayName, null) ?: throw UnsupportedFormatException()
        val stash = newStash(context)
        file.inputStream().use { input ->
            stash.outputStream().use { output -> input.copyTo(output) }
        }
        return importStashed(context, stash, displayName, kind)
    }

    private fun newStash(context: Context): File {
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        return File(booksDir, "${UUID.randomUUID()}.tmp")
    }

    private fun importStashed(
        context: Context,
        stash: File,
        displayName: String,
        kind: Kind
    ): Imported? {
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val (file, finalKind) = when (kind) {
            Kind.ZIP -> {
                // A plain zip may still be an EPUB in disguise: some libraries
                // ship .epub renamed, and a few ship .zip holding one.
                val extracted = extractFb2(stash, booksDir)
                if (extracted != null) {
                    stash.delete()
                    extracted to "FB2"
                } else if (looksLikeEpub(stash)) {
                    rename(stash, "epub") to "EPUB"
                } else {
                    throw UnsupportedFormatException()
                }
            }
            Kind.FB2 -> rename(stash, "fb2") to "FB2"
            Kind.TXT -> rename(stash, "txt") to "TXT"
            Kind.EPUB -> rename(stash, "epub") to "EPUB"
        }

        val parsed = file.inputStream().use { stream ->
            BookText.parse(stream, finalKind, displayName.substringBeforeLast('.'))
        }
        val coverPath = writeCover(context, parsed.coverDataUri)
        return Imported(
            filePath = file.absolutePath,
            kind = finalKind,
            title = parsed.title.ifBlank { displayName.substringBeforeLast('.') },
            author = parsed.author,
            coverPath = coverPath,
            estimatedPages = parsed.estimatedPages
        )
    }

    /** Pulls the first `.fb2` entry out of an archive into app storage. */
    internal fun extractFb2(archive: File, booksDir: File): File? {
        return runCatching {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".fb2")) {
                        val target = File(booksDir, "${UUID.randomUUID()}.fb2")
                        target.outputStream().use { output -> zip.copyTo(output) }
                        return@use target
                    }
                    entry = zip.nextEntry
                }
                null
            }
        }.getOrNull()
    }

    /** Does this archive hold an EPUB rather than an FB2? */
    internal fun looksLikeEpub(file: File): Boolean = runCatching {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            var checked = 0
            while (entry != null && checked < 8) {
                val name = entry.name.lowercase()
                if (name == "meta-inf/container.xml" || name == "mimetype") return@use true
                checked++
                entry = zip.nextEntry
            }
            false
        }
    }.getOrDefault(false)

    private fun rename(file: File, ext: String): File {
        val target = File(file.parentFile, "${UUID.randomUUID()}.$ext")
        return if (file.renameTo(target)) target else file
    }

    private fun writeCover(context: Context, dataUri: String): String {
        if (dataUri.isBlank() || !dataUri.startsWith("data:")) return ""
        val comma = dataUri.indexOf(',')
        if (comma < 0) return ""
        return try {
            val bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
            val dir = File(context.filesDir, "covers").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.img")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (_: Exception) {
            ""
        }
    }

    private enum class Kind { FB2, TXT, ZIP, EPUB }

    /** Testable mapping from a file name/MIME to the supported container kinds. */
    fun detectKindName(displayName: String, mimeType: String?): String? = when (detectKind(displayName, mimeType)) {
        Kind.FB2 -> "FB2"
        Kind.TXT -> "TXT"
        Kind.ZIP -> "ZIP"
        Kind.EPUB -> "EPUB"
        null -> null
    }

    private fun detectKind(displayName: String, mimeType: String?): Kind? {
        val name = displayName.lowercase()
        val mime = mimeType.orEmpty().lowercase()
        return when {
            name.endsWith(".epub") -> Kind.EPUB
            name.endsWith(".fb2") -> Kind.FB2
            name.endsWith(".txt") -> Kind.TXT
            name.endsWith(".fb2.zip") || name.endsWith(".zip") -> Kind.ZIP
            mime.contains("epub") -> Kind.EPUB
            mime.contains("fb2") -> Kind.FB2
            mime == "text/plain" -> Kind.TXT
            mime.contains("zip") -> Kind.ZIP
            else -> null
        }
    }

    private fun queryName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
}
