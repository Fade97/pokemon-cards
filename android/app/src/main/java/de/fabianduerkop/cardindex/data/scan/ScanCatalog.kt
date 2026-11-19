// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CatalogDao
import de.fabianduerkop.cardindex.model.Region
import de.fabianduerkop.cardindex.recognition.RefCard

/**
 * Catalog rows for scanned cards. Owned copies and review candidates point to `cards`, but until language packs
 * fill the catalog (ADR 0003) the recognition index is the only source. Its rows are inserted only where nothing
 * is there yet, so pack data always wins; the set's series and the card's rarity and type stay empty until then.
 */
object ScanCatalog {
    fun set(card: RefCard) = CardSetEntity(
        id = card.setId,
        name = card.setName,
        series = "",
        language = card.lang,
        printedTotal = card.official,
        // TCGdex keeps JP prints in their own sets; every other language shares the international set.
        region = if (card.lang == "ja") Region.JP else Region.INTERNATIONAL,
    )

    fun card(card: RefCard) = CardEntity(
        id = card.cardId,
        setId = card.setId,
        localId = card.localId,
        name = card.name,
        language = card.lang,
        rarity = null,
        energyType = null,
        imageUrl = card.imageUrl,
        printings = card.printings,
    )

    suspend fun ensure(dao: CatalogDao, cards: Collection<RefCard>) {
        dao.insertSetsIfMissing(cards.distinctBy { it.setId }.map(::set))
        dao.insertCardsIfMissing(cards.distinctBy { it.cardId to it.lang }.map(::card))
    }
}
