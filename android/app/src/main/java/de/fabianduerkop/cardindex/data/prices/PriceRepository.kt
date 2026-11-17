// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.prices

import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.db.CatalogDao
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface RefreshResult {
    /** [cards] got fresh prices; the rest were fresh enough already or unknown to the backend. */
    data class Updated(val cards: Int) : RefreshResult

    /** The backend could not be reached (offline, not deployed yet, rate-limited too often). Cached prices stay. */
    data class Failed(val cards: Int, val cause: IOException) : RefreshResult
}

/**
 * Last-known prices, cached in Room so the collection is valued offline (#11). [refresh] asks the backend only for
 * cards whose cache is older than [maxAge]: the backend imports once a night, so asking more often only spends the
 * shared rate limit (120 requests a minute per IP).
 */
class PriceRepository(
    private val dao: CatalogDao,
    private val api: PriceApi,
    private val clock: Clock = Clock.systemUTC(),
    private val maxAge: Duration = Duration.ofHours(12),
    private val sleep: suspend (millis: Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()

    /** Cards the backend doesn't know (e.g. no market product); not asked again until the app restarts. */
    private val unknown: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun observeAll(): Flow<Map<String, List<CachedPriceEntity>>> =
        dao.observeAllPrices().map { rows -> rows.groupBy { it.cardId } }

    fun observe(cardId: String): Flow<List<CachedPriceEntity>> = dao.observePrices(cardId)

    suspend fun refresh(cardIds: Collection<String>, force: Boolean = false): RefreshResult = mutex.withLock {
        val ids = cardIds.distinct().filterNot { it in unknown }
        val due = if (force) ids else stale(ids)
        var updated = 0
        for (chunk in due.chunked(PriceApi.MAX_BATCH)) {
            val batch = try {
                fetchWithRetry(chunk)
            } catch (e: IOException) {
                return RefreshResult.Failed(updated, e)
            }
            // Replace, not upsert: a printing the market stopped pricing must not keep its old amount.
            dao.replacePrices(batch.prices.keys.toList(), batch.prices.values.flatten())
            unknown += batch.missing
            updated += batch.prices.size
        }
        RefreshResult.Updated(updated)
    }

    private suspend fun stale(ids: List<String>): List<String> {
        val cutoff = clock.instant().minus(maxAge)
        // Chunked because SQLite before 3.32 (Android 8–10) allows only 999 bound parameters.
        val fresh = ids.chunked(500).flatMap { dao.priceAges(it) }.filter { it.fetchedAt.isAfter(cutoff) }.map { it.cardId }.toSet()
        return ids.filterNot { it in fresh }
    }

    /** Waits out a 429 as the backend asks, up to [MAX_ATTEMPTS] tries per chunk. */
    private suspend fun fetchWithRetry(chunk: List<String>): PriceBatch {
        repeat(MAX_ATTEMPTS - 1) {
            try {
                return api.fetch(chunk)
            } catch (e: RateLimitedException) {
                sleep(e.retryAfterSeconds.coerceIn(1, 300) * 1000)
            }
        }
        return api.fetch(chunk)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
