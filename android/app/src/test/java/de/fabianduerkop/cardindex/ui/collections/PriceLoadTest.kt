// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.prices.RefreshResult
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.io.IOException
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class PriceLoadTest {
    private val cached = CachedPriceEntity(
        cardId = "sv03.5-025",
        source = PriceSource.CARDMARKET,
        variant = PriceVariant.HOLO,
        currency = "EUR",
        trend = 120,
        low = null,
        avg = null,
        priceDate = null,
        fetchedAt = Instant.EPOCH,
    )

    @Test
    fun refreshResult_mapsToTheLoadState() {
        assertEquals(PriceLoad.DONE, RefreshResult.Updated(0).toLoad())
        assertEquals(PriceLoad.FAILED, RefreshResult.Failed(0, IOException("offline")).toLoad())
    }

    @Test
    fun failedRefreshWithoutACachedPrice_isAnErrorAndNotNoPrice() {
        assertEquals(NoPrice.FAILED, noPrice(emptyList(), PriceLoad.FAILED))
        assertEquals(NoPrice.LOADING, noPrice(emptyList(), PriceLoad.LOADING))
        assertEquals(NoPrice.NONE, noPrice(emptyList(), PriceLoad.DONE))
    }

    @Test
    fun cachedPricesForOtherPrintings_meanNoPriceForThisOne_whateverTheRefreshDid() {
        PriceLoad.entries.forEach { assertEquals(NoPrice.NONE, noPrice(listOf(cached), it)) }
    }
}
