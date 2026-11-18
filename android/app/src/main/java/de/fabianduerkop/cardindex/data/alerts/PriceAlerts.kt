// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.alerts

import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow

/*
 * Pro price alerts (#76, ADR 0013). Everything in this file is plain Kotlin without Android, Room or WorkManager: the
 * rules, the check and the schedule are meant to move to the backend with sign-in and push (#16), which then runs
 * [PriceAlertChecker] after each import instead of the phone.
 */

enum class AlertDirection {
    /** Fires when the price reaches the threshold or goes above it. */
    ABOVE,

    /** Fires when the price falls to the threshold or below it. */
    BELOW,
}

/** One rule: a card's printing on one market, a direction and a threshold in that market's currency. */
data class PriceAlert(
    val uuid: String,
    val cardId: String,
    /** The print the user set it on, for the notification; the price is the same for every print language. */
    val cardLanguage: String,
    val source: PriceSource,
    val variant: PriceVariant,
    val direction: AlertDirection,
    val thresholdMinor: Long,
    val currency: String,
    val createdAt: Instant,
    val triggeredAt: Instant? = null,
    /** True while the alert may fire. A fired alert stays quiet until the price is back across the threshold. */
    val armed: Boolean = true,
) {
    val key: PriceKey get() = PriceKey(cardId, source, variant)

    /** Whether [amountMinor] is on the alert's side of the threshold (inclusive). */
    fun isMet(amountMinor: Long): Boolean = when (direction) {
        AlertDirection.ABOVE -> amountMinor >= thresholdMinor
        AlertDirection.BELOW -> amountMinor <= thresholdMinor
    }
}

/** The price an alert watches: one printing of one card on one market. */
data class PriceKey(val cardId: String, val source: PriceSource, val variant: PriceVariant)

/** A current headline price (Cardmarket trend, TCGplayer market) in minor units. */
data class MarketPrice(val amountMinor: Long, val currency: String)

/** An alert that fired in a check, with the price that made it fire. */
data class PriceAlertHit(val alert: PriceAlert, val price: MarketPrice)

object PriceAlertRules {
    /**
     * The alert after seeing [price], and whether it fires. It fires once when it is armed and the price is on its
     * side of the threshold, then disarms; it re-arms (without firing) once the price is back on the other side. So
     * a price hovering around the threshold notifies once per real crossing, not every day.
     *
     * No price, or a price in another currency than the threshold, changes nothing: a market that stopped pricing a
     * card neither fires nor re-arms its alerts.
     */
    fun evaluate(alert: PriceAlert, price: MarketPrice?, now: Instant): Pair<PriceAlert, Boolean> {
        if (price == null || price.currency != alert.currency) return alert to false
        val met = alert.isMet(price.amountMinor)
        return when {
            alert.armed && met -> alert.copy(armed = false, triggeredAt = now) to true
            !alert.armed && !met -> alert.copy(armed = true) to false
            else -> alert to false
        }
    }
}

/** Where alerts live. On the phone this is Room; with #16 it can be the backend's store. */
interface PriceAlertStore {
    fun observeAll(): Flow<List<PriceAlert>>
    suspend fun all(): List<PriceAlert>
    suspend fun count(): Int
    suspend fun save(alert: PriceAlert)
    suspend fun update(alerts: List<PriceAlert>)
    suspend fun delete(uuid: String)
}

/** Current prices for a check; null when they could not be fetched (offline, backend down). */
fun interface CurrentPrices {
    suspend fun fetch(cardIds: Set<String>): Map<PriceKey, MarketPrice>?
}

sealed interface AlertCheckResult {
    /** Every alert was evaluated; [hits] fired and need a notification. */
    data class Checked(val hits: List<PriceAlertHit>) : AlertCheckResult

    /** The prices could not be fetched; nothing changed, try again later. */
    data object PricesUnavailable : AlertCheckResult
}

/** The daily check: fetch the current prices of every alerted card once, evaluate each alert, store what changed. */
class PriceAlertChecker(
    private val store: PriceAlertStore,
    private val prices: CurrentPrices,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun check(): AlertCheckResult {
        val alerts = store.all()
        if (alerts.isEmpty()) return AlertCheckResult.Checked(emptyList())
        val current = prices.fetch(alerts.map { it.cardId }.toSet()) ?: return AlertCheckResult.PricesUnavailable
        val now = clock()
        val changed = mutableListOf<PriceAlert>()
        val hits = mutableListOf<PriceAlertHit>()
        for (alert in alerts) {
            val price = current[alert.key]
            val (next, fired) = PriceAlertRules.evaluate(alert, price, now)
            if (next != alert) changed += next
            if (fired) hits += PriceAlertHit(next, price!!)
        }
        if (changed.isNotEmpty()) store.update(changed)
        return AlertCheckResult.Checked(hits)
    }
}

object PriceAlertSchedule {
    /** The import runs at 05:30 Europe/Berlin (backend/README.md) and takes well under an hour. */
    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    /**
     * The check runs from 07:00 Berlin. A periodic job of 24 hours drifts by an hour when the clocks change, so the
     * margin keeps even a drifted run (06:00) after the import.
     */
    val CHECK_TIME: LocalTime = LocalTime.of(7, 0)

    /** How long from [now] to the next [CHECK_TIME] in Berlin; always more than zero and at most a day. */
    fun delayUntilNextCheck(now: Instant): Duration {
        val local = now.atZone(ZONE)
        var next = ZonedDateTime.of(local.toLocalDate(), CHECK_TIME, ZONE)
        if (!next.toInstant().isAfter(now)) next = ZonedDateTime.of(local.toLocalDate().plusDays(1), CHECK_TIME, ZONE)
        return Duration.between(now, next.toInstant())
    }
}
