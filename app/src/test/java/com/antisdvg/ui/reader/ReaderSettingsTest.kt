package com.antisdvg.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader stores its settings in preferences as enum ordinals, so this pins
 * the two contracts that would silently break a user's saved choice: existing
 * values never move, and the defaults stay at index 0.
 */
class ReaderSettingsTest {

    @Test
    fun themeOrderIsAppendOnly() {
        assertEquals(
            listOf("PAPER", "SEPIA", "NIGHT", "BLACK"),
            ReaderTheme.entries.map { it.name }
        )
    }

    @Test
    fun firstThemeIsStillTheDefault() {
        assertEquals(ReaderTheme.PAPER, ReaderTheme.entries[0])
    }

    @Test
    fun firstModeIsThePagedOne() {
        assertEquals(ReaderMode.PAGED, ReaderMode.entries[0])
    }

    @Test
    fun everyFontHasItsOwnCssFamily() {
        val families = ReaderFont.entries.map { it.css }
        assertTrue(families.all { it.isNotBlank() })
        assertEquals(families.size, families.distinct().size)
        assertEquals(ReaderFont.SERIF, ReaderFont.entries[0])
    }

    @Test
    fun genericCssFamiliesArePresentSoMissingFontsStillFallBack() {
        assertTrue(ReaderFont.SERIF.css.endsWith("serif"))
        assertTrue(ReaderFont.SANS.css.endsWith("sans-serif"))
        assertTrue(ReaderFont.MONO.css.endsWith("monospace"))
    }
}
