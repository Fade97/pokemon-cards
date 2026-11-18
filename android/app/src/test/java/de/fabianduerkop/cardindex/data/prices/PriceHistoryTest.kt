// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.prices

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.db.PriceHistoryEntity
import de.fabianduerkop.cardindex.data.pro.Entitlements
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.testing.TestClock
import de.fabianduerkop.cardindex.testing.inMemoryDatabase
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private fun d(day: String) = LocalDate.parse(day)

private fun row(
    cardId: String,
    source: PriceSource,
    variant: PriceVariant,
    vararg points: Pair<String, Long?>,
): PriceHistoryEntity {
    val dates = points.map { d(it.first) to it.second }
    return PriceHistoryCodec.entity(cardId, source, variant, YearMonth.from(dates.first().first), dates)
}

private fun copy(cardId: String, language: String = "en", quantity: Int = 1, finish: Finish = Finish.NORMAL) =
    OwnedCopyEntity(cardId = cardId, cardLanguage = language, quantity = quantity, finish = finish, createdAt = Instant.EPOCH)

/** Robolectric for org.json; the history logic itself is plain Kotlin. */
@RunWith(AndroidJUnit4::class)
class PriceHistoryTest {
    @Test
    fun shards_matchTheBackendsShardOf() {
        // The cases of backend/importer/test/import.test.ts ("shards card IDs by set"), plus a few more.
        assertEquals("sv03.5", PriceShards.of("sv03.5-025"))
        assertEquals("SV2a", PriceShards.of("SV2a-025"))
        assertEquals("swsh12pt5gg", PriceShards.of("swsh12pt5gg-GG01"))
        assertEquals("_misc", PriceShards.of("nodash"))
        assertEquals("_misc", PriceShards.of("..-1"))
        assertEquals("swsh12.5-GG", PriceShards.of("swsh12.5-GG-01"))
        assertEquals("_misc", PriceShards.of("-025"))
        assertEquals("_misc", PriceShards.of(".x-025"))
    }

    @Test
    fun codec_packsAMonth_andSkipsGapsAndMalformedRows() {
        val packed = row("a-1", PriceSource.CARDMARKET, PriceVariant.NORMAL, "2026-11-17" to 120, "2026-11-18" to null, "2026-11-19" to 125)
        assertEquals("2026-11" to "17,18,19", packed.month to packed.days)
        assertEquals("120,,125", packed.amounts)
        assertEquals(
            listOf(PricePoint(d("2026-11-17"), 120), PricePoint(d("2026-11-19"), 125)),
            PriceHistoryCodec.points(packed),
        )
        assertEquals(emptyList<PricePoint>(), PriceHistoryCodec.points(packed.copy(amounts = "1,2")))
        assertEquals(emptyList<PricePoint>(), PriceHistoryCodec.points(packed.copy(month = "nope")))
        assertEquals(listOf(PricePoint(d("2026-11-17"), 120)), PriceHistoryCodec.points(packed.copy(days = "17,31,40")))
    }

    @Test
    fun json_mapsTheBackendFormat_toRowsPerPrinting() {
        // The example from backend/README.md ("Price history"), plus a set without a file and odd TCGplayer printings.
        val envelope = """
            {"month":"2026-11","sets":{
              "sv03.5":{"version":1,"set":"sv03.5","month":"2026-11","generatedAt":"2026-11-18T04:40:00.000Z",
                "days":["2026-11-17","2026-11-18"],
                "cards":{"sv03.5-025":{"cardmarket":{"normal":[120,125],"holo":[240,null]},
                                       "tcgplayer":{"Normal":[80,82],"Cosmos Holofoil":[5,5],"Unlimited":[1,1]}}}},
              "sv08":null}}
        """.trimIndent()
        val months = PriceHistoryJson.parseEnvelope(envelope)
        assertEquals(setOf("sv03.5"), months.keys)
        val rows = months.getValue("sv03.5").cards.getValue("sv03.5-025").associateBy { it.source to it.variant }
        // Holo is Cardmarket's foil price, stored as reverse holo; "Unlimited" is a second NORMAL and dropped.
        assertEquals(3, rows.size)
        assertEquals("17,18" to "120,125", rows.getValue(PriceSource.CARDMARKET to PriceVariant.NORMAL).let { it.days to it.amounts })
        assertEquals("240,", rows.getValue(PriceSource.CARDMARKET to PriceVariant.REVERSE_HOLO).amounts)
        assertEquals("80,82", rows.getValue(PriceSource.TCGPLAYER to PriceVariant.NORMAL).amounts)
    }

    @Test
    fun series_usesThePriceVariantCandidates_dayByDay() {
        val rows = listOf(
            row("c-1", PriceSource.TCGPLAYER, PriceVariant.HOLO, "2026-11-17" to 500, "2026-11-18" to null),
            row("c-1", PriceSource.TCGPLAYER, PriceVariant.NORMAL, "2026-11-17" to 100, "2026-11-18" to 110),
            row("c-1", PriceSource.CARDMARKET, PriceVariant.NORMAL, "2026-11-17" to 999),
        )
        // A holo copy on TCGplayer: holo where there is one, else the normal price (PriceVariant.candidates).
        assertEquals(
            listOf(PricePoint(d("2026-11-17"), 500), PricePoint(d("2026-11-18"), 110)),
            PriceHistorySelection.series(rows, PriceSource.TCGPLAYER, Finish.HOLO, Edition.UNLIMITED),
        )
        // Cardmarket doesn't split editions, so a 1st-edition copy has no Cardmarket history.
        assertEquals(
            emptyList<PricePoint>(),
            PriceHistorySelection.series(rows, PriceSource.CARDMARKET, Finish.NORMAL, Edition.FIRST_EDITION),
        )
    }

    @Test
    fun portfolio_valuesTodaysCopies_carryingPricesOverGaps() {
        val history = mapOf(
            "a-1" to listOf(
                row("a-1", PriceSource.CARDMARKET, PriceVariant.NORMAL, "2026-10-31" to 100),
                row("a-1", PriceSource.CARDMARKET, PriceVariant.NORMAL, "2026-11-01" to null, "2026-11-02" to 120, "2026-11-03" to 130),
            ),
            "b-1" to listOf(row("b-1", PriceSource.CARDMARKET, PriceVariant.REVERSE_HOLO, "2026-11-02" to 50)),
        )
        val copies = listOf(
            copy("a-1", quantity = 2),
            copy("b-1", language = "de", finish = Finish.REVERSE_HOLO),
            copy("c-1"), // no history at all
        )

        val points = PriceHistorySelection.portfolio(copies, history, PriceSource.CARDMARKET, from = d("2026-11-01"))

        // Nov 1 has no a-1 price: its Oct 31 price carries over. b-1 counts from its first price on.
        assertEquals(
            listOf(
                PortfolioPoint(d("2026-11-02"), 2 * 120 + 50, 3),
                PortfolioPoint(d("2026-11-03"), 2 * 130 + 50, 3),
            ),
            points,
        )
        // TCGplayer prices only English copies, as in PriceSelection.total.
        val tcgplayer = mapOf("b-1" to listOf(row("b-1", PriceSource.TCGPLAYER, PriceVariant.REVERSE_HOLO, "2026-11-02" to 70)))
        assertEquals(emptyList<PortfolioPoint>(), PriceHistorySelection.portfolio(copies, tcgplayer, PriceSource.TCGPLAYER, d("2026-11-01")))
    }

    @Test
    fun portfolio_countsACopyAsUnpricedBeforeItsFirstPrice() {
        val history = mapOf(
            "a-1" to listOf(row("a-1", PriceSource.CARDMARKET, PriceVariant.NORMAL, "2026-11-01" to 100, "2026-11-02" to 100)),
            "b-1" to listOf(row("b-1", PriceSource.CARDMARKET, PriceVariant.NORMAL, "2026-11-02" to 40)),
        )
        val points = PriceHistorySelection.portfolio(listOf(copy("a-1"), copy("b-1")), history, PriceSource.CARDMARKET, d("2026-11-01"))
        assertEquals(listOf(PortfolioPoint(d("2026-11-01"), 100, 1), PortfolioPoint(d("2026-11-02"), 140, 2)), points)
    }

    @Test
    fun finalMonths_endAfterTheBackendsRebuildWindow() {
        val november = YearMonth.of(2026, 11)
        assertEquals(false, PriceHistoryRepository.isFinal(november, Instant.parse("2026-12-07T23:59:00Z")))
        assertEquals(true, PriceHistoryRepository.isFinal(november, Instant.parse("2026-12-08T00:00:00Z")))
    }
}

@RunWith(AndroidJUnit4::class)
class PriceHistoryRepositoryTest {
    private lateinit var db: CardIndexDatabase
    private val clock = TestClock(Instant.parse("2026-12-10T10:00:00Z"))
    private val sleeps = mutableListOf<Long>()

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() = db.close()

    private class Pro(private val token: String? = "t".repeat(32)) : Entitlements {
        override val isPro: Flow<Boolean> = flowOf(true)
        override fun token() = token
    }

    /** Every set gets a file with a price on the month's first day for each card it is asked about, plus a stranger. */
    private class FakeApi : PriceHistoryApi {
        val calls = mutableListOf<Pair<YearMonth, List<String>>>()
        var fail: IOException? = null

        override suspend fun fetchMonth(month: YearMonth, sets: List<String>, token: String): Map<String, HistoryMonth> {
            fail?.let { throw it }
            calls += month to sets
            return sets.associateWith { set ->
                val cards = listOf("$set-001", "$set-999").associateWith { id ->
                    listOf(row(id, PriceSource.CARDMARKET, PriceVariant.NORMAL, month.atDay(1).toString() to month.monthValue * 100L))
                }
                HistoryMonth(cards)
            }
        }
    }

    private fun repo(api: PriceHistoryApi, entitlements: Entitlements = Pro()) =
        PriceHistoryRepository(db.priceHistoryDao(), api, entitlements, clock, Duration.ofHours(12)) { sleeps += it }

    @Test
    fun refresh_asksPerMonth_inBatchesOf20Sets_andKeepsOnlyTheAskedCards() = runTest {
        val api = FakeApi()
        val cards = (1..22).map { "s$it-001" }
        val result = repo(api).refresh(cards, from = d("2026-11-15"))

        assertEquals(HistoryRefresh.Updated(44), result)
        assertEquals(listOf(11, 11, 12, 12), api.calls.map { it.first.monthValue })
        assertEquals(listOf(20, 2, 20, 2), api.calls.map { it.second.size })
        val stored = db.priceHistoryDao().observeAll("2026-11").first()
        assertEquals(44, stored.size)
        assertTrue(stored.none { it.cardId.endsWith("-999") })
    }

    @Test
    fun refresh_fetchesFinalMonthsOnce_andTheCurrentOneWhenStale() = runTest {
        val api = FakeApi()
        val repo = repo(api)
        repo.refresh(listOf("sv03.5-001"), d("2026-11-15"))
        api.calls.clear()

        repo.refresh(listOf("sv03.5-001"), d("2026-11-15"))
        assertEquals(emptyList<Any>(), api.calls)

        clock.advance(13 * 3600)
        repo.refresh(listOf("sv03.5-001"), d("2026-11-15"))
        // November was fetched after Dec 8: final. December is still growing.
        assertEquals(listOf(YearMonth.of(2026, 12) to listOf("sv03.5")), api.calls)

        // A card new to the cache gets its whole range, even from a set already fetched.
        api.calls.clear()
        repo.refresh(listOf("sv03.5-001", "sv03.5-002"), d("2026-11-15"))
        assertEquals(listOf(11, 12), api.calls.map { it.first.monthValue })
    }

    @Test
    fun refresh_asksAgainForTheLastMonth_whileTheBackendMayStillRebuildIt() = runTest {
        clock.now = Instant.parse("2026-12-06T10:00:00Z")
        val api = FakeApi()
        val repo = repo(api)
        repo.refresh(listOf("sv03.5-001"), d("2026-11-15"))
        clock.advance(13 * 3600)
        api.calls.clear()
        repo.refresh(listOf("sv03.5-001"), d("2026-11-15"))
        assertEquals(listOf(11, 12), api.calls.map { it.first.monthValue })
    }

    @Test
    fun refresh_withoutTokenOrPro_orOffline_keepsTheCache() = runTest {
        val api = FakeApi()
        assertEquals(HistoryRefresh.NotPro, repo(api, Pro(token = null)).refresh(listOf("a-001"), d("2026-12-01")))
        assertEquals(emptyList<Any>(), api.calls)

        repo(api).refresh(listOf("a-001"), d("2026-12-01"))
        clock.advance(13 * 3600)
        api.fail = ProRequiredException(403)
        assertEquals(HistoryRefresh.NotPro, repo(api).refresh(listOf("a-001"), d("2026-12-01")))
        api.fail = IOException("offline")
        assertTrue(repo(api).refresh(listOf("a-001"), d("2026-12-01")) is HistoryRefresh.Failed)
        assertEquals(1, db.priceHistoryDao().observeCard("a-001").size)
    }

    @Test
    fun refresh_dropsMonthsNoRangeReaches() = runTest {
        val dao = db.priceHistoryDao()
        dao.replaceMonth(
            "2025-10",
            listOf("a-001"),
            listOf(row("a-001", PriceSource.CARDMARKET, PriceVariant.NORMAL, "2025-10-01" to 1)),
            clock.now,
        )
        repo(FakeApi()).refresh(listOf("a-001"), d("2026-12-01"))
        assertEquals(listOf("2026-12"), dao.observeAll("2000-01").first().map { it.month })
        assertEquals(listOf("2026-12"), dao.fetchesSince("2000-01").map { it.month })
    }

    @Test
    fun httpApi_sendsTheToken_andMapsRefusalsAndCutOffAnswers() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"month":"2026-11","sets":{"sv03.5":null,"SV2a":null}}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"pro required"}"""))
        server.enqueue(MockResponse().setBody("""{"month":"2026-11","sets":{"sv03.5":{"version":1,"""))
        server.start()
        try {
            val api = HttpPriceHistoryApi(server.url("/").toString(), OkHttpClient())
            val token = "t".repeat(32)
            assertEquals(emptyMap<String, HistoryMonth>(), api.fetchMonth(YearMonth.of(2026, 11), listOf("sv03.5", "SV2a"), token))
            val request = server.takeRequest()
            assertEquals("/v1/history/2026-11", request.requestUrl!!.encodedPath)
            assertEquals("sv03.5,SV2a", request.requestUrl!!.queryParameter("sets"))
            assertEquals("Bearer $token", request.getHeader("Authorization"))

            val refused = runCatching { api.fetchMonth(YearMonth.of(2026, 11), listOf("sv03.5"), token) }.exceptionOrNull()
            assertEquals(403, (refused as ProRequiredException).status)
            val cutOff = runCatching { api.fetchMonth(YearMonth.of(2026, 11), listOf("sv03.5"), token) }.exceptionOrNull()
            assertTrue(cutOff is IOException && cutOff !is ProRequiredException)
        } finally {
            server.shutdown()
        }
    }

    private suspend fun de.fabianduerkop.cardindex.data.db.PriceHistoryDao.observeCard(cardId: String) =
        observe(cardId, "2000-01").first()
}
