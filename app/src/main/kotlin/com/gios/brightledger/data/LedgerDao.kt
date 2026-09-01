package com.gios.brightledger.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccounts(accounts: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransactions(transactions: List<TransactionEntity>)

    @Query("SELECT * FROM accounts ORDER BY balanceCents DESC")
    fun accounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun accountsNow(): List<AccountEntity>

    @Query(
        """
        SELECT t.accountId, t.txnId, t.at, t.posted, t.amountCents, t.description, t.pending,
               a.name AS accountName, a.currency AS currency
        FROM transactions t
        JOIN accounts a ON a.id = t.accountId
        ORDER BY t.pending DESC, t.at DESC
        LIMIT :limit
        """,
    )
    fun feed(limit: Int = 400): Flow<List<TransactionWithAccount>>

    @Query(
        """
        SELECT t.accountId, t.txnId, t.at, t.posted, t.amountCents, t.description, t.pending,
               a.name AS accountName, a.currency AS currency
        FROM transactions t
        JOIN accounts a ON a.id = t.accountId
        WHERE t.accountId = :accountId AND t.txnId = :txnId
        """,
    )
    fun transaction(accountId: String, txnId: String): Flow<TransactionWithAccount?>

    @Query("SELECT * FROM transactions ORDER BY at DESC LIMIT :limit")
    suspend fun allTransactions(limit: Int = 4000): List<TransactionEntity>

    /**
     * Drops pending rows in the window that the server no longer reports.
     *
     * A pending charge can simply disappear — an authorization hold that is released, a
     * restaurant tip adjustment that reposts under a new id. Nothing tells the app it went
     * away; it is just absent from the next response. Without this sweep those ghosts stay in
     * the feed forever and quietly inflate the month's spend.
     *
     * Scoped to the window that was actually fetched, so an incremental sync cannot delete
     * pending rows older than the range it asked about and knows nothing on.
     */
    @Query(
        """
        DELETE FROM transactions
        WHERE pending = 1
          AND accountId = :accountId
          AND at >= :windowStart
          AND txnId NOT IN (:seen)
        """,
    )
    suspend fun sweepPending(accountId: String, windowStart: Long, seen: List<String>)

    @Query("DELETE FROM transactions WHERE pending = 1 AND accountId = :accountId AND at >= :windowStart")
    suspend fun sweepAllPending(accountId: String, windowStart: Long)

    @Query("DELETE FROM transactions")
    suspend fun clearTransactions()

    @Query("DELETE FROM accounts")
    suspend fun clearAccounts()
}

@Database(
    entities = [AccountEntity::class, TransactionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        @Volatile
        private var instance: LedgerDatabase? = null

        fun get(context: Context): LedgerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LedgerDatabase::class.java,
                "brightledger.db",
            )
                // The database is a cache of a server that still has everything. If a future
                // schema change makes migration awkward, throwing the cache away and
                // re-syncing is both correct and cheap — there is no local-only data here.
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
