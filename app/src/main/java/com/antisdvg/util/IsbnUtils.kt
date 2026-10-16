package com.antisdvg.util

/**
 * ISBN normalization shared by manual input, Yandex flow and camera scan.
 *
 * Accepts 13-digit, 10-digit, hyphen/space-separated variants and returns
 * a clean 13-digit ISBN for Google Books / Open Library queries.
 * Returns "" when the input cannot be interpreted as ISBN-10/13.
 */
object IsbnUtils {

    fun normalize(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase()
        if (digits.length == 13 && digits.all(Char::isDigit) && isValidIsbn13(digits)) return digits
        if (digits.length == 10 && isValidIsbn10(digits)) return isbn10to13(digits)
        // Try stripping to digits only (drops X) for hyphenated 13 with X? No — invalid.
        val onlyDigits = raw.filter(Char::isDigit)
        if (onlyDigits.length == 13 && isValidIsbn13(onlyDigits)) return onlyDigits
        if (onlyDigits.length == 10 && isValidIsbn10(onlyDigits)) return isbn10to13(onlyDigits)
        return ""
    }

    fun isValidIsbn13(d: String): Boolean {
        if (d.length != 13 || !d.all(Char::isDigit)) return false
        var sum = 0
        for (i in d.indices) {
            val v = d[i] - '0'
            sum += if (i % 2 == 0) v else v * 3
        }
        return sum % 10 == 0
    }

    fun isValidIsbn10(d: String): Boolean {
        if (d.length != 10) return false
        var sum = 0
        for (i in 0 until 10) {
            val c = d[i]
            val v = when {
                i == 9 && (c == 'X') -> 10
                c.isDigit() -> c - '0'
                else -> return false
            }
            sum += v * (10 - i)
        }
        return sum % 11 == 0
    }

    fun isbn10to13(isbn10: String): String {
        val core = isbn10.substring(0, 9).filter(Char::isDigit)
        if (core.length != 9) return ""
        val twelve = "978$core"
        var sum = 0
        for (i in twelve.indices) {
            val v = twelve[i] - '0'
            sum += if (i % 2 == 0) v else v * 3
        }
        val check = (10 - sum % 10) % 10
        return "$twelve$check"
    }
}
