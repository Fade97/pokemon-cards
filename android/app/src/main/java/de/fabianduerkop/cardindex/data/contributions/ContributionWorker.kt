// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.contributions

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.fabianduerkop.cardindex.data.appContainer
import java.util.concurrent.TimeUnit

/**
 * Sends queued contributions and withdrawals when the phone is online (offline scans wait here). The backoff starts
 * at a minute, the backend's `Retry-After` for its upload limit.
 */
class ContributionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (applicationContext.appContainer.contributions.processQueue()) {
        QueueOutcome.Done -> Result.success()
        QueueOutcome.Retry -> Result.retry()
    }

    companion object {
        private const val NAME = "contributions"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ContributionWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
