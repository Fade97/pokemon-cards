// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import de.fabianduerkop.cardindex.recognition.PackIntegrityException
import de.fabianduerkop.cardindex.recognition.sha256Hex
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A set entry of a pack's card file. Everything but [id] and [name] can be null (docs/packs.md). */
data class PackSet(
    val id: String,
    val name: String,
    val serieId: String?,
    val serieName: String?,
    val releaseDate: String?,
    val official: Int?,
    val total: Int?,
    val abbreviation: String?,
    val logo: String?,
    val symbol: String?,
)

/** A card entry. [image] is TCGdex's base URL (append `/low.webp`), null when TCGdex has none. */
data class PackCard(val id: String, val setId: String, val localId: String, val name: String, val image: String?)

/**
 * One language pack in memory, mirroring `packs.Pack` in ml/packs.py: [cards] in canonical order (by id, code unit by
 * code unit), [rows] the embedding of every indexed card (`dim * 4` bytes, float32 little-endian).
 */
class PackContent(
    val lang: String,
    val version: Int,
    sets: List<PackSet>,
    cards: List<PackCard>,
    val rows: Map<String, ByteArray>,
    val dim: Int,
) {
    val sets: List<PackSet> = sets.sortedWith(compareBy<PackSet>({ it.releaseDate ?: "9999" }, { it.id }))
    val cards: List<PackCard> = cards.sortedBy { it.id }

    init {
        val ids = this.cards.mapTo(HashSet()) { it.id }
        require(ids.size == this.cards.size) { "$lang: duplicate card ids" }
        require(ids.containsAll(rows.keys)) { "$lang: index rows for unknown cards" }
        require(rows.values.all { it.size == dim * 4 }) { "$lang: index rows are not $dim float32 values" }
    }

    /** Ids of the cards with an index row, in row order. */
    val indexedIds: List<String> get() = cards.filter { it.id in rows }.map { it.id }

    fun indexBytes(): ByteArray {
        val out = ByteArrayOutputStream(rows.size * dim * 4)
        for (id in indexedIds) out.write(rows.getValue(id))
        return out.toByteArray()
    }

    /** SHA-256 of the indexed ids joined with `\n`, the catalog's `keysSha256`. */
    fun keysSha256(): String = sha256Hex(indexedIds.joinToString("\n").toByteArray(Charsets.UTF_8))

    /** The card file (`cards-<v>.json.gz` layout), so the installed pack can be the base of the next delta. */
    fun cardsJsonGz(): ByteArray {
        val doc = JSONObject()
            .put("formatVersion", PackCatalog.FORMAT_VERSION)
            .put("lang", lang)
            .put("version", version)
            .put("sets", JSONArray(sets.map(::setJson)))
            .put("cards", JSONArray(cards.map { cardJson(it).put("indexed", it.id in rows) }))
        return gzip(doc.toString().toByteArray(Charsets.UTF_8))
    }

    /** Checks the pack against what the catalog promised for it. */
    fun verify(expectedKeysSha256: String, expectedIndexSha256: String?, expectedCards: Int, expectedMetadataCards: Int) {
        val problems = buildList {
            if (cards.size != expectedMetadataCards) add("$lang has ${cards.size} cards, expected $expectedMetadataCards")
            if (rows.size != expectedCards) add("$lang has ${rows.size} index rows, expected $expectedCards")
            if (!keysSha256().equals(expectedKeysSha256, ignoreCase = true)) add("$lang: indexed card ids differ")
            val index = if (rows.isEmpty()) null else sha256Hex(indexBytes())
            if (expectedIndexSha256 != null && !expectedIndexSha256.equals(index, ignoreCase = true)) add("$lang: index differs")
        }
        if (problems.isNotEmpty()) throw PackIntegrityException(problems.joinToString("; "))
    }

    /**
     * Applies a delta (docs/packs.md, "Applying a delta"). The caller checks the result with [verify] against the
     * delta's `result` and falls back to the full pack when it differs.
     */
    fun applyDelta(deltaJsonGz: ByteArray, deltaRows: ByteArray?): PackContent {
        val doc = JSONObject(gunzip(deltaJsonGz).toString(Charsets.UTF_8))
        if (doc.optInt("formatVersion") != PackCatalog.FORMAT_VERSION) throw UnsupportedPackFormatException("delta format ${doc.optInt("formatVersion")}")
        if (doc.getString("lang") != lang || doc.getInt("from") != version) {
            throw PackIntegrityException("delta ${doc.getString("lang")} ${doc.getInt("from")}→ does not apply to $lang $version")
        }
        val upsert = doc.getJSONArray("upsert").objects()
        val indexedUpserts = upsert.count { it.optBoolean("indexed") }
        val rowBytes = deltaRows ?: ByteArray(0)
        var rowDim = dim
        if (indexedUpserts > 0) {
            if (rowDim <= 0) rowDim = rowBytes.size / indexedUpserts / 4
            if (rowBytes.size != indexedUpserts * rowDim * 4) throw PackIntegrityException("delta rows do not match its indexed cards")
        }

        val sets = this.sets.associateByTo(LinkedHashMap()) { it.id }
        doc.getJSONArray("removeSets").strings().forEach(sets::remove)
        doc.getJSONArray("upsertSets").objects().map(::parseSet).forEach { sets[it.id] = it }

        val cards = this.cards.associateByTo(LinkedHashMap()) { it.id }
        val rows = HashMap(this.rows)
        for (id in doc.getJSONArray("remove").strings()) {
            cards.remove(id)
            rows.remove(id)
        }
        val step = rowDim * 4
        var n = 0
        for (o in upsert) {
            val card = parseCard(o)
            cards[card.id] = card
            if (o.optBoolean("indexed")) {
                rows[card.id] = rowBytes.copyOfRange(n * step, (n + 1) * step)
                n++
            } else {
                rows.remove(card.id)
            }
        }
        return PackContent(lang, doc.getInt("to"), sets.values.toList(), cards.values.toList(), rows, if (rows.isEmpty()) 0 else rowDim)
    }

    companion object {
        /** Reads a card file and its index (null for a pack without embeddings). */
        fun parse(cardsJsonGz: ByteArray, index: ByteArray?, dim: Int): PackContent {
            val doc = JSONObject(gunzip(cardsJsonGz).toString(Charsets.UTF_8))
            if (doc.optInt("formatVersion") != PackCatalog.FORMAT_VERSION) {
                throw UnsupportedPackFormatException("pack format ${doc.optInt("formatVersion")}")
            }
            val lang = doc.getString("lang")
            val cardObjects = doc.getJSONArray("cards").objects()
            val cards = cardObjects.map(::parseCard)
            // Rows follow the indexed cards in canonical order, whatever order the file lists them in.
            val indexed = cardObjects.filter { it.optBoolean("indexed") }.map { it.getString("id") }.sorted()
            val rows = HashMap<String, ByteArray>(indexed.size * 2)
            if (indexed.isNotEmpty()) {
                if (index == null || dim <= 0 || index.size != indexed.size * dim * 4) {
                    throw PackIntegrityException("$lang: index.bin (${index?.size ?: 0} bytes) does not fit ${indexed.size} cards of dim $dim")
                }
                val step = dim * 4
                indexed.forEachIndexed { i, id -> rows[id] = index.copyOfRange(i * step, (i + 1) * step) }
            }
            return PackContent(lang, doc.getInt("version"), doc.getJSONArray("sets").objects().map(::parseSet), cards, rows, if (rows.isEmpty()) 0 else dim)
        }

        /** Only the card and set entries, without the index: enough to fill the Room catalog. */
        fun parseMetadata(cardsJsonGz: ByteArray): Pair<List<PackSet>, List<Pair<PackCard, Boolean>>> {
            val doc = JSONObject(gunzip(cardsJsonGz).toString(Charsets.UTF_8))
            val sets = doc.getJSONArray("sets").objects().map(::parseSet)
            val cards = doc.getJSONArray("cards").objects().map { parseCard(it) to it.optBoolean("indexed") }
            return sets to cards
        }

        private fun parseSet(o: JSONObject) = PackSet(
            id = o.getString("id"),
            name = o.optStringOrNull("name") ?: o.getString("id"),
            serieId = o.optStringOrNull("serieId"),
            serieName = o.optStringOrNull("serieName"),
            releaseDate = o.optStringOrNull("releaseDate"),
            official = o.optIntOrNull("official"),
            total = o.optIntOrNull("total"),
            abbreviation = o.optStringOrNull("abbreviation"),
            logo = o.optStringOrNull("logo"),
            symbol = o.optStringOrNull("symbol"),
        )

        private fun parseCard(o: JSONObject) = PackCard(
            id = o.getString("id"),
            setId = o.getString("setId"),
            localId = o.getString("localId"),
            name = o.optStringOrNull("name").orEmpty(),
            image = o.optStringOrNull("image"),
        )

        internal fun setJson(s: PackSet) = JSONObject()
            .put("id", s.id).put("name", s.name)
            .put("serieId", s.serieId ?: JSONObject.NULL).put("serieName", s.serieName ?: JSONObject.NULL)
            .put("releaseDate", s.releaseDate ?: JSONObject.NULL)
            .put("official", s.official ?: JSONObject.NULL).put("total", s.total ?: JSONObject.NULL)
            .put("abbreviation", s.abbreviation ?: JSONObject.NULL)
            .put("logo", s.logo ?: JSONObject.NULL).put("symbol", s.symbol ?: JSONObject.NULL)

        internal fun cardJson(c: PackCard) = JSONObject()
            .put("id", c.id).put("setId", c.setId).put("localId", c.localId).put("name", c.name)
            .put("image", c.image ?: JSONObject.NULL)

        private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else getInt(key)
        private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
        private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

        fun gzip(bytes: ByteArray): ByteArray =
            ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

        fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
    }
}
