// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.packs.PackFixtures.card
import de.fabianduerkop.cardindex.data.packs.PackFixtures.content
import de.fabianduerkop.cardindex.data.packs.PackFixtures.row
import de.fabianduerkop.cardindex.data.packs.PackFixtures.set
import de.fabianduerkop.cardindex.recognition.PackIntegrityException
import de.fabianduerkop.cardindex.recognition.sha256Hex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #89: a delta is installed by streaming the old index.bin and the delta's rows into a new file. The result
 * must be the same, byte for byte, as applying the delta in memory ([PackContent.applyDelta]).
 */
@RunWith(AndroidJUnit4::class)
class StreamedDeltaTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val filler = (10..39).map { card("sv2-0$it") }

    private val v1 = content(
        "en", 1,
        listOf(set("sv1"), set("sv2")),
        listOf(card("sv1-001"), card("sv1-002"), card("sv1-003", image = null), card("sv2-001"), card("sv2-002")) + filler,
    )

    /** A renamed card, a removed one, a new set with a card with and one without a row. */
    private val v2 = content(
        "en", 2,
        listOf(set("sv1"), set("sv2"), set("sv3")),
        listOf(
            card("sv1-001", name = "Renamed"), card("sv1-003", image = null), card("sv2-001"), card("sv2-002"),
            card("sv3-001"), card("sv3-002", image = null),
        ) + filler,
    )

    /** From [v2]: a card that gains a row, one that loses it, one re-embedded (same entry, other row), a set removed. */
    private val v3 = PackContent(
        "en", 3,
        listOf(set("sv1"), set("sv2")),
        v2.cards.filter { it.setId != "sv3" } + card("sv1-004"),
        v2.rows.filterKeys { !it.startsWith("sv3-") && it != "sv2-002" } +
            mapOf("sv1-003" to row(1003), "sv2-015" to row(2015), "sv1-004" to row(1004)),
        PackFixtures.DIM,
    )

    private fun onDisk(pack: PackContent): StreamedPack {
        val index = tmp.newFile().apply { writeBytes(pack.indexBytes()) }
        return StreamedPack(PackCards.parse(pack.cardsJsonGz()), pack.dim, index, sha256Hex(pack.indexBytes()))
    }

    private var outputs = 0

    private fun out(): File = File(tmp.root, "out-${outputs++}.bin")

    private val entry = PackEntry(
        "en", 0, "mnv3", PackFixtures.DIM, 0, "abc", null, "", 0, 0, PackFile("x", 0, "x"), null, 0, emptyList(),
    )

    private fun assertSamePack(expected: PackContent, actual: StreamedPack) {
        assertArrayEquals(expected.indexBytes(), actual.index.readBytes())
        assertEquals(sha256Hex(expected.indexBytes()), actual.indexSha256)
        assertEquals(expected.dim, actual.dim)
        assertArrayEquals(expected.cardsJsonGz(), actual.cardFile.cardsJsonGz())
        assertEquals(PackMeta.of(entry, expected), PackMeta.of(entry, actual.cardFile, actual.dim, actual.indexSha256))
        // A pack without rows has no index checksum in the catalog.
        val indexSha = if (expected.rows.isEmpty()) null else sha256Hex(expected.indexBytes())
        actual.verify(expected.keysSha256(), indexSha, expected.rows.size, expected.cards.size)
    }

    private fun applyBoth(base: PackContent, target: PackContent, delta: Pair<ByteArray, ByteArray> = PackFixtures.delta(base, target)) {
        val (doc, rows) = delta
        val inMemory = base.applyDelta(doc, rows)
        val streamed = onDisk(base).applyDelta(doc, tmp.newFile().apply { writeBytes(rows) }, out())
        assertSamePack(inMemory, streamed)
        assertSamePack(target, streamed)
    }

    @Test
    fun upsertsRemovesAndNewCardsGiveTheSameBytesAsInMemory() = applyBoth(v1, v2)

    @Test
    fun gainedLostAndReembeddedRowsGiveTheSameBytesAsInMemory() = applyBoth(v2, v3)

    @Test
    fun upsertsInAnyOrderTakeTheirOwnRows() {
        val (doc, rows) = PackFixtures.delta(v2, v3)
        // Reverse the upserts and, with them, the rows of the indexed ones.
        val json = JSONObject(PackContent.gunzip(doc).toString(Charsets.UTF_8))
        val upsert = json.getJSONArray("upsert")
        json.put("upsert", JSONArray((upsert.length() - 1 downTo 0).map { upsert.getJSONObject(it) }))
        val step = PackFixtures.DIM * 4
        val reversed = (rows.size / step - 1 downTo 0).flatMap { rows.copyOfRange(it * step, (it + 1) * step).toList() }.toByteArray()
        applyBoth(v2, v3, PackContent.gzip(json.toString().toByteArray()) to reversed)
    }

    @Test
    fun chainOfDeltasStreamsFileToFile() {
        val (d12, r12) = PackFixtures.delta(v1, v2)
        val (d23, r23) = PackFixtures.delta(v2, v3)
        val inMemory = v1.applyDelta(d12, r12).applyDelta(d23, r23)
        val streamed = onDisk(v1)
            .applyDelta(d12, tmp.newFile().apply { writeBytes(r12) }, out())
            .applyDelta(d23, tmp.newFile().apply { writeBytes(r23) }, out())
        assertSamePack(inMemory, streamed)
        assertSamePack(v3, streamed)
    }

    @Test
    fun packWithoutRowsAndBack() {
        val empty = PackContent("en", 4, v3.sets, v3.cards.map { it.copy(image = null) }, emptyMap(), 0)
        applyBoth(v3, empty)
        val (doc, rows) = PackFixtures.delta(empty, content("en", 5, v1.sets, v1.cards))
        // A base without rows has dim 0; the delta's rows give the width.
        val streamed = onDisk(empty).applyDelta(doc, tmp.newFile().apply { writeBytes(rows) }, out())
        assertSamePack(empty.applyDelta(doc, rows), streamed)
        assertEquals(PackFixtures.DIM, streamed.dim)
    }

    @Test
    fun deltaWithoutRowFile() {
        // Only a card without a row changes, so the delta has no .bin.
        val renamed = content("en", 2, v1.sets, v1.cards.map { if (it.id == "sv1-003") it.copy(name = "Renamed") else it })
        val (doc, rows) = PackFixtures.delta(v1, renamed)
        assertEquals(0, rows.size)
        val streamed = onDisk(v1).applyDelta(doc, null, out())
        assertSamePack(v1.applyDelta(doc, null), streamed)
    }

    @Test
    fun damagedInputIsRefused() {
        val (doc, rows) = PackFixtures.delta(v1, v2)
        val base = onDisk(v1)
        base.index.writeBytes(base.index.readBytes().copyOf(base.index.length().toInt() - 4))
        assertThrows(PackIntegrityException::class.java) { base.applyDelta(doc, tmp.newFile().apply { writeBytes(rows) }, out()) }

        val short = tmp.newFile().apply { writeBytes(rows.copyOf(rows.size - 4)) }
        assertThrows(PackIntegrityException::class.java) { onDisk(v1).applyDelta(doc, short, out()) }

        val (otherDoc, otherRows) = PackFixtures.delta(v2, v3)
        assertThrows(PackIntegrityException::class.java) { onDisk(v1).applyDelta(otherDoc, tmp.newFile().apply { writeBytes(otherRows) }, out()) }
    }
}
