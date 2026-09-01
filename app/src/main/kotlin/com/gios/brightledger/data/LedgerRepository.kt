package com.gios.brightledger.data

import android.content.Context
import com.gios.brightledger.simplefin.SfAccountSet
import com.gios.brightledger.simplefin.SimpleFinClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One sync across every connected server.
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

    /** What happened to one connection. */
    sealed interface Outcome {
        data class Ok(val accounts: Int, val transactions: Int, val warnings: List<String>) :
            Outcome

        /** The server revoked us. The user has to reconnect; the cache stays put. */
        data object NeedsReconnect : Outcome

        data object PaymentRequired : Outcome

        data class Failed(val message: String) : Outcome
    }

    data class SyncReport(
        val results: Map<String, Outcome>,
        /** True when nothing is connected at all, or the vault is shut. */
        val notConnected: Boolean = false,
    ) {
        val anySucceeded: Boolean get() = results.values.any { it is Outcome.Ok }
        val warnings: List<String> get() = results.values
            .filterIsInstance<Outcome.Ok>()
            .flatMap { it.warnings }
        fun firstProblem(): Outcome? = results.values.firstOrNull { it !is Outcome.Ok }
    }

    /**
     * Syncs every connection in turn.
     *
     * Sequentially rather than in parallel, deliberately. Two or three servers is the whole
     * range here, the phone is often on cellular, and one connection at a time keeps a
     * failure attributable — a `Failed` result names the server it came from, which a
     * gathered exception from a parallel job would not.
     *
     * A connection that fails does not stop the others. That is the entire point of the
     * report: a dead Revolut broker on the home server must not stop the Bridge accounts from
     * refreshing.
     */
    suspend fun sync(full: Boolean = false): SyncReport = withContext(Dispatchers.IO) {
        Connections.migrateLegacy(context)

        val connections = Connections.all(context)
        if (connections.isEmpty()) return@withContext SyncReport(emptyMap(), notConnected = true)

        val results = LinkedHashMap<String, Outcome>()
        for (connection in connections) {
            results[connection.id] = syncOne(connection.id, full)
        }
        SyncReport(results)
    }

    /** One server. Public so the connections screen can retry a single sick one. */
    suspend fun syncOne(connectionId: String, full: Boolean = false): Outcome =
        withContext(Dispatchers.IO) {
            val access = Connections.load(context, connectionId)
                ?: return@withContext Outcome.Failed("Locked.")

            val windowStart = if (full) {
                SyncState.initialWindowStart()
            } else {
                SyncState.incrementalWindowStart(context, connectionId)
            }

            when (val result = client.fetchAccounts(access, startDate = windowStart)) {
                is SimpleFinClient.FetchResult.Forbidden -> {
                    SyncState.recordError(context, connectionId, "Access was revoked.")
                    Outcome.NeedsReconnect
                }

                is SimpleFinClient.FetchResult.PaymentRequired -> {
                    SyncState.recordError(context, connectionId, "The server needs payment.")
                    Outcome.PaymentRequired
                }

                is SimpleFinClient.FetchResult.Failed -> {
                    SyncState.recordError(context, connectionId, result.message)
                    Outcome.Failed(result.message)
                }

                is SimpleFinClient.FetchResult.Success -> {
                    val counts = store(connectionId, result.data, windowStart)
                    SyncState.recordSuccess(context, connectionId)
                    Outcome.Ok(
                        accounts = counts.first,
                        transactions = counts.second,
                        // Displayed verbatim but never trusted: these strings come from the
                        // server, so they are shown as plain text in a Text composable and
                        // never parsed, formatted, or turned into a link.
                        warnings = result.data.errors.map { it.msg },
                    )
                }
            }
        }

    private suspend fun store(
        connectionId: String,
        data: SfAccountSet,
        windowStart: Long,
    ): Pair<Int, Int> {
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
                id = AccountEntity.key(connectionId, account.id),
                connectionId = connectionId,
                remoteId = account.id,
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
            val key = AccountEntity.key(connectionId, account.id)
            account.transactions.map { txn ->
                TransactionEntity(
                    accountId = key,
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
            val key = AccountEntity.key(connectionId, account.id)
            val seen = account.transactions.mapTo(HashSet()) { it.id }
            if (seen.isEmpty()) {
                dao.sweepAllPending(key, windowStart)
            } else {
                sweepVanishedPending(key, windowStart, seen)
            }
        }

        return accounts.size to transactions.size
    }

    /**
     * The pending sweep, as a read-then-delete rather than one `NOT IN (...)`.
     *
     * Room would happily generate that statement; SQLite would then refuse it at runtime once
     * the id list crossed its bound-parameter limit, and only on the accounts busy enough to
     * matter. Reading the window's pending ids back and deleting the difference has no such
     * ceiling, and the delete is chunked for the same reason.
     */
    private suspend fun sweepVanishedPending(
        accountKey: String,
        windowStart: Long,
        seen: Set<String>,
    ) {
        val stale = dao.pendingIn(accountKey, windowStart)
            .map { it.txnId }
            .filterNot(seen::contains)
        stale.chunked(400).forEach { chunk -> dao.deleteTransactions(accountKey, chunk) }
    }

    /** Forgets one server and everything it cached. The others are untouched. */
    suspend fun disconnect(connectionId: String) = withContext(Dispatchers.IO) {
        dao.deleteTransactionsFor(connectionId)
        dao.deleteAccountsFor(connectionId)
        Connections.remove(context, connectionId)
    }

    suspend fun disconnectAll() = withContext(Dispatchers.IO) {
        Connections.clear(context)
        SyncState.clearAll(context)
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
