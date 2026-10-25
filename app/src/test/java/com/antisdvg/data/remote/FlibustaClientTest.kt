package com.antisdvg.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlibustaClientTest {

    @Test
    fun `case does not change the set of queries`() {
        val lower = FlibustaClient.searchVariants("крёстный отец")
        val upper = FlibustaClient.searchVariants("Крёстный отец")

        assertEquals(lower, upper)
        assertTrue(lower.contains("крёстный отец"))
        assertTrue(lower.contains("Крёстный отец"))
    }

    @Test
    fun `yo spelling is searched as well`() {
        val variants = FlibustaClient.searchVariants("Крёстный отец")

        assertTrue(variants.contains("Крестный отец"))
    }

    @Test
    fun `blank query produces no requests`() {
        assertTrue(FlibustaClient.searchVariants("   ").isEmpty())
    }

    @Test
    fun `merge keeps one copy of a book found in several case variants`() {
        val lower = listOf(
            CatalogBook("flibusta:1", CatalogSource.FLIBUSTA, "Крестный отец", "Пьюзо Марио", "рус", "FB2", "https://flibusta.is/b/1/fb2"),
            CatalogBook("flibusta:2", CatalogSource.FLIBUSTA, "Крестный отец", "Пьюзо Марио", "рус", "FB2", "https://flibusta.is/b/2/fb2")
        )
        val upper = listOf(
            CatalogBook("flibusta:1", CatalogSource.FLIBUSTA, "Крестный отец", "Пьюзо Марио", "рус", "FB2", "https://flibusta.is/b/1/fb2"),
            CatalogBook("flibusta:3", CatalogSource.FLIBUSTA, "Крестный отец [The Godfather]", "Пьюзо Марио", "рус", "FB2", "https://flibusta.is/b/3/fb2")
        )

        val merged = FlibustaClient.mergeResults(listOf(lower, upper))

        assertEquals(
            listOf(
                "https://flibusta.is/b/1/fb2",
                "https://flibusta.is/b/2/fb2",
                "https://flibusta.is/b/3/fb2"
            ),
            merged.map { it.downloadUrl }
        )
    }

    @Test
    fun `merge does not depend on the input case`() {
        val lowerCase = listOf(
            CatalogBook("flibusta:1", CatalogSource.FLIBUSTA, "Сицилиец", "Пьюзо Марио", "рус", "FB2", "https://flibusta.is/b/9/fb2")
        )
        val upperCase = listOf(
            CatalogBook("flibusta:1", CatalogSource.FLIBUSTA, "Сицилиец", "Пьюзо Марио", "рус", "FB2", "https://flibusta.is/b/9/fb2")
        )

        assertEquals(
            FlibustaClient.mergeResults(listOf(lowerCase, upperCase)),
            FlibustaClient.mergeResults(listOf(upperCase, lowerCase))
        )
    }
}
