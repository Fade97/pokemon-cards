// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.alerts.AlertDirection
import de.fabianduerkop.cardindex.data.catalog.RoomCatalogSource
import de.fabianduerkop.cardindex.data.collections.CollectionRepository
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.db.PriceAlertEntity
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Grader
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.testing.TestClock
import de.fabianduerkop.cardindex.testing.inMemoryDatabase
import de.fabianduerkop.cardindex.testing.seedCatalog
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupTest {
    private val clock = TestClock()
    private lateinit var source: CardIndexDatabase
    private lateinit var target: CardIndexDatabase

    @Before
    fun setUp() {
        source = inMemoryDatabase()
        target = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun collections(db: CardIndexDatabase) = CollectionRepository(db, RoomCatalogSource(db.catalogDao()), clock = clock)

    /** Two copies (one graded, with purchase data) and a binder holding one of them. */
    private suspend fun seedSource() {
        source.seedCatalog()
        val repo = collections(source)
        val binder = repo.createCollection("Trade binder", "Doubles, \"for trade\"")
        repo.saveCopy(
            OwnedCopyEntity(
                cardId = "sv03.5-006",
                cardLanguage = "de",
                quantity = 2,
                finish = Finish.HOLO,
                edition = Edition.FIRST_EDITION,
                condition = Condition.LIGHTLY_PLAYED,
                purchasePriceCents = 2450,
                purchaseCurrency = "EUR",
                purchaseDate = LocalDate.of(2025, 8, 2),
                createdAt = Instant.EPOCH,
            ),
            setOf(binder),
        )
        clock.advance(60)
        repo.saveCopy(
            OwnedCopyEntity(
                cardId = "SV2a-006",
                cardLanguage = "ja",
                grader = Grader.PSA,
                grade = "9",
                createdAt = Instant.EPOCH,
            ),
            emptySet(),
        )
    }

    /** How often an import asked for the alert check to be scheduled. */
    private var alertSchedules = 0

    private fun repository(db: CardIndexDatabase) = BackupRepository(db, clock, onAlertsImported = { alertSchedules++ })

    private suspend fun export(db: CardIndexDatabase, type: BackupFileType): String =
        ByteArrayOutputStream().also { repository(db).export(type, it) }.toString(Charsets.UTF_8.name())

    private suspend fun import(db: CardIndexDatabase, text: String) =
        repository(db).import(text.byteInputStream(Charsets.UTF_8))

    /**
     * Two alerts: one armed on a card the user owns, one that already fired, on a card they don't own (a card they
     * want to buy), so its catalog row must come along in the backup.
     */
    private suspend fun seedAlerts() {
        val dao = source.priceAlertDao()
        dao.upsert(
            PriceAlertEntity(
                uuid = "8a6b3a52-0d55-4bb4-9a4e-2f0c3d1e5a01",
                cardId = "sv03.5-006",
                cardLanguage = "de",
                source = PriceSource.CARDMARKET,
                variant = PriceVariant.HOLO,
                direction = AlertDirection.ABOVE,
                thresholdMinor = 5000,
                currency = "EUR",
                createdAt = Instant.parse("2026-11-01T08:00:00Z"),
                triggeredAt = null,
                armed = true,
            ),
        )
        dao.upsert(
            PriceAlertEntity(
                uuid = "8a6b3a52-0d55-4bb4-9a4e-2f0c3d1e5a02",
                cardId = "sv03.5-025",
                cardLanguage = "de",
                source = PriceSource.TCGPLAYER,
                variant = PriceVariant.REVERSE_HOLO,
                direction = AlertDirection.BELOW,
                thresholdMinor = 199,
                currency = "USD",
                createdAt = Instant.parse("2026-11-02T08:00:00Z"),
                triggeredAt = Instant.parse("2026-11-10T06:00:00Z"),
                armed = false,
            ),
        )
    }

    private suspend fun copiesOf(db: CardIndexDatabase) = db.copyDao().all().map { it.copy(id = 0) }.sortedBy { it.uuid }

    @Test
    fun json_roundTripsIntoAnEmptyDatabase_includingTheCatalogItNeeds() = runTest {
        seedSource()
        val json = export(source, BackupFileType.JSON)

        val result = import(target, json)

        assertEquals(ImportResult(copiesAdded = 2, collectionsAdded = 1), result)
        assertEquals(copiesOf(source), copiesOf(target)) // uuids, timestamps, every field
        val binder = collections(target).observeCustomCollections().first().single()
        assertEquals("Trade binder" to 2, binder.name to binder.cards)
        assertEquals("Doubles, \"for trade\"", binder.description)
        assertEquals("ポケモンカード151", target.catalogDao().backupSets().single { it.id == "SV2a" }.name)
    }

    @Test
    fun json_importIsIdempotent() = runTest {
        seedSource()
        val json = export(source, BackupFileType.JSON)
        import(target, json)

        val again = import(target, json)

        assertEquals(ImportResult(copiesUnchanged = 2), again)
        assertEquals(2, target.copyDao().all().size)
        assertEquals(1, collections(target).observeCustomCollections().first().size)
        assertEquals(2, collections(target).observeCustomCollections().first().single().cards)
    }

    @Test
    fun json_newerSideWins() = runTest {
        seedSource()
        val json = export(source, BackupFileType.JSON)
        import(target, json)

        // The phone's copy changes after the backup: an older backup must not undo that.
        val local = target.copyDao().all().first { it.cardId == "sv03.5-006" }
        clock.advance(60)
        collections(target).saveCopy(local.copy(quantity = 5), emptySet())
        assertEquals(ImportResult(copiesUnchanged = 2), import(target, json))
        assertEquals(5, target.copyDao().byUuid(local.uuid)!!.quantity)

        // A newer backup replaces it.
        val sourceCopy = source.copyDao().byUuid(local.uuid)!!
        clock.advance(60)
        collections(source).saveCopy(sourceCopy.copy(quantity = 7), emptySet())
        val newer = import(target, export(source, BackupFileType.JSON))
        assertEquals(1, newer.copiesUpdated)
        assertEquals(7, target.copyDao().byUuid(local.uuid)!!.quantity)
    }

    @Test
    fun json_rejectsForeignAndNewerFiles() = runTest {
        assertThrows(BackupFormatException::class.java) { BackupJson.read("""{"hello":1}""") }
        assertThrows(BackupFormatException::class.java) { BackupJson.read("""{"format":"cardindex-backup","version":3}""") }
        assertThrows(BackupFormatException::class.java) { BackupJson.read("not json") }
    }

    @Test
    fun csv_roundTrips_andMatchesCollectionsByName() = runTest {
        seedSource()
        val csv = export(source, BackupFileType.CSV)
        assertTrue(csv.startsWith("\uFEFFuuid,card_id,language,"))

        target.seedCatalog()
        val existing = collections(target).createCollection("trade binder", null) // same name, other case

        val result = import(target, csv)

        assertEquals(2, result.copiesAdded)
        assertEquals(0, result.collectionsAdded)
        assertEquals(copiesOf(source), copiesOf(target))
        assertEquals(setOf(existing), collections(target).observeCollectionIdsOf(target.copyDao().all().first { it.cardId == "sv03.5-006" }.id).first())

        assertEquals(ImportResult(copiesUnchanged = 2), import(target, csv))
    }

    @Test
    fun csv_editedInASpreadsheet_updatesTheCopy() = runTest {
        seedSource()
        import(target, export(source, BackupFileType.JSON))
        val csv = export(target, BackupFileType.CSV)

        // Same updated_at as on the phone: the edit still applies, and counts as the newest change.
        val edited = csv.replace(",2,HOLO,FIRST_EDITION,", ",3,HOLO,FIRST_EDITION,")
        clock.advance(600)
        val result = import(target, edited)

        assertEquals(1, result.copiesUpdated)
        val copy = target.copyDao().all().first { it.cardId == "sv03.5-006" }
        assertEquals(3, copy.quantity)
        assertEquals(clock.now, copy.updatedAt)
    }

    @Test
    fun csv_quotedFieldsAndNewRowsWithoutUuid() = runTest {
        target.seedCatalog()
        val csv = "card_id,language,set_id,number,name,quantity,purchase_price,collections\r\n" +
            "sv03.5-001,en,sv03.5,001,\"Bulbasaur, the \"\"seed\"\"\",2,\"1,50\",Wishlist | Binder\r\n"

        val result = import(target, csv)

        assertEquals(1, result.copiesAdded)
        assertEquals(2, result.collectionsAdded)
        val copy = target.copyDao().all().single()
        assertEquals(2, copy.quantity)
        assertEquals(150L, copy.purchasePriceCents)
        assertThrows(BackupFormatException::class.java) { BackupCsv.read("card_id,language\r\nx,en\r\n", clock.now) }
        assertEquals(
            listOf(listOf("a", "b,c", "d\"e", "multi\nline")),
            Csv.parse("a,\"b,c\",\"d\"\"e\",\"multi\nline\"\r\n"),
        )
    }

    @Test
    fun import_skipsCopiesOfCardsNobodyKnows() = runTest {
        val json = """
            {"format":"cardindex-backup","version":1,"sets":[],"cards":[],"collections":[],
             "copies":[{"uuid":"6f1c3c2e-6a43-4c39-9bb6-0f5f8a1e2b3c","cardId":"x-1","cardLanguage":"en","quantity":1,
               "finish":"NORMAL","edition":"UNLIMITED","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}]}
        """.trimIndent()

        assertEquals(ImportResult(copiesSkipped = 1), import(target, json))
    }

    @Test
    fun json_roundTripsAlerts_withTheirUuidsStateAndCards() = runTest {
        seedSource()
        seedAlerts()
        val json = export(source, BackupFileType.JSON)
        assertTrue(json.contains("\"version\": 2"))

        val result = import(target, json)

        assertEquals(2, result.alertsAdded)
        assertEquals(source.priceAlertDao().all(), target.priceAlertDao().all()) // uuids, armed, triggeredAt, every field
        assertTrue(target.catalogDao().hasCard("sv03.5-025", "de")) // not owned, but alerted
        assertEquals(1, alertSchedules)
    }

    @Test
    fun json_reimport_doesNotDuplicateAlerts_andKeepsTheirLocalState() = runTest {
        seedSource()
        seedAlerts()
        val json = export(source, BackupFileType.JSON)
        import(target, json)
        // The phone's check fires the armed alert after the restore; the older backup must not re-arm it.
        val fired = target.priceAlertDao().byUuid("8a6b3a52-0d55-4bb4-9a4e-2f0c3d1e5a01")!!
            .copy(armed = false, triggeredAt = Instant.parse("2026-11-18T06:00:00Z"))
        target.priceAlertDao().update(listOf(fired))

        val again = import(target, json)

        assertEquals(ImportResult(copiesUnchanged = 2, alertsUnchanged = 2), again)
        assertEquals(2, target.priceAlertDao().count())
        assertEquals(fired, target.priceAlertDao().byUuid(fired.uuid))
        assertEquals(1, alertSchedules) // only the first import added alerts
    }

    @Test
    fun json_version1WithoutAlerts_stillImports() = runTest {
        target.seedCatalog()
        val json = """
            {"format":"cardindex-backup","version":1,"exportedAt":"2026-11-17T10:00:00Z","sets":[],"cards":[],
             "copies":[{"uuid":"6f1c3c2e-6a43-4c39-9bb6-0f5f8a1e2b3c","cardId":"sv03.5-006","cardLanguage":"de",
               "quantity":1,"finish":"HOLO","edition":"UNLIMITED","createdAt":"2026-01-01T00:00:00Z",
               "updatedAt":"2026-01-01T00:00:00Z"}],
             "collections":[]}
        """.trimIndent()

        val result = import(target, json)

        assertEquals(ImportResult(copiesAdded = 1), result)
        assertFalse(result.hasAlerts)
        assertEquals(0, target.priceAlertDao().count())
        assertEquals(0, alertSchedules)
    }

    @Test
    fun import_skipsAlertsOnCardsNobodyKnows() = runTest {
        val json = """
            {"format":"cardindex-backup","version":2,"sets":[],"cards":[],"copies":[],"collections":[],
             "alerts":[{"uuid":"8a6b3a52-0d55-4bb4-9a4e-2f0c3d1e5a03","cardId":"x-1","cardLanguage":"en",
               "source":"CARDMARKET","variant":"NORMAL","direction":"BELOW","thresholdMinor":100,"currency":"EUR",
               "createdAt":"2026-11-01T08:00:00Z","armed":true}]}
        """.trimIndent()

        assertEquals(ImportResult(alertsSkipped = 1), import(target, json))
        assertEquals(0, target.priceAlertDao().count())
        assertEquals(0, alertSchedules)
    }

    @Test
    fun csv_staysOneRowPerCopy_withoutAlerts() = runTest {
        seedSource()
        seedAlerts()

        val lines = export(source, BackupFileType.CSV).trimEnd().lines()

        assertEquals(1 + 2, lines.size) // header + the two copies, nothing for the alerts
        assertFalse(lines.any { "sv03.5-025" in it })
    }
}
