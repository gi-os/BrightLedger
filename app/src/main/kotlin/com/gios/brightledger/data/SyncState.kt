package com.gios.brightledger.data

import android.content.Context

/**
 * When the last sync happened, and how far back the next one should ask.
 *
 * Kept in preferences rather than Room because it survives the cache being thrown away, and
 * because the sync needs it before it can safely open the database.
 */
object SyncState {

    private const val PREFS_NAME = "brightledger_sync"
    private const val KEY_LAST_OK = "last_success"
    private const val KEY_LAST_ERROR = "last_error"

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

    fun incrementalWindowStart(context: Context): Long {
        val last = lastSuccess(context)
        if (last <= 0L) return initialWindowStart()
        return minOf(last - OVERLAP_DAYS * DAY, nowSeconds()).coerceAtLeast(
            nowSeconds() - INITIAL_DAYS * DAY,
        )
    }

    fun lastSuccess(context: Context): Long = prefs(context).getLong(KEY_LAST_OK, 0L)

    fun lastError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)

    fun recordSuccess(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_OK, nowSeconds())
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordError(context: Context, message: String) {
        prefs(context).edit().putString(KEY_LAST_ERROR, message).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
