package com.gios.brightledger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.brightledger.data.AccountEntity
import com.gios.brightledger.data.Connections
import com.gios.brightledger.data.LedgerRepository
import com.gios.brightledger.data.Prefs
import com.gios.brightledger.demo.Demo
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

class LedgerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LedgerRepository.get(app)
    private val client = SimpleFinClient()

    /**
     * Demo mode. Temporary, for screenshots, and scheduled for removal.
     *
     * It is applied here, at the single point every screen reads from, rather than in the
     * composables. Everything downstream — the totals header, the recurring detector, the
     * detail page — then works through its normal path on synthetic rows, so what gets
     * photographed is the real app with different numbers in it rather than a mock-up that
     * will drift away from the app.
     */
    private val _demo = MutableStateFlow(Prefs.demoMode(app))
    val demo: StateFlow<Boolean> = _demo.asStateFlow()

    /**
     * The one feed every derived value reads.
     *
     * Totals and recurring used to subscribe to `repo.dao.feed()` separately. That was fine
     * until demo mode, at which point three independent subscriptions would each need the
     * swap applied and any one of them missing it would show real numbers next to fake ones.
     */
    private val feedSource: kotlinx.coroutines.flow.Flow<List<TransactionWithAccount>> =
        combine(repo.dao.feed(), _demo) { rows, demo -> if (demo) Demo.feed() else rows }

    private val _pinned = MutableStateFlow(Prefs.pinned(app))
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    /**
     * Accounts with the pinned ones first.
     *
     * The default order is balance descending, which is a fine guess and usually the wrong
     * one: the account you check is your current account, not your biggest. Pinning is the
     * override, and it is applied here rather than in SQL because the pins are not in the
     * database — see [Prefs] for why.
     */
    val accounts: StateFlow<List<AccountEntity>> =
        combine(repo.dao.accounts(), _pinned, _demo) { real, pins, demo ->
            val list = if (demo) Demo.accounts() else real
            list.sortedWith(
                compareByDescending<AccountEntity> { it.id in pins }
                    .thenByDescending { it.balanceCents },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val feed: StateFlow<List<TransactionWithAccount>> = feedSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Recomputed from the feed rather than stored.
     *
     * Both the totals and the recurring list are cheap pure functions over rows already in
     * memory, and deriving them means they can never disagree with what the list underneath
     * is showing — which is exactly the bug that a cached summary column produces the first
     * time a sync half-fails.
     */
    val totals: StateFlow<Summary.Totals> = feedSource
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

    val recurring: StateFlow<List<Recurring.Charge>> = feedSource
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

    /**
     * Claiming a token, which is *not* the same thing as syncing.
     *
     * These were one flag until a scan silently did nothing. The home screen starts a
     * refresh on every foregrounding, a refresh can sit on the network for ninety seconds,
     * and the walk to the scanner takes less than that — so `connect` kept hitting a
     * "already busy" guard that belonged to an unrelated background job, and returned
     * without a request, a dialog, or any other trace. A user-initiated claim gets its own
     * flag, and the only thing it refuses is a second claim.
     */
    private val _claiming = MutableStateFlow(false)
    val claiming: StateFlow<Boolean> = _claiming.asStateFlow()

    /**
     * Whether amounts are masked right now.
     *
     * Session state, seeded from the saved default and reset to it by [onBackgrounded]. A
     * setting alone would be a preference; re-arming on background is what makes it useful
     * when the phone is face-up on a table, and it matches how the PIN lock already behaves.
     */
    private val _hideTotals = MutableStateFlow(Prefs.hideTotalsByDefault(app))
    val hideTotals: StateFlow<Boolean> = _hideTotals.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _connections = MutableStateFlow(Connections.all(app))
    val connections: StateFlow<List<Connections.Connection>> = _connections.asStateFlow()

    private val _connected = MutableStateFlow(Connections.isConnected(app))
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _needsReconnect = MutableStateFlow<Set<String>>(emptySet())
    val needsReconnect: StateFlow<Set<String>> = _needsReconnect.asStateFlow()

    fun lastSyncSeconds(): Long =
        if (_demo.value) Demo.lastSync() else SyncState.lastSuccessAny(getApplication())

    /** One row for the detail screen, which in demo mode is not in the database. */
    fun demoTransaction(accountId: String, txnId: String): TransactionWithAccount? =
        if (_demo.value) Demo.transaction(accountId, txnId) else null

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

    fun setDemoMode(on: Boolean) {
        Prefs.setDemoMode(getApplication(), on)
        _demo.value = on
    }

    fun togglePin(accountKey: String) {
        Prefs.togglePin(getApplication(), accountKey)
        _pinned.value = Prefs.pinned(getApplication())
    }

    /** Reveal or re-hide for this session only. The saved default is untouched. */
    fun toggleHideTotals() {
        _hideTotals.value = !_hideTotals.value
    }

    fun setHideTotalsDefault(hide: Boolean) {
        Prefs.setHideTotalsByDefault(getApplication(), hide)
        _hideTotals.value = hide
    }

    /** Called from `onStop`. Anything revealed this session goes back under. */
    fun onBackgrounded() {
        _hideTotals.value = Prefs.hideTotalsByDefault(getApplication())
        // Demo mode deliberately does *not* reset here. A screenshot session is a dozen
        // trips in and out of the app, and a flag that reset on its own would put a real
        // balance in the next shot.
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
        // Refusing is fine; refusing in silence is not. A dropped scan with no feedback is
        // indistinguishable from a broken camera, and cost an evening finding that out.
        if (_claiming.value) {
            _message.value = "Still working on the last token. Give it a moment."
            return
        }
        _claiming.value = true
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
            _claiming.value = false
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
