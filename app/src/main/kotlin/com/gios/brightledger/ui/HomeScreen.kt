package com.gios.brightledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightledger.hw.WheelScroll
import com.gios.brightledger.simplefin.Money
import com.gios.brightledger.ui.theme.Dim
import com.gios.brightledger.ui.theme.Faint

/**
 * Everything worth knowing, in one scroll.
 *
 * The ordering is deliberate and is the whole design: today's spend, then the month, then
 * what is in the accounts, then what happened. That is the order the questions actually
 * arrive in — "can I buy this" comes before "what did I buy" — and it means the answer to
 * the common one is legible without scrolling at all.
 */
@Composable
fun HomeScreen(
    vm: LedgerViewModel,
    onOpenTransaction: (String, String) -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val feed by vm.feed.collectAsStateWithLifecycle()
    val totals by vm.totals.collectAsStateWithLifecycle()
    val recurring by vm.recurring.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    WheelScroll(listState)

    // One refresh per foregrounding. The Bridge refreshes upstream banks on its own schedule,
    // so hammering it on every recomposition would cost battery and change nothing.
    LaunchedEffect(Unit) { vm.refresh() }

    val currency = accounts.firstOrNull()?.currency

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            ActionBar(
                listOf(
                    BarAction(if (syncing) "SYNCING" else "SYNC") { vm.refresh() },
                    BarAction("REPEATS", onOpenRecurring),
                    BarAction("SETTINGS", onOpenSettings),
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item { SpendHeader(totals, currency, vm.lastSyncSeconds(), syncing) }

            if (accounts.isNotEmpty()) {
                item { SectionLabel("Accounts") }
                items(accounts, key = { it.id }) { account ->
                    AccountRow(account)
                }
            }

            if (recurring.isNotEmpty()) {
                item { SectionLabel("Repeats") }
                item {
                    val monthly = recurring.sumOf { com.gios.brightledger.ledger.Recurring.monthlyCostCents(it) }
                    MenuRow(
                        label = Money.symbol(currency) + Money.format(monthly) + " a month",
                        sub = recurring.size.toString() + " repeating charges",
                        onClick = onOpenRecurring,
                    )
                }
            }

            item { SectionLabel("Activity") }
            if (feed.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Text(
                            if (syncing) "Loading…" else "Nothing here yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Dim,
                        )
                    }
                }
            } else {
                items(feed, key = { it.accountId + "/" + it.txnId }) { txn ->
                    TransactionRow(txn) { onOpenTransaction(txn.accountId, txn.txnId) }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    message?.let { MessageDialog(it) { vm.dismissMessage() } }
}

/**
 * The number the app exists to show.
 *
 * Today's spend gets the display type and the month gets a line under it, rather than the
 * two sharing a row. On this panel a pair of large numbers side by side reads as one
 * comparison the user has to resolve; stacked, the big one is simply the answer.
 */
@Composable
private fun SpendHeader(
    totals: com.gios.brightledger.ledger.Summary.Totals,
    currency: String?,
    lastSync: Long,
    syncing: Boolean,
) {
    val symbol = Money.symbol(currency)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp)) {
        Text(
            "SPENT TODAY",
            style = MaterialTheme.typography.labelSmall,
            color = Faint,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            symbol + Money.format(totals.todayCents),
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
            maxLines = 1,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                symbol + Money.format(totals.monthCents) + " this month",
                style = MaterialTheme.typography.bodyLarge,
                color = Dim,
                maxLines = 1,
            )
            if (totals.pendingCount > 0) {
                Text(
                    totals.pendingCount.toString() + " pending",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Faint,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // The staleness line is never hidden. A finance app that shows a confident number
        // without saying how old it is has told a small lie, and on a phone that is often
        // offline the number is frequently hours behind.
        Text(
            when {
                syncing -> "Syncing…"
                lastSync <= 0L -> "Not synced yet"
                else -> "Updated " + Format.since(lastSync)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Faint,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Rule()
    }
}
