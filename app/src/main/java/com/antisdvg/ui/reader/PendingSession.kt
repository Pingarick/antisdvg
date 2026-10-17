package com.antisdvg.ui.reader

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * A finished reading session whose test was postponed.
 *
 * Walking out of the reader used to cost the session outright. Keeping what the
 * session produced — the honest minutes, the pages that were on screen and their
 * text — lets the test be taken later against exactly those pages. Local only,
 * and it expires: a claim nobody comes back to is not a debt.
 */
data class PendingSession(
    val bookId: Long,
    val minutesRead: Int,
    val fromPage: Int,
    val toPage: Int,
    val pages: Int,
    val excerpt: String,
    val savedAtMs: Long
) {

    /** Still worth offering. */
    fun isFresh(nowMs: Long): Boolean = nowMs - savedAtMs <= TTL_MS

    /**
     * Folds in an older postponed session for the same book, so postponing twice
     * keeps both readings instead of quietly replacing the first one.
     */
    fun mergedWith(previous: PendingSession?): PendingSession {
        if (previous == null || previous.bookId != bookId) return this
        return copy(
            minutesRead = minutesRead + previous.minutesRead,
            fromPage = minOf(fromPage, previous.fromPage),
            toPage = maxOf(toPage, previous.toPage),
            pages = pages + previous.pages,
            excerpt = previous.excerpt + "\n\n" + excerpt,
            savedAtMs = maxOf(savedAtMs, previous.savedAtMs)
        )
    }

    companion object {
        /** A day is enough to come back to a session; after that it is forgotten. */
        const val TTL_MS = 24L * 60 * 60 * 1000
    }
}

/** JSON (de)serialisation for postponed sessions, keyed by book. */
object PendingSessions {

    /** A handful of postponed books is plenty; the oldest ones fall off. */
    const val LIMIT = 5

    private val gson = Gson()
    private val type = object : TypeToken<Map<String, PendingSession>>() {}.type

    fun encode(sessions: Map<Long, PendingSession>): String =
        gson.toJson(sessions.mapKeys { it.key.toString() })

    /** Reads the stored sessions, dropping expired or unreadable ones. */
    fun decode(raw: String?, nowMs: Long): Map<Long, PendingSession> {
        if (raw.isNullOrBlank()) return emptyMap()
        val parsed: Map<String, PendingSession>? =
            runCatching { gson.fromJson<Map<String, PendingSession>>(raw, type) }.getOrNull()
        if (parsed == null) return emptyMap()
        return parsed.entries
            .mapNotNull { (key, value) ->
                val id = key.toLongOrNull()
                if (id == null || value == null || !value.isFresh(nowMs)) {
                    null
                } else {
                    id to value
                }
            }
            .toMap()
    }

    /** Keeps only the most recently saved [LIMIT] sessions. */
    fun capped(sessions: Map<Long, PendingSession>): Map<Long, PendingSession> =
        if (sessions.size <= LIMIT) {
            sessions
        } else {
            sessions.entries
                .sortedByDescending { it.value.savedAtMs }
                .take(LIMIT)
                .associate { it.key to it.value }
        }
}
