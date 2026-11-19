// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.backup

import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CollectionEntity
import de.fabianduerkop.cardindex.data.alerts.AlertDirection
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.db.PriceAlertEntity
import de.fabianduerkop.cardindex.data.db.WantedCardEntity
import de.fabianduerkop.cardindex.model.CollectionKind
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.EnergyType
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Grader
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.model.Region
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything a backup holds: the user's copies, custom collections and price alerts, plus the catalog rows those
 * point to, so an import works on a phone whose language packs don't have the cards yet. Format: android/README.md,
 * "Backup".
 */
data class BackupData(
    val sets: List<CardSetEntity>,
    val cards: List<CardEntity>,
    val copies: List<OwnedCopyEntity>,
    val collections: List<CollectionEntity>,
    /** Collection uuid → uuids of the copies in it. */
    val memberships: Map<String, List<String>>,
    /** Price alerts (#76), since format version 2. Only the JSON carries them; the CSV is one row per copy. */
    val alerts: List<PriceAlertEntity> = emptyList(),
    /** The wishlist (#134), since format version 3. JSON only, like alerts. */
    val wanted: List<WantedCardEntity> = emptyList(),
)

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A copy is at least one card (#127); both readers turn the thrown IllegalArgumentException into a damaged-file error. */
internal fun validQuantity(quantity: Int): Int = quantity.also { require(it >= 1) { "quantity $it" } }

/** The versioned JSON backup. Enums are stored by name, dates as ISO-8601, money in minor units. */
object BackupJson {
    const val FORMAT = "cardindex-backup"

    /**
     * Bump when a reader of the old version would misread the new one or silently drop part of it; other new fields
     * need no bump. 2 added `alerts` (#94): a version-1 app would restore such a file without its alerts and not say so.
     * 3 added `wanted` (#134), the wishlist, for the same reason.
     */
    const val VERSION = 3

    fun write(data: BackupData, exportedAt: Instant): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("exportedAt", exportedAt.toString())
        .put("sets", JSONArray(data.sets.map { it.toJson() }))
        .put("cards", JSONArray(data.cards.map { it.toJson() }))
        .put("copies", JSONArray(data.copies.map { it.toJson() }))
        .put(
            "collections",
            JSONArray(
                data.collections.map { c ->
                    JSONObject()
                        .put("uuid", c.uuid)
                        .put("name", c.name)
                        .putOpt("description", c.description)
                        .put("updatedAt", c.updatedAt.toString())
                        .put("copies", JSONArray(data.memberships[c.uuid].orEmpty()))
                },
            ),
        )
        .put("alerts", JSONArray(data.alerts.map { it.toJson() }))
        .put("wanted", JSONArray(data.wanted.map { it.toJson() }))
        .toString(2)

    fun read(text: String): BackupData {
        val root = try {
            JSONObject(text)
        } catch (e: org.json.JSONException) {
            throw BackupFormatException("Not a JSON file", e)
        }
        if (root.optString("format") != FORMAT) throw BackupFormatException("Not a Foilark backup")
        val version = root.optInt("version", -1)
        if (version < 1) throw BackupFormatException("Backup has no version")
        if (version > VERSION) throw BackupFormatException("Backup is from a newer app version ($version)")
        return try {
            val collections = root.optJSONArray("collections").objects()
            BackupData(
                sets = root.optJSONArray("sets").objects().map(::setFromJson),
                cards = root.optJSONArray("cards").objects().map(::cardFromJson),
                copies = root.optJSONArray("copies").objects().map(::copyFromJson),
                collections = collections.map {
                    CollectionEntity(
                        uuid = it.getString("uuid"),
                        name = it.getString("name"),
                        description = it.optStringOrNull("description"),
                        kind = CollectionKind.CUSTOM,
                        updatedAt = Instant.parse(it.getString("updatedAt")),
                    )
                },
                memberships = collections.associate { it.getString("uuid") to it.optJSONArray("copies").strings() },
                // Missing in version 1 files: those restore without alerts.
                alerts = root.optJSONArray("alerts").objects().map(::alertFromJson),
                // Missing before version 3: those restore without a wishlist.
                wanted = root.optJSONArray("wanted").objects().map(::wantedFromJson),
            )
        } catch (e: org.json.JSONException) {
            throw BackupFormatException("Backup is damaged: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("Backup is damaged: ${e.message}", e)
        } catch (e: java.time.format.DateTimeParseException) {
            throw BackupFormatException("Backup is damaged: ${e.message}", e)
        }
    }

    private fun CardSetEntity.toJson() = JSONObject()
        .put("id", id).put("name", name).put("series", series).put("language", language)
        .put("printedTotal", printedTotal).put("region", region.name)

    private fun setFromJson(o: JSONObject) = CardSetEntity(
        id = o.getString("id"),
        name = o.getString("name"),
        series = o.optString("series"),
        language = o.getString("language"),
        printedTotal = o.optInt("printedTotal"),
        region = Region.valueOf(o.optString("region", Region.INTERNATIONAL.name)),
    )

    private fun CardEntity.toJson() = JSONObject()
        .put("id", id).put("language", language).put("setId", setId).put("localId", localId).put("name", name)
        .putOpt("rarity", rarity).putOpt("energyType", energyType?.name).putOpt("imageUrl", imageUrl)

    private fun cardFromJson(o: JSONObject) = CardEntity(
        id = o.getString("id"),
        setId = o.getString("setId"),
        localId = o.getString("localId"),
        name = o.getString("name"),
        language = o.getString("language"),
        rarity = o.optStringOrNull("rarity"),
        energyType = o.optStringOrNull("energyType")?.let(EnergyType::valueOf),
        imageUrl = o.optStringOrNull("imageUrl"),
    )

    private fun OwnedCopyEntity.toJson() = JSONObject()
        .put("uuid", uuid)
        .put("cardId", cardId)
        .put("cardLanguage", cardLanguage)
        .put("quantity", quantity)
        .put("finish", finish.name)
        .put("edition", edition.name)
        .putOpt("condition", condition?.name)
        .putOpt("grader", grader?.name)
        .putOpt("grade", grade)
        .putOpt("purchasePriceMinor", purchasePriceCents)
        .putOpt("purchaseCurrency", purchaseCurrency)
        .putOpt("purchaseDate", purchaseDate?.toString())
        .put("createdAt", createdAt.toString())
        .put("updatedAt", updatedAt.toString())

    private fun copyFromJson(o: JSONObject): OwnedCopyEntity {
        val createdAt = Instant.parse(o.getString("createdAt"))
        return OwnedCopyEntity(
            uuid = UUID.fromString(o.getString("uuid")).toString(),
            cardId = o.getString("cardId"),
            cardLanguage = o.getString("cardLanguage"),
            quantity = validQuantity(o.optInt("quantity", 1)),
            finish = Finish.valueOf(o.optString("finish", Finish.NORMAL.name)),
            edition = Edition.valueOf(o.optString("edition", Edition.UNLIMITED.name)),
            condition = o.optStringOrNull("condition")?.let(Condition::valueOf),
            grader = o.optStringOrNull("grader")?.let(Grader::valueOf),
            grade = o.optStringOrNull("grade"),
            purchasePriceCents = if (o.isNull("purchasePriceMinor")) null else o.getLong("purchasePriceMinor"),
            purchaseCurrency = o.optStringOrNull("purchaseCurrency"),
            purchaseDate = o.optStringOrNull("purchaseDate")?.let(LocalDate::parse),
            createdAt = createdAt,
            updatedAt = o.optStringOrNull("updatedAt")?.let(Instant::parse) ?: createdAt,
        )
    }

    private fun WantedCardEntity.toJson() = JSONObject()
        .put("uuid", uuid)
        .put("cardId", cardId)
        .put("cardLanguage", cardLanguage)
        .put("createdAt", createdAt.toString())
        .put("updatedAt", updatedAt.toString())

    private fun wantedFromJson(o: JSONObject): WantedCardEntity {
        val createdAt = Instant.parse(o.getString("createdAt"))
        return WantedCardEntity(
            uuid = UUID.fromString(o.getString("uuid")).toString(),
            cardId = o.getString("cardId"),
            cardLanguage = o.getString("cardLanguage"),
            createdAt = createdAt,
            updatedAt = o.optStringOrNull("updatedAt")?.let(Instant::parse) ?: createdAt,
        )
    }

    /** Same field names as [PriceAlertEntity], which the server keeps when alerts move there with #16 (ADR 0013). */
    private fun PriceAlertEntity.toJson() = JSONObject()
        .put("uuid", uuid)
        .put("cardId", cardId)
        .put("cardLanguage", cardLanguage)
        .put("source", source.name)
        .put("variant", variant.name)
        .put("direction", direction.name)
        .put("thresholdMinor", thresholdMinor)
        .put("currency", currency)
        .put("createdAt", createdAt.toString())
        .putOpt("triggeredAt", triggeredAt?.toString())
        .put("armed", armed)

    private fun alertFromJson(o: JSONObject): PriceAlertEntity {
        val threshold = o.getLong("thresholdMinor")
        require(threshold > 0) { "thresholdMinor $threshold" }
        return PriceAlertEntity(
            uuid = UUID.fromString(o.getString("uuid")).toString(),
            cardId = o.getString("cardId"),
            cardLanguage = o.getString("cardLanguage"),
            source = PriceSource.valueOf(o.getString("source")),
            variant = PriceVariant.valueOf(o.getString("variant")),
            direction = AlertDirection.valueOf(o.getString("direction")),
            thresholdMinor = threshold,
            currency = o.getString("currency"),
            createdAt = Instant.parse(o.getString("createdAt")),
            triggeredAt = o.optStringOrNull("triggeredAt")?.let(Instant::parse),
            armed = o.optBoolean("armed", true),
        )
    }
}

/**
 * The CSV export: one row per copy, readable in a spreadsheet. It carries the same copy data as the JSON plus the
 * set and card names; collections are listed by name. Importing it back works like the JSON import.
 */
object BackupCsv {
    val COLUMNS = listOf(
        "uuid", "card_id", "language", "set_id", "set_name", "set_total", "set_region", "number", "name",
        "quantity", "finish", "edition", "condition", "grader", "grade",
        "purchase_price", "purchase_currency", "purchase_date", "collections", "created_at", "updated_at",
    )

    /** Separates collection names in the `collections` column. */
    const val COLLECTION_SEPARATOR = " | "

    fun write(data: BackupData): String {
        val sets = data.sets.associateBy { it.id }
        val cards = data.cards.associateBy { it.id to it.language }
        val names = data.collections.associate { it.uuid to it.name }
        val collectionsOf = data.memberships.flatMap { (c, copies) -> copies.map { it to c } }
            .groupBy({ it.first }, { names[it.second] }).mapValues { (_, n) -> n.filterNotNull().sorted() }
        return buildString {
            // A byte order mark makes Excel read the file as UTF-8 (Japanese card names).
            append('\uFEFF')
            append(Csv.line(COLUMNS)).append("\r\n")
            for (copy in data.copies) {
                val card = cards[copy.cardId to copy.cardLanguage]
                val set = card?.let { sets[it.setId] }
                val row = listOf(
                    copy.uuid, copy.cardId, copy.cardLanguage, card?.setId, set?.name, set?.printedTotal?.toString(),
                    set?.region?.name, card?.localId, card?.name, copy.quantity.toString(), copy.finish.name,
                    copy.edition.name, copy.condition?.name, copy.grader?.name, copy.grade,
                    copy.purchasePriceCents?.let { BigDecimal.valueOf(it, 2).toPlainString() }, copy.purchaseCurrency,
                    copy.purchaseDate?.toString(), collectionsOf[copy.uuid].orEmpty().joinToString(COLLECTION_SEPARATOR),
                    copy.createdAt.toString(), copy.updatedAt.toString(),
                )
                append(Csv.line(row.map(Csv::guard))).append("\r\n")
            }
        }
    }

    /**
     * Reads a CSV in this format. Collections come back by name ([CsvBackup.collectionNames]); rows without a uuid
     * (added by hand in a spreadsheet) get a new one, so importing such a file twice adds them twice.
     */
    fun read(text: String, now: Instant): CsvBackup {
        val records = Csv.parse(text)
        if (records.isEmpty()) throw BackupFormatException("Empty file")
        val header = records.first().map { it.trim().lowercase() }
        val index = COLUMNS.associateWith { header.indexOf(it) }
        for (required in listOf("card_id", "language", "set_id", "number", "name")) {
            if (index.getValue(required) < 0) throw BackupFormatException("Column $required is missing")
        }
        val sets = LinkedHashMap<String, CardSetEntity>()
        val cards = LinkedHashMap<Pair<String, String>, CardEntity>()
        val copies = mutableListOf<OwnedCopyEntity>()
        val collectionNames = mutableMapOf<String, List<String>>()
        records.drop(1).forEachIndexed { row, values ->
            fun col(name: String): String? = index.getValue(name).takeIf { it >= 0 }?.let { values.getOrNull(it) }
                ?.let(Csv::unguard)?.trim()?.ifEmpty { null }
            try {
                val cardId = col("card_id") ?: throw IllegalArgumentException("card_id is empty")
                val language = col("language") ?: throw IllegalArgumentException("language is empty")
                val setId = col("set_id") ?: throw IllegalArgumentException("set_id is empty")
                sets.getOrPut(setId) {
                    CardSetEntity(
                        id = setId,
                        name = col("set_name") ?: setId,
                        series = "",
                        language = language,
                        printedTotal = col("set_total")?.toInt() ?: 0,
                        region = col("set_region")?.let(Region::valueOf) ?: Region.INTERNATIONAL,
                    )
                }
                cards.getOrPut(cardId to language) {
                    CardEntity(cardId, setId, col("number") ?: "", col("name") ?: cardId, language, null, null, null)
                }
                val createdAt = col("created_at")?.let(Instant::parse) ?: now
                val price = col("purchase_price")?.let { Money.parseMinor(it) ?: throw IllegalArgumentException("purchase_price $it") }
                val copy = OwnedCopyEntity(
                    uuid = col("uuid")?.let { UUID.fromString(it).toString() } ?: UUID.randomUUID().toString(),
                    cardId = cardId,
                    cardLanguage = language,
                    quantity = validQuantity(col("quantity")?.toInt() ?: 1),
                    finish = col("finish")?.let(Finish::valueOf) ?: Finish.NORMAL,
                    edition = col("edition")?.let(Edition::valueOf) ?: Edition.UNLIMITED,
                    condition = col("condition")?.let(Condition::valueOf),
                    grader = col("grader")?.let(Grader::valueOf),
                    grade = col("grade"),
                    purchasePriceCents = price,
                    purchaseCurrency = if (price != null) col("purchase_currency") ?: Money.localCurrency() else null,
                    purchaseDate = col("purchase_date")?.let(LocalDate::parse),
                    createdAt = createdAt,
                    updatedAt = col("updated_at")?.let(Instant::parse) ?: now,
                )
                copies += copy
                col("collections")?.split(COLLECTION_SEPARATOR.trim())?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.takeIf { it.isNotEmpty() }?.let { collectionNames[copy.uuid] = it }
            } catch (e: RuntimeException) {
                // Row numbers as a spreadsheet shows them: header is row 1.
                throw BackupFormatException("Row ${row + 2}: ${e.message}", e)
            }
        }
        return CsvBackup(BackupData(sets.values.toList(), cards.values.toList(), copies, emptyList(), emptyMap()), collectionNames)
    }
}

/** A parsed CSV: the data plus, per copy uuid, the names of the collections it belongs to. */
data class CsvBackup(val data: BackupData, val collectionNames: Map<String, List<String>>)

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
