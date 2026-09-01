package com.gios.brightledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightledger.BuildConfig
import com.gios.brightledger.bus.NightlySync
import com.gios.brightledger.data.Prefs
import com.gios.brightledger.hw.WheelScroll
import com.gios.brightledger.ui.theme.Faint
import com.gios.brightledger.vault.Vault

@Composable
fun SettingsScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onSetPin: () -> Unit,
    onChangePin: () -> Unit,
    onOpenConnections: () -> Unit,
    onDisconnected: () -> Unit,
) {
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val connections by vm.connections.collectAsStateWithLifecycle()
    val hideTotals by vm.hideTotals.collectAsStateWithLifecycle()
    var confirmDisconnect by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var nightly by remember { mutableStateOf(Prefs.nightlySync(context)) }
    var pinSet by remember { mutableStateOf(Vault.isPinSet()) }
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        bottomBar = { ActionBar(listOf(BarAction("BACK", onBack))) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll)) {

            SectionLabel("Data")
            MenuRow(
                label = "Servers",
                sub = when (connections.size) {
                    0 -> "None connected"
                    1 -> connections.first().label
                    else -> connections.size.toString() + " connected"
                },
                onClick = onOpenConnections,
            )
            MenuRow(
                label = "Last sync",
                sub = Format.since(vm.lastSyncSeconds()),
            )
            MenuRow(
                label = if (syncing) "Syncing…" else "Sync all history",
                sub = "Re-reads about a year from every server, rather than the usual two weeks",
                onClick = { vm.refresh(full = true) },
            )
            ToggleRow(
                label = "Nightly sync while charging",
                sub = "Refreshes every server once a day while the phone charges, so the " +
                    "journal and this screen start the morning current. With a PIN lock on, " +
                    "the credential stays sealed and the overnight run quietly skips.",
                checked = nightly,
                onToggle = { wanted ->
                    Prefs.setNightlySync(context, wanted)
                    NightlySync.ensureScheduled(context)
                    nightly = wanted
                },
            )
            MenuRow(
                label = "Erase everything",
                sub = "Forget every server and all cached data on this phone",
                onClick = { confirmDisconnect = true },
            )

            SectionLabel("Security")
            ToggleRow(
                label = "Hide amounts",
                sub = "Start with the spend total and balances masked. Tap the header on the " +
                    "home screen to reveal them, and they re-hide when the app goes to the " +
                    "background.",
                checked = hideTotals,
                onToggle = vm::setHideTotalsDefault,
            )
            ToggleRow(
                label = "PIN lock",
                sub = if (pinSet) {
                    "Required each time the app comes to the front"
                } else {
                    "Credentials are already encrypted to this phone. A PIN also protects " +
                        "them from someone holding the phone unlocked."
                },
                checked = pinSet,
                onToggle = { wanted ->
                    if (wanted) {
                        onSetPin()
                    } else {
                        Vault.clearPin()
                        pinSet = Vault.isPinSet()
                    }
                },
            )
            if (pinSet) {
                MenuRow(label = "Change PIN", onClick = onChangePin)
                ToggleRow(
                    label = "Erase after 10 wrong PINs",
                    sub = "Destroys the key, which destroys every stored credential",
                    checked = Vault.isWipeEnabled(),
                    onToggle = { Vault.setWipeEnabled(it) },
                )
            }

            SectionLabel("About")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "BrightLedger v" + BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Balances and transactions over the SimpleFIN protocol. There is no " +
                        "BrightLedger server: the phone talks straight to the SimpleFIN " +
                        "servers you connected, using read-only credentials that only exist " +
                        "on this device. Revoking access is done wherever each credential " +
                        "was issued, not here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Faint,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (confirmDisconnect) {
        ConfirmDialog(
            message = "Forget every server and erase all cached transactions from this " +
                "phone?\n\nThis does not revoke access. Do that wherever each credential " +
                "was issued.",
            confirmLabel = "ERASE",
            onConfirm = {
                confirmDisconnect = false
                vm.disconnectAll(onDisconnected)
            },
            onDismiss = { confirmDisconnect = false },
        )
    }
}
