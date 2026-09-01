package com.gios.brightledger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.brightledger.data.AccessUrlStore
import com.gios.brightledger.data.AccountEntity
import com.gios.brightledger.data.LedgerRepository
import com.gios.brightledger.data.SyncState
import com.gios.brightledger.data.TransactionWithAccount
import com.gios.brightledger.ledger.Recurring
import com.gios.brightledger.ledger.Summary
import com.gios.brightledger.simplefin.AccessUrl
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

    private val _needsReconnect = MutableStateFlow(false)
    val needsReconnect: StateFlow<Boolean> = _needsReconnect.asStateFlow()

    private val _connected = MutableStateFlow(AccessUrlStore.isConnected(app))
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun lastSyncSeconds(): Long = SyncState.lastSuccess(getApplication())

    fun serverHost(): String? = AccessUrlStore.hostHint(getApplication())

    fun dismissMessage() {
        _message.value = null
    }

    fun refresh(full: Boolean = false) {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            when (val result = repo.sync(full = full)) {
                is LedgerRepository.SyncResult.Ok -> {
                    _needsReconnect.value = false
                    // Server-side warnings are surfaced once, not stored. A degraded
                    // connection reports the same string on every sync, and a dialog that
                    // reappears on every pull is one the user learns to dismiss unread.
                    result.warnings.firstOrNull()?.let { _message.value = it }
                }

                is LedgerRepository.SyncResult.NeedsReconnect -> {
                    _needsReconnect.value = true
                    _message.value = "This connection was revoked. Reconnect from Settings."
                }

                is LedgerRepository.SyncResult.PaymentRequired ->
                    _message.value = "The SimpleFIN Bridge is refusing requests until its " +
                        "subscription is renewed. That is between you and the Bridge — " +
                        "BrightLedger has no billing."

                is LedgerRepository.SyncResult.NotConnected -> _connected.value = false

                is LedgerRepository.SyncResult.Failed -> _message.value = result.message
            }
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
    fun connect(token: String, onDone: (Boolean) -> Unit) {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { client.claim(token.trim()) }
            var ok = false
            when (outcome) {
                is SimpleFinClient.ClaimResult.Success ->
                    ok = saveClaim(outcome, outcome.accessUrl.rawForStorage())

                is SimpleFinClient.ClaimResult.AlreadyClaimed -> _message.value =
                    "That token was already used. If you did not use it, treat it as " +
                        "compromised: delete it on the Bridge and make a new one."

                is SimpleFinClient.ClaimResult.Failed -> _message.value = outcome.message
            }
            _syncing.value = false
            if (ok) {
                _connected.value = true
                _needsReconnect.value = false
                refresh(full = true)
            }
            onDone(ok)
        }
    }

    private fun saveClaim(result: SimpleFinClient.ClaimResult.Success, raw: String): Boolean {
        val saved = AccessUrlStore.save(getApplication(), result.accessUrl, raw)
        if (!saved) {
            _message.value = "Connected, but the credential could not be saved because the " +
                "app is locked. Unlock and connect again with a new token."
        }
        return saved
    }

    fun disconnect(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.disconnect()
            _connected.value = false
            _needsReconnect.value = false
            onDone()
        }
    }
}
