// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import android.util.Log
import de.fabianduerkop.cardindex.recognition.PackIntegrityException
import de.fabianduerkop.cardindex.recognition.RefCard
import de.fabianduerkop.cardindex.recognition.ReferenceIndex
import de.fabianduerkop.cardindex.recognition.sha256Hex
import java.io.File
import java.nio.FloatBuffer

/** The installed packs, checked and loaded for the recognition engine (the index rows memory-mapped). */
class LoadedPacks(
    val model: InstalledModel,
    val modelBytes: ByteArray,
    /** Index rows of every usable language, one after the other. */
    val cards: List<RefCard>,
    /** One memory-mapped `index.bin` per language, in the order of [cards] (off the Java heap, issue #74). */
    val vectors: List<FloatBuffer>,
    val dim: Int,
    val languages: Set<String>,
)

/**
 * [loaded] is null when no language is usable; [broken] and [modelBroken] go back to the [PackStore]. [from] is the
 * snapshot that was loaded: a sync may have replaced its directories since, and their failures say nothing about
 * the new ones.
 */
data class PackLoad(val loaded: LoadedPacks?, val broken: Set<String>, val modelBroken: Boolean, val from: InstalledPacks)

/**
 * The checks on load (docs/packs.md, "Checks"): SHA-256 of the model = `modelSha256`, SHA-256 of `index.bin` =
 * `indexSha256`, its size = `cards × dim × 4`, and the card file's indexed ids = `keysSha256`. A pack that fails is
 * reported broken and left out; the others still load. Hashing tens of megabytes on every start is wasted work, so a
 * passed check is remembered with the file's size and modification time and repeated when either changes.
 */
object PackLoader {
    private const val TAG = "PackLoader"
    private const val VERIFIED = "verified"

    fun load(installed: InstalledPacks): PackLoad {
        val model = installed.model
        if (model == null || installed.modelBroken) return PackLoad(null, emptySet(), modelBroken = model != null, from = installed)
        val candidates = installed.packs.filterKeys { it !in installed.broken }
        val modelBytes = try {
            model.embedderFile.readBytes().also { bytes ->
                if (!isVerified(model.dir, model.embedderFile)) {
                    check(bytes, model.embedderSha256, "model ${model.id}")
                    markVerified(model.dir, model.embedderFile)
                }
                model.cornersFile?.let { f -> check(f, model.cornersSha256!!, "corner model") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "model ${model.id} failed its check", e)
            return PackLoad(null, emptySet(), modelBroken = true, from = installed)
        }

        val broken = HashSet<String>()
        val cards = ArrayList<RefCard>()
        // Each index.bin is mapped, not read: seven languages (~107k cards) are ~104 MB, which on the Java heap left
        // a 192 MB heap at its limit (issues #56, #74). The mapped pages are clean and outside the heap.
        val vectors = ArrayList<FloatBuffer>()
        for ((lang, pack) in candidates.toSortedMap()) {
            try {
                val meta = pack.meta
                if (!meta.modelSha256.equals(model.embedderSha256, ignoreCase = true)) {
                    throw PackIntegrityException("$lang was built for another model")
                }
                if (meta.dim != model.dim) throw PackIntegrityException("$lang has dim ${meta.dim}, the model ${model.dim}")
                val expectedSize = meta.cards.toLong() * meta.dim * 4
                if (pack.indexFile.length() != expectedSize) {
                    throw PackIntegrityException("$lang index.bin has ${pack.indexFile.length()} bytes, expected $expectedSize")
                }
                if (!isVerified(pack.dir, pack.indexFile)) {
                    check(pack.indexFile, meta.indexSha256, "$lang index.bin")
                    markVerified(pack.dir, pack.indexFile)
                }
                val (sets, entries) = PackContent.parseMetadata(pack.cardsFile.readBytes())
                val indexed = entries.filter { it.second }.map { it.first }.sortedBy { it.id }
                val keys = sha256Hex(indexed.joinToString("\n") { it.id }.toByteArray(Charsets.UTF_8))
                if (indexed.size != meta.cards || !keys.equals(meta.keysSha256, ignoreCase = true)) {
                    throw PackIntegrityException("$lang card file does not match its index")
                }
                val setsById = sets.associateBy { it.id }
                val rows = ReferenceIndex.mapVectors(pack.indexFile)
                check(rows.capacity().toLong() == meta.cards.toLong() * meta.dim) { "$lang index.bin changed while loading" }
                val langCards = indexed.map { c ->
                    val set = setsById[c.setId]
                    RefCard(
                        key = "$lang/${c.id}",
                        cardId = c.id,
                        lang = lang,
                        setId = c.setId,
                        setName = set?.name ?: c.setId,
                        localId = c.localId,
                        official = set?.official ?: set?.total ?: 0,
                        name = c.name,
                        imageUrl = c.image.orEmpty(),
                        unseen = false,
                        printings = c.printings,
                    )
                }
                vectors += rows
                cards += langCards
            } catch (e: Exception) {
                Log.w(TAG, "language pack $lang failed its check", e)
                broken += lang
            }
        }
        if (cards.size < 2) return PackLoad(null, broken, modelBroken = false, from = installed)
        val languages = candidates.keys - broken
        return PackLoad(
            LoadedPacks(model, modelBytes, cards, vectors, model.dim, languages), broken, modelBroken = false, from = installed,
        )
    }

    private fun check(bytes: ByteArray, expected: String, what: String) {
        val actual = sha256Hex(bytes)
        if (!actual.equals(expected, ignoreCase = true)) throw PackIntegrityException("$what: SHA-256 $actual, expected $expected")
    }

    private fun check(file: File, expected: String, what: String) {
        val actual = sha256Hex(file)
        if (!actual.equals(expected, ignoreCase = true)) throw PackIntegrityException("$what: SHA-256 $actual, expected $expected")
    }

    private fun stamp(file: File) = "${file.name}:${file.length()}:${file.lastModified()}"

    private fun isVerified(dir: File, file: File): Boolean =
        File(dir, VERIFIED).let { it.isFile && stamp(file) in it.readLines() }

    private fun markVerified(dir: File, file: File) {
        val marker = File(dir, VERIFIED)
        val lines = if (marker.isFile) marker.readLines().filterNot { it.startsWith("${file.name}:") } else emptyList()
        marker.writeText((lines + stamp(file)).joinToString("\n"))
    }
}
