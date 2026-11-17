// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import de.fabianduerkop.cardindex.recognition.sha256Hex
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/** Small packs (dim 4) and a fake pack service, built the way ml/packs.py builds the real ones. */
object PackFixtures {
    const val DIM = 4

    fun set(id: String, official: Int = 10) =
        PackSet(id, "Set $id", "sv", "Scarlet & Violet", "2023-0${id.length % 9 + 1}-01", official, official + 2, null, null, null)

    fun card(id: String, name: String = "Card $id", image: String? = "https://assets.tcgdex.net/x/$id") =
        PackCard(id, id.substringBefore('-'), id.substringAfter('-'), name, image)

    /** A deterministic float32 row for [seed]. */
    fun row(seed: Int): ByteArray {
        val buf = ByteBuffer.allocate(DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(DIM) { buf.putFloat(((seed * 31 + it * 7) % 97) / 97f) }
        return buf.array()
    }

    /** A pack with [cards]; every card with an image gets an index row (seeded by its id). */
    fun content(lang: String, version: Int, sets: List<PackSet>, cards: List<PackCard>) =
        PackContent(lang, version, sets, cards, cards.filter { it.image != null }.associate { it.id to row(it.id.hashCode()) }, DIM)

    /** compute_delta + delta_document of ml/packs.py: the delta's card file and its rows. */
    fun delta(old: PackContent, new: PackContent): Pair<ByteArray, ByteArray> {
        val oldSets = old.sets.associateBy { it.id }
        val oldCards = old.cards.associateBy { it.id }
        val upsert = new.cards.filter { c -> oldCards[c.id] != c || !old.rows[c.id].contentEquals(new.rows[c.id]) }
        val doc = JSONObject()
            .put("formatVersion", 1).put("lang", new.lang).put("from", old.version).put("to", new.version)
            .put("upsertSets", JSONArray(new.sets.filter { oldSets[it.id] != it }.map { PackContent.setJson(it) }))
            .put("removeSets", JSONArray((oldSets.keys - new.sets.map { it.id }.toSet()).sorted()))
            .put("upsert", JSONArray(upsert.map { PackContent.cardJson(it).put("indexed", it.id in new.rows) }))
            .put("remove", JSONArray((oldCards.keys - new.cards.map { it.id }.toSet()).sorted()))
        val rows = Buffer().apply { upsert.filter { it.id in new.rows }.forEach { write(new.rows.getValue(it.id)) } }.readByteArray()
        return PackContent.gzip(doc.toString().toByteArray()) to rows
    }

    fun file(path: String, bytes: ByteArray) = JSONObject().put("path", path).put("size", bytes.size).put("sha256", sha256Hex(bytes))
}

/**
 * The backend's pack routes in memory: ETag/`If-None-Match` on the catalog, a single `Range` → `206`. [corrupt]
 * serves other bytes for a path than the catalog promises.
 */
class FakePackServer : Dispatcher() {
    val files = HashMap<String, ByteArray>()
    val corrupt = HashMap<String, ByteArray>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val models = JSONObject()
    private val packs = JSONObject()
    private var generation = 0
    var catalogOverride: String? = null

    val catalogJson: String
        get() = catalogOverride ?: JSONObject().put("formatVersion", 1).put("generatedAt", "2026-11-17T03:00:00Z")
            .put("models", models).put("packs", packs).toString()

    private val etag get() = "\"c$generation-${catalogJson.hashCode()}\""

    fun paths() = requests.map { it.path!!.removePrefix("/v1/packs/") }

    /** Publishes a model; returns its id. */
    fun publishModel(name: String, bytes: ByteArray, corners: ByteArray? = null): String {
        val id = "$name-${sha256Hex(bytes).take(12)}"
        files["models/$id.tflite"] = bytes
        val entry = JSONObject().put("embedder", PackFixtures.file("models/$id.tflite", bytes)).put("dim", PackFixtures.DIM).put("inputSize", 224)
        corners?.let {
            files["models/corners-$id.tflite"] = it
            entry.put("corners", PackFixtures.file("models/corners-$id.tflite", it))
        }
        models.put(id, entry)
        generation++
        return id
    }

    /** Publishes [content] for [modelId], with deltas from each of [bases] (oldest first). */
    fun publish(
        content: PackContent,
        modelId: String,
        bases: List<PackContent> = emptyList(),
        /** Changes a delta's rows after the result hashes were taken (its file checksum still matches). */
        tamperDeltaRows: (ByteArray) -> ByteArray = { it },
    ) {
        val lang = content.lang
        val v = content.version
        val cards = content.cardsJsonGz()
        val index = content.indexBytes()
        files["$lang/cards-$v.json.gz"] = cards
        files["$lang/index-$v.bin"] = index
        val modelSha = sha256Hex(files.getValue("models/$modelId.tflite"))
        val deltas = JSONArray()
        for (base in bases) {
            val (doc, cleanRows) = PackFixtures.delta(base, content)
            val rows = tamperDeltaRows(cleanRows)
            val f = JSONObject().put("cards", PackFixtures.file("$lang/delta-${base.version}-$v.json.gz", doc))
            files["$lang/delta-${base.version}-$v.json.gz"] = doc
            if (rows.isNotEmpty()) {
                files["$lang/delta-${base.version}-$v.bin"] = rows
                f.put("index", PackFixtures.file("$lang/delta-${base.version}-$v.bin", rows))
            }
            deltas.put(
                JSONObject().put("from", base.version).put("to", v).put("files", f)
                    .put("size", doc.size + rows.size)
                    .put(
                        "result",
                        JSONObject().put("indexSha256", sha256Hex(index)).put("keysSha256", content.keysSha256())
                            .put("cards", content.rows.size).put("metadataCards", content.cards.size),
                    ),
            )
        }
        packs.put(
            lang,
            JSONObject().put("version", v).put("builtAt", "2026-11-17T03:00:00Z").put("model", modelId).put("dim", PackFixtures.DIM)
                .put("cards", content.rows.size).put("modelSha256", modelSha).put("indexSha256", sha256Hex(index))
                .put("keysSha256", content.keysSha256()).put("metadataCards", content.cards.size).put("sets", content.sets.size)
                .put("files", JSONObject().put("cards", PackFixtures.file("$lang/cards-$v.json.gz", cards)).put("index", PackFixtures.file("$lang/index-$v.bin", index)))
                .put("size", cards.size + index.size)
                .put("deltas", deltas),
        )
        generation++
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.path!!.removePrefix("/v1/packs/")
        if (path == "catalog.json") {
            if (request.getHeader("If-None-Match") == etag) return MockResponse().setResponseCode(304)
            return MockResponse().setBody(catalogJson).setHeader("ETag", etag)
        }
        val bytes = corrupt[path] ?: files[path] ?: return MockResponse().setResponseCode(404)
        val range = request.getHeader("Range")
        if (range != null) {
            val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
            if (start >= bytes.size) return MockResponse().setResponseCode(416)
            return MockResponse().setResponseCode(206).setBody(Buffer().write(bytes, start, bytes.size - start))
                .setHeader("Content-Range", "bytes $start-${bytes.size - 1}/${bytes.size}")
        }
        return MockResponse().setBody(Buffer().write(bytes))
    }
}
