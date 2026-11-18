// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.alerts

import de.fabianduerkop.cardindex.data.db.CatalogDao
import de.fabianduerkop.cardindex.data.db.PriceAlertDao
import de.fabianduerkop.cardindex.data.db.PriceAlertEntity
import de.fabianduerkop.cardindex.data.prices.PriceRepository
import de.fabianduerkop.cardindex.data.prices.RefreshResult
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What the screens do with alerts: list, create, delete. Keeps the daily check scheduled exactly while alerts exist;
 * [schedule] and [unschedule] are the WorkManager calls, passed in so this class stays free of Android.
 */
class PriceAlertRepository(
    private val store: PriceAlertStore,
    private val schedule: () -> Unit,
    private val unschedule: () -> Unit,
    private val clock: () -> Instant = Instant::now,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) {
    fun observeAll(): Flow<List<PriceAlert>> = store.observeAll()

    fun observeCard(cardId: String): Flow<List<PriceAlert>> =
        store.observeAll().map { alerts -> alerts.filter { it.cardId == cardId } }

    /** Adds an armed alert; true when it is the user's first one (the moment to ask for notifications). */
    @Suppress("LongParameterList")
    suspend fun create(
        cardId: String,
        cardLanguage: String,
        source: PriceSource,
        variant: PriceVariant,
        direction: AlertDirection,
        thresholdMinor: Long,
        currency: String,
    ): Boolean {
        require(thresholdMinor > 0) { "Threshold must be positive" }
        val first = store.count() == 0
        store.save(
            PriceAlert(
                uuid = newUuid(),
                cardId = cardId,
                cardLanguage = cardLanguage,
                source = source,
                variant = variant,
                direction = direction,
                thresholdMinor = thresholdMinor,
                currency = currency,
                createdAt = clock(),
            ),
        )
        schedule()
        return first
    }

    suspend fun delete(uuid: String) {
        store.delete(uuid)
        if (store.count() == 0) unschedule()
    }

    /** On app start: the check runs while alerts exist (also after a restore or an app update). */
    suspend fun syncSchedule() {
        if (store.count() > 0) schedule() else unschedule()
    }
}

/** [PriceAlertStore] on the phone's Room database. */
class RoomPriceAlertStore(private val dao: PriceAlertDao) : PriceAlertStore {
    override fun observeAll(): Flow<List<PriceAlert>> = dao.observeAll().map { rows -> rows.map { it.toAlert() } }
    override suspend fun all(): List<PriceAlert> = dao.all().map { it.toAlert() }
    override suspend fun count(): Int = dao.count()
    override suspend fun save(alert: PriceAlert) = dao.upsert(alert.toEntity())
    override suspend fun update(alerts: List<PriceAlert>) = dao.update(alerts.map { it.toEntity() })
    override suspend fun delete(uuid: String) = dao.delete(uuid)
}

/**
 * Current prices through the free price API (`POST /v1/prices`, batched by [PriceRepository]). Forced, because the
 * app may have cached a card's price in the evening, before the night's import.
 */
class CachedCurrentPrices(private val prices: PriceRepository, private val catalog: CatalogDao) : CurrentPrices {
    override suspend fun fetch(cardIds: Set<String>): Map<PriceKey, MarketPrice>? {
        if (prices.refresh(cardIds, force = true) is RefreshResult.Failed) return null
        return catalog.observeAllPrices().first()
            .filter { it.cardId in cardIds && it.trend != null }
            .associate { PriceKey(it.cardId, it.source, it.variant) to MarketPrice(it.trend!!, it.currency) }
    }
}

private fun PriceAlertEntity.toAlert() = PriceAlert(
    uuid = uuid,
    cardId = cardId,
    cardLanguage = cardLanguage,
    source = source,
    variant = variant,
    direction = direction,
    thresholdMinor = thresholdMinor,
    currency = currency,
    createdAt = createdAt,
    triggeredAt = triggeredAt,
    armed = armed,
)

private fun PriceAlert.toEntity() = PriceAlertEntity(
    uuid = uuid,
    cardId = cardId,
    cardLanguage = cardLanguage,
    source = source,
    variant = variant,
    direction = direction,
    thresholdMinor = thresholdMinor,
    currency = currency,
    createdAt = createdAt,
    triggeredAt = triggeredAt,
    armed = armed,
)
