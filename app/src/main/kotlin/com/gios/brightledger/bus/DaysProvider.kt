package com.gios.brightledger.bus

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.gios.brightledger.data.LedgerDatabase
import com.gios.brightledger.data.SyncState
import com.gios.brightledger.ledger.Recurring
import java.time.LocalDate
import java.time.ZoneId

/**
 * The journal bus: a read-only window other Light apps can look through.
 *
 * LightNotebook's journal wants to say what was spent on a day and what is about to be
 * charged, and the polite way to hand that over on this phone is an exported, query-only
 * ContentProvider — no service to bind, no file to share, no broadcast to miss. The
 * Notebook consumer is built against exactly the paths and columns below, so they are a
 * pinned contract: add columns if you must, never rename or reorder these.
 *
 * Paths, all under `content://com.gios.brightledger.days`:
 *
 *  - `spending/<yyyy-MM-dd>` — one row per transaction on that **local** calendar day
 *    (the phone's current zone; local-midnight-to-local-midnight, so DST days are 23 or
 *    25 hours and nothing leaks across midnight). Columns:
 *    `postedAt` (Long, epoch **ms** — the transaction's effective timestamp: SimpleFIN's
 *    `transacted_at` when the server sent one, otherwise `posted`),
 *    `merchant` (String, the bank's display description, verbatim),
 *    `amountCents` (Long, sign **as stored: charges are negative, credits positive** —
 *    the SimpleFIN convention this database keeps),
 *    `account` (String, account display name),
 *    `pending` (Int, 0 or 1).
 *
 *  - `bills/upcoming` — projected next occurrences of every detected recurring charge in
 *    the next 35 days, soonest first. Columns:
 *    `dueEpochDay` (Long, `LocalDate.toEpochDay` of the predicted local due date),
 *    `merchant` (String), `amountCents` (Long, negative — these are charges),
 *    `cadenceDays` (Int, the matched cadence's nominal interval: 7/14/30/91/365).
 *
 *  - `status` — one row, `syncedAt` (Long, epoch ms of the last successful sync of any
 *    connection, 0 if never). Consumers should show staleness from this rather than
 *    assuming the numbers are fresh.
 *
 * Read-only in the strong sense: insert, update and delete throw. Reads never throw —
 * a bad date, a missing table, anything unexpected comes back as an **empty cursor**,
 * because a journal page must not crash over a ledger hiccup. Everything served here is
 * the Room cache, which is not inside the PIN vault (only the credential is), so reads
 * work while the app is locked; they simply go stale, which `status` reports honestly.
 */
class DaysProvider : ContentProvider() {

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "spending/*", CODE_SPENDING)
        addURI(AUTHORITY, "bills/upcoming", CODE_BILLS)
        addURI(AUTHORITY, "status", CODE_STATUS)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = try {
        when (matcher.match(uri)) {
            CODE_SPENDING -> spending(uri.lastPathSegment.orEmpty())
            CODE_BILLS -> bills()
            CODE_STATUS -> status()
            else -> MatrixCursor(emptyArray())
        }
    } catch (t: Throwable) {
        // An empty page beats a dead journal. The columns still match the path asked for,
        // so a consumer iterating an empty result stays type-correct.
        when (matcher.match(uri)) {
            CODE_SPENDING -> MatrixCursor(SPENDING_COLUMNS)
            CODE_BILLS -> MatrixCursor(BILLS_COLUMNS)
            CODE_STATUS -> MatrixCursor(STATUS_COLUMNS)
            else -> MatrixCursor(emptyArray())
        }
    }

    private fun spending(segment: String): Cursor {
        val cursor = MatrixCursor(SPENDING_COLUMNS)
        val day = DayWindow.parse(segment) ?: return cursor
        val context = context ?: return cursor
        val zone = ZoneId.systemDefault()

        val rows = LedgerDatabase.get(context).dao().postedBetween(
            startSeconds = DayWindow.startSeconds(day, zone),
            endSeconds = DayWindow.endSeconds(day, zone),
        )
        for (row in rows) {
            cursor.addRow(
                arrayOf<Any?>(
                    row.at * 1000L,
                    row.description,
                    row.amountCents,
                    row.accountName,
                    if (row.pending) 1 else 0,
                ),
            )
        }
        return cursor
    }

    private fun bills(): Cursor {
        val cursor = MatrixCursor(BILLS_COLUMNS)
        val context = context ?: return cursor
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        // Settled rows only, same as the Recurring screen: a pending amount can still
        // change, and the detector's medians should not learn from it.
        val settled = LedgerDatabase.get(context).dao().settledTransactions()
        val charges = Recurring.detect(
            settled.map { Recurring.Row(it.at, it.amountCents, it.description) },
        )

        val rows = ArrayList<Array<Any?>>()
        for (charge in charges) {
            for (epochDay in DayWindow.dueEpochDays(
                nextAtSeconds = charge.nextAt,
                cadenceDays = charge.cadence.approxDays,
                today = today,
                zone = zone,
            )) {
                rows.add(
                    arrayOf<Any?>(
                        epochDay,
                        charge.name,
                        charge.amountCents,
                        charge.cadence.approxDays,
                    ),
                )
            }
        }
        rows.sortBy { it[0] as Long }
        rows.forEach(cursor::addRow)
        return cursor
    }

    private fun status(): Cursor {
        val cursor = MatrixCursor(STATUS_COLUMNS)
        val context = context ?: return cursor
        cursor.addRow(arrayOf<Any?>(SyncState.lastSuccessAny(context) * 1000L))
        return cursor
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_SPENDING -> "vnd.android.cursor.dir/vnd.$AUTHORITY.spending"
        CODE_BILLS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.bills"
        CODE_STATUS -> "vnd.android.cursor.item/vnd.$AUTHORITY.status"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("The BrightLedger day bus is read-only.")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("The BrightLedger day bus is read-only.")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("The BrightLedger day bus is read-only.")

    companion object {
        const val AUTHORITY = "com.gios.brightledger.days"

        private const val CODE_SPENDING = 1
        private const val CODE_BILLS = 2
        private const val CODE_STATUS = 3

        val SPENDING_COLUMNS = arrayOf("postedAt", "merchant", "amountCents", "account", "pending")
        val BILLS_COLUMNS = arrayOf("dueEpochDay", "merchant", "amountCents", "cadenceDays")
        val STATUS_COLUMNS = arrayOf("syncedAt")
    }
}
