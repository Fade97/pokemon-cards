// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.CollectionCopyCrossRef
import de.fabianduerkop.cardindex.data.db.CollectionEntity
import de.fabianduerkop.cardindex.data.settings.AppSettings
import de.fabianduerkop.cardindex.model.CollectionKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * The custom collection scans go into besides All Cards (#139), by its uuid: it stays right across backup imports,
 * which keep uuids but not row ids. Null, an unknown uuid (the collection was deleted) or a collection that isn't
 * custom all mean All Cards only.
 */
internal object ScanTarget {
    suspend fun resolve(db: CardIndexDatabase, uuid: String?): CollectionEntity? =
        uuid?.let { db.collectionDao().byUuid(it) }?.takeIf { it.kind == CollectionKind.CUSTOM }

    /** Puts [copyId] into the collection [uuid]; call inside the transaction that added the copy. */
    suspend fun addCopy(db: CardIndexDatabase, copyId: Long, uuid: String?) {
        val target = resolve(db, uuid) ?: return
        db.collectionDao().addCopy(CollectionCopyCrossRef(target.id, copyId))
    }
}

/** The user's scan target (#139): the setting, resolved to its collection. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanTargetChoice(private val db: CardIndexDatabase, private val settings: AppSettings) {
    /** Uuid of the chosen collection for [ScanRepository.addFromScan] and [ReviewQueueRepository.confirm]. */
    val uuid: String? get() = settings.scanTargetCollection

    /**
     * The chosen collection, null for All Cards only. Once the collection is deleted the setting goes back to All
     * Cards, so the chip never names a collection that is gone.
     */
    fun observe(): Flow<CollectionEntity?> = settings.scanTargetCollectionFlow.flatMapLatest { uuid ->
        if (uuid == null) {
            flowOf(null)
        } else {
            db.collectionDao().observeByUuid(uuid)
                .map { it?.takeIf { c -> c.kind == CollectionKind.CUSTOM } }
                .onEach { if (it == null) settings.clearScanTarget(uuid) }
        }
    }

    /** Scans go into custom collection [collectionId] from now on; null (or a collection that is gone) is All Cards only. */
    suspend fun choose(collectionId: Long?) {
        settings.scanTargetCollection = collectionId
            ?.let { db.collectionDao().byId(it) }
            ?.takeIf { it.kind == CollectionKind.CUSTOM }
            ?.uuid
    }
}
