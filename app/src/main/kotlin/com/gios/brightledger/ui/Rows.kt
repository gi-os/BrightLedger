package com.gios.brightledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.brightledger.data.AccountEntity
import com.gios.brightledger.data.TransactionWithAccount
import com.gios.brightledger.ledger.Recurring
import com.gios.brightledger.simplefin.Money
import com.gios.brightledger.ui.theme.Dim
import com.gios.brightledger.ui.theme.Faint

/**
 * The three list rows, kept together because they have to agree.
 *
 * All of them are a label block on the left and a number on the right, and the number column
 * has to line up down the whole screen or the list stops reading as a column of money. The
 * amount is therefore always `Text` with `TextAlign.End` at the row's trailing edge, and the
 * left block always takes `weight(1f)` — never a fractional `fillMaxWidth`, which compounds
 * against its parent and drifts the column as nesting changes.
 */

@Composable
fun TransactionRow(
    txn: TransactionWithAccount,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                txn.description.ifBlank { "No description" },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(Format.relativeDate(txn.at))
                    append("  ·  ")
                    append(txn.accountName)
                    if (txn.pending) append("  ·  PENDING")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (txn.pending) Faint else Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            Money.symbol(txn.currency) + Money.formatSigned(txn.amountCents),
            style = MaterialTheme.typography.bodyLarge,
            // Pending amounts are dimmed rather than marked, because the amount is the thing
            // most likely to change when the charge settles.
            color = if (txn.pending) Dim else Color.White,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

@Composable
fun AccountRow(account: AccountEntity, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                account.name,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = account.problem
                ?: account.connName
                ?: Format.relativeDate(account.balanceDate)
            Text(
                sub,
                style = MaterialTheme.typography.bodyMedium,
                color = if (account.problem != null) Color.White else Dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            Money.symbol(account.currency) + Money.format(account.balanceCents),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

@Composable
fun RecurringRow(charge: Recurring.Charge) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                charge.name,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                charge.cadence.label + "  ·  " + Format.until(charge.nextAt),
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            Money.format(-charge.amountCents),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/** Small all-caps heading above a block. The only section marker the design uses. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Faint,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}
