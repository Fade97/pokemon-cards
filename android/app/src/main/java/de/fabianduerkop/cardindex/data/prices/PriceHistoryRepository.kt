// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.prices

import de.fabianduerkop.cardindex.data.db.PriceHistoryDao
import de.fabianduerkop.cardindex.data.db.PriceHistoryEntity
import de.fabianduerkop.cardindex.data.pro.Entitlements
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface HistoryRefresh {
    /** [cards] months of cards were fetched; the rest were cached. */
    data class Updated(val cards: Int) : HistoryRefresh

    /** No Pro, no token, or the backend refused it (401/403). */
    data object NotPro : HistoryRefresh

    /** Offline or the backend failed. What is cached stays. */
    data class Failed(val cause: IOException) : HistoryRefresh
}

/**
 * Pro price history (#17), cached in Room per card and month. The backend's files are per set and month, so a refresh
 * asks for every month of the range once per 20 sets, and keeps only the cards it was asked about.
 *
 * Past months are final a few days after the backend's rebuild window ([isFinal]) and are fetched once; the current month
 * again when older than [maxAge] (the backend adds a day each night).
 */
class PriceHistoryRepository(
    private val dao: PriceHistoryDao,
    private val api: PriceHistoryApi,
    private val entitlements: Entitlements,
    private val clock: Clock = Clock.systemUTC(),
    private val maxAge: Duration = Duration.ofHours(12),
    private val sleep: suspend (millis: Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()

    fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneOffset.UTC))

    fun observeCard(cardId: String, from: LocalDate): Flow<List<PriceHistoryEntity>> =
        dao.observe(cardId, YearMonth.from(from).toString())

    fun observeAll(from: LocalDate): Flow<Map<String, List<PriceHistoryEntity>>> =
        dao.observeAll(YearMonth.from(from).toString()).map { rows -> rows.groupBy { it.cardId } }

    suspend fun refresh(cardIds: Collection<String>, from: LocalDate): HistoryRefresh = mutex.withLock {
        val token = entitlements.token() ?: return HistoryRefresh.NotPro
        val now = clock.instant()
        val months = generateSequence(YearMonth.from(from)) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(YearMonth.from(today())) }
            .toList()
        val ids = cardIds.distinct().filter { BACKEND_CARD_ID.matches(it) }
        if (ids.isEmpty() || months.isEmpty()) return HistoryRefresh.Updated(0)
        val fetched = dao.fetchesSince(months.first().toString()).associate { (it.cardId to it.month) to it.fetchedAt }

        var updated = 0
        for (month in months) {
            val due = ids.filter { id -> isDue(month, fetched[id to month.toString()], now) }
            for (chunk in due.groupBy { PriceShards.of(it) }.entries.chunked(PriceHistoryApi.MAX_SETS)) {
                val result = try {
                    fetchWithRetry(month, chunk.map { it.key }, token)
                } catch (e: ProRequiredException) {
                    return HistoryRefresh.NotPro
                } catch (e: IOException) {
                    return HistoryRefresh.Failed(e)
                }
                val cards = chunk.flatMap { it.value }
                val rows = chunk.flatMap { (set, setCards) -> setCards.flatMap { result[set]?.cards?.get(it).orEmpty() } }
                dao.replaceMonth(month.toString(), cards, rows, now)
                updated += cards.size
            }
        }
        // A year is the longest range; one more month keeps the start of a year-long chart.
        dao.pruneBefore(YearMonth.from(today()).minusMonths(KEEP_MONTHS).toString())
        HistoryRefresh.Updated(updated)
    }

    private fun isDue(month: YearMonth, fetchedAt: Instant?, now: Instant): Boolean = when {
        fetchedAt == null -> true
        isFinal(month, fetchedAt) -> false
        else -> fetchedAt.isBefore(now.minus(maxAge))
    }

    private suspend fun fetchWithRetry(month: YearMonth, sets: List<String>, token: String): Map<String, HistoryMonth> {
        repeat(MAX_ATTEMPTS - 1) {
            try {
                return api.fetchMonth(month, sets, token)
            } catch (e: RateLimitedException) {
                sleep(e.retryAfterSeconds.coerceIn(1, 300) * 1000)
            }
        }
        return api.fetchMonth(month, sets, token)
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val KEEP_MONTHS = 13L

        /**
         * The backend rebuilds the previous month until the 3rd (importer/history.ts, `monthsToRebuild`); the days up to
         * the 8th leave time for a manual `--rebuild-history` after failed nights before the app stops asking.
         */
        fun isFinal(month: YearMonth, fetchedAt: Instant): Boolean =
            !fetchedAt.isBefore(month.plusMonths(1).atDay(FINAL_DAY).atStartOfDay().toInstant(ZoneOffset.UTC))

        private const val FINAL_DAY = 8

        /** The id pattern the backend accepts (`CARD_ID` in backend/src/price-data.ts). */
        private val BACKEND_CARD_ID = Regex("^[A-Za-z0-9._-]{1,40}$")
    }
}
