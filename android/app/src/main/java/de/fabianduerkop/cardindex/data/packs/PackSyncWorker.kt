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
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Runs [PackManager.sync] in the background: once when the user changes languages, and once a day to poll the
 * catalog (`If-None-Match`, so an unchanged catalog costs a `304`). Downloads survive the app being closed and resume
 * with `Range` after a lost connection. Both wait for an unmetered network unless the user allowed mobile data (#137).
 */
class PackSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val packs = applicationContext.appContainer.packs
        return when (packs.sync(poll = inputData.getBoolean(KEY_POLL, false))) {
            SyncOutcome.Done -> Result.success()
            is SyncOutcome.Retry -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure().also { packs.gaveUp() }
            is SyncOutcome.Failed -> Result.failure()
        }
    }

    companion object {
        private const val KEY_POLL = "poll"
        internal const val SYNC = "pack-sync"
        internal const val POLL = "pack-poll"
        private const val MAX_ATTEMPTS = 8

        /**
         * Packs are large (all seven about 110 MB), so they wait for an unmetered network unless the user allowed
         * mobile data in Settings (#137). The catalog poll follows the same rule: it downloads updates right away.
         */
        fun networkType(mobileData: Boolean): NetworkType = if (mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED

        private fun constraints(mobileData: Boolean) =
            Constraints.Builder().setRequiredNetworkType(networkType(mobileData)).build()

        private fun mobileData(context: Context) = context.appContainer.settings.packsOnMobileData

        /** A sync now (when the network allows). A sync already running finishes first; this one follows it. */
        fun enqueue(
            context: Context,
            poll: Boolean = false,
            mobileData: Boolean = mobileData(context),
            policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE,
        ) {
            val request = OneTimeWorkRequestBuilder<PackSyncWorker>()
                .setConstraints(constraints(mobileData))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_POLL to poll))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SYNC, policy, request)
        }

        /**
         * The daily catalog poll. UPDATE keeps an existing schedule (and a run in progress) and only refreshes the
         * network rule, so a poll scheduled before #137 with any network moves to the current setting.
         */
        fun schedulePolling(context: Context, mobileData: Boolean = mobileData(context)) {
            val request = PeriodicWorkRequestBuilder<PackSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints(mobileData))
                .setInputData(workDataOf(KEY_POLL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(POLL, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * The mobile data switch changed. The daily poll keeps its schedule with the new network rule. A sync that is
         * waiting or running starts over under the new rule: one waiting for Wi-Fi can run now, one on mobile data
         * stops and waits for Wi-Fi. Downloads resume with `Range`, so nothing is fetched twice. Without unfinished
         * work there is nothing to move, and no sync is started.
         */
        suspend fun applyNetworkSetting(context: Context, mobileData: Boolean) {
            schedulePolling(context, mobileData)
            val unfinished = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SYNC).first()
                .any { !it.state.isFinished }
            if (unfinished) enqueue(context, mobileData = mobileData, policy = ExistingWorkPolicy.REPLACE)
        }
    }
}
