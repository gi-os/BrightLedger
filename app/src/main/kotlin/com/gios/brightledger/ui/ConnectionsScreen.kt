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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightledger.data.Connections
import com.gios.brightledger.demo.Demo
import com.gios.brightledger.hw.WheelScroll
import com.gios.brightledger.ui.theme.Faint

/**
 * The servers this phone reads from, one row each.
 *
 * Worth a screen of its own rather than a section in Settings, because with more than one
 * connection the interesting state is per-server: which one is stale, which one was revoked,
 * which one is the reason the totals look wrong. A single "last sync" line cannot say any of
 * that, and a half-failed sync is the normal case once a self-hosted server is in the mix.
 */
@Composable
fun ConnectionsScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
) {
    val realConnections by vm.connections.collectAsStateWithLifecycle()
    val demo by vm.demo.collectAsStateWithLifecycle()
    // The servers screen names hosts, and a host is identifying. In demo mode it shows one
    // invented row instead.
    val connections = if (demo) {
        listOf(Connections.Connection(Demo.CONNECTION_ID, Demo.connectionLabel(), Demo.HOST))
    } else {
        realConnections
    }
    val needsReconnect by vm.needsReconnect.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf<Connections.Connection?>(null) }
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            ActionBar(
                listOf(
                    BarAction("BACK", onBack),
                    BarAction("ADD", onAdd),
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll)) {
            if (connections.isEmpty()) {
                EmptyState("No servers connected.")
                return@Column
            }

            SectionLabel("Servers")
            for (connection in connections) {
                val error = vm.lastErrorFor(connection.id)
                val revoked = connection.id in needsReconnect
                MenuRow(
                    label = connection.label,
                    sub = when {
                        revoked -> "Revoked — add a new token to reconnect"
                        error != null -> error
                        else -> connection.host + "  ·  " +
                            Format.since(vm.lastSyncFor(connection.id))
                    },
                    onClick = { vm.retry(connection.id) },
                )
                MenuRow(
                    label = "    Remove",
                    sub = "    Forget this server on this phone",
                    onClick = { confirmRemove = connection },
                )
                Rule()
            }

            Spacer(Modifier.height(16.dp))
            Text(
                if (syncing) {
                    "Syncing…"
                } else {
                    "Tap a server to sync just that one. Each keeps its own history window, " +
                        "so one being unreachable never costs another its place."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Faint,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    confirmRemove?.let { target ->
        ConfirmDialog(
            // Saying what this does *not* do matters more than what it does. Someone who
            // believes this revoked the grant leaves a live read credential in place.
            message = "Forget " + target.label + " and erase its cached accounts and " +
                "transactions from this phone?\n\nThis does not revoke access. Do that " +
                "wherever the credential was issued.",
            confirmLabel = "FORGET",
            onConfirm = {
                confirmRemove = null
                vm.disconnect(target.id)
            },
            onDismiss = { confirmRemove = null },
        )
    }
}
