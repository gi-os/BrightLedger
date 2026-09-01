package com.gios.brightledger.data

import android.content.Context

/**
 * When each connection last synced, and how far back its next sync should ask.
 *
 * Per connection, not global. Two servers refresh on their own schedules and one of them
 * being unreachable for a week must not widen the other's window — nor narrow it, which is
 * the more dangerous direction: a shared "last success" advanced by the healthy server would
 * make the sick one skip everything that happened while it was down.
 */
object SyncState {

    private const val PREFS_NAME = "brightledger_sync"
    private const val LAST_OK = "last_success_"
    private const val LAST_ERROR = "last_error_"

    /** The v1.0 global keys, adopted by the first connection on upgrade. */
    private const val LEGACY_LAST_OK = "last_success"

    private const val DAY = 86_400L

    /** How much history a first sync pulls. Enough for the recurring detector to see a year. */
    private const val INITIAL_DAYS = 400L

    /**
     * How far back an incremental sync re-reads.
     *
     * Not "since the last sync". A bank can post a transaction dated several days earlier
     * than the moment it appears, and a pending charge can change its amount when it settles,
     * so a window that starts at the last sync silently misses both. Fourteen days of overlap
     * costs a few kilobytes and makes the sync self-healing: every row in the window is
     * re-fetched and upserted, so a bad sync is repaired by the next good one.
     */
    private const val OVERLAP_DAYS = 14L

    fun initialWindowStart(): Long = nowSeconds() - INITIAL_DAYS * DAY

    fun incrementalWindowStart(context: Context, connectionId: String): Long {
        val last = lastSuccess(context, connectionId)
        if (last <= 0L) return initialWindowStart()
        return minOf(last - OVERLAP_DAYS * DAY, nowSeconds())
            .coerceAtLeast(nowSeconds() - INITIAL_DAYS * DAY)
    }

    fun lastSuccess(context: Context, connectionId: String): Long =
        prefs(context).getLong(LAST_OK + connectionId, 0L)

    /** The freshest of any connection, for the one staleness line on the home screen. */
    fun lastSuccessAny(context: Context): Long =
        Connections.all(context).maxOfOrNull { lastSuccess(context, it.id) } ?: 0L

    fun lastError(context: Context, connectionId: String): String? =
        prefs(context).getString(LAST_ERROR + connectionId, null)

    fun recordSuccess(context: Context, connectionId: String) {
        prefs(context).edit()
            .putLong(LAST_OK + connectionId, nowSeconds())
            .remove(LAST_ERROR + connectionId)
            .apply()
    }

    fun recordError(context: Context, connectionId: String, message: String) {
        prefs(context).edit().putString(LAST_ERROR + connectionId, message).apply()
    }

    fun clear(context: Context, connectionId: String) {
        prefs(context).edit()
            .remove(LAST_OK + connectionId)
            .remove(LAST_ERROR + connectionId)
            .apply()
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * Hands the v1.0 global last-sync to the connection that inherited its credential, so an
     * upgrade does not re-download a year on first launch.
     */
    fun adoptLegacy(context: Context, connectionId: String) {
        val prefs = prefs(context)
        val legacy = prefs.getLong(LEGACY_LAST_OK, 0L)
        if (legacy <= 0L) return
        prefs.edit()
            .putLong(LAST_OK + connectionId, legacy)
            .remove(LEGACY_LAST_OK)
            .apply()
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
