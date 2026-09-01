package com.gios.brightledger.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The non-exportable AES key that wraps the vault key, which in turn wraps the Access URL.
 *
 * Generated inside AndroidKeyStore and never leaving it, so the `shared_prefs` file is inert
 * once copied off the phone. That property is the whole reason the app can keep a live bank
 * credential on a device with no lock screen: the file is worthless without this hardware.
 *
 * No user authentication is set on the key. LightOS has no biometrics, and requiring the
 * device lock would leave the app unable to sync on a phone with no PIN set — which is most
 * of them. The optional app PIN is the layer that covers that, and it sits inside this one.
 */
internal class LedgerKeystore(
    private val keyAlias: String = KEY_ALIAS,
) {
    fun ensureKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) return

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            ?: error("BrightLedger keystore key is missing")
        return entry.secretKey
    }

    companion object {
        const val KEY_ALIAS = "brightledger_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
