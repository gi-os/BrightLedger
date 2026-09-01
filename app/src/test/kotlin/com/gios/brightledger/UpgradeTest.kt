package com.gios.brightledger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gios.brightledger.data.Connections
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v1.0 to v1.1 upgrade, which shipped broken in v1.1.0 and stranded people's banks.
 *
 * The failure was not data loss — the sealed credential sat in preferences the whole time —
 * but a deadlock between three correct-looking pieces. `isConnected` counted only migrated
 * connections, the navigation graph chose its start destination from `isConnected`, and the
 * migration ran only inside a sync that the setup screen never triggers. A v1.0 install
 * therefore opened on "Connect a bank" and stayed there, which from the outside is
 * indistinguishable from the app having forgotten the bank.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UpgradeTest {

    private lateinit var context: Context

    private fun prefs() =
        context.getSharedPreferences("brightledger_access", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    /** A v1.0 install: one sealed blob under the old key, and no connection list at all. */
    private fun writeLegacyInstall() {
        prefs().edit()
            .putString("sealed_access_url", "not-real-ciphertext")
            .putString("host_hint", "beta-bridge.simplefin.org")
            .commit()
    }

    @Test
    fun `a v1_0 install counts as connected before anything has migrated`() {
        writeLegacyInstall()
        assertTrue(Connections.isConnected(context))
        assertTrue(Connections.hasLegacy(context))
        // The list really is empty — this is the state the start destination was read from.
        assertTrue(Connections.all(context).isEmpty())
    }

    @Test
    fun `a fresh install is not connected`() {
        assertFalse(Connections.isConnected(context))
        assertFalse(Connections.hasLegacy(context))
    }

    /**
     * With a PIN set there is no vault key at process start, so the migration cannot run
     * yet. It must decline rather than consume the credential, or the retry after unlock
     * would have nothing left to re-file.
     */
    @Test
    fun `migrating with the vault shut leaves the credential alone`() {
        writeLegacyInstall()
        assertFalse(Connections.migrateLegacy(context))
        assertTrue(Connections.hasLegacy(context))
        assertTrue(Connections.isConnected(context))
    }

    @Test
    fun `removing a connection does not disturb the others`() {
        prefs().edit()
            .putString("connection_ids", "aaa,bbb")
            .putString("sealed_aaa", "x")
            .putString("host_aaa", "one.example")
            .putString("label_aaa", "One")
            .putString("sealed_bbb", "y")
            .putString("host_bbb", "two.example")
            .putString("label_bbb", "Two")
            .commit()

        Connections.remove(context, "aaa")

        val left = Connections.all(context)
        assertTrue(left.size == 1)
        assertTrue(left.single().id == "bbb")
        assertTrue(left.single().label == "Two")
    }
}
