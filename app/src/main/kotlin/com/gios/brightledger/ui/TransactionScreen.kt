package com.gios.brightledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightledger.data.LedgerRepository
import com.gios.brightledger.simplefin.Money
import com.gios.brightledger.ui.theme.Dim
import com.gios.brightledger.ui.theme.Faint
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember

/**
 * One transaction, with the fields the feed row had to drop.
 *
 * Reads its own row from the database rather than taking it as an argument. A detail screen
 * handed a snapshot goes stale the moment a sync lands underneath it — the pending charge
 * you are looking at posts, its amount changes, and the page keeps showing the old one.
 * Holding the query means it simply updates.
 */
@Composable
fun TransactionScreen(
    vm: LedgerViewModel,
    accountId: String,
    txnId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { LedgerRepository.get(context) }
    val stored by repo.dao.transaction(accountId, txnId)
        .collectAsStateWithLifecycle(initialValue = null)
    // Demo rows are generated, never written, so the query behind this screen finds nothing
    // for them. Preferring the synthetic row keeps the tap-through working in a screenshot
    // session without putting fabricated data anywhere near the database.
    val txn = vm.demoTransaction(accountId, txnId) ?: stored

    Scaffold(
        containerColor = Color.Black,
        bottomBar = { ActionBar(listOf(BarAction("BACK", onBack))) },
    ) { padding ->
        val row = txn
        if (row == null) {
            EmptyState("This transaction is no longer in the ledger.", Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            Text(
                Money.symbol(row.currency) + Money.formatSigned(row.amountCents),
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                maxLines = 1,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                row.description.ifBlank { "No description" },
                style = MaterialTheme.typography.titleMedium,
                color = Dim,
            )
            Spacer(Modifier.height(24.dp))
            Rule()

            Field("Account", row.accountName)
            Field("Status", if (row.pending) "Pending" else "Posted")
            Field("Date", Format.fullDate(row.at))
            // Only shown when the two differ. A row where the bank sent both and they match
            // would otherwise repeat the same date twice for no reason.
            if (row.posted > 0L && row.posted != row.at) {
                Field("Posted", Format.fullDate(row.posted))
            }
            Field("Reference", row.txnId)
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Faint,
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
    }
}
