package com.gios.brightledger.simplefin

import org.json.JSONArray
import org.json.JSONObject

/**
 * The SimpleFIN v2.0.0 wire types, and the parser that turns a response body into them.
 *
 * Hand-rolled against `org.json` rather than a serialization library, for two reasons. The
 * payload is four small shapes and will not grow — the protocol has had two versions in a
 * decade. And every field here comes from a server the user chose, so each one is treated
 * as untrusted input that may be absent, null, or the wrong type; a parser that throws on
 * a missing optional would take the whole sync down for one bad row.
 *
 * The rule throughout: a malformed *row* is dropped, a malformed *document* is an error.
 */

/** A structured error from `errlist`. See the spec's `prefix.[subcode]` scheme. */
data class SfError(
    val code: String,
    val msg: String,
    val connId: String? = null,
    val accountId: String? = null,
) {
    /**
     * `gen`, `con` or `act`. Unknown subcodes fall back to the bare prefix, which is what
     * the spec asks consumers to do rather than treating a new subcode as unrecognised.
     */
    val prefix: String get() = code.substringBefore('.', code)

    /** True for the errors that mean "reconnect this bank", not "try again later". */
    val needsReauth: Boolean get() = code == "con.auth" || code == "gen.auth"
}

/** One login at one institution. Two logins at the same bank are two Connections. */
data class SfConnection(
    val connId: String,
    val name: String,
    val orgId: String,
    val orgUrl: String? = null,
    val sfinUrl: String? = null,
)

data class SfTransaction(
    val id: String,
    val posted: Long,
    val amountCents: Long,
    val description: String,
    val transactedAt: Long? = null,
    val pending: Boolean = false,
) {
    /**
     * When it happened, falling back to when it posted.
     *
     * A pending transaction may carry `posted = 0`, which the spec permits, so ordering on
     * `posted` alone drops every pending row to the bottom of the feed — the opposite of
     * where the thing you just bought belongs.
     */
    val effectiveAt: Long get() = transactedAt ?: posted.takeIf { it > 0L } ?: 0L
}

data class SfAccount(
    val id: String,
    val name: String,
    val connId: String?,
    val currency: String,
    val balanceCents: Long,
    val availableBalanceCents: Long?,
    val balanceDate: Long,
    val transactions: List<SfTransaction> = emptyList(),
)

data class SfAccountSet(
    val errors: List<SfError>,
    val connections: List<SfConnection>,
    val accounts: List<SfAccount>,
)

object SfParser {

    /** Throws only when the body is not a JSON object at all. */
    fun parseAccountSet(body: String): SfAccountSet {
        val root = JSONObject(body)
        return SfAccountSet(
            errors = parseErrors(root),
            connections = root.optJSONArray("connections").mapNotNull(::parseConnection),
            accounts = root.optJSONArray("accounts").mapNotNull(::parseAccount),
        )
    }

    /**
     * `errlist` in v2, with a fallback to the v1 `errors` array of bare strings.
     *
     * v2.0.0 deprecated `errors` but did not remove it, and a server may report version 2
     * while still populating the old field, so both are read and merged.
     */
    private fun parseErrors(root: JSONObject): List<SfError> {
        val structured = root.optJSONArray("errlist").mapNotNull { item ->
            val code = item.optString("code").ifBlank { return@mapNotNull null }
            SfError(
                code = code,
                msg = item.optString("msg").ifBlank { code },
                connId = item.optStringOrNull("conn_id"),
                accountId = item.optStringOrNull("account_id"),
            )
        }
        val legacy = buildList {
            val array = root.optJSONArray("errors") ?: return@buildList
            for (index in 0 until array.length()) {
                val text = array.optString(index).takeIf { it.isNotBlank() } ?: continue
                add(SfError(code = "gen.", msg = text))
            }
        }
        return structured + legacy
    }

    private fun parseConnection(item: JSONObject): SfConnection? {
        val connId = item.optStringOrNull("conn_id") ?: return null
        return SfConnection(
            connId = connId,
            name = item.optStringOrNull("name") ?: connId,
            orgId = item.optStringOrNull("org_id").orEmpty(),
            orgUrl = item.optStringOrNull("org_url"),
            sfinUrl = item.optStringOrNull("sfin_url"),
        )
    }

    private fun parseAccount(item: JSONObject): SfAccount? {
        val id = item.optStringOrNull("id") ?: return null
        // A balance that will not parse is not an account worth showing: every screen in the
        // app leads with one, and a zero placeholder would read as a real empty account.
        val balance = Money.parseCents(item.optStringOrNull("balance")) ?: return null
        return SfAccount(
            id = id,
            name = item.optStringOrNull("name") ?: id,
            connId = item.optStringOrNull("conn_id"),
            currency = item.optStringOrNull("currency") ?: "USD",
            balanceCents = balance,
            availableBalanceCents = Money.parseCents(item.optStringOrNull("available-balance")),
            balanceDate = item.optLong("balance-date", 0L),
            transactions = item.optJSONArray("transactions").mapNotNull(::parseTransaction),
        )
    }

    private fun parseTransaction(item: JSONObject): SfTransaction? {
        val id = item.optStringOrNull("id") ?: return null
        val amount = Money.parseCents(item.optStringOrNull("amount")) ?: return null
        return SfTransaction(
            id = id,
            posted = item.optLong("posted", 0L),
            amountCents = amount,
            description = item.optStringOrNull("description").orEmpty(),
            transactedAt = if (item.has("transacted_at")) {
                item.optLong("transacted_at").takeIf { it > 0L }
            } else {
                null
            },
            pending = item.optBoolean("pending", false),
        )
    }

    /**
     * `optString` returns the literal text "null" for a JSON null, which then flows into the
     * UI as an account named null. Everything reads strings through here instead.
     */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private inline fun <T> JSONArray?.mapNotNull(transform: (JSONObject) -> T?): List<T> {
        val array = this ?: return emptyList()
        val out = ArrayList<T>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            transform(item)?.let(out::add)
        }
        return out
    }
}
