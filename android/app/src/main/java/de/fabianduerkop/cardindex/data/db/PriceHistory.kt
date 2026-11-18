// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * One month of daily prices of one printing of a card from one market (Pro price history, #17), cached from the
 * backend's history files (`backend/README.md`, "Price history"). A month is one row: [days] and [amounts] hold the
 * month's series packed as text, which keeps a year of a 1,000-card collection at ~30k rows. Decoded by
 * `PriceHistoryCodec`. Like [CachedPriceEntity] a cache keyed by TCGdex card id, never user data.
 */
@Entity(tableName = "price_history", primaryKeys = ["cardId", "source", "variant", "month"])
data class PriceHistoryEntity(
    val cardId: String,
    val source: PriceSource,
    val variant: PriceVariant,
    /** Calendar month in UTC, `YYYY-MM`. */
    val month: String,
    /** Days of the month with a price snapshot, ascending and comma-separated: `17,18,19`. */
    val days: String,
    /** Minor units per day of [days], comma-separated; empty where the market had no price: `120,,125`. */
    val amounts: String,
)

/**
 * When a card's month was last fetched, also when the backend had no prices for it, so a past month is asked for once
 * and the current one at most every few hours.
 */
@Entity(tableName = "price_history_fetches", primaryKeys = ["cardId", "month"])
data class PriceHistoryFetchEntity(
    val cardId: String,
    val month: String,
    val fetchedAt: Instant,
)

@Dao
interface PriceHistoryDao {
    @Query("SELECT * FROM price_history WHERE cardId = :cardId AND month >= :fromMonth")
    fun observe(cardId: String, fromMonth: String): Flow<List<PriceHistoryEntity>>

    /** Every cached card: the table only holds cards the user owns or opened. */
    @Query("SELECT * FROM price_history WHERE month >= :fromMonth")
    fun observeAll(fromMonth: String): Flow<List<PriceHistoryEntity>>

    @Query("SELECT * FROM price_history_fetches WHERE month >= :fromMonth")
    suspend fun fetchesSince(fromMonth: String): List<PriceHistoryFetchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(rows: List<PriceHistoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFetches(rows: List<PriceHistoryFetchEntity>)

    @Query("DELETE FROM price_history WHERE month = :month AND cardId IN (:cardIds)")
    suspend fun deleteMonth(month: String, cardIds: List<String>)

    /** Replaces the cards' rows of [month]: a printing the market stopped pricing must not keep its old series. */
    @Transaction
    suspend fun replaceMonth(month: String, cardIds: List<String>, rows: List<PriceHistoryEntity>, fetchedAt: Instant) {
        // Chunked because SQLite before 3.32 (Android 8–10) allows only 999 bound parameters.
        cardIds.chunked(500).forEach { deleteMonth(month, it) }
        insertHistory(rows)
        insertFetches(cardIds.map { PriceHistoryFetchEntity(it, month, fetchedAt) })
    }

    @Query("DELETE FROM price_history WHERE month < :month")
    suspend fun deleteHistoryBefore(month: String)

    @Query("DELETE FROM price_history_fetches WHERE month < :month")
    suspend fun deleteFetchesBefore(month: String)

    /** Drops months no chart range reaches any more. */
    @Transaction
    suspend fun pruneBefore(month: String) {
        deleteHistoryBefore(month)
        deleteFetchesBefore(month)
    }
}
