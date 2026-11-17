// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.fabianduerkop.cardindex.data.appContainer
import java.util.concurrent.TimeUnit

/**
 * Runs [PackManager.sync] in the background: once when the user changes languages, and once a day to poll the
 * catalog (`If-None-Match`, so an unchanged catalog costs a `304`). Downloads survive the app being closed and resume
 * with `Range` after a lost connection.
 */
class PackSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val packs = applicationContext.appContainer.packs
        return when (packs.sync(poll = inputData.getBoolean(KEY_POLL, false))) {
            SyncOutcome.Done -> Result.success()
            is SyncOutcome.Retry -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            is SyncOutcome.Failed -> Result.failure()
        }
    }

    companion object {
        private const val KEY_POLL = "poll"
        private const val SYNC = "pack-sync"
        private const val POLL = "pack-poll"
        private const val MAX_ATTEMPTS = 8

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** A sync now (when online). A sync already running finishes first; this one follows it. */
        fun enqueue(context: Context, poll: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<PackSyncWorker>()
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_POLL to poll))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SYNC, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** The daily catalog poll; keeps an existing schedule. */
        fun schedulePolling(context: Context) {
            val request = PeriodicWorkRequestBuilder<PackSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(network)
                .setInputData(workDataOf(KEY_POLL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(POLL, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
