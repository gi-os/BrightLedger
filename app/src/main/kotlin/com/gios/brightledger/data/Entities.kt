package com.gios.brightledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One account.
 *
 * [id] is **synthetic**: `connectionId|remoteId`, built by [key]. SimpleFIN only promises an
 * account id is unique within one server, so once the phone talks to more than one server the
 * server's own id is not a primary key any more. Two Bridges each numbering an account `1`
 * would otherwise be the same row, and the visible symptom is a balance that flickers between
 * two banks on every sync.
 *
 * Composing the key instead of widening the primary key to a pair keeps every downstream
 * query, the transaction foreign key and the navigation route single-valued. [remoteId] is
 * kept alongside so the value sent back to a server is always the one it issued.
 */
@Entity(tableName = "accounts", indices = [Index("connectionId")])
data class AccountEntity(
    @PrimaryKey val id: String,
    val connectionId: String,
    val remoteId: String,
    val name: String,
    val connId: String?,
    val connName: String?,
    val currency: String,
    val balanceCents: Long,
    val availableCents: Long?,
    val balanceDate: Long,
    /** Set when the last sync reported an error against this account or its connection. */
    val problem: String? = null,
) {
    companion object {
        fun key(connectionId: String, remoteId: String): String = connectionId + "|" + remoteId
    }
}

/**
 * One transaction, keyed on the pair the protocol guarantees is unique.
 *
 * SimpleFIN promises a transaction id is unique *within an account* and explicitly permits
 * two accounts to reuse the same id, so the primary key has to be the pair. Getting this
 * wrong is not a crash — it is one account's rows quietly overwriting another's, which looks
 * like a bank dropping transactions.
 *
 * [accountId] is the synthetic key from [AccountEntity.key], so the pair is unique across
 * servers as well as across accounts.
 *
 * The composite key is also what makes syncing idempotent: every sync re-fetches an
 * overlapping window and simply upserts, so a request that is retried, duplicated, or
 * interrupted halfway cannot produce a double entry.
 */
@Entity(
    tableName = "transactions",
    primaryKeys = ["accountId", "txnId"],
    indices = [Index("at"), Index("accountId")],
)
data class TransactionEntity(
    val accountId: String,
    val txnId: String,
    /** `transacted_at` when the server sends one, otherwise `posted`. Ordering key. */
    val at: Long,
    val posted: Long,
    val amountCents: Long,
    val description: String,
    val pending: Boolean,
)

/** A transaction joined to the account it belongs to, for the feed and the detail page. */
data class TransactionWithAccount(
    val accountId: String,
    val txnId: String,
    val at: Long,
    val posted: Long,
    val amountCents: Long,
    val description: String,
    val pending: Boolean,
    val accountName: String,
    val currency: String,
)
