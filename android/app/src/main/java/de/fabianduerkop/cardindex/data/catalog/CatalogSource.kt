// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.catalog

import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CatalogDao
import kotlinx.coroutines.flow.Flow

/**
 * Card metadata (sets, cards in each language) as the collection screens need it.
 *
 * The catalog will come from language packs (#5). Until then the only implementation reads whatever card rows the
 * database has (written by the scan flow, an import or the debug sample data), so set grids show every number up to
 * the printed total but can only name the cards the database knows. A pack-backed source replaces
 * [RoomCatalogSource] here without touching the screens.
 */
interface CatalogSource {
    fun observeSet(setId: String): Flow<CardSetEntity?>

    /** Every print of every card in the set, all languages. */
    fun observeCardsInSet(setId: String): Flow<List<CardEntity>>

    /** One card in every language the catalog has it in. */
    fun observePrints(cardId: String): Flow<List<CardEntity>>
}

class RoomCatalogSource(private val dao: CatalogDao) : CatalogSource {
    override fun observeSet(setId: String) = dao.observeSet(setId)
    override fun observeCardsInSet(setId: String) = dao.observeCardsInSet(setId)
    override fun observePrints(cardId: String) = dao.observeCardPrints(cardId)
}
