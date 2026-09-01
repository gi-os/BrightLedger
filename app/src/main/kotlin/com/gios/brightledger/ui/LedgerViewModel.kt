package com.gios.brightledger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.brightledger.data.AccountEntity
import com.gios.brightledger.data.Connections
import com.gios.brightledger.data.LedgerRepository
import com.gios.brightledger.data.SyncState
import com.gios.brightledger.data.TransactionWithAccount
import com.gios.brightledger.ledger.Recurring
import com.gios.brightledger.ledger.Summary
import com.gios.brightledger.simplefin.SimpleFinClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

class LedgerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LedgerRepository.get(app)
    private val client = SimpleFinClient()

    val accounts: StateFlow<List<AccountEntity>> = repo.dao.accounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val feed: StateFlow<List<TransactionWithAccount>> = repo.dao.feed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Recomputed from the feed rather than stored.
     *
     * Both the totals and the recurring list are cheap pure functions over rows already in
     * memory, and deriving them means they can never disagree with what the list underneath
     * is showing — which is exactly the bug that a cached summary column produces the first
     * time a sync half-fails.
     */
    val totals: StateFlow<Summary.Totals> = repo.dao.feed()
        .map { rows ->
            Summary.of(
                rows = rows.map { Summary.Row(it.at, it.amountCents, it.pending) },
                now = Instant.now(),
                zone = ZoneId.systemDefault(),
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            Summary.Totals(0, 0, 0, 0),
        )

    val recurring: StateFlow<List<Recurring.Charge>> = repo.dao.feed()
        .map { rows ->
            withContext(Dispatchers.Default) {
                Recurring.detect(
                    rows.filterNot { it.pending }
                        .map { Recurring.Row(it.at, it.amountCents, it.description) },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _connections = MutableStateFlow(Connections.all(app))
    val connections: StateFlow<List<Connections.Connection>> = _connections.asStateFlow()

    private val _connected = MutableStateFlow(Connections.isConnected(app))
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _needsReconnect = MutableStateFlow<Set<String>>(emptySet())
    val needsReconnect: StateFlow<Set<String>> = _needsReconnect.asStateFlow()

    fun lastSyncSeconds(): Long = SyncState.lastSuccessAny(getApplication())

    fun lastSyncFor(connectionId: String): Long =
        SyncState.lastSuccess(getApplication(), connectionId)

    fun lastErrorFor(connectionId: String): String? =
        SyncState.lastError(getApplication(), connectionId)

    /**
     * Re-reads the connection list, then syncs.
     *
     * Called when the vault opens. The list is built at construction, which for a PIN-locked
     * install is before any credential is readable, so without this the app would still
     * believe it had none.
     */
    fun onVaultOpened() {
        refreshConnections()
        refresh()
    }

    fun dismissMessage() {
        _message.value = null
    }

    private fun refreshConnections() {
        _connections.value = Connections.all(getApplication())
        _connected.value = Connections.isConnected(getApplication())
    }

    fun refresh(full: Boolean = false) {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            val report = repo.sync(full = full)
            refreshConnections()

            if (report.notConnected) {
                _syncing.value = false
                return@launch
            }

            _needsReconnect.value = report.results
                .filterValues { it is LedgerRepository.Outcome.NeedsReconnect }
                .keys

            // One message, not one per connection. Three servers having the same network
            // problem is one fact, and a dialog per server is a dialog the user dismisses
            // unread. Server-side warnings come first because they say something specific.
            val warning = report.warnings.firstOrNull()
            val problem = report.firstProblem()
            _message.value = when {
                warning != null -> warning
                problem is LedgerRepository.Outcome.NeedsReconnect ->
                    "A connection was revoked. Reconnect it from Settings."
                problem is LedgerRepository.Outcome.PaymentRequired ->
                    "A SimpleFIN server is refusing requests until its subscription is " +
                        "renewed. That is between you and that server."
                // Only worth a dialog when nothing at all got through. One sick connection
                // out of three is a line on the connections screen, not an interruption.
                problem is LedgerRepository.Outcome.Failed && !report.anySucceeded ->
                    problem.message
                else -> null
            }
            _syncing.value = false
        }
    }

    fun retry(connectionId: String) {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            val outcome = repo.syncOne(connectionId)
            if (outcome is LedgerRepository.Outcome.Failed) _message.value = outcome.message
            if (outcome !is LedgerRepository.Outcome.NeedsReconnect) {
                _needsReconnect.value = _needsReconnect.value - connectionId
            }
            refreshConnections()
            _syncing.value = false
        }
    }

    /**
     * Claims a setup token and, only on success, stores what it bought.
     *
     * The token is single-use. If the claim succeeds and the save then fails — the vault is
     * locked, say — the token is spent and the Access URL is gone, so the save is checked and
     * a failure is reported as the serious thing it is rather than as a retryable error.
     */
    fun connect(token: String, label: String? = null, onDone: (Boolean) -> Unit) {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { client.claim(token.trim()) }
            var newId: String? = null
            when (outcome) {
                is SimpleFinClient.ClaimResult.Success -> {
                    newId = Connections.add(
                        getApplication(),
                        outcome.accessUrl,
                        outcome.accessUrl.rawForStorage(),
                        label,
                    )
                    if (newId == null) {
                        _message.value = "Connected, but the credential could not be saved " +
                            "because the app is locked. Unlock and connect again with a new " +
                            "token."
                    }
                }

                is SimpleFinClient.ClaimResult.AlreadyClaimed -> _message.value =
                    "That token was already used. If you did not use it, treat it as " +
                        "compromised: delete it on the server that issued it and make a new one."

                is SimpleFinClient.ClaimResult.Failed -> _message.value = outcome.message
            }
            _syncing.value = false
            refreshConnections()

            val id = newId
            if (id != null) {
                // Only the new server, and only its full history. Re-reading a year from
                // every server because one was added would turn adding a second bank into a
                // minutes-long stall on cellular.
                _syncing.value = true
                repo.syncOne(id, full = true)
                _syncing.value = false
            }
            onDone(id != null)
        }
    }

    fun disconnect(connectionId: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.disconnect(connectionId)
            _needsReconnect.value = _needsReconnect.value - connectionId
            refreshConnections()
            onDone()
        }
    }

    fun disconnectAll(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.disconnectAll()
            _needsReconnect.value = emptySet()
            refreshConnections()
            onDone()
        }
    }
}
