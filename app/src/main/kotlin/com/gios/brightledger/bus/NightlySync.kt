package com.gios.brightledger.bus

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gios.brightledger.data.LedgerRepository
import com.gios.brightledger.data.Prefs
import com.gios.brightledger.vault.Vault
import java.util.concurrent.TimeUnit

/**
 * The once-a-day background refresh that keeps the journal bus worth reading.
 *
 * Without it, [DaysProvider] serves whatever was cached the last time someone opened the
 * app — accurate but stale, and a journal that shows last Tuesday's spending as today's is
 * worse than one that shows nothing. A periodic WorkManager job constrained to
 * **charging + network** lands it on the overnight charge, which on this phone is the one
 * reliable daily ritual, and costs nothing on a battery that is being filled anyway.
 *
 * The PIN case is handled by declining, not by weakening anything. With no PIN set,
 * [Vault.init] unwraps the key with the AndroidKeyStore layer alone and the sync runs
 * headless exactly as it does in the foreground. With a PIN set the credential is sealed
 * until a human types the digits, so the worker finds no key and returns without a fetch —
 * `status.syncedAt` then simply stays at the last real sync, which is the honest answer.
 */
object NightlySync {

    private const val WORK_NAME = "nightly-sync"

    /**
     * Enqueues or cancels the job to match the setting. Safe to call on every launch:
     * [ExistingPeriodicWorkPolicy.KEEP] leaves an already-scheduled job's timing alone.
     */
    fun ensureScheduled(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!Prefs.nightlySync(context)) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<Worker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    class Worker(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            // The toggle is re-checked at run time as well as at scheduling time, so a job
            // already queued when the setting was switched off does nothing.
            if (!Prefs.nightlySync(applicationContext)) return Result.success()

            // Cold process: WorkManager woke us, MainActivity never ran. init() is what
            // unwraps the key when no PIN is set; when one is set it leaves the vault
            // locked, which the key check below turns into a quiet skip.
            if (!Vault.ready) Vault.init(applicationContext)
            if (Vault.key() == null) return Result.success()

            // The ordinary sync path, failures and all. A failed fetch records its error
            // per connection and leaves the cache untouched — same contract as a
            // foreground refresh — so there is nothing to retry aggressively here; the
            // next night, or the next app open, is the retry.
            LedgerRepository.get(applicationContext).sync()
            return Result.success()
        }
    }
}
