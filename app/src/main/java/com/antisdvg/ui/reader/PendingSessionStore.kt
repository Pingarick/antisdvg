package com.antisdvg.ui.reader

import android.content.Context

/**
 * Postponed sessions on disk: one per book, in the reader's own preferences.
 *
 * Deliberately not a database table — this is short-lived state of one screen,
 * and the whole point of it is to survive a reader being closed.
 */
class PendingSessionStore(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val prefs = context.getSharedPreferences("reader_pending", Context.MODE_PRIVATE)

    /** The postponed session for [bookId], if it is still worth offering. */
    fun forBook(bookId: Long): PendingSession? = all()[bookId]

    /** Stores a session, adding it to whatever this book already has. */
    fun put(session: PendingSession) {
        val current = all()
        val merged = session.mergedWith(current[session.bookId])
        write(PendingSessions.capped(current + (session.bookId to merged)))
    }

    /** Drops the session of one book — claimed, or forgotten by the user. */
    fun remove(bookId: Long) = write(all() - bookId)

    private fun all(): Map<Long, PendingSession> =
        PendingSessions.decode(prefs.getString(KEY, null), now())

    private fun write(sessions: Map<Long, PendingSession>) {
        if (sessions.isEmpty()) {
            prefs.edit().remove(KEY).apply()
        } else {
            prefs.edit().putString(KEY, PendingSessions.encode(sessions)).apply()
        }
    }

    private companion object {
        const val KEY = "sessions"
    }
}
