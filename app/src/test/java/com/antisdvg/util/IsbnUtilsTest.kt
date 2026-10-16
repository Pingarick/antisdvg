package com.antisdvg.util

import org.junit.Assert.assertEquals
import org.junit.Test

class IsbnUtilsTest {

    @Test
    fun normalize_plain13() {
        assertEquals("9780132350884", IsbnUtils.normalize("9780132350884"))
    }

    @Test
    fun normalize_hyphenated13() {
        assertEquals("9780132350884", IsbnUtils.normalize("978-0-13-235088-4"))
    }

    @Test
    fun normalize_isbn10_converts() {
        // ISBN-10 0-13-235088-2 -> ISBN-13 9780132350884
        assertEquals("9780132350884", IsbnUtils.normalize("0132350882"))
        assertEquals("9780132350884", IsbnUtils.normalize("0-13-235088-2"))
    }

    @Test
    fun normalize_invalid_empty() {
        assertEquals("", IsbnUtils.normalize("hello"))
        assertEquals("", IsbnUtils.normalize("123"))
    }
}
