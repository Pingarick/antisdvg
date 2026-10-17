package com.antisdvg.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A postponed session is what an exit without the test leaves behind, so it has
 * to be conservative: keep what was read, add a second postponement to the
 * first, and forget itself before it becomes a suspicious old debt.
 */
class PendingSessionTest {

    private fun session(
        bookId: Long = 100L,
        minutes: Int = 5,
        from: Int = 3,
        to: Int = 7,
        pages: Int = 5,
        text: String = "первый кусок",
        savedAt: Long = 1_000L
    ) = PendingSession(bookId, minutes, from, to, pages, text, savedAt)

    @Test
    fun `merging a second postponement keeps both readings`() {
        val first = session(minutes = 5, from = 3, to = 7, pages = 5, text = "первый")
        val second = session(minutes = 4, from = 6, to = 9, pages = 4, text = "второй")

        val merged = second.mergedWith(first)

        assertEquals(9, merged.minutesRead)
        assertEquals(3, merged.fromPage)
        assertEquals(9, merged.toPage)
        assertEquals(9, merged.pages)
        // Both passages stay quiz-able, and the blank line keeps a sentence from
        // being stitched together across the two readings.
        assertEquals("первый\n\nвторой", merged.excerpt)
    }

    @Test
    fun `merging does not touch another book's session`() {
        val other = session(bookId = 42L, minutes = 30, text = "чужая книга")

        val merged = session(bookId = 100L, minutes = 5, text = "своя").mergedWith(other)

        assertEquals(5, merged.minutesRead)
        assertEquals("своя", merged.excerpt)
    }

    @Test
    fun `merging without anything kept just returns itself`() {
        val fresh = session()
        assertEquals(fresh, fresh.mergedWith(null))
    }

    @Test
    fun `a session is offered while it is young and forgotten after a day`() {
        val savedAt = 1_000_000L
        val claim = session(savedAt = savedAt)

        assertTrue(claim.isFresh(savedAt + PendingSession.TTL_MS))
        assertFalse(claim.isFresh(savedAt + PendingSession.TTL_MS + 1))
    }

    @Test
    fun `codec keeps every field`() {
        val original = session(minutes = 17, from = 12, to = 34, pages = 23, text = "текст страниц")

        val restored = PendingSessions.decode(PendingSessions.encode(mapOf(100L to original)), 1_500L)

        assertEquals(original, restored[100L])
    }

    @Test
    fun `decode drops expired entries and tolerates garbage`() {
        val now = 10_000_000L
        val old = session(savedAt = now - PendingSession.TTL_MS - 1)
        val fresh = session(bookId = 200L, savedAt = now - 1_000L)

        val restored = PendingSessions.decode(
            PendingSessions.encode(mapOf(100L to old, 200L to fresh)),
            now
        )

        assertNull(restored[100L])
        assertEquals(fresh, restored[200L])
        assertTrue(PendingSessions.decode("не json", 1L).isEmpty())
        assertTrue(PendingSessions.decode(null, 1L).isEmpty())
    }

    @Test
    fun `only the newest sessions survive the cap`() {
        val many = (1L..8L).associateWith { session(bookId = it, savedAt = it * 1_000L) }

        val capped = PendingSessions.capped(many)

        assertEquals(PendingSessions.LIMIT, capped.size)
        assertEquals(true, capped.containsKey(8L))
        assertEquals(false, capped.containsKey(1L))
    }
}
