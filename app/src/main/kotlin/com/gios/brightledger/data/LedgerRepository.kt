package com.gios.brightledger.data

import android.content.Context
import com.gios.brightledger.simplefin.SfAccountSet
import com.gios.brightledger.simplefin.SimpleFinClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One sync, start to finish.
 *
 * Offline-first in the strict sense: the screens read Room and only Room, and a sync is a
 * background write into it. A failed refresh therefore changes nothing on screen except the
 * staleness line — which is the correct behaviour on a phone that spends its day in a pocket
 * with no signal, and the reason nothing here returns data to a caller.
 */
class LedgerRepository private constructor(
    private val context: Context,
    private val client: SimpleFinClient = SimpleFinClient(),
) {

    val dao: LedgerDao = LedgerDatabase.get(context).dao()

    sealed interface SyncResult {
        data class Ok(val accounts: Int, val transactions: Int, val warnings: List<String>) :
            SyncResult

        /** No credential available: either nothing is connected, or the vault is locked. */
        data object NotConnected : SyncResult

        /** The Bridge revoked us. The user has to reconnect; the cache stays put. */
        data object NeedsReconnect : SyncResult

        data object PaymentRequired : SyncResult

        data class Failed(val message: String) : SyncResult
    }

    suspend fun sync(full: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        val access = AccessUrlStore.load(context) ?: return@withContext SyncResult.NotConnected

        val windowStart = if (full) {
            SyncState.initialWindowStart()
        } else {
            SyncState.incrementalWindowStart(context)
        }

        when (val result = client.fetchAccounts(access, startDate = windowStart)) {
            is SimpleFinClient.FetchResult.Forbidden -> {
                SyncState.recordError(context, "Access was revoked.")
                SyncResult.NeedsReconnect
            }

            is SimpleFinClient.FetchResult.PaymentRequired -> {
                SyncState.recordError(context, "The SimpleFIN Bridge needs payment.")
                SyncResult.PaymentRequired
            }

            is SimpleFinClient.FetchResult.Failed -> {
                SyncState.recordError(context, result.message)
                SyncResult.Failed(result.message)
            }

            is SimpleFinClient.FetchResult.Success -> {
                val counts = store(result.data, windowStart)
                SyncState.recordSuccess(context)
                SyncResult.Ok(
                    accounts = counts.first,
                    transactions = counts.second,
                    // Displayed verbatim but never trusted: these strings come from the
                    // server, so they are shown as plain text in a Text composable and never
                    // parsed, formatted, or turned into a link.
                    warnings = result.data.errors.map { it.msg },
                )
            }
        }
    }

    private suspend fun store(data: SfAccountSet, windowStart: Long): Pair<Int, Int> {
        val connectionNames = data.connections.associate { it.connId to it.name }

        // Errors are attached to the account they name, or to every account on the
        // connection they name, so a single broken bank shows up on its own rows instead of
        // as one banner covering accounts that are perfectly fine.
        val accountProblems = HashMap<String, String>()
        for (error in data.errors) {
            when {
                error.accountId != null -> accountProblems[error.accountId] = error.msg
                error.connId != null -> data.accounts
                    .filter { it.connId == error.connId }
                    .forEach { accountProblems[it.id] = error.msg }
            }
        }

        val accounts = data.accounts.map { account ->
            AccountEntity(
                id = account.id,
                name = account.name,
                connId = account.connId,
                connName = account.connId?.let(connectionNames::get),
                currency = account.currency,
                balanceCents = account.balanceCents,
                availableCents = account.availableBalanceCents,
                balanceDate = account.balanceDate,
                problem = accountProblems[account.id],
            )
        }

        val transactions = data.accounts.flatMap { account ->
            account.transactions.map { txn ->
                TransactionEntity(
                    accountId = account.id,
                    txnId = txn.id,
                    at = txn.effectiveAt,
                    posted = txn.posted,
                    amountCents = txn.amountCents,
                    description = txn.description,
                    pending = txn.pending,
                )
            }
        }

        if (accounts.isNotEmpty()) dao.upsertAccounts(accounts)
        if (transactions.isNotEmpty()) dao.upsertTransactions(transactions)

        for (account in data.accounts) {
            val seen = account.transactions.map { it.id }
            if (seen.isEmpty()) {
                dao.sweepAllPending(account.id, windowStart)
            } else {
                // Chunked because SQLite caps the number of bound parameters in one
                // statement, and a busy month on a card can pass a thousand ids.
                val kept = HashSet(seen)
                dao.sweepAllPendingExcept(account.id, windowStart, kept)
            }
        }

        return accounts.size to transactions.size
    }

    /**
     * The sweep, done as a read-then-delete rather than a `NOT IN` with a thousand binds.
     *
     * Room would happily generate the statement; SQLite would then refuse it at runtime once
     * the id list crossed its bound-parameter limit, and only on the accounts busy enough to
     * matter. Reading the pending ids back and deleting the difference has no such ceiling.
     */
    private suspend fun LedgerDao.sweepAllPendingExcept(
        accountId: String,
        windowStart: Long,
        keep: Set<String>,
    ) {
        val stale = allTransactions()
            .filter { it.pending && it.accountId == accountId && it.at >= windowStart }
            .map { it.txnId }
            .filterNot(keep::contains)
        stale.chunked(400).forEach { chunk -> sweepPending(accountId, windowStart, chunk.toList()) }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        AccessUrlStore.clear(context)
        SyncState.clear(context)
        dao.clearTransactions()
        dao.clearAccounts()
    }

    companion object {
        @Volatile
        private var instance: LedgerRepository? = null

        fun get(context: Context): LedgerRepository = instance ?: synchronized(this) {
            instance ?: LedgerRepository(context.applicationContext).also { instance = it }
        }
    }
}
