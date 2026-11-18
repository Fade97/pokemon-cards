// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import de.fabianduerkop.cardindex.data.alerts.AlertDirection
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * A Pro price alert (#76): notify once when the price of one printing of a card on one market crosses a threshold.
 * Stored as the user set it; the rule logic is in `data/alerts/` and only sees `PriceAlert`, so the rules can move to
 * the server with #16 (ADR 0013). Keyed by a uuid, not a row id, so a later server copy keeps the same identity.
 */
@Entity(tableName = "price_alerts", indices = [Index("cardId")])
data class PriceAlertEntity(
    @PrimaryKey val uuid: String,
    /** TCGdex card id, as in `cached_prices`. */
    val cardId: String,
    /** The print the alert was set on; the notification names it and opens it. Prices don't depend on it. */
    val cardLanguage: String,
    val source: PriceSource,
    /** The market's printing whose headline price is watched (Cardmarket trend, TCGplayer market). */
    val variant: PriceVariant,
    val direction: AlertDirection,
    val thresholdMinor: Long,
    /** ISO 4217 code of [thresholdMinor]: the market's currency. */
    val currency: String,
    val createdAt: Instant,
    /** When the alert last fired; null if it never did. */
    val triggeredAt: Instant?,
    /** False after it fired, until the price is back on the other side of the threshold. */
    val armed: Boolean,
)

@Dao
interface PriceAlertDao {
    @Query("SELECT * FROM price_alerts ORDER BY createdAt")
    fun observeAll(): Flow<List<PriceAlertEntity>>

    @Query("SELECT * FROM price_alerts ORDER BY createdAt")
    suspend fun all(): List<PriceAlertEntity>

    @Query("SELECT COUNT(*) FROM price_alerts")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(alert: PriceAlertEntity)

    @Update
    suspend fun update(alerts: List<PriceAlertEntity>)

    @Query("DELETE FROM price_alerts WHERE uuid = :uuid")
    suspend fun delete(uuid: String)
}
