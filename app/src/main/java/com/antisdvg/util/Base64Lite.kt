package com.antisdvg.util

/**
 * Base64 for the reader's inline images.
 *
 * `android.util.Base64` does not exist in JVM unit tests and `java.util.Base64`
 * only arrived in API 26, so the twenty lines it takes live here instead — and
 * are covered by a test with known vectors.
 */
internal object Base64Lite {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n shr 18) and 0x3F])
                .append(ALPHABET[(n shr 12) and 0x3F])
                .append(ALPHABET[(n shr 6) and 0x3F])
                .append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n shr 18) and 0x3F])
                    .append(ALPHABET[(n shr 12) and 0x3F])
                    .append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n shr 18) and 0x3F])
                    .append(ALPHABET[(n shr 12) and 0x3F])
                    .append(ALPHABET[(n shr 6) and 0x3F])
                    .append('=')
            }
        }
        return out.toString()
    }
}
