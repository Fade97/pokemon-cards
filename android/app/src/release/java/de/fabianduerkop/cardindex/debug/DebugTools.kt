// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.debug

import de.fabianduerkop.cardindex.data.AppContainer
import de.fabianduerkop.cardindex.data.scan.ReviewQueueRepository

/** Release builds have no debug tools; see the debug source set. */
object DebugTools {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    suspend fun addSampleData(container: AppContainer) = Unit

    @Suppress("UNUSED_PARAMETER")
    suspend fun addSampleReviewItems(queue: ReviewQueueRepository) = Unit
}
