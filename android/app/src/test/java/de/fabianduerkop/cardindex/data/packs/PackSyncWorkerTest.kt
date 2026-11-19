// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import de.fabianduerkop.cardindex.data.settings.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Language packs wait for an unmetered network unless the user allows mobile data (#137). */
@RunWith(AndroidJUnit4::class)
class PackSyncWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val config = Configuration.Builder().setMinimumLoggingLevel(Log.WARN).setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    private fun work(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun unfinished(name: String) = work(name).filter { !it.state.isFinished }

    @Test
    fun mobileDataIsOffByDefaultAndPersists() {
        val settings = AppSettings(context)
        assertFalse(settings.packsOnMobileData)
        settings.packsOnMobileData = true
        assertTrue(AppSettings(context).packsOnMobileData)
    }

    @Test
    fun networkTypeFollowsTheSwitch() {
        assertEquals(NetworkType.UNMETERED, PackSyncWorker.networkType(mobileData = false))
        assertEquals(NetworkType.CONNECTED, PackSyncWorker.networkType(mobileData = true))
    }

    @Test
    fun syncAndPollWaitForUnmeteredByDefault() {
        PackSyncWorker.enqueue(context, mobileData = false)
        PackSyncWorker.schedulePolling(context, mobileData = false)

        // Constraints are never met in the test WorkManager, so the sync stays waiting.
        val sync = unfinished(PackSyncWorker.SYNC).single()
        assertEquals(WorkInfo.State.ENQUEUED, sync.state)
        assertEquals(NetworkType.UNMETERED, sync.constraints.requiredNetworkType)
        assertEquals(NetworkType.UNMETERED, unfinished(PackSyncWorker.POLL).single().constraints.requiredNetworkType)
    }

    @Test
    fun turningMobileDataOnMovesAWaitingSyncAndThePoll() = runTest {
        PackSyncWorker.enqueue(context, mobileData = false)
        PackSyncWorker.schedulePolling(context, mobileData = false)
        val pollId = unfinished(PackSyncWorker.POLL).single().id

        PackSyncWorker.applyNetworkSetting(context, mobileData = true)

        val sync = unfinished(PackSyncWorker.SYNC).single()
        assertEquals(NetworkType.CONNECTED, sync.constraints.requiredNetworkType)
        val poll = unfinished(PackSyncWorker.POLL).single()
        assertEquals(NetworkType.CONNECTED, poll.constraints.requiredNetworkType)
        assertEquals("the daily poll keeps its schedule", pollId, poll.id)

        PackSyncWorker.applyNetworkSetting(context, mobileData = false)
        assertEquals(NetworkType.UNMETERED, unfinished(PackSyncWorker.SYNC).single().constraints.requiredNetworkType)
        assertEquals(NetworkType.UNMETERED, unfinished(PackSyncWorker.POLL).single().constraints.requiredNetworkType)
    }

    @Test
    fun noPendingSyncMeansNoNewOne() = runTest {
        PackSyncWorker.applyNetworkSetting(context, mobileData = true)

        assertTrue(unfinished(PackSyncWorker.SYNC).isEmpty())
        assertEquals(NetworkType.CONNECTED, unfinished(PackSyncWorker.POLL).single().constraints.requiredNetworkType)
    }

    @Test
    fun newSyncsQueueBehindAWaitingOneWithTheSameRule() {
        PackSyncWorker.enqueue(context, mobileData = false)
        PackSyncWorker.enqueue(context, mobileData = false)

        val pending = unfinished(PackSyncWorker.SYNC)
        assertEquals(2, pending.size)
        assertTrue(pending.all { it.constraints.requiredNetworkType == NetworkType.UNMETERED })
    }
}
