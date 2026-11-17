// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PackPlannerTest {
    private fun file(path: String, size: Long) = PackFile(path, size, "0".repeat(64))

    private fun delta(from: Int, to: Int, size: Long) =
        PackDelta(from, to, file("en/delta-$from-$to.json.gz", size), null, size, DeltaResult(null, "k", 1, 1))

    private fun entry(
        lang: String = "en",
        version: Int = 5,
        model: String = "m1",
        modelSha: String = "sha-$model",
        size: Long = 1000,
        deltas: List<PackDelta> = emptyList(),
    ) = PackEntry(
        lang, version, model, 4, 10, modelSha, "idx", "keys", 12, 2,
        file("$lang/cards-$version.json.gz", 100), file("$lang/index-$version.bin", size - 100), size, deltas,
    )

    private fun installed(version: Int, lang: String = "en", modelSha: String = "sha-m1") =
        PackMeta(lang, version, "m1", 4, 10, modelSha, "idx", "keys", 12, 2)

    private fun catalog(vararg entries: PackEntry): PackCatalog {
        val models = entries.mapNotNull { it.model }.distinct().associateWith { PackModel(it, file("models/$it.tflite", 50), null, 4, 224) }
        return PackCatalog("now", models, entries.associateBy { it.lang })
    }

    @Test
    fun nothingInstalledGetsTheFullPack() {
        assertEquals(PackDownload.Full(entry()), PackPlanner.pick(null, entry()))
    }

    @Test
    fun currentVersionNeedsNothing() {
        assertNull(PackPlanner.pick(installed(5), entry(version = 5)))
    }

    @Test
    fun completeSmallerChainIsDownloadedAsDeltas() {
        val e = entry(version = 5, deltas = listOf(delta(2, 3, 10), delta(3, 4, 10), delta(4, 5, 10)))
        val pick = PackPlanner.pick(installed(3), e)
        assertEquals(PackDownload.Deltas(e, listOf(delta(3, 4, 10), delta(4, 5, 10))), pick)
        assertEquals(20L, pick!!.size)
    }

    @Test
    fun chainAsLargeAsTheFullPackTakesTheFullPack() {
        val e = entry(version = 5, size = 1000, deltas = listOf(delta(3, 4, 500), delta(4, 5, 500)))
        assertEquals(PackDownload.Full(e), PackPlanner.pick(installed(3), e))
    }

    @Test
    fun gapInTheChainTakesTheFullPack() {
        val e = entry(version = 5, deltas = listOf(delta(4, 5, 10)))
        assertNull(PackPlanner.deltaChain(2, e))
        assertEquals(PackDownload.Full(e), PackPlanner.pick(installed(2), e))
    }

    @Test
    fun otherModelOrOlderCatalogTakesTheFullPack() {
        val e = entry(version = 5, deltas = listOf(delta(4, 5, 10)))
        assertEquals(PackDownload.Full(e), PackPlanner.pick(installed(4, modelSha = "sha-old"), e))
        assertEquals(PackDownload.Full(entry(version = 3)), PackPlanner.pick(installed(4), entry(version = 3)))
    }

    @Test
    fun modelChangeDownloadsTheModelAndEveryLanguageInFull() {
        val en = entry("en", version = 6, model = "m2", deltas = listOf(delta(5, 6, 10)))
        val de = entry("de", version = 2, model = "m2")
        val plan = PackPlanner.plan(
            catalog(en, de), activeModel = "m1", modelOk = true,
            installed = mapOf("en" to installed(5), "de" to installed(2, "de")),
            wanted = setOf("en", "de"),
        )
        assertTrue(plan.modelChange)
        assertTrue(plan.downloadModel)
        assertEquals("m2", plan.model!!.id)
        assertEquals(mapOf("de" to PackDownload.Full(de), "en" to PackDownload.Full(en)), plan.downloads)
    }

    @Test
    fun firstInstallFetchesTheModelWithoutCountingAsAChange() {
        val plan = PackPlanner.plan(catalog(entry()), null, true, emptyMap(), setOf("en"))
        assertFalse(plan.modelChange)
        assertTrue(plan.downloadModel)
        assertEquals(setOf("en"), plan.downloads.keys)
    }

    @Test
    fun unwantedAreRemovedMissingAreUnavailableBrokenAreForcedFull() {
        val en = entry("en", version = 5)
        val plan = PackPlanner.plan(
            catalog(en), "m1", modelOk = true,
            installed = mapOf("en" to installed(5), "fr" to installed(1, "fr")),
            wanted = setOf("en", "pl"),
            forceFull = setOf("en"),
        )
        assertEquals(setOf("fr"), plan.remove)
        assertEquals(setOf("pl"), plan.unavailable)
        assertEquals(mapOf("en" to PackDownload.Full(en)), plan.downloads)
        assertFalse(plan.downloadModel)
    }

    @Test
    fun brokenModelIsDownloadedAgain() {
        val plan = PackPlanner.plan(catalog(entry()), "m1", modelOk = false, mapOf("en" to installed(5)), setOf("en"))
        assertTrue(plan.downloadModel)
        assertFalse(plan.modelChange)
        assertTrue(plan.downloads.isEmpty())
    }

    @Test
    fun duringAModelRolloutTheInstalledModelStays() {
        val en = entry("en", version = 6, model = "m2")
        val de = entry("de", version = 3, model = "m1")
        val plan = PackPlanner.plan(
            catalog(en, de), "m1", true,
            mapOf("en" to installed(5), "de" to installed(2, "de")),
            setOf("en", "de"),
        )
        assertFalse(plan.modelChange)
        assertEquals("m1", plan.model!!.id)
        assertEquals(setOf("de"), plan.downloads.keys)
        assertEquals(setOf("en"), plan.unavailable)
    }

    @Test
    fun packsWithoutEmbeddingsAreNotUsable() {
        val noIndex = entry().copy(model = null, modelSha256 = null, indexSha256 = null, indexFile = null, cards = 0)
        val plan = PackPlanner.plan(catalog(noIndex), null, true, emptyMap(), setOf("en"))
        assertTrue(plan.isEmpty)
        assertEquals(setOf("en"), plan.unavailable)
    }

    @Test
    fun catalogPollIsDueOncePerDay() {
        val now = Instant.parse("2026-11-17T12:00:00Z")
        assertTrue(CatalogPolling.isDue(null, now))
        assertFalse(CatalogPolling.isDue(now - Duration.ofHours(3), now))
        assertTrue(CatalogPolling.isDue(now - Duration.ofHours(21), now))
        // A clock that jumped back must not stop polling for good.
        assertTrue(CatalogPolling.isDue(now + Duration.ofDays(2), now))
    }
}
