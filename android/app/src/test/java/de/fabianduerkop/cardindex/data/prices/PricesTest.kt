// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.prices

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.testing.TestClock
import de.fabianduerkop.cardindex.testing.inMemoryDatabase
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Robolectric for org.json and Room; the selection logic itself is plain Kotlin. */
@RunWith(AndroidJUnit4::class)
class PricesTest {
    private val fetchedAt = Instant.parse("2026-11-17T08:00:00Z")

    /** The card price example from backend/README.md, plus a 1st-edition TCGplayer price. */
    private val example = """
        {
          "id": "sv03.5-025",
          "cardmarket": {
            "productId": 712345, "currency": "EUR", "mixedLanguages": true, "priceDate": "2026-11-16T02:00:00Z",
            "normal": { "low": 0.5, "trend": 1.2, "avg": 1.1, "avg1": 1.0, "avg7": 1.15, "avg30": 1.3 },
            "holo": { "low": 1.5, "trend": 2.4, "avg": 2.2, "avg1": 2.0, "avg7": 2.3, "avg30": 2.5 }
          },
          "tcgplayer": {
            "productId": 501234, "currency": "USD", "priceDate": "2026-11-15T20:00:00Z",
            "variants": {
              "Normal": { "low": 0.4, "mid": 0.9, "high": 5.0, "market": 0.8, "directLow": null },
              "1st Edition Holofoil": { "low": 40, "mid": 50, "high": 90, "market": 55.5, "directLow": null },
              "Cosmos Holofoil": { "low": 3, "mid": 4, "high": 5, "market": 4.2, "directLow": null }
            }
          }
        }
    """.trimIndent()

    private fun rows() = PriceJson.parseCard("sv03.5-025", JSONObject(example), fetchedAt)

    @Test
    fun parse_mapsMarketsAndPrintingsToCacheRows() {
        val rows = rows().associateBy { it.source to it.variant }

        assertEquals(4, rows.size) // Cosmos Holofoil has no PriceVariant and is left out
        val cmNormal = rows.getValue(PriceSource.CARDMARKET to PriceVariant.NORMAL)
        assertEquals(120L, cmNormal.trend)
        assertEquals(50L, cmNormal.low)
        assertEquals(712345L, cmNormal.productId)
        assertEquals(Instant.parse("2026-11-16T02:00:00Z"), cmNormal.priceDate)
        assertEquals(240L, rows.getValue(PriceSource.CARDMARKET to PriceVariant.REVERSE_HOLO).trend)

        val tcgNormal = rows.getValue(PriceSource.TCGPLAYER to PriceVariant.NORMAL)
        assertEquals(80L, tcgNormal.trend) // TCGplayer market price is the headline
        assertEquals(90L, tcgNormal.avg) // mid
        assertEquals("USD", tcgNormal.currency)
        assertEquals(5550L, rows.getValue(PriceSource.TCGPLAYER to PriceVariant.FIRST_EDITION_HOLO).trend)
    }

    @Test
    fun parse_keepsTheProductIdOfAMarketWithoutPrices() {
        val rows = PriceJson.parseCard(
            "x",
            JSONObject("""{"id":"x","cardmarket":{"productId":9,"priceDate":null,"normal":null,"holo":null},"tcgplayer":null}"""),
            fetchedAt,
        )
        val row = rows.single()
        assertEquals(9L, row.productId)
        assertNull(row.trend)
    }

    @Test
    fun selection_followsPriceVariantCandidates() {
        val prices = rows()

        assertEquals(120L, PriceSelection.quote(prices, PriceSource.CARDMARKET, Finish.HOLO, Edition.UNLIMITED)?.amountMinor)
        assertEquals(240L, PriceSelection.quote(prices, PriceSource.CARDMARKET, Finish.REVERSE_HOLO, Edition.UNLIMITED)?.amountMinor)
        // The guide doesn't split editions: no Cardmarket price rather than the unlimited one.
        assertNull(PriceSelection.quote(prices, PriceSource.CARDMARKET, Finish.HOLO, Edition.FIRST_EDITION))
        // TCGplayer falls back from HOLO to NORMAL, and prices 1st edition separately.
        assertEquals(80L, PriceSelection.quote(prices, PriceSource.TCGPLAYER, Finish.HOLO, Edition.UNLIMITED)?.amountMinor)
        assertEquals(5550L, PriceSelection.quote(prices, PriceSource.TCGPLAYER, Finish.HOLO, Edition.FIRST_EDITION)?.amountMinor)
        assertNull(PriceSelection.quote(prices, PriceSource.TCGPLAYER, Finish.REVERSE_HOLO, Edition.UNLIMITED))

        val (main, secondary) = PriceSelection.pair(prices, PriceSource.TCGPLAYER, Finish.NORMAL, Edition.UNLIMITED)
        assertEquals(PriceSource.TCGPLAYER to "USD", main!!.source to main.currency)
        assertEquals(PriceSource.CARDMARKET, secondary!!.source)
        assertTrue(secondary.mixesLanguages)
    }

    @Test
    fun total_multipliesQuantities_andCountsUnpricedCopies() {
        val copies = listOf(
            OwnedCopyEntity(cardId = "sv03.5-025", cardLanguage = "de", quantity = 3, createdAt = fetchedAt),
            OwnedCopyEntity(cardId = "sv03.5-025", cardLanguage = "en", finish = Finish.REVERSE_HOLO, createdAt = fetchedAt),
            OwnedCopyEntity(cardId = "sv03.5-025", cardLanguage = "en", edition = Edition.FIRST_EDITION, quantity = 2, createdAt = fetchedAt),
            OwnedCopyEntity(cardId = "unknown", cardLanguage = "en", createdAt = fetchedAt),
        )
        val value = PriceSelection.total(copies, mapOf("sv03.5-025" to rows()), PriceSource.CARDMARKET)
        assertEquals(3 * 120L + 240L, value.amountMinor)
        assertEquals("EUR", value.currency)
        assertEquals(3, value.unpricedCopies)
    }

    @Test
    fun region_picksTheMarketByCountry() {
        assertEquals(PriceSource.CARDMARKET, PriceRegion.defaultSource(Locale.GERMANY))
        assertEquals(PriceSource.CARDMARKET, PriceRegion.defaultSource(Locale.FRANCE))
        assertEquals(PriceSource.TCGPLAYER, PriceRegion.defaultSource(Locale.US))
        assertEquals(PriceSource.TCGPLAYER, PriceRegion.defaultSource(Locale.CANADA_FRENCH))
        assertEquals(PriceSource.CARDMARKET, PriceRegion.defaultSource(Locale.ENGLISH)) // no country
        assertEquals(PriceSource.TCGPLAYER, PriceRegion.effective(PriceSourcePreference.TCGPLAYER, Locale.GERMANY))
        assertEquals(PriceSource.CARDMARKET, PriceRegion.effective(PriceSourcePreference.AUTO, Locale.ITALY))
    }

    @Test
    fun links_filterCardmarketByTheCardsLanguage() {
        assertEquals(
            "https://www.cardmarket.com/de/Pokemon/Products?idProduct=712345&language=3",
            MarketLinks.cardmarket(712345, "de", Locale.GERMANY),
        )
        assertEquals(
            "https://www.cardmarket.com/en/Pokemon/Products?idProduct=1&language=7",
            MarketLinks.cardmarket(1, "ja", Locale.JAPAN),
        )
        assertEquals("https://www.cardmarket.com/en/Pokemon/Products?idProduct=1", MarketLinks.cardmarket(1, "nl", Locale.US))
        assertEquals("https://www.tcgplayer.com/product/501234", MarketLinks.tcgplayer(501234))
    }
}

@RunWith(AndroidJUnit4::class)
class PriceRepositoryTest {
    private lateinit var db: CardIndexDatabase
    private val clock = TestClock()
    private val sleeps = mutableListOf<Long>()

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() = db.close()

    private fun row(cardId: String, variant: PriceVariant, trend: Long, fetchedAt: Instant = clock.now) = CachedPriceEntity(
        cardId, PriceSource.CARDMARKET, variant, "EUR", trend, null, null, null, fetchedAt, productId = 1,
    )

    /** Answers from a script; records the ids of every call. */
    private class FakeApi(private val answers: MutableList<(List<String>) -> PriceBatch>) : PriceApi {
        val calls = mutableListOf<List<String>>()
        override suspend fun fetch(ids: List<String>): PriceBatch {
            calls += ids
            return answers.removeAt(0)(ids)
        }
    }

    private fun repo(api: PriceApi) = PriceRepository(db.catalogDao(), api, clock, Duration.ofHours(12)) { sleeps += it }

    @Test
    fun refresh_asksInBatchesOfAtMost100_andOnlyForStaleCards() = runTest {
        val ids = (1..230).map { "c$it" }
        db.catalogDao().upsertPrices(listOf(row("c1", PriceVariant.NORMAL, 5))) // fresh, skipped
        db.catalogDao().upsertPrices(listOf(row("c2", PriceVariant.NORMAL, 5, clock.now.minus(Duration.ofDays(1)))))
        val api = FakeApi(MutableList(3) { { batch -> PriceBatch(batch.associateWith { listOf(row(it, PriceVariant.NORMAL, 7)) }, emptyList()) } })

        val result = repo(api).refresh(ids)

        assertEquals(RefreshResult.Updated(229), result)
        assertEquals(listOf(100, 100, 29), api.calls.map { it.size })
        assertTrue("c1" !in api.calls.flatten())
        assertEquals(7L, db.catalogDao().observePrices("c2").first().single().trend)
    }

    @Test
    fun refresh_waitsOutA429_andReplacesDroppedPrintings() = runTest {
        db.catalogDao().upsertPrices(
            listOf(
                row("a", PriceVariant.NORMAL, 5, Instant.EPOCH),
                row("a", PriceVariant.REVERSE_HOLO, 9, Instant.EPOCH),
            ),
        )
        val api = FakeApi(
            mutableListOf(
                { _ -> throw RateLimitedException(60) },
                { _ -> PriceBatch(mapOf("a" to listOf(row("a", PriceVariant.NORMAL, 6))), listOf("b")) },
            ),
        )
        val repository = repo(api)

        assertEquals(RefreshResult.Updated(1), repository.refresh(listOf("a", "b")))
        assertEquals(listOf(60_000L), sleeps)
        assertEquals(listOf(PriceVariant.NORMAL to 6L), db.catalogDao().observePrices("a").first().map { it.variant to it.trend })

        // "b" is unknown to the backend and "a" is fresh: nothing to ask.
        assertEquals(RefreshResult.Updated(0), repository.refresh(listOf("a", "b")))
        assertEquals(2, api.calls.size)
    }

    @Test
    fun refresh_skipsIdsTheBackendWouldReject_insteadOfFailingTheBatch() = runTest {
        val api = FakeApi(mutableListOf({ batch -> PriceBatch(batch.associateWith { listOf(row(it, PriceVariant.NORMAL, 7)) }, emptyList()) }))

        val result = repo(api).refresh(listOf("sv03.5-025", "exu-?", "x".repeat(41)))

        assertEquals(RefreshResult.Updated(1), result)
        assertEquals(listOf(listOf("sv03.5-025")), api.calls)
    }

    @Test
    fun refresh_offline_keepsTheCache() = runTest {
        db.catalogDao().upsertPrices(listOf(row("a", PriceVariant.NORMAL, 5, Instant.EPOCH)))
        val api = FakeApi(mutableListOf({ _ -> throw IOException("offline") }))

        val result = repo(api).refresh(listOf("a"))

        assertTrue(result is RefreshResult.Failed)
        assertEquals(5L, db.catalogDao().observePrices("a").first().single().trend)
    }

    @Test
    fun httpApi_postsIds_andMaps429() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        server.enqueue(
            MockResponse().setBody(
                """{"prices":{"sv03.5-025":{"id":"sv03.5-025","cardmarket":null,"tcgplayer":{"productId":5,"currency":"USD","priceDate":null,"variants":{"Holofoil":{"low":1,"mid":2,"high":3,"market":2.5,"directLow":null}}}}},"missing":["nope"]}""",
            ),
        )
        server.start()
        try {
            val api = HttpPriceApi(server.url("/").toString(), OkHttpClient()) { clock.now }

            val limited = runCatching { api.fetch(listOf("sv03.5-025", "nope")) }.exceptionOrNull()
            assertEquals(60L, (limited as RateLimitedException).retryAfterSeconds)

            val batch = api.fetch(listOf("sv03.5-025", "nope"))
            assertEquals(listOf("nope"), batch.missing)
            val row = batch.prices.getValue("sv03.5-025").single()
            assertEquals(PriceVariant.HOLO to 250L, row.variant to row.trend)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/prices", request.path)
            assertEquals("""{"ids":["sv03.5-025","nope"]}""", request.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }
}
