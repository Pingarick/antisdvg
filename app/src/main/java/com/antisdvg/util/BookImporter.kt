package com.antisdvg.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File
import java.util.UUID

/**
 * Copies an e-book picked through the Storage Access Framework into app-private
 * storage and extracts its metadata, so the built-in reader can open it later
 * without needing the original document permission.
 *
 * Supported now: FB2 (XML) and TXT (UTF-8 or windows-1251).
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

    /** Thrown when the picked document is neither FB2 nor TXT. */
    class UnsupportedFormatException : Exception("Unsupported book format")

    /**
     * Returns null when the document could not be read at all.
     * @throws UnsupportedFormatException when the file type is not FB2/TXT.
     */
    fun import(context: Context, uri: Uri): Imported? {
        val displayName = queryName(context, uri) ?: "book"
        val kind = kindOf(displayName, context.contentResolver.getType(uri))
            ?: throw UnsupportedFormatException()

        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val ext = if (kind == "FB2") "fb2" else "txt"
        val target = File(booksDir, "${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null

        val parsed = target.inputStream().use { stream ->
            BookText.parse(stream, kind, displayName.substringBeforeLast('.'))
        }
        val coverPath = writeCover(context, parsed.coverDataUri)
        return Imported(
            filePath = target.absolutePath,
            kind = kind,
            title = parsed.title.ifBlank { displayName.substringBeforeLast('.') },
            author = parsed.author,
            coverPath = coverPath,
            estimatedPages = parsed.estimatedPages
        )
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

    fun kindOf(displayName: String, mimeType: String?): String? {
        val name = displayName.lowercase()
        return when {
            name.endsWith(".fb2") || name.endsWith(".fb2.zip") -> "FB2"
            name.endsWith(".txt") -> "TXT"
            mimeType?.contains("fb2", ignoreCase = true) == true -> "FB2"
            mimeType == "text/plain" -> "TXT"
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
