// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.alerts

import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceAlertsTest {
    private val t0 = Instant.parse("2026-11-18T06:00:00Z")

    private fun alert(
        direction: AlertDirection = AlertDirection.ABOVE,
        threshold: Long = 1000,
        armed: Boolean = true,
        uuid: String = "a-1",
        cardId: String = "sv03.5-025",
        source: PriceSource = PriceSource.CARDMARKET,
        variant: PriceVariant = PriceVariant.NORMAL,
        currency: String = "EUR",
    ) = PriceAlert(
        uuid = uuid,
        cardId = cardId,
        cardLanguage = "en",
        source = source,
        variant = variant,
        direction = direction,
        thresholdMinor = threshold,
        currency = currency,
        createdAt = t0,
        armed = armed,
    )

    private fun eur(minor: Long) = MarketPrice(minor, "EUR")

    // --- crossing and re-arm ---

    @Test
    fun above_firesOnce_atOrOverTheThreshold_andDisarms() {
        val (next, fired) = PriceAlertRules.evaluate(alert(), eur(1000), t0)
        assertTrue(fired)
        assertFalse(next.armed)
        assertEquals(t0, next.triggeredAt)
    }

    @Test
    fun above_staysQuiet_belowTheThreshold() {
        val start = alert()
        val (next, fired) = PriceAlertRules.evaluate(start, eur(999), t0)
        assertFalse(fired)
        assertEquals(start, next)
    }

    @Test
    fun below_firesAtOrUnderTheThreshold_only() {
        val start = alert(direction = AlertDirection.BELOW, threshold = 500)
        assertFalse(PriceAlertRules.evaluate(start, eur(501), t0).second)
        assertTrue(PriceAlertRules.evaluate(start, eur(500), t0).second)
        assertTrue(PriceAlertRules.evaluate(start, eur(120), t0).second)
    }

    @Test
    fun fired_staysQuietWhileThePriceStaysAcross() {
        val fired = alert(armed = false).copy(triggeredAt = t0)
        val (next, notified) = PriceAlertRules.evaluate(fired, eur(1500), t0.plus(Duration.ofDays(1)))
        assertFalse(notified)
        assertEquals(fired, next)
    }

    @Test
    fun fired_rearmsWithoutNotifying_onceThePriceCrossesBack() {
        val fired = alert(armed = false).copy(triggeredAt = t0)
        val (next, notified) = PriceAlertRules.evaluate(fired, eur(999), t0.plus(Duration.ofDays(1)))
        assertFalse(notified)
        assertTrue(next.armed)
        assertEquals("keeps when it last fired", t0, next.triggeredAt)
    }

    @Test
    fun hoveringPrice_notifiesOncePerRealCrossing() {
        // Days: below, above, above, below, above, above → two notifications (day 2 and day 5).
        val days = listOf(990L, 1010, 1020, 980, 1000, 1050)
        var current = alert()
        val firedOn = days.mapIndexedNotNull { day, amount ->
            val (next, fired) = PriceAlertRules.evaluate(current, eur(amount), t0.plus(Duration.ofDays(day.toLong())))
            current = next
            day.takeIf { fired }
        }
        assertEquals(listOf(1, 4), firedOn)
    }

    @Test
    fun missingPriceOrOtherCurrency_changesNothing() {
        val armed = alert()
        val disarmed = alert(armed = false)
        assertEquals(armed to false, PriceAlertRules.evaluate(armed, null, t0))
        assertEquals(disarmed to false, PriceAlertRules.evaluate(disarmed, null, t0))
        assertEquals(armed to false, PriceAlertRules.evaluate(armed, MarketPrice(5000, "USD"), t0))
    }

    // --- the check ---

    private class FakeStore(initial: List<PriceAlert>) : PriceAlertStore {
        val alerts = MutableStateFlow(initial)
        var updates = 0
        override fun observeAll(): Flow<List<PriceAlert>> = alerts
        override suspend fun all() = alerts.value
        override suspend fun count() = alerts.value.size
        override suspend fun save(alert: PriceAlert) { alerts.value = alerts.value.filterNot { it.uuid == alert.uuid } + alert }
        override suspend fun update(alerts: List<PriceAlert>) {
            updates++
            val byUuid = alerts.associateBy { it.uuid }
            this.alerts.value = this.alerts.value.map { byUuid[it.uuid] ?: it }
        }
        override suspend fun delete(uuid: String) { alerts.value = alerts.value.filterNot { it.uuid == uuid } }
    }

    @Test
    fun check_fetchesEveryAlertedCardOnce_andStoresOnlyChanges() = runBlocking {
        val store = FakeStore(
            listOf(
                alert(uuid = "up", threshold = 1000),
                alert(uuid = "down", direction = AlertDirection.BELOW, threshold = 500),
                alert(uuid = "us", cardId = "base1-4", source = PriceSource.TCGPLAYER, variant = PriceVariant.HOLO, currency = "USD", threshold = 30000),
            ),
        )
        val asked = mutableListOf<Set<String>>()
        val checker = PriceAlertChecker(
            store,
            prices = { ids ->
                asked += ids
                mapOf(
                    PriceKey("sv03.5-025", PriceSource.CARDMARKET, PriceVariant.NORMAL) to eur(1200),
                    PriceKey("base1-4", PriceSource.TCGPLAYER, PriceVariant.HOLO) to MarketPrice(25000, "USD"),
                )
            },
            clock = { t0 },
        )

        val result = checker.check() as AlertCheckResult.Checked

        assertEquals(listOf(setOf("sv03.5-025", "base1-4")), asked)
        assertEquals(listOf("up"), result.hits.map { it.alert.uuid })
        assertEquals(eur(1200), result.hits.single().price)
        assertEquals(mapOf("up" to false, "down" to true, "us" to true), store.alerts.value.associate { it.uuid to it.armed })
        assertEquals(1, store.updates)

        // Next day, same prices: nothing fires and nothing is written.
        val again = checker.check() as AlertCheckResult.Checked
        assertTrue(again.hits.isEmpty())
        assertEquals(1, store.updates)
    }

    @Test
    fun check_withoutPrices_changesNothing() = runBlocking {
        val store = FakeStore(listOf(alert()))
        val result = PriceAlertChecker(store, prices = { null }, clock = { t0 }).check()
        assertEquals(AlertCheckResult.PricesUnavailable, result)
        assertTrue(store.alerts.value.single().armed)
        assertEquals(0, store.updates)
    }

    @Test
    fun check_withoutAlerts_doesNotAskForPrices() = runBlocking {
        var asked = false
        val result = PriceAlertChecker(FakeStore(emptyList()), prices = { asked = true; emptyMap() }).check()
        assertEquals(AlertCheckResult.Checked(emptyList()), result)
        assertFalse(asked)
    }

    // --- repository: first alert and schedule ---

    @Test
    fun repository_reportsTheFirstAlert_andSchedulesWhileAlertsExist() = runBlocking {
        val store = FakeStore(emptyList())
        var scheduled = false
        var ids = 0
        val repository = PriceAlertRepository(
            store,
            schedule = { scheduled = true },
            unschedule = { scheduled = false },
            clock = { t0 },
            newUuid = { "u-${++ids}" },
        )
        assertTrue(repository.create("sv03.5-025", "de", PriceSource.CARDMARKET, PriceVariant.NORMAL, AlertDirection.ABOVE, 1000, "EUR"))
        assertFalse(repository.create("sv03.5-025", "de", PriceSource.CARDMARKET, PriceVariant.NORMAL, AlertDirection.BELOW, 500, "EUR"))
        assertTrue(scheduled)
        assertTrue(store.alerts.value.all { it.armed && it.triggeredAt == null && it.cardLanguage == "de" })

        repository.delete("u-1")
        assertTrue(scheduled)
        repository.delete("u-2")
        assertFalse(scheduled)
    }

    // --- schedule ---

    @Test
    fun nextCheck_isTheNext7amInBerlin_inWinterAndSummer() {
        // Winter (CET, UTC+1): 07:00 Berlin = 06:00Z.
        assertEquals(Duration.ofHours(1), PriceAlertSchedule.delayUntilNextCheck(Instant.parse("2026-11-18T05:00:00Z")))
        assertEquals(Duration.ofHours(23), PriceAlertSchedule.delayUntilNextCheck(Instant.parse("2026-11-18T07:00:00Z")))
        assertEquals("exactly at the time means tomorrow", Duration.ofDays(1), PriceAlertSchedule.delayUntilNextCheck(Instant.parse("2026-11-18T06:00:00Z")))
        // Summer (CEST, UTC+2): 07:00 Berlin = 05:00Z.
        assertEquals(Duration.ofMinutes(30), PriceAlertSchedule.delayUntilNextCheck(Instant.parse("2026-07-01T04:30:00Z")))
    }
}
