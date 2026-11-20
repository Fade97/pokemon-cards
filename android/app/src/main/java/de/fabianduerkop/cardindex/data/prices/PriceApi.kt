// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.prices

import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** The backend's price API (`backend/README.md`, "API"). */
interface PriceApi {
    /** Prices for up to [MAX_BATCH] TCGdex card ids. Ids the backend doesn't know come back in [PriceBatch.missing]. */
    suspend fun fetch(ids: List<String>): PriceBatch

    companion object {
        /** The backend's limit for one `POST /v1/prices` (D1 binds at most 100 parameters). */
        const val MAX_BATCH = 100
    }
}

data class PriceBatch(val prices: Map<String, List<CachedPriceEntity>>, val missing: List<String>)

/** HTTP 429. The backend sends `Retry-After: 60`. */
class RateLimitedException(val retryAfterSeconds: Long) : IOException("Rate limited, retry after $retryAfterSeconds s")

class HttpPriceApi(
    private val baseUrl: String,
    private val client: Call.Factory,
    private val clock: () -> Instant = Instant::now,
) : PriceApi {
    override suspend fun fetch(ids: List<String>): PriceBatch {
        require(ids.size <= PriceApi.MAX_BATCH) { "At most ${PriceApi.MAX_BATCH} ids per request" }
        if (ids.isEmpty()) return PriceBatch(emptyMap(), emptyList())
        val body = JSONObject().put("ids", JSONArray(ids)).toString().toRequestBody(JSON)
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/v1/prices").post(body).build()
        // await() resumes on the caller's dispatcher; reading the body there could be the main thread.
        return withContext(Dispatchers.IO) { read(request) }
    }

    private suspend fun read(request: Request): PriceBatch =
        client.newCall(request).await().use { response ->
            when {
                response.code == 429 -> throw RateLimitedException(response.header("Retry-After")?.toLongOrNull() ?: 60)
                !response.isSuccessful -> throw IOException("Price API answered ${response.code}")
                else -> PriceJson.parseBatch(response.body.string(), clock())
            }
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/** Maps the API's JSON onto cache rows; see the card price example in `backend/README.md`. */
object PriceJson {
    fun parseBatch(json: String, fetchedAt: Instant): PriceBatch {
        val root = JSONObject(json)
        val prices = root.optJSONObject("prices") ?: JSONObject()
        val result = prices.keys().asSequence().associateWith { id -> parseCard(id, prices.getJSONObject(id), fetchedAt) }
        val missing = root.optJSONArray("missing")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty()
        return PriceBatch(result, missing)
    }

    /**
     * One card's prices as rows per printing. Cardmarket's normal fields price the card as printed and its holo
     * fields the foil (reverse holo), see [PriceVariant.candidates]. A market with a product but no price still gets a
     * row without amounts, so the card keeps its product id for the deep link.
     */
    fun parseCard(id: String, card: JSONObject, fetchedAt: Instant): List<CachedPriceEntity> = buildList {
        card.optJSONObject("cardmarket")?.let { market ->
            val productId = market.optLongOrNull("productId")
            val date = market.optInstant("priceDate")
            val normal = market.optJSONObject("normal")
            val holo = market.optJSONObject("holo")
            val week = WeekAgo.of(market)
            add(
                row(id, PriceSource.CARDMARKET, PriceVariant.NORMAL, "EUR", productId, normal, "trend", "low", "avg", date, fetchedAt)
                    .withWeekAgo(week, "normal"),
            )
            if (holo != null) {
                add(
                    row(id, PriceSource.CARDMARKET, PriceVariant.REVERSE_HOLO, "EUR", productId, holo, "trend", "low", "avg", date, fetchedAt)
                        .withWeekAgo(week, "holo"),
                )
            }
        }
        card.optJSONObject("tcgplayer")?.let { market ->
            val productId = market.optLongOrNull("productId")
            val date = market.optInstant("priceDate")
            val variants = market.optJSONObject("variants") ?: JSONObject()
            val week = WeekAgo.of(market)
            val rows = variants.keys().asSequence().mapNotNull { subType ->
                val variant = PriceVariant.fromTcgplayerSubType(subType) ?: return@mapNotNull null
                row(id, PriceSource.TCGPLAYER, variant, "USD", productId, variants.getJSONObject(subType), "market", "low", "mid", date, fetchedAt)
                    .withWeekAgo(week, subType)
            }.distinctBy { it.variant }.toList()
            if (rows.isEmpty()) {
                add(row(id, PriceSource.TCGPLAYER, PriceVariant.NORMAL, "USD", productId, null, "market", "low", "mid", date, fetchedAt))
            } else {
                addAll(rows)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun row(
        id: String,
        source: PriceSource,
        variant: PriceVariant,
        currency: String,
        productId: Long?,
        points: JSONObject?,
        trendKey: String,
        lowKey: String,
        avgKey: String,
        priceDate: Instant?,
        fetchedAt: Instant,
    ) = CachedPriceEntity(
        cardId = id,
        source = source,
        variant = variant,
        currency = currency,
        trend = points?.optMinor(trendKey),
        low = points?.optMinor(lowKey),
        avg = points?.optMinor(avgKey),
        priceDate = priceDate,
        fetchedAt = fetchedAt,
        productId = productId,
    )

    /**
     * A market's `weekAgo` (#186): each printing's price a week before the file, keyed like the backend's history files
     * (Cardmarket `normal` / `holo`, TCGplayer sub types). Files from before #186 have none.
     */
    private class WeekAgo(val date: LocalDate, val prices: JSONObject) {
        companion object {
            fun of(market: JSONObject): WeekAgo? {
                val week = market.optJSONObject("weekAgo") ?: return null
                val date = runCatching { LocalDate.parse(week.getString("date")) }.getOrNull() ?: return null
                return WeekAgo(date, week.optJSONObject("prices") ?: return null)
            }
        }
    }

    private fun CachedPriceEntity.withWeekAgo(week: WeekAgo?, key: String): CachedPriceEntity {
        val amount = week?.prices?.optMinor(key) ?: return this
        return copy(weekAgoMinor = amount, weekAgoDate = week.date)
    }

    /** A `0` in the price guide means no price, as on the backend. */
    private fun JSONObject.optMinor(key: String): Long? =
        if (isNull(key) || !has(key)) null else optDouble(key).takeIf { it.isFinite() && it > 0 }?.let(Money::toMinor)

    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)

    private fun JSONObject.optInstant(key: String): Instant? =
        if (isNull(key) || !has(key)) null else runCatching { Instant.parse(getString(key)) }.getOrNull()
}

internal suspend fun Call.await(): Response = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: Call, e: IOException) { cont.resumeWith(Result.failure(e)) }
        override fun onResponse(call: Call, response: Response) { cont.resumeWith(Result.success(response)) }
    })
}
