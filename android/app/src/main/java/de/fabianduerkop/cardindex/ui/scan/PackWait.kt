// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import de.fabianduerkop.cardindex.data.packs.LanguageProgress
import de.fabianduerkop.cardindex.data.packs.PackSyncState

/**
 * What the Scan screen says while no language is installed yet but the user chose some: right after the first-run
 * picker the packs are still downloading, or waiting for Wi-Fi (#137), and "no language is installed" would read as
 * an error.
 */
sealed interface PackWait {
    /** [done] of [total] bytes; [total] is 0 before the first file starts. */
    data class Downloading(val done: Long, val total: Long) : PackWait

    data object Installing : PackWait

    /** No sync is running: the download waits for a network ([wifiOnly]: an unmetered one). */
    data class Waiting(val wifiOnly: Boolean) : PackWait

    companion object {
        /**
         * Null when nothing is on its way ([pending] is empty) or the last sync failed for good: then the screen
         * keeps its "choose languages" message, and the languages screen shows the failure. A sync that stopped on
         * the network (its languages marked failed until the retry) is waiting again.
         */
        fun of(pending: Set<String>, sync: PackSyncState, mobileData: Boolean): PackWait? {
            if (pending.isEmpty()) return null
            if (!sync.running) return if (sync.failed) null else Waiting(wifiOnly = !mobileData)
            val progress = pending.mapNotNull { sync.progress[it] }.filterNot { it is LanguageProgress.Failed }
            val downloads = progress.filterIsInstance<LanguageProgress.Downloading>()
            return when {
                downloads.isNotEmpty() -> Downloading(downloads.sumOf { it.done }, downloads.sumOf { it.total })
                progress.isNotEmpty() -> Installing
                else -> Downloading(0, 0)
            }
        }
    }
}
