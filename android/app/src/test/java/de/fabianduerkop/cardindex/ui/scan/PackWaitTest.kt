// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import de.fabianduerkop.cardindex.data.packs.LanguageProgress
import de.fabianduerkop.cardindex.data.packs.PackSyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class PackWaitTest {
    @Test
    fun nothingChosen_noWait() {
        assertEquals(null, PackWait.of(emptySet(), PackSyncState(running = true), mobileData = false))
    }

    @Test
    fun downloadsAddUp() {
        val sync = PackSyncState(
            running = true,
            progress = mapOf(
                "en" to LanguageProgress.Downloading(10, 100),
                "de" to LanguageProgress.Downloading(5, 50),
                "fr" to LanguageProgress.Downloading(1, 1), // not chosen, e.g. an update
            ),
        )
        assertEquals(PackWait.Downloading(15, 150), PackWait.of(setOf("en", "de"), sync, mobileData = false))
    }

    @Test
    fun installingAfterTheDownload() {
        val sync = PackSyncState(running = true, progress = mapOf("en" to LanguageProgress.Installing))
        assertEquals(PackWait.Installing, PackWait.of(setOf("en"), sync, mobileData = false))
    }

    @Test
    fun aRunningSyncWithoutProgressIsStarting() {
        assertEquals(PackWait.Downloading(0, 0), PackWait.of(setOf("en"), PackSyncState(running = true), mobileData = false))
    }

    @Test
    fun noSync_waitsForTheRightNetwork() {
        assertEquals(PackWait.Waiting(wifiOnly = true), PackWait.of(setOf("en"), PackSyncState(), mobileData = false))
        assertEquals(PackWait.Waiting(wifiOnly = false), PackWait.of(setOf("en"), PackSyncState(), mobileData = true))
        // A sync that stopped halfway keeps its last progress; it is waiting again, not downloading.
        val stopped = PackSyncState(progress = mapOf("en" to LanguageProgress.Downloading(10, 100)), error = "network error")
        assertEquals(PackWait.Waiting(wifiOnly = true), PackWait.of(setOf("en"), stopped, mobileData = false))
    }

    @Test
    fun aFailedDownloadLeavesTheNormalMessage() {
        val sync = PackSyncState(progress = mapOf("en" to LanguageProgress.Failed("bad checksum")))
        assertEquals(null, PackWait.of(setOf("en"), sync, mobileData = false))
    }
}
