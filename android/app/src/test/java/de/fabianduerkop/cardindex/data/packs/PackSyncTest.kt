// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.catalog.PackCatalogImporter
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.packs.PackFixtures.card
import de.fabianduerkop.cardindex.data.packs.PackFixtures.content
import de.fabianduerkop.cardindex.data.packs.PackFixtures.set
import de.fabianduerkop.cardindex.data.scan.ScanCatalog
import de.fabianduerkop.cardindex.model.EnergyType
import de.fabianduerkop.cardindex.model.Region
import de.fabianduerkop.cardindex.recognition.PackIntegrityException
import de.fabianduerkop.cardindex.recognition.RefCard
import de.fabianduerkop.cardindex.recognition.sha256Hex
import de.fabianduerkop.cardindex.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PackSyncTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val fake = FakePackServer()
    private lateinit var db: CardIndexDatabase
    private lateinit var store: PackStore
    private lateinit var http: PackHttp
    private lateinit var prefs: PackPreferences
    private lateinit var manager: PackManager

    private val modelBytes = ByteArray(300) { (it * 7).toByte() }

    /** Unchanged between the versions; they make the full pack much bigger than a delta, as in real packs. */
    private val filler = (10..39).map { card("sv2-0$it") }

    private val en1 = content(
        "en", 1,
        listOf(set("sv1"), set("sv2")),
        listOf(card("sv1-001"), card("sv1-002"), card("sv1-003", image = null), card("sv2-001"), card("sv2-002")) + filler,
    )

    /** v2: a renamed card, a removed one, a new set with two cards (one without image). */
    private val en2 = content(
        "en", 2,
        listOf(set("sv1"), set("sv2"), set("sv3")),
        listOf(
            card("sv1-001", name = "Renamed"), card("sv1-003", image = null), card("sv2-001"), card("sv2-002"),
            card("sv3-001"), card("sv3-002", image = null),
        ) + filler,
    )

    @Before
    fun setUp() {
        server.dispatcher = fake
        server.start()
        db = inMemoryDatabase()
        store = PackStore(tmp.newFolder("packs"))
        http = PackHttp(server.url("/").toString(), OkHttpClient(), store.downloadDir)
        prefs = PackPreferences(ApplicationProvider.getApplicationContext())
        prefs.wanted = emptySet()
        prefs.lastPolledAt = null
        manager = newManager()
    }

    private fun newManager() = PackManager(store, prefs, http, PackCatalogImporter(db) { listOf("de", "en") }, clock = { Instant.parse("2026-11-17T12:00:00Z") })

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    // --- Downloads and checks --------------------------------------------------------------------------------

    @Test
    fun repairAndLoadReports_doNotDeleteAPackASyncHasWrittenButNotCommitted() {
        val pending = store.newPackDir("de", 1)
        File(pending, "cards.json.gz").writeBytes(byteArrayOf(1))

        newManager().repair("en")
        newManager().reportLoad(PackLoad(loaded = null, broken = setOf("en"), modelBroken = true))
        assertTrue(pending.exists())

        // A commit under the sync lock cleans up as before.
        store.commit { }
        assertFalse(pending.exists())
    }

    @Test
    fun downloadChecksSizeAndChecksum() = runTest {
        val bytes = ByteArray(5000) { it.toByte() }
        fake.files["en/index-1.bin"] = bytes
        val file = http.download(PackFile("en/index-1.bin", bytes.size.toLong(), sha256Hex(bytes)))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun partialDownloadResumesWithRange() = runTest {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        fake.files["en/index-1.bin"] = bytes
        val entry = PackFile("en/index-1.bin", bytes.size.toLong(), sha256Hex(bytes))
        store.downloadDir.mkdirs()
        File(store.downloadDir, "${entry.sha256}-index-1.bin.part").writeBytes(bytes.copyOf(2000))

        val file = http.download(entry)

        assertArrayEquals(bytes, file.readBytes())
        assertEquals("bytes=2000-", fake.requests.single().getHeader("Range"))
    }

    @Test
    fun fileThatKeepsFailingItsChecksumIsRefetchedTwiceThenRefused() = runTest {
        val bytes = ByteArray(100) { 1 }
        fake.files["en/index-1.bin"] = bytes
        fake.corrupt["en/index-1.bin"] = ByteArray(100) { 2 }
        val entry = PackFile("en/index-1.bin", 100, sha256Hex(bytes))

        assertThrows(PackIntegrityException::class.java) { kotlinx.coroutines.runBlocking { http.download(entry) } }
        assertEquals(1 + PackHttp.MAX_REFETCHES, fake.requests.size)
        assertTrue(store.downloadDir.listFiles().orEmpty().isEmpty())
    }

    // --- Deltas ------------------------------------------------------------------------------------------------

    @Test
    fun deltaTurnsTheOldPackIntoTheNewOneExactly() {
        val (doc, rows) = PackFixtures.delta(en1, en2)
        val applied = en1.applyDelta(doc, rows)

        applied.verify(en2.keysSha256(), sha256Hex(en2.indexBytes()), en2.rows.size, en2.cards.size)
        assertEquals(en2.cards, applied.cards)
        assertEquals(en2.sets, applied.sets)
        assertArrayEquals(en2.indexBytes(), applied.indexBytes())
        assertEquals(2, applied.version)
    }

    @Test
    fun deltaThatDoesNotReproduceTheResultIsRefused() {
        val (doc, rows) = PackFixtures.delta(en1, en2)
        rows[0] = (rows[0] + 1).toByte()
        val applied = en1.applyDelta(doc, rows)
        assertThrows(PackIntegrityException::class.java) {
            applied.verify(en2.keysSha256(), sha256Hex(en2.indexBytes()), en2.rows.size, en2.cards.size)
        }
    }

    @Test
    fun deltaForAnotherVersionIsRefused() {
        val (doc, rows) = PackFixtures.delta(en2, content("en", 3, en2.sets, en2.cards))
        assertThrows(PackIntegrityException::class.java) { en1.applyDelta(doc, rows) }
    }

    @Test
    fun cardFileRoundTripsWithItsIndex() {
        val parsed = PackContent.parse(en2.cardsJsonGz(), en2.indexBytes(), PackFixtures.DIM)
        assertEquals(en2.cards, parsed.cards)
        assertEquals(en2.keysSha256(), parsed.keysSha256())
        assertArrayEquals(en2.indexBytes(), parsed.indexBytes())
    }

    // --- Catalog polling -------------------------------------------------------------------------------------

    @Test
    fun catalogIsAskedForWithItsEtagAndA304KeepsIt() = runTest {
        val model = fake.publishModel("mnv3", modelBytes)
        fake.publish(en1, model)

        val first = manager.refreshCatalog()
        val second = manager.refreshCatalog()

        assertEquals(first, second)
        assertNull(fake.requests[0].getHeader("If-None-Match"))
        assertNotNull(fake.requests[1].getHeader("If-None-Match"))
        assertEquals(Instant.parse("2026-11-17T12:00:00Z"), prefs.lastPolledAt)
        assertFalse(manager.isPollDue())
    }

    @Test
    fun catalogOfAnUnknownFormatKeepsTheInstalledPacks() = runTest {
        val model = fake.publishModel("mnv3", modelBytes)
        fake.publish(en1, model)
        manager.addLanguages(setOf("en"))
        assertEquals(SyncOutcome.Done, manager.sync(poll = true))

        fake.catalogOverride = """{"formatVersion": 2, "packs": {}}"""
        val outcome = manager.sync(poll = true)

        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(setOf("en"), store.installed.value.packs.keys)
        assertEquals(1, manager.catalog.value!!.packs.getValue("en").version)
    }

    // --- Sync ------------------------------------------------------------------------------------------------

    @Test
    fun firstSyncInstallsModelAndPackAndFillsTheCatalog() = runTest {
        val model = fake.publishModel("mnv3", modelBytes, corners = ByteArray(40) { 3 })
        fake.publish(en1, model)
        // A card the scan flow added before the pack existed: the pack's row replaces it.
        db.catalogDao().insertSetsIfMissing(listOf(CardSetEntity("sv1", "scan name", "", "en", 0, Region.INTERNATIONAL)))
        manager.addLanguages(setOf("en"))

        assertEquals(SyncOutcome.Done, manager.sync(poll = false))

        val installed = store.installed.value
        assertEquals(model, installed.model!!.id)
        assertEquals(1, installed.packs.getValue("en").meta.version)
        assertNotNull(installed.model!!.cornersFile)
        assertEquals("Set sv1", db.catalogDao().observeSet("sv1").first()!!.name)
        assertEquals(35, db.catalogDao().cardsInLanguage("en").size)
        assertEquals(mapOf("en" to installed.packs.getValue("en").dir.name), store.installed.value.catalogFilled)
    }

    @Test
    fun updateUsesTheDeltaChainAndRetiresRemovedCards() = runTest {
        val model = fake.publishModel("mnv3", modelBytes)
        fake.publish(en1, model)
        manager.addLanguages(setOf("en"))
        manager.sync(poll = false)
        // An owned copy of the card v2 drops: it must survive as a retired card.
        db.copyDao().insert(OwnedCopyEntity(cardId = "sv1-002", cardLanguage = "en", createdAt = Instant.EPOCH))

        fake.publish(en2, model, bases = listOf(en1))
        fake.requests.clear()
        assertEquals(SyncOutcome.Done, manager.sync(poll = true))

        assertEquals(listOf("catalog.json", "en/delta-1-2.json.gz", "en/delta-1-2.bin"), fake.paths())
        val pack = store.installed.value.packs.getValue("en")
        assertEquals(2, pack.meta.version)
        assertArrayEquals(en2.indexBytes(), pack.indexFile.readBytes())
        val cards = db.catalogDao().cardsInLanguage("en").associateBy { it.id }
        assertTrue(cards.getValue("sv1-002").retired)
        assertEquals("Renamed", cards.getValue("sv1-001").name)
        assertFalse(cards.getValue("sv3-001").retired)
    }

    @Test
    fun brokenDeltaFallsBackToTheFullPack() = runTest {
        val model = fake.publishModel("mnv3", modelBytes)
        fake.publish(en1, model)
        manager.addLanguages(setOf("en"))
        manager.sync(poll = false)

        // A delta that doesn't produce its promised result, though its files match their checksums.
        fake.publish(en2, model, bases = listOf(en1)) { rows -> rows.copyOf().also { it[0] = (it[0] + 1).toByte() } }
        fake.requests.clear()
        assertEquals(SyncOutcome.Done, manager.sync(poll = true))

        assertTrue("en/cards-2.json.gz" in fake.paths())
        assertEquals(2, store.installed.value.packs.getValue("en").meta.version)
        assertArrayEquals(en2.indexBytes(), store.installed.value.packs.getValue("en").indexFile.readBytes())
    }

    @Test
    fun modelChangeSwitchesEverythingOnlyAfterAllFilesAreVerified() = runTest {
        val m1 = fake.publishModel("mnv3", modelBytes)
        val de1 = content("de", 1, listOf(set("sv1")), listOf(card("sv1-001"), card("sv1-002")))
        fake.publish(en1, m1)
        fake.publish(de1, m1)
        manager.addLanguages(setOf("en", "de"))
        manager.sync(poll = false)
        val before = store.installed.value

        // New model; the new DE index is served damaged, so nothing may switch.
        val m2 = fake.publishModel("mnv4", ByteArray(310) { 9 })
        fake.publish(content("en", 2, en1.sets, en1.cards), m2)
        fake.publish(content("de", 2, de1.sets, de1.cards), m2)
        fake.corrupt["de/index-2.bin"] = ByteArray(fake.files.getValue("de/index-2.bin").size)
        assertTrue(manager.sync(poll = true) is SyncOutcome.Failed)
        assertEquals(before.model!!.dir, store.installed.value.model!!.dir)
        assertEquals(before.packs.mapValues { it.value.dir }, store.installed.value.packs.mapValues { it.value.dir })
        assertTrue(before.model!!.embedderFile.isFile)

        fake.corrupt.clear()
        assertEquals(SyncOutcome.Done, manager.sync(poll = false))
        val after = store.installed.value
        assertEquals(m2, after.model!!.id)
        assertEquals(mapOf("de" to 2, "en" to 2), after.packs.mapValues { it.value.meta.version })
        assertFalse("the old model is deleted after the switch", before.model!!.dir.exists())
    }

    @Test
    fun removedLanguageGoesButItsCardsStay() = runTest {
        val model = fake.publishModel("mnv3", modelBytes)
        fake.publish(en1, model)
        manager.addLanguages(setOf("en"))
        manager.sync(poll = false)
        val dir = store.installed.value.packs.getValue("en").dir

        manager.removeLanguage("en")

        assertTrue(store.installed.value.packs.isEmpty())
        assertFalse(dir.exists())
        assertEquals(35, db.catalogDao().cardsInLanguage("en").size)
    }

    // --- Loading for recognition ---------------------------------------------------------------------------

    @Test
    fun loaderReadsInstalledPacksAndLeavesOutABrokenOne() = runTest {
        val model = fake.publishModel("mnv3", modelBytes)
        val de1 = content("de", 1, listOf(set("sv1")), listOf(card("sv1-001"), card("sv1-002")))
        fake.publish(en1, model)
        fake.publish(de1, model)
        manager.addLanguages(setOf("en", "de"))
        manager.sync(poll = false)

        val ok = PackLoader.load(store.installed.value)
        assertEquals(setOf("de", "en"), ok.loaded!!.languages)
        assertEquals(en1.rows.size + de1.rows.size, ok.loaded!!.cards.size)
        val first: RefCard = ok.loaded!!.cards.first()
        assertEquals("de/sv1-001", first.key)
        assertEquals("Set sv1", first.setName)
        assertEquals(10, first.official)

        // Same size, other bytes: only the checksum can tell. The cached check must notice the new mtime.
        val index = store.installed.value.packs.getValue("de").indexFile
        index.writeBytes(ByteArray(index.length().toInt()))
        index.setLastModified(index.lastModified() + 5_000)
        val load = PackLoader.load(store.installed.value)
        assertEquals(setOf("de"), load.broken)
        assertEquals(setOf("en"), load.loaded!!.languages)

        manager.reportLoad(load)
        assertEquals(setOf("en"), store.installed.value.usable)
        // A repair downloads the broken pack in full and clears the mark.
        manager.repair("de")
        assertEquals(SyncOutcome.Done, manager.sync(poll = false))
        assertEquals(setOf("de", "en"), store.installed.value.usable)
    }

    @Test
    fun damagedModelDisablesRecognition() = runTest {
        val model = fake.publishModel("mnv3", modelBytes)
        fake.publish(en1, model)
        manager.addLanguages(setOf("en"))
        manager.sync(poll = false)
        store.installed.value.model!!.embedderFile.writeBytes(ByteArray(300))

        val load = PackLoader.load(store.installed.value)

        assertNull(load.loaded)
        assertTrue(load.modelBroken)
        manager.reportLoad(load)
        assertTrue(store.installed.value.usable.isEmpty())
        assertEquals(SyncOutcome.Done, manager.sync(poll = false))
        assertFalse(store.installed.value.modelBroken)
        assertEquals(setOf("en"), PackLoader.load(store.installed.value).loaded!!.languages)
    }

    // --- Room catalog ----------------------------------------------------------------------------------------

    @Test
    fun setNamesFollowThePreferredLanguageAndCardsKeepBackupDetails() = runTest {
        val importer = PackCatalogImporter(db) { listOf("de", "en") }
        // A card from a backup import with a type the pack doesn't know.
        db.catalogDao().upsertSets(listOf(CardSetEntity("sv1", "Backup", "", "en", 10, Region.INTERNATIONAL)))
        db.catalogDao().insertCardsIfMissing(listOf(ScanCatalog.card(ref("sv1-001")).copy(energyType = EnergyType.FIRE)))

        importer.import("en", listOf(set("sv1")), listOf(card("sv1-001")))
        assertEquals("Set sv1", db.catalogDao().observeSet("sv1").first()!!.name)
        assertEquals(EnergyType.FIRE, db.catalogDao().cardsInLanguage("en").single().energyType)

        importer.import("de", listOf(set("sv1").copy(name = "Karmesin")), listOf(card("sv1-001")))
        assertEquals("Karmesin", db.catalogDao().observeSet("sv1").first()!!.name)
        // English comes after German in the preference: it doesn't take the name back.
        importer.import("en", listOf(set("sv1").copy(name = "Scarlet")), listOf(card("sv1-001")))
        assertEquals("Karmesin", db.catalogDao().observeSet("sv1").first()!!.name)
    }

    private fun ref(id: String) = RefCard("en/$id", id, "en", id.substringBefore('-'), "s", id.substringAfter('-'), 10, "n", "u", false)
}
