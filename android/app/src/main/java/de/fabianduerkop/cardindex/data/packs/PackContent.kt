// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import de.fabianduerkop.cardindex.model.Printings
import de.fabianduerkop.cardindex.recognition.PackIntegrityException
import de.fabianduerkop.cardindex.recognition.sha256Hex
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.DigestOutputStream
import java.security.MessageDigest
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

/**
 * A card entry. [image] is TCGdex's base URL (append `/low.webp`), null when TCGdex has none. [printings] is null
 * when unknown: TCGdex had no answer, or the pack is older than the field (#143).
 */
data class PackCard(
    val id: String,
    val setId: String,
    val localId: String,
    val name: String,
    val image: String?,
    val printings: Printings? = null,
)

/**
 * A pack's card file: [sets] and [cards] in canonical order (cards by id, code unit by code unit) and which cards
 * have an index row ([indexed]). The rows themselves are in [PackContent] or, while a delta is installed, in a file
 * ([StreamedPack]).
 */
class PackCards(
    val lang: String,
    val version: Int,
    sets: List<PackSet>,
    cards: List<PackCard>,
    val indexed: Set<String>,
) {
    val sets: List<PackSet> = sets.sortedWith(compareBy<PackSet>({ it.releaseDate ?: "9999" }, { it.id }))
    val cards: List<PackCard> = cards.sortedBy { it.id }

    init {
        val ids = this.cards.mapTo(HashSet()) { it.id }
        require(ids.size == this.cards.size) { "$lang: duplicate card ids" }
        require(ids.containsAll(indexed)) { "$lang: index rows for unknown cards" }
    }

    /** Ids of the cards with an index row, in row order. */
    val indexedIds: List<String> by lazy { this.cards.filter { it.id in indexed }.map { it.id } }

    /** SHA-256 of the indexed ids joined with `\n`, the catalog's `keysSha256`. */
    fun keysSha256(): String = sha256Hex(indexedIds.joinToString("\n").toByteArray(Charsets.UTF_8))

    /** The card file (`cards-<v>.json.gz` layout), so the installed pack can be the base of the next delta. */
    fun cardsJsonGz(): ByteArray {
        val doc = JSONObject()
            .put("formatVersion", PackCatalog.FORMAT_VERSION)
            .put("lang", lang)
            .put("version", version)
            .put("sets", JSONArray(sets.map(PackContent::setJson)))
            .put("cards", JSONArray(cards.map { PackContent.cardJson(it).put("indexed", it.id in indexed) }))
        return PackContent.gzip(doc.toString().toByteArray(Charsets.UTF_8))
    }

    /** Checks the pack against what the catalog promised; [indexSha256] is that of its index, null without rows. */
    fun verify(
        indexSha256: () -> String?,
        expectedKeysSha256: String,
        expectedIndexSha256: String?,
        expectedCards: Int,
        expectedMetadataCards: Int,
    ) {
        val problems = buildList {
            if (cards.size != expectedMetadataCards) add("$lang has ${cards.size} cards, expected $expectedMetadataCards")
            if (indexed.size != expectedCards) add("$lang has ${indexed.size} index rows, expected $expectedCards")
            if (!keysSha256().equals(expectedKeysSha256, ignoreCase = true)) add("$lang: indexed card ids differ")
            if (expectedIndexSha256 != null && !expectedIndexSha256.equals(indexSha256(), ignoreCase = true)) add("$lang: index differs")
        }
        if (problems.isNotEmpty()) throw PackIntegrityException(problems.joinToString("; "))
    }

    /** Steps 2 to 4 of "Applying a delta" (docs/packs.md) for the card file; the rows are the caller's. */
    internal fun apply(delta: DeltaDocument): PackCards {
        val sets = this.sets.associateByTo(LinkedHashMap()) { it.id }
        delta.removeSets.forEach(sets::remove)
        delta.upsertSets.forEach { sets[it.id] = it }

        val cards = this.cards.associateByTo(LinkedHashMap()) { it.id }
        val indexed = HashSet(this.indexed)
        for (id in delta.remove) {
            cards.remove(id)
            indexed.remove(id)
        }
        for ((card, hasRow) in delta.upsert) {
            cards[card.id] = card
            if (hasRow) indexed += card.id else indexed -= card.id
        }
        return PackCards(lang, delta.to, sets.values.toList(), cards.values.toList(), indexed)
    }

    companion object {
        /** Reads a card file without its index. */
        fun parse(cardsJsonGz: ByteArray): PackCards {
            val doc = JSONObject(PackContent.gunzip(cardsJsonGz).toString(Charsets.UTF_8))
            if (doc.optInt("formatVersion") != PackCatalog.FORMAT_VERSION) {
                throw UnsupportedPackFormatException("pack format ${doc.optInt("formatVersion")}")
            }
            val cardObjects = doc.getJSONArray("cards").objects()
            return PackCards(
                lang = doc.getString("lang"),
                version = doc.getInt("version"),
                sets = doc.getJSONArray("sets").objects().map(PackContent::parseSet),
                cards = cardObjects.map(PackContent::parseCard),
                indexed = cardObjects.filter { it.optBoolean("indexed") }.mapTo(HashSet()) { it.getString("id") },
            )
        }
    }
}

/** A delta's card file (docs/packs.md, "Deltas"); its rows are a separate file. */
internal class DeltaDocument(
    val lang: String,
    val from: Int,
    val to: Int,
    val upsertSets: List<PackSet>,
    val removeSets: List<String>,
    /** Cards to insert or replace, each with whether it takes the next row of the delta's `.bin`. */
    val upsert: List<Pair<PackCard, Boolean>>,
    val remove: List<String>,
) {
    val indexedUpserts: Int = upsert.count { it.second }

    fun checkAppliesTo(lang: String, version: Int) {
        if (this.lang != lang || from != version) throw PackIntegrityException("delta ${this.lang} $from→ does not apply to $lang $version")
    }

    /** The row width: the pack's [dim], or for a pack without rows the one [rowBytes] (the `.bin`'s size) implies. */
    fun rowDim(dim: Int, rowBytes: Long): Int {
        if (indexedUpserts == 0) return dim
        val rowDim = if (dim > 0) dim else (rowBytes / indexedUpserts / 4).toInt()
        if (rowBytes != indexedUpserts.toLong() * rowDim * 4) throw PackIntegrityException("delta rows do not match its indexed cards")
        return rowDim
    }

    companion object {
        fun parse(deltaJsonGz: ByteArray): DeltaDocument {
            val doc = JSONObject(PackContent.gunzip(deltaJsonGz).toString(Charsets.UTF_8))
            if (doc.optInt("formatVersion") != PackCatalog.FORMAT_VERSION) throw UnsupportedPackFormatException("delta format ${doc.optInt("formatVersion")}")
            return DeltaDocument(
                lang = doc.getString("lang"),
                from = doc.getInt("from"),
                to = doc.getInt("to"),
                upsertSets = doc.getJSONArray("upsertSets").objects().map(PackContent::parseSet),
                removeSets = doc.getJSONArray("removeSets").strings(),
                upsert = doc.getJSONArray("upsert").objects().map { PackContent.parseCard(it) to it.optBoolean("indexed") },
                remove = doc.getJSONArray("remove").strings(),
            )
        }
    }
}

/**
 * A pack whose index is a file of [dim] float32 rows in [PackCards.indexedIds] order. Applying a delta streams the
 * old index and the delta's rows into a new file, row by row, so neither index sits on the Java heap (issue #89;
 * a large index is ~22 MB). The result is the same as [PackContent.applyDelta], byte for byte.
 */
class StreamedPack(val cardFile: PackCards, val dim: Int, val index: File, val indexSha256: String) {
    fun verify(expectedKeysSha256: String, expectedIndexSha256: String?, expectedCards: Int, expectedMetadataCards: Int) =
        cardFile.verify(
            { if (cardFile.indexed.isEmpty()) null else indexSha256 },
            expectedKeysSha256, expectedIndexSha256, expectedCards, expectedMetadataCards,
        )

    /** Applies a delta, writing the new index to [out]. The caller verifies the result as with [PackContent]. */
    fun applyDelta(deltaJsonGz: ByteArray, deltaRows: File?, out: File): StreamedPack {
        val delta = DeltaDocument.parse(deltaJsonGz)
        delta.checkAppliesTo(cardFile.lang, cardFile.version)
        val rowDim = delta.rowDim(dim, deltaRows?.length() ?: 0L)
        val next = cardFile.apply(delta)
        val oldIds = cardFile.indexedIds
        if (oldIds.isNotEmpty() && (dim <= 0 || index.length() != oldIds.size.toLong() * dim * 4)) {
            throw PackIntegrityException("${cardFile.lang}: index.bin (${index.length()} bytes) does not fit ${oldIds.size} cards of dim $dim")
        }
        // Row n of the delta's .bin belongs to its n-th indexed upsert; a later upsert of the same card wins.
        val fromDelta = HashMap<String, Int>()
        var n = 0
        for ((card, hasRow) in delta.upsert) if (hasRow) fromDelta[card.id] = n++ else fromDelta.remove(card.id)

        // With rows on both sides, dim > 0 and rowDim == dim, so one buffer fits old and new rows.
        val row = ByteArray(rowDim * 4)
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(out.outputStream().buffered(), digest).use { sink ->
            (if (oldIds.isEmpty()) null else DataInputStream(index.inputStream().buffered())).use { old ->
                deltaRows?.let { RandomAccessFile(it, "r") }.use { rows ->
                    var oldPos = 0
                    for (id in next.indexedIds) {
                        val d = fromDelta[id]
                        if (d != null) {
                            rows?.run {
                                seek(d.toLong() * row.size)
                                readFully(row)
                            }
                        } else {
                            // The kept rows come in the old index's order: skip the ones dropped or replaced.
                            while (oldPos < oldIds.size && oldIds[oldPos] != id) {
                                old!!.readFully(row)
                                oldPos++
                            }
                            check(oldPos < oldIds.size) { "${cardFile.lang}: $id has no row in the old index" }
                            old!!.readFully(row)
                            oldPos++
                        }
                        sink.write(row)
                    }
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return StreamedPack(next, if (next.indexed.isEmpty()) 0 else rowDim, out, sha)
    }
}

/**
 * One language pack in memory, mirroring `packs.Pack` in ml/packs.py: [cards] in canonical order (by id, code unit by
 * code unit), [rows] the embedding of every indexed card (`dim * 4` bytes, float32 little-endian).
 */
class PackContent(
    lang: String,
    version: Int,
    sets: List<PackSet>,
    cards: List<PackCard>,
    val rows: Map<String, ByteArray>,
    val dim: Int,
) {
    val cardFile = PackCards(lang, version, sets, cards, rows.keys)
    val lang: String get() = cardFile.lang
    val version: Int get() = cardFile.version
    val sets: List<PackSet> get() = cardFile.sets
    val cards: List<PackCard> get() = cardFile.cards

    init {
        require(rows.values.all { it.size == dim * 4 }) { "$lang: index rows are not $dim float32 values" }
    }

    /** Ids of the cards with an index row, in row order. */
    val indexedIds: List<String> get() = cardFile.indexedIds

    fun indexBytes(): ByteArray {
        val out = ByteArrayOutputStream(rows.size * dim * 4)
        for (id in indexedIds) out.write(rows.getValue(id))
        return out.toByteArray()
    }

    /** SHA-256 of the indexed ids joined with `\n`, the catalog's `keysSha256`. */
    fun keysSha256(): String = cardFile.keysSha256()

    /** The card file (`cards-<v>.json.gz` layout), so the installed pack can be the base of the next delta. */
    fun cardsJsonGz(): ByteArray = cardFile.cardsJsonGz()

    /** Checks the pack against what the catalog promised for it. */
    fun verify(expectedKeysSha256: String, expectedIndexSha256: String?, expectedCards: Int, expectedMetadataCards: Int) =
        cardFile.verify(
            { if (rows.isEmpty()) null else sha256Hex(indexBytes()) },
            expectedKeysSha256, expectedIndexSha256, expectedCards, expectedMetadataCards,
        )

    /**
     * Applies a delta (docs/packs.md, "Applying a delta") in memory. The app streams deltas with [StreamedPack]; this
     * is the reference the tests compare it with. The caller checks the result with [verify] against the delta's
     * `result` and falls back to the full pack when it differs.
     */
    fun applyDelta(deltaJsonGz: ByteArray, deltaRows: ByteArray?): PackContent {
        val delta = DeltaDocument.parse(deltaJsonGz)
        delta.checkAppliesTo(lang, version)
        val rowBytes = deltaRows ?: ByteArray(0)
        val rowDim = delta.rowDim(dim, rowBytes.size.toLong())
        val next = cardFile.apply(delta)
        val rows = HashMap(this.rows)
        delta.remove.forEach(rows::remove)
        val step = rowDim * 4
        var n = 0
        for ((card, hasRow) in delta.upsert) {
            if (hasRow) {
                rows[card.id] = rowBytes.copyOfRange(n * step, (n + 1) * step)
                n++
            } else {
                rows.remove(card.id)
            }
        }
        return PackContent(lang, next.version, next.sets, next.cards, rows, if (rows.isEmpty()) 0 else rowDim)
    }

    companion object {
        /** Reads a card file and its index (null for a pack without embeddings). */
        fun parse(cardsJsonGz: ByteArray, index: ByteArray?, dim: Int): PackContent {
            val file = PackCards.parse(cardsJsonGz)
            // Rows follow the indexed cards in canonical order, whatever order the file lists them in.
            val indexed = file.indexedIds
            val rows = HashMap<String, ByteArray>(indexed.size * 2)
            if (indexed.isNotEmpty()) {
                if (index == null || dim <= 0 || index.size != indexed.size * dim * 4) {
                    throw PackIntegrityException("${file.lang}: index.bin (${index?.size ?: 0} bytes) does not fit ${indexed.size} cards of dim $dim")
                }
                val step = dim * 4
                indexed.forEachIndexed { i, id -> rows[id] = index.copyOfRange(i * step, (i + 1) * step) }
            }
            return PackContent(file.lang, file.version, file.sets, file.cards, rows, if (rows.isEmpty()) 0 else dim)
        }

        /** Only the card and set entries, without the index: enough to fill the Room catalog. */
        fun parseMetadata(cardsJsonGz: ByteArray): Pair<List<PackSet>, List<Pair<PackCard, Boolean>>> {
            val doc = JSONObject(gunzip(cardsJsonGz).toString(Charsets.UTF_8))
            val sets = doc.getJSONArray("sets").objects().map(::parseSet)
            val cards = doc.getJSONArray("cards").objects().map { parseCard(it) to it.optBoolean("indexed") }
            return sets to cards
        }

        internal fun parseSet(o: JSONObject) = PackSet(
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

        internal fun parseCard(o: JSONObject) = PackCard(
            id = o.getString("id"),
            setId = o.getString("setId"),
            localId = o.getString("localId"),
            name = o.optStringOrNull("name").orEmpty(),
            image = o.optStringOrNull("image"),
            printings = o.optJSONArray("printings")?.strings()?.let(Printings::ofKeys),
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
            .put("printings", c.printings?.let { JSONArray(it.keys()) } ?: JSONObject.NULL)

        private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else getInt(key)

        fun gzip(bytes: ByteArray): ByteArray =
            ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

        fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
    }
}

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
