package com.gios.brightledger.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightledger.hw.WheelScroll
import com.gios.brightledger.ledger.Recurring
import com.gios.brightledger.simplefin.Money
import com.gios.brightledger.ui.theme.Faint

/**
 * What is on repeat, and what it adds up to.
 *
 * The header is the number that changes behaviour: not what each subscription costs, which
 * everyone already thinks they know, but their total per month, which almost nobody does.
 */
@Composable
fun RecurringScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val charges by vm.recurring.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    WheelScroll(listState)

    val symbol = Money.symbol(accounts.firstOrNull()?.currency)
    val monthly = charges.sumOf { Recurring.monthlyCostCents(it) }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = { ActionBar(listOf(BarAction("BACK", onBack))) },
    ) { padding ->
        if (charges.isEmpty()) {
            EmptyState(
                "Nothing repeating yet.\n\nA charge shows up here once it has happened " +
                    "three times at a steady interval.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp)) {
                    Text(
                        "REPEATING",
                        style = MaterialTheme.typography.labelSmall,
                        color = Faint,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        symbol + Money.format(monthly),
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "a month across " + charges.size + " charges",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Faint,
                    )
                    Spacer(Modifier.height(16.dp))
                    Rule()
                }
            }

            items(charges, key = { it.name + it.amountCents }) { charge ->
                RecurringRow(charge)
            }

            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Worked out from your transaction history on this phone. Nothing is " +
                        "sent anywhere to compute it, and a charge that changes price or " +
                        "skips a cycle may drop off this list until the pattern returns.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Faint,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
