// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.backup

import androidx.room.withTransaction
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.CollectionCopyCrossRef
import de.fabianduerkop.cardindex.data.db.CollectionEntity
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.CollectionKind
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What an import changed. Importing the same file again adds and updates nothing. */
data class ImportResult(
    val copiesAdded: Int = 0,
    val copiesUpdated: Int = 0,
    val copiesUnchanged: Int = 0,
    /** Copies of cards neither the backup nor this phone's catalog has; left out instead of failing the import. */
    val copiesSkipped: Int = 0,
    val collectionsAdded: Int = 0,
    val collectionsUpdated: Int = 0,
    val alertsAdded: Int = 0,
    /** Alerts this phone already has (same uuid); they keep their local state. */
    val alertsUnchanged: Int = 0,
    /** Alerts on cards neither the backup nor this phone's catalog has; left out like such copies. */
    val alertsSkipped: Int = 0,
) {
    val hasAlerts: Boolean get() = alertsAdded + alertsUnchanged + alertsSkipped > 0
}

enum class BackupFileType { JSON, CSV }

/**
 * Free JSON and CSV backup (#13, ADR 0006). Rows are matched by uuid, never by the local row id, so an import is
 * idempotent and can merge a backup into a collection that changed since. A copy or collection in the file replaces
 * the local one when the file's `updatedAt` is not older and the content differs; otherwise the local one stays.
 * Memberships are merged as a union: an import adds copies to collections but never takes them out.
 *
 * Price alerts (JSON only) are added when their uuid is new and left alone when it exists: the app can't edit an
 * alert, only its armed/notified state changes, and the phone's own state is the one its checks rely on.
 * [onAlertsImported] runs after an import that added alerts, so their daily check gets scheduled.
 */
class BackupRepository(
    private val db: CardIndexDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val onAlertsImported: suspend () -> Unit = {},
) {
    private val catalog = db.catalogDao()
    private val copies = db.copyDao()
    private val collections = db.collectionDao()
    private val alerts = db.priceAlertDao()

    suspend fun snapshot(): BackupData = db.withTransaction {
        val custom = collections.allCustom()
        BackupData(
            sets = catalog.backupSets(),
            cards = catalog.backupCards(),
            copies = copies.all(),
            collections = custom,
            memberships = collections.membershipUuids().groupBy({ it.collectionUuid }, { it.copyUuid }),
            alerts = alerts.all(),
        )
    }

    suspend fun export(type: BackupFileType, out: OutputStream) {
        val data = snapshot()
        val text = when (type) {
            BackupFileType.JSON -> BackupJson.write(data, clock.instant())
            BackupFileType.CSV -> BackupCsv.write(data)
        }
        withContext(Dispatchers.IO) { out.bufferedWriter(Charsets.UTF_8).use { it.write(text) } }
    }

    /** Reads a JSON or CSV backup (told apart by content, since SAF names and MIME types are unreliable). */
    suspend fun import(input: InputStream): ImportResult {
        val text = withContext(Dispatchers.IO) { input.bufferedReader(Charsets.UTF_8).use { it.readText() } }
        return if (text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("{")) {
            import(BackupJson.read(text))
        } else {
            val csv = BackupCsv.read(text, clock.instant())
            import(csv.data, csv.collectionNames)
        }
    }

    suspend fun import(data: BackupData, collectionNamesByCopy: Map<String, List<String>> = emptyMap()): ImportResult {
        val result = importInTransaction(data, collectionNamesByCopy)
        if (result.alertsAdded > 0) onAlertsImported()
        return result
    }

    private suspend fun importInTransaction(data: BackupData, collectionNamesByCopy: Map<String, List<String>>): ImportResult =
        db.withTransaction {
            // Catalog rows only fill gaps: a language pack's data wins over a backup's copy of it.
            catalog.insertSetsIfMissing(data.sets)
            catalog.insertCardsIfMissing(data.cards)

            var result = ImportResult()
            for (copy in data.copies) {
                if (!catalog.hasCard(copy.cardId, copy.cardLanguage)) {
                    result = result.copy(copiesSkipped = result.copiesSkipped + 1)
                    continue
                }
                val local = copies.byUuid(copy.uuid)
                result = when {
                    local == null -> {
                        copies.insert(copy.copy(id = 0))
                        result.copy(copiesAdded = result.copiesAdded + 1)
                    }
                    copy.updatedAt >= local.updatedAt && !copy.sameContentAs(local) -> {
                        copies.update(copy.copy(id = local.id, createdAt = local.createdAt, updatedAt = merged(copy.updatedAt, local.updatedAt)))
                        result.copy(copiesUpdated = result.copiesUpdated + 1)
                    }
                    else -> result.copy(copiesUnchanged = result.copiesUnchanged + 1)
                }
            }

            val collectionIds = mutableMapOf<String, Long>()
            for (collection in data.collections) {
                val local = collections.byUuid(collection.uuid)
                when {
                    local == null -> {
                        collectionIds[collection.uuid] = collections.insert(collection.copy(id = 0, kind = CollectionKind.CUSTOM))
                        result = result.copy(collectionsAdded = result.collectionsAdded + 1)
                    }
                    else -> {
                        collectionIds[collection.uuid] = local.id
                        val changed = local.name != collection.name || local.description != collection.description
                        if (collection.updatedAt >= local.updatedAt && changed) {
                            collections.update(
                                local.copy(
                                    name = collection.name,
                                    description = collection.description,
                                    updatedAt = merged(collection.updatedAt, local.updatedAt),
                                ),
                            )
                            result = result.copy(collectionsUpdated = result.collectionsUpdated + 1)
                        }
                    }
                }
            }
            for ((collectionUuid, copyUuids) in data.memberships) {
                val collectionId = collectionIds[collectionUuid] ?: continue
                for (copyUuid in copyUuids) {
                    val copyId = copies.byUuid(copyUuid)?.id ?: continue
                    collections.addCopy(CollectionCopyCrossRef(collectionId, copyId))
                }
            }

            // CSV names its collections: reuse a custom collection of that name, or create it.
            if (collectionNamesByCopy.isNotEmpty()) {
                val byName = collections.allCustom().associateBy { it.name.lowercase() }.toMutableMap()
                for ((copyUuid, names) in collectionNamesByCopy) {
                    val copyId = copies.byUuid(copyUuid)?.id ?: continue
                    for (name in names) {
                        val id = byName[name.lowercase()]?.id ?: run {
                            val created = CollectionEntity(name = name, kind = CollectionKind.CUSTOM, updatedAt = clock.instant())
                            result = result.copy(collectionsAdded = result.collectionsAdded + 1)
                            created.copy(id = collections.insert(created)).also { byName[name.lowercase()] = it }.id
                        }
                        collections.addCopy(CollectionCopyCrossRef(id, copyId))
                    }
                }
            }

            // Same rule as copies: an alert on a card nobody knows is skipped, not stored without its card.
            for (alert in data.alerts) {
                result = when {
                    !catalog.hasCard(alert.cardId, alert.cardLanguage) -> result.copy(alertsSkipped = result.alertsSkipped + 1)
                    alerts.byUuid(alert.uuid) == null -> {
                        alerts.upsert(alert)
                        result.copy(alertsAdded = result.alertsAdded + 1)
                    }
                    else -> result.copy(alertsUnchanged = result.alertsUnchanged + 1)
                }
            }
            result
        }

    /**
     * The updatedAt of a row the import changed: the file's when it is newer; when both are equal (a backup edited in
     * a spreadsheet keeps the old timestamp) now, so the change still counts as the latest one for sync.
     */
    private fun merged(fromFile: Instant, local: Instant): Instant = if (fromFile > local) fromFile else clock.instant()

    private fun OwnedCopyEntity.sameContentAs(other: OwnedCopyEntity): Boolean =
        copy(id = 0, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH) ==
            other.copy(id = 0, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
}
