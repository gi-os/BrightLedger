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
import com.gios.brightledger.hw.WheelScroll
import com.gios.brightledger.ui.theme.Faint
import com.gios.brightledger.vault.Vault

@Composable
fun SettingsScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onSetPin: () -> Unit,
    onChangePin: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnected: () -> Unit,
) {
    val context = LocalContext.current
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    var confirmDisconnect by remember { mutableStateOf(false) }
    var pinSet by remember { mutableStateOf(Vault.isPinSet()) }
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        bottomBar = { ActionBar(listOf(BarAction("BACK", onBack))) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll)) {

            SectionLabel("Connection")
            MenuRow(
                label = "Server",
                sub = vm.serverHost() ?: "Not connected",
            )
            MenuRow(
                label = "Last sync",
                sub = Format.since(vm.lastSyncSeconds()),
            )
            MenuRow(
                label = if (syncing) "Syncing…" else "Sync all history",
                sub = "Re-reads about a year, rather than the usual two weeks",
                onClick = { vm.refresh(full = true) },
            )
            MenuRow(
                label = "Reconnect",
                sub = "Use a new setup token from the Bridge",
                onClick = onReconnect,
            )
            MenuRow(
                label = "Disconnect",
                sub = "Erase the stored credential and all cached data on this phone",
                onClick = { confirmDisconnect = true },
            )

            SectionLabel("Security")
            ToggleRow(
                label = "PIN lock",
                sub = if (pinSet) {
                    "Required each time the app comes to the front"
                } else {
                    "The credential is already encrypted to this phone. A PIN also " +
                        "protects it from someone holding the phone unlocked."
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
                    sub = "Destroys the key, which destroys the stored credential",
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
                        "server you connected, using a read-only credential that only " +
                        "exists on this device. Revoking access is done on the Bridge, " +
                        "not here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Faint,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (confirmDisconnect) {
        ConfirmDialog(
            // Saying what disconnecting does *not* do matters more than what it does. A user
            // who believes this revoked the grant will leave a live read credential sitting
            // on the Bridge indefinitely.
            message = "Erase the stored credential and all cached transactions from this " +
                "phone?\n\nThis does not revoke access. To do that, delete the app on " +
                "bridge.simplefin.org.",
            confirmLabel = "ERASE",
            onConfirm = {
                confirmDisconnect = false
                vm.disconnect(onDisconnected)
            },
            onDismiss = { confirmDisconnect = false },
        )
    }
}
