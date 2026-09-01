package com.gios.brightledger.data

import android.content.Context

/**
 * Small settings that belong to the person rather than to the servers.
 *
 * Pins live here rather than as a column on `accounts`, and that is deliberate. The accounts
 * table is a **cache** — [LedgerDatabase] destroys it on any schema change because the server
 * still has everything, and a disconnect drops it outright. A pin is the one thing on that
 * screen the server does not know and cannot send back, so putting it in the same table would
 * mean the next migration silently threw away the only local data in the app.
 */
object Prefs {

    private const val PREFS_NAME = "brightledger_prefs"
    private const val KEY_PINNED = "pinned_accounts"
    private const val KEY_HIDE_TOTALS = "hide_totals"

    /** Account keys, as built by [AccountEntity.key]. */
    fun pinned(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PINNED, emptySet())?.toSet() ?: emptySet()

    fun isPinned(context: Context, accountKey: String): Boolean =
        accountKey in pinned(context)

    /** Returns the new state, so a caller can react without re-reading. */
    fun togglePin(context: Context, accountKey: String): Boolean {
        val current = pinned(context).toMutableSet()
        val nowPinned = current.add(accountKey)
        if (!nowPinned) current.remove(accountKey)
        // A fresh set, not the one getStringSet handed back: the returned instance must not
        // be mutated, and SharedPreferences will not notice if it is.
        prefs(context).edit().putStringSet(KEY_PINNED, HashSet(current)).apply()
        return nowPinned
    }

    /**
     * Whether amounts start hidden.
     *
     * Only the *default*. The live state is held in the view model and re-set from this every
     * time the app leaves the foreground, so "hidden" survives a glance over the shoulder in
     * the same way the PIN lock does.
     */
    fun hideTotalsByDefault(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_TOTALS, false)

    fun setHideTotalsByDefault(context: Context, hide: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_TOTALS, hide).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
