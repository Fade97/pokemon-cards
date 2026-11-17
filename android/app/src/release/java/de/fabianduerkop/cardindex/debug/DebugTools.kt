// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.debug

import de.fabianduerkop.cardindex.data.AppContainer

/** Release builds have no debug tools; see the debug source set. */
object DebugTools {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    suspend fun addSampleData(container: AppContainer) = Unit
}
