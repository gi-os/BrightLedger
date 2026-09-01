package com.gios.brightledger.data

import android.content.Context
import android.util.Base64
import com.gios.brightledger.simplefin.AccessUrl
import com.gios.brightledger.vault.Vault
import com.gios.brightledger.vault.VaultCrypto
import java.nio.charset.StandardCharsets

/**
 * Where the Access URL lives, and the only thing that can read it back.
 *
 * One value, encrypted under the vault key, in its own preferences file. The vault key is
 * itself wrapped by AndroidKeyStore and — if the user turns a PIN on — by the PIN as well, so
 * this inherits both layers without knowing about either.
 *
 * [load] returning null is a normal state, not a failure: it means either that no bank is
 * connected yet or that the vault is locked. Callers distinguish the two by asking the vault,
 * never by treating null as "not set up".
 */
object AccessUrlStore {

    private const val PREFS_NAME = "brightledger_access"
    private const val KEY_SEALED = "sealed_access_url"
    private const val KEY_HOST = "host_hint"

    fun save(context: Context, access: AccessUrl, raw: String): Boolean {
        val key = Vault.key() ?: return false
        val sealed = VaultCrypto.seal(key, raw.toByteArray(StandardCharsets.UTF_8))
        prefs(context).edit()
            .putString(KEY_SEALED, Base64.encodeToString(sealed, Base64.NO_WRAP))
            // Host only. The settings screen wants to say which server it is talking to, and
            // reading that out of the sealed value would mean unlocking to draw a label.
            .putString(KEY_HOST, access.host)
            .apply()
        return true
    }

    fun load(context: Context): AccessUrl? {
        val key = Vault.key() ?: return null
        val stored = prefs(context).getString(KEY_SEALED, null) ?: return null
        val blob = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return null
        val opened = VaultCrypto.open(key, blob) ?: return null
        return AccessUrl.parse(String(opened, StandardCharsets.UTF_8))
    }

    /** Whether a bank is connected, answerable while locked. */
    fun isConnected(context: Context): Boolean =
        prefs(context).contains(KEY_SEALED)

    fun hostHint(context: Context): String? =
        prefs(context).getString(KEY_HOST, null)

    /**
     * Forgets the credential locally. It does **not** revoke anything: revocation happens on
     * the Bridge, and the disconnect screen says so, because an app that implied otherwise
     * would leave a live grant behind on a phone the user thinks they cleaned.
     */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
