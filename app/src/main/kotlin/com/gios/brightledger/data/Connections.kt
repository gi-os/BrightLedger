package com.gios.brightledger.data

import android.content.Context
import android.util.Base64
import com.gios.brightledger.simplefin.AccessUrl
import com.gios.brightledger.vault.Vault
import com.gios.brightledger.vault.VaultCrypto
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Every SimpleFIN server this phone is connected to.
 *
 * v1.0 held exactly one Access URL, on the assumption that one Bridge account covers every
 * bank. It does not. The hosted Bridge is MX-backed and US/Canada only, so an institution it
 * has never heard of — Revolut being the one that forced this — can only be reached by
 * pointing at a *different* SimpleFIN server. The protocol was designed for that: its own
 * spec expects institutions to run their own servers and treats the Bridge as the fallback.
 *
 * So a connection is not "the Bridge", it is "a server", and there can be several. Each gets
 * a local id, generated here and never leaving the phone, which is what namespaces its
 * accounts in the database and keys its sync state.
 */
object Connections {

    private const val PREFS_NAME = "brightledger_access"
    private const val KEY_IDS = "connection_ids"

    private const val SEALED = "sealed_"
    private const val HOST = "host_"
    private const val LABEL = "label_"

    /** The v1.0 single-connection keys, read once by [migrateLegacy] and then dropped. */
    private const val LEGACY_SEALED = "sealed_access_url"
    private const val LEGACY_HOST = "host_hint"

    /** What the UI can know without unlocking the vault. */
    data class Connection(
        val id: String,
        val label: String,
        val host: String,
    )

    fun all(context: Context): List<Connection> {
        val prefs = prefs(context)
        return ids(context).map { id ->
            Connection(
                id = id,
                label = prefs.getString(LABEL + id, null) ?: prefs.getString(HOST + id, null)
                    ?: "SimpleFIN server",
                host = prefs.getString(HOST + id, null) ?: "unknown",
            )
        }
    }

    fun isConnected(context: Context): Boolean = ids(context).isNotEmpty()

    /**
     * Adds a claimed server. Returns its new id, or null when the vault is shut.
     *
     * Null is the serious case and callers must treat it as one: the setup token that bought
     * this Access URL is already spent, so a failure here is not retryable with the same
     * token.
     */
    fun add(context: Context, access: AccessUrl, raw: String, label: String? = null): String? {
        val key = Vault.key() ?: return null
        val id = UUID.randomUUID().toString().take(8)
        val sealed = VaultCrypto.seal(key, raw.toByteArray(StandardCharsets.UTF_8))
        prefs(context).edit()
            .putString(SEALED + id, Base64.encodeToString(sealed, Base64.NO_WRAP))
            // Host only. The connections screen names the server it is talking to, and
            // reading that out of the sealed value would mean unlocking to draw a label.
            .putString(HOST + id, access.host)
            .putString(LABEL + id, label ?: access.host)
            .putString(KEY_IDS, (ids(context) + id).joinToString(","))
            .apply()
        return id
    }

    /** Null when the vault is locked or the id is gone. Never cached. */
    fun load(context: Context, id: String): AccessUrl? {
        val key = Vault.key() ?: return null
        val stored = prefs(context).getString(SEALED + id, null) ?: return null
        val blob = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return null
        val opened = VaultCrypto.open(key, blob) ?: return null
        return AccessUrl.parse(String(opened, StandardCharsets.UTF_8))
    }

    fun rename(context: Context, id: String, label: String) {
        prefs(context).edit().putString(LABEL + id, label).apply()
    }

    /**
     * Forgets one server locally. Does **not** revoke anything — revocation happens wherever
     * the credential was issued, and every screen that calls this says so.
     */
    fun remove(context: Context, id: String) {
        prefs(context).edit()
            .remove(SEALED + id)
            .remove(HOST + id)
            .remove(LABEL + id)
            .putString(KEY_IDS, ids(context).filterNot { it == id }.joinToString(","))
            .apply()
        SyncState.clear(context, id)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * Moves a v1.0 install onto the list, in place, without asking for a new token.
     *
     * The old blob was sealed with the same vault key, so it is re-filed rather than
     * re-claimed. It has to run while the vault is open, which is why it is called from the
     * repository on first sync rather than from `Application.onCreate` — at process start a
     * PIN-locked install has no key and the migration would silently do nothing and then
     * mark itself done.
     */
    fun migrateLegacy(context: Context): Boolean {
        val prefs = prefs(context)
        val legacy = prefs.getString(LEGACY_SEALED, null) ?: return false
        if (Vault.key() == null) return false

        val id = UUID.randomUUID().toString().take(8)
        prefs.edit()
            .putString(SEALED + id, legacy)
            .putString(HOST + id, prefs.getString(LEGACY_HOST, null) ?: "unknown")
            .putString(LABEL + id, prefs.getString(LEGACY_HOST, null) ?: "SimpleFIN Bridge")
            .putString(KEY_IDS, (ids(context) + id).joinToString(","))
            .remove(LEGACY_SEALED)
            .remove(LEGACY_HOST)
            .apply()

        // The accounts already cached under v1.0 carry no connection id, so they would sit in
        // the database forever as orphans that no sync ever updates. Dropping them is safe:
        // the next sync re-reads a year.
        SyncState.adoptLegacy(context, id)
        return true
    }

    private fun ids(context: Context): List<String> =
        prefs(context).getString(KEY_IDS, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
