package com.gios.brightledger

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gios.brightledger.hw.LightKey
import com.gios.brightledger.hw.LightKeys
import com.gios.brightledger.hw.LocalWheelBus
import com.gios.brightledger.hw.WheelBus
import com.gios.brightledger.ui.ConnectionsScreen
import com.gios.brightledger.ui.HomeScreen
import com.gios.brightledger.ui.LedgerViewModel
import com.gios.brightledger.ui.LockScreen
import com.gios.brightledger.ui.PinSetupScreen
import com.gios.brightledger.ui.RecurringScreen
import com.gios.brightledger.ui.ScanScreen
import com.gios.brightledger.ui.SettingsScreen
import com.gios.brightledger.ui.SetupScreen
import com.gios.brightledger.ui.TransactionScreen
import com.gios.brightledger.ui.theme.BrightLedgerTheme
import com.gios.brightledger.vault.Vault
import com.gios.brightledger.vault.VaultState

class MainActivity : ComponentActivity() {

    private val wheel = WheelBus()

    /**
     * The wheel is read here, before the view hierarchy sees it, so a focused text field
     * cannot swallow a notch. Both halves of the pair are consumed — one notch is a complete
     * DOWN plus UP, and letting the UP through lands as a keypress in whatever has focus.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Before anything reads the credential: this decides whether the app opens on the
        // ledger or on the keypad.
        Vault.init(this)

        // Balances and merchant names are exactly the sort of thing that should not end up
        // in the recents thumbnail or a screenshot taken by something else.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        setContent {
            BrightLedgerTheme {
                val nav = rememberNavController()
                val vm: LedgerViewModel = viewModel()
                val vaultState by Vault.state.collectAsStateWithLifecycle()
                val connected by vm.connected.collectAsStateWithLifecycle()

                CompositionLocalProvider(LocalWheelBus provides wheel) {
                    if (vaultState == VaultState.Locked) {
                        // Not a route. A locked vault must not be something the back stack
                        // can navigate away from, and every screen below assumes a readable
                        // credential.
                        // The PIN itself is not wanted here — the vault has already used it
                        // to unwrap the key by the time this fires.
                        LockScreen(onUnlocked = { _ -> vm.refresh() })
                    } else {
                        NavHost(
                            nav,
                            startDestination = if (connected) "home" else "setup",
                        ) {
                            composable("setup") {
                                SetupScreen(
                                    vm = vm,
                                    onScan = { nav.navigate("scan") },
                                    onConnected = {
                                        nav.navigate("home") {
                                            popUpTo("setup") { inclusive = true }
                                        }
                                    },
                                )
                            }
                            composable("add-server") {
                                SetupScreen(
                                    vm = vm,
                                    onScan = { nav.navigate("scan-add") },
                                    // Back to the connections list, not to home: the user
                                    // came here to manage servers and has just added one.
                                    onConnected = { nav.popBackStack() },
                                    onBack = { nav.popBackStack() },
                                )
                            }
                            composable("connections") {
                                ConnectionsScreen(
                                    vm = vm,
                                    onBack = { nav.popBackStack() },
                                    onAdd = { nav.navigate("add-server") },
                                )
                            }
                            // Two scan destinations for one screen, because where a scan
                            // returns to is not the same in both cases: the first one has to
                            // replace the setup screen with the ledger, and a later one has
                            // to land back on the connections list without touching the
                            // stack underneath it.
                            composable("scan") {
                                ScanScreen(
                                    onBack = { nav.popBackStack() },
                                    onScanned = { raw ->
                                        // Leave the scanner first: a bad token surfaces as a
                                        // dialog, and a dialog cannot show over a screen that
                                        // is about to be popped.
                                        nav.popBackStack()
                                        vm.connect(raw) { ok ->
                                            if (ok) {
                                                nav.navigate("home") {
                                                    popUpTo("setup") { inclusive = true }
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                            composable("scan-add") {
                                ScanScreen(
                                    onBack = { nav.popBackStack() },
                                    onScanned = { raw ->
                                        nav.popBackStack()
                                        vm.connect(raw) { ok ->
                                            if (ok) nav.popBackStack()
                                        }
                                    },
                                )
                            }
                            composable("home") {
                                HomeScreen(
                                    vm = vm,
                                    onOpenTransaction = { accountId, txnId ->
                                        nav.navigate("txn/" + accountId + "/" + txnId)
                                    },
                                    onOpenRecurring = { nav.navigate("recurring") },
                                    onOpenSettings = { nav.navigate("settings") },
                                )
                            }
                            composable(
                                "txn/{account}/{txn}",
                                arguments = listOf(
                                    navArgument("account") { type = NavType.StringType },
                                    navArgument("txn") { type = NavType.StringType },
                                ),
                            ) { entry ->
                                TransactionScreen(
                                    accountId = entry.arguments?.getString("account").orEmpty(),
                                    txnId = entry.arguments?.getString("txn").orEmpty(),
                                    onBack = { nav.popBackStack() },
                                )
                            }
                            composable("recurring") {
                                RecurringScreen(vm = vm, onBack = { nav.popBackStack() })
                            }
                            composable("settings") {
                                SettingsScreen(
                                    vm = vm,
                                    onBack = { nav.popBackStack() },
                                    onSetPin = { nav.navigate("pin/new") },
                                    onChangePin = { nav.navigate("pin/change") },
                                    onOpenConnections = { nav.navigate("connections") },
                                    onDisconnected = {
                                        nav.navigate("setup") {
                                            popUpTo("home") { inclusive = true }
                                        }
                                    },
                                )
                            }
                            composable(
                                "pin/{mode}",
                                arguments = listOf(
                                    navArgument("mode") { type = NavType.StringType },
                                ),
                            ) { entry ->
                                PinSetupScreen(
                                    changing = entry.arguments?.getString("mode") == "change",
                                    onDone = { nav.popBackStack("settings", inclusive = false) },
                                    onCancel = { nav.popBackStack() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The key leaves memory when the app stops being what is on screen.
     *
     * `onStop` rather than `onPause`, because `onPause` also fires for the camera permission
     * dialog the scanner puts up, and re-locking mid-connect would throw away a token that
     * can only be used once.
     */
    override fun onStop() {
        super.onStop()
        Vault.lock()
    }
}
