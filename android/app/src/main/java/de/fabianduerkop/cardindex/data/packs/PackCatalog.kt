// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import org.json.JSONObject

/*
 * The language-pack catalog (`GET /v1/packs/catalog.json`), exactly as docs/packs.md specifies it. Only the fields the
 * app uses are read; unknown fields are ignored so the builder can add some without an app update.
 */

/** One file below `/v1/packs/`; [size] and [sha256] are of the bytes as served. */
data class PackFile(val path: String, val size: Long, val sha256: String)

/** The shared recognition model: embedder plus the optional learned corner detector. */
data class PackModel(val id: String, val embedder: PackFile, val corners: PackFile?, val dim: Int, val inputSize: Int) {
    val size: Long get() = embedder.size + (corners?.size ?: 0)
}

/** What applying a delta must produce; checked before the result replaces the installed pack. */
data class DeltaResult(val indexSha256: String?, val keysSha256: String, val cards: Int, val metadataCards: Int)

data class PackDelta(
    val from: Int,
    val to: Int,
    val cards: PackFile,
    /** Absent when no upserted card is indexed. */
    val index: PackFile?,
    val size: Long,
    val result: DeltaResult,
)

/** `packs.<lang>`. [model], [modelSha256], [indexSha256] and [indexFile] are null for test packs without embeddings. */
data class PackEntry(
    val lang: String,
    val version: Int,
    val model: String?,
    val dim: Int,
    /** Index rows. */
    val cards: Int,
    val modelSha256: String?,
    val indexSha256: String?,
    val keysSha256: String,
    /** Cards in the card file, including those without an index row. */
    val metadataCards: Int,
    val sets: Int,
    val cardsFile: PackFile,
    val indexFile: PackFile?,
    /** The full pack's files; the model is counted once, under [PackCatalog.models]. */
    val size: Long,
    /** Oldest first, at most 6. */
    val deltas: List<PackDelta>,
) {
    /** A pack the app can recognize with. The builder refuses to upload others, but the app must not trust that. */
    val usable: Boolean get() = model != null && modelSha256 != null && indexSha256 != null && indexFile != null && cards > 0
}

data class PackCatalog(
    val generatedAt: String?,
    val models: Map<String, PackModel>,
    val packs: Map<String, PackEntry>,
) {
    /** Download size of [lang] for someone who has no model yet (the model is counted once per download, not per pack). */
    fun packSize(lang: String): Long? = packs[lang]?.size

    fun modelOf(lang: String): PackModel? = packs[lang]?.model?.let(models::get)

    companion object {
        const val FORMAT_VERSION = 1

        /** Parses catalog.json; throws [UnsupportedPackFormatException] for a format this app doesn't know. */
        fun parse(json: String): PackCatalog {
            val root = JSONObject(json)
            val format = root.optInt("formatVersion", -1)
            if (format != FORMAT_VERSION) throw UnsupportedPackFormatException("catalog format $format, this app reads $FORMAT_VERSION")
            val models = root.optJSONObject("models") ?: JSONObject()
            val packs = root.optJSONObject("packs") ?: JSONObject()
            return PackCatalog(
                generatedAt = root.optStringOrNull("generatedAt"),
                models = models.keys().asSequence().associateWith { id -> parseModel(id, models.getJSONObject(id)) },
                packs = packs.keys().asSequence().associateWith { lang -> parsePack(lang, packs.getJSONObject(lang)) },
            )
        }

        private fun parseModel(id: String, o: JSONObject) = PackModel(
            id = id,
            embedder = parseFile(o.getJSONObject("embedder")),
            corners = o.optJSONObject("corners")?.let(::parseFile),
            dim = o.getInt("dim"),
            inputSize = o.optInt("inputSize", 224),
        )

        private fun parsePack(lang: String, o: JSONObject): PackEntry {
            val files = o.getJSONObject("files")
            val deltas = o.optJSONArray("deltas")
            return PackEntry(
                lang = lang,
                version = o.getInt("version"),
                model = o.optStringOrNull("model"),
                dim = o.optInt("dim", 0),
                cards = o.optInt("cards", 0),
                modelSha256 = o.optStringOrNull("modelSha256"),
                indexSha256 = o.optStringOrNull("indexSha256"),
                keysSha256 = o.getString("keysSha256"),
                metadataCards = o.optInt("metadataCards", 0),
                sets = o.optInt("sets", 0),
                cardsFile = parseFile(files.getJSONObject("cards")),
                indexFile = files.optJSONObject("index")?.let(::parseFile),
                size = o.getLong("size"),
                deltas = if (deltas == null) emptyList() else List(deltas.length()) { parseDelta(deltas.getJSONObject(it)) },
            )
        }

        private fun parseDelta(o: JSONObject): PackDelta {
            val files = o.getJSONObject("files")
            val result = o.getJSONObject("result")
            return PackDelta(
                from = o.getInt("from"),
                to = o.getInt("to"),
                cards = parseFile(files.getJSONObject("cards")),
                index = files.optJSONObject("index")?.let(::parseFile),
                size = o.getLong("size"),
                result = DeltaResult(
                    indexSha256 = result.optStringOrNull("indexSha256"),
                    keysSha256 = result.getString("keysSha256"),
                    cards = result.getInt("cards"),
                    metadataCards = result.getInt("metadataCards"),
                ),
            )
        }

        private val SHA256 = Regex("[0-9a-f]{64}")

        private fun parseFile(o: JSONObject): PackFile {
            val path = o.getString("path")
            // The path becomes part of a URL and a cache file name; a catalog must not point outside /v1/packs/.
            require(path.isNotEmpty() && !path.startsWith("/") && ".." !in path.split('/')) { "bad pack path $path" }
            // The hash becomes part of a download file name too (#131): only the 64 hex digits of a SHA-256.
            val sha256 = o.getString("sha256").lowercase()
            require(SHA256.matches(sha256)) { "bad sha256 for $path" }
            return PackFile(path, o.getLong("size"), sha256)
        }
    }
}

/** A catalog or pack file in a format version this app doesn't know. The installed packs stay as they are. */
class UnsupportedPackFormatException(message: String) : IllegalStateException(message)

internal fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)
