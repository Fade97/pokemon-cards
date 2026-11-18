// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.prices

import de.fabianduerkop.cardindex.data.db.PriceHistoryEntity
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The backend's Pro history API (`backend/README.md`, "Price history"). */
interface PriceHistoryApi {
    /**
     * The history of [month] for up to [MAX_SETS] sets ([PriceShards]), as cache rows per card. A set the backend has
     * no file for is missing from the result.
     */
    suspend fun fetchMonth(month: YearMonth, sets: List<String>, token: String): Map<String, HistoryMonth>

    companion object {
        /** The backend's limit per request (`MAX_HISTORY_SETS`). */
        const val MAX_SETS = 20
    }
}

/** One set's month: the rows of every card the file has. */
data class HistoryMonth(val cards: Map<String, List<PriceHistoryEntity>>)

/** HTTP 401/403: no token, or a token without Pro. Retrying doesn't help. */
class ProRequiredException(val status: Int) : IOException("Pro required (HTTP $status)")

class HttpPriceHistoryApi(private val baseUrl: String, private val client: Call.Factory) : PriceHistoryApi {
    override suspend fun fetchMonth(month: YearMonth, sets: List<String>, token: String): Map<String, HistoryMonth> {
        require(sets.size <= PriceHistoryApi.MAX_SETS) { "At most ${PriceHistoryApi.MAX_SETS} sets per request" }
        if (sets.isEmpty()) return emptyMap()
        val url = (baseUrl.trimEnd('/') + "/v1/history/$month").toHttpUrl().newBuilder()
            .addQueryParameter("sets", sets.joinToString(","))
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        return client.newCall(request).await().use { response ->
            when {
                response.code == 401 || response.code == 403 -> throw ProRequiredException(response.code)
                response.code == 429 -> throw RateLimitedException(response.header("Retry-After")?.toLongOrNull() ?: 60)
                !response.isSuccessful -> throw IOException("History API answered ${response.code}")
                // A stream cut off mid-way is invalid JSON: a failed request, not an empty month.
                else -> try {
                    PriceHistoryJson.parseEnvelope(response.body.string())
                } catch (e: JSONException) {
                    throw IOException("Malformed history answer", e)
                }
            }
        }
    }
}

/** Maps the history files onto cache rows. Cardmarket's `holo` is stored as reverse holo, as in [PriceJson]. */
object PriceHistoryJson {
    fun parseEnvelope(json: String): Map<String, HistoryMonth> {
        val sets = JSONObject(json).optJSONObject("sets") ?: return emptyMap()
        return sets.keys().asSequence()
            .filterNot { sets.isNull(it) }
            .associateWith { parseFile(sets.getJSONObject(it)) }
    }

    fun parseFile(file: JSONObject): HistoryMonth {
        val month = YearMonth.parse(file.getString("month"))
        val days = file.getJSONArray("days").let { array -> List(array.length()) { LocalDate.parse(array.getString(it)) } }
        val cards = file.optJSONObject("cards") ?: JSONObject()
        return HistoryMonth(
            cards.keys().asSequence().associateWith { id -> parseCard(id, cards.getJSONObject(id), month, days) },
        )
    }

    private fun parseCard(id: String, card: JSONObject, month: YearMonth, days: List<LocalDate>): List<PriceHistoryEntity> =
        buildList {
            card.optJSONObject("cardmarket")?.let { market ->
                market.optJSONArray("normal")?.let { add(row(id, PriceSource.CARDMARKET, PriceVariant.NORMAL, month, days, it)) }
                market.optJSONArray("holo")?.let { add(row(id, PriceSource.CARDMARKET, PriceVariant.REVERSE_HOLO, month, days, it)) }
            }
            card.optJSONObject("tcgplayer")?.let { market ->
                market.keys().asSequence()
                    .mapNotNull { subType ->
                        val variant = PriceVariant.fromTcgplayerSubType(subType) ?: return@mapNotNull null
                        market.optJSONArray(subType)?.let { row(id, PriceSource.TCGPLAYER, variant, month, days, it) }
                    }
                    .distinctBy { it.variant }
                    .forEach(::add)
            }
        }

    @Suppress("LongParameterList")
    private fun row(
        id: String,
        source: PriceSource,
        variant: PriceVariant,
        month: YearMonth,
        days: List<LocalDate>,
        values: JSONArray,
    ): PriceHistoryEntity {
        val points = days.mapIndexed { i, day ->
            day to if (i >= values.length() || values.isNull(i)) null else values.optLong(i).takeIf { it > 0 }
        }
        return PriceHistoryCodec.entity(id, source, variant, month, points)
    }
}
