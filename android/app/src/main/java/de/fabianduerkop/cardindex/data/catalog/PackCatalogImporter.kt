// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.catalog

import androidx.room.withTransaction
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.packs.PackCard
import de.fabianduerkop.cardindex.data.packs.PackSet
import de.fabianduerkop.cardindex.model.Region

/**
 * Writes a language pack's card metadata into the Room catalog that [CatalogSource] reads. Pack rows win over the
 * gap-filling rows from scans and backups (`insert…IfMissing`), and cards a pack no longer lists are retired, never
 * deleted, so owned copies keep their card (Entities.kt).
 *
 * Sets are shared by all languages of the international prints, but a set row has one name. The name comes from the
 * pack in the first of [preferredLanguages] (the phone's language, then English) that is installed; a set row from a
 * scan or a backup (empty series) is always replaced.
 */
class PackCatalogImporter(
    private val db: CardIndexDatabase,
    private val preferredLanguages: () -> List<String>,
) {
    suspend fun import(lang: String, sets: List<PackSet>, cards: List<PackCard>) {
        val dao = db.catalogDao()
        db.withTransaction {
            val existingSets = sets.map { it.id }.chunked(MAX_PARAMS).flatMap { dao.setsByIds(it) }.associateBy { it.id }
            val rank = preferredLanguages().withIndex().associate { (i, l) -> l to i }
            fun rankOf(l: String) = rank[l] ?: Int.MAX_VALUE
            dao.upsertSets(
                sets.filter { s ->
                    val old = existingSets[s.id]
                    old == null || old.series.isBlank() || old.language == lang || rankOf(lang) < rankOf(old.language)
                }.map { it.toEntity(lang) },
            )
            // Rarity and type are not in packs yet; keep what a backup import brought.
            val existingCards = dao.cardsInLanguage(lang).associateBy { it.id }
            dao.retireCardsInLanguage(lang)
            dao.upsertCards(
                cards.map { c ->
                    val old = existingCards[c.id]
                    CardEntity(
                        id = c.id,
                        setId = c.setId,
                        localId = c.localId,
                        name = c.name,
                        language = lang,
                        rarity = old?.rarity,
                        energyType = old?.energyType,
                        imageUrl = c.image,
                        retired = false,
                        printings = c.printings,
                    )
                },
            )
            dao.refreshSetRetirement()
        }
    }

    private fun PackSet.toEntity(lang: String) = CardSetEntity(
        id = id,
        name = name,
        series = serieName.orEmpty(),
        language = lang,
        printedTotal = official ?: total ?: 0,
        // TCGdex keeps JP prints in their own sets; every other language shares the international set.
        region = if (lang == "ja") Region.JP else Region.INTERNATIONAL,
        retired = false,
    )

    private companion object {
        /** SQLite's default bound-parameter limit on older Android versions is 999. */
        const val MAX_PARAMS = 900
    }
}
