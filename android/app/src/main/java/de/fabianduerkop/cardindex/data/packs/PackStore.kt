// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The stored pack entry, `pack.json` next to the pack's files: the catalog fields `RecognitionEngine` checks on
 * load (docs/packs.md, "Checks", "On load").
 */
data class PackMeta(
    val lang: String,
    val version: Int,
    val model: String,
    val dim: Int,
    val cards: Int,
    val modelSha256: String,
    val indexSha256: String,
    val keysSha256: String,
    val metadataCards: Int,
    val sets: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("lang", lang).put("version", version).put("model", model).put("dim", dim).put("cards", cards)
        .put("modelSha256", modelSha256).put("indexSha256", indexSha256).put("keysSha256", keysSha256)
        .put("metadataCards", metadataCards).put("sets", sets)

    companion object {
        fun fromJson(o: JSONObject) = PackMeta(
            lang = o.getString("lang"),
            version = o.getInt("version"),
            model = o.getString("model"),
            dim = o.getInt("dim"),
            cards = o.getInt("cards"),
            modelSha256 = o.getString("modelSha256"),
            indexSha256 = o.getString("indexSha256"),
            keysSha256 = o.getString("keysSha256"),
            metadataCards = o.getInt("metadataCards"),
            sets = o.getInt("sets"),
        )

        /** The entry for an installed [content] that was checked against [entry] (or a delta result leading to it). */
        fun of(
            entry: PackEntry,
            content: PackContent,
            indexSha256: String = de.fabianduerkop.cardindex.recognition.sha256Hex(content.indexBytes()),
        ) = PackMeta(
            lang = entry.lang,
            version = content.version,
            model = entry.model!!,
            dim = content.dim,
            cards = content.rows.size,
            modelSha256 = entry.modelSha256!!,
            indexSha256 = indexSha256,
            keysSha256 = content.keysSha256(),
            metadataCards = content.cards.size,
            sets = content.sets.size,
        )
    }
}

data class InstalledPack(val meta: PackMeta, val dir: File) {
    val cardsFile: File get() = File(dir, CARDS)
    val indexFile: File get() = File(dir, INDEX)

    /** Bytes on disk. */
    val size: Long get() = dir.listFiles().orEmpty().sumOf { it.length() }

    companion object {
        const val CARDS = "cards.json.gz"
        const val INDEX = "index.bin"
        const val META = "pack.json"
    }
}

data class InstalledModel(
    val id: String,
    val dir: File,
    val dim: Int,
    val embedderSha256: String,
    val cornersSha256: String?,
) {
    val embedderFile: File get() = File(dir, EMBEDDER)
    val cornersFile: File? get() = cornersSha256?.let { File(dir, CORNERS) }
    val size: Long get() = dir.listFiles().orEmpty().sumOf { it.length() }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("dim", dim).put("embedderSha256", embedderSha256)
        .put("cornersSha256", cornersSha256 ?: JSONObject.NULL)

    companion object {
        const val EMBEDDER = "embedder.tflite"
        const val CORNERS = "corners.tflite"
        const val META = "model.json"

        fun read(dir: File): InstalledModel {
            val o = JSONObject(File(dir, META).readText())
            return InstalledModel(o.getString("id"), dir, o.getInt("dim"), o.getString("embedderSha256"), o.optStringOrNull("cornersSha256"))
        }
    }
}

/** What is installed, as one consistent snapshot. */
data class InstalledPacks(
    val model: InstalledModel?,
    val packs: Map<String, InstalledPack>,
    /** Languages whose pack failed a check; recognition skips them until a fresh download replaces them. */
    val broken: Set<String> = emptySet(),
    /** The model file failed its check: nothing can be recognized until it is downloaded again. */
    val modelBroken: Boolean = false,
    /** Installed pack directory per language whose cards are in the Room catalog. */
    val catalogFilled: Map<String, String> = emptyMap(),
) {
    /** Languages recognition can use. */
    val usable: Set<String> get() = if (model == null || modelBroken) emptySet() else packs.keys - broken

    val isEmpty: Boolean get() = packs.isEmpty()
}

/**
 * Packs on disk, in app-private storage that is not backed up (they can be downloaded again):
 *
 * ```
 * <root>/state.json                 which model and pack directories are active (replaced atomically)
 * <root>/models/<id>-<n>/           embedder.tflite, corners.tflite, model.json
 * <root>/langs/<lang>-<v>-<n>/      cards.json.gz, index.bin, pack.json
 * <root>/downloads/                 verified and partial downloads (resumable)
 * <root>/catalog.json, catalog.etag the last catalog and its ETag
 * ```
 *
 * New packs are written into fresh directories and only become active when state.json points to them, so a crash
 * or a failed check never leaves a half-written pack in use. Directories state.json doesn't name are deleted.
 */
class PackStore(val root: File) {
    val downloadDir = File(root, "downloads")
    private val stateFile = File(root, "state.json")
    private val catalogFile = File(root, "catalog.json")
    private val etagFile = File(root, "catalog.etag")
    private val modelsDir = File(root, "models")
    private val langsDir = File(root, "langs")

    private val _installed = MutableStateFlow(read())
    val installed: StateFlow<InstalledPacks> = _installed.asStateFlow()

    private data class State(
        val modelDir: String?,
        val packs: Map<String, String>,
        val broken: Set<String>,
        val modelBroken: Boolean,
        val catalogFilled: Map<String, String>,
    )

    /** A change of the active state; [Change.packs] maps a language to its directory name (null removes it). */
    class Change {
        var model: File? = null
        val packs = LinkedHashMap<String, File?>()
        var replaceAllPacks = false
        val clearBroken = HashSet<String>()
        val addBroken = HashSet<String>()
        var modelBroken: Boolean? = null
        val catalogFilled = LinkedHashMap<String, String>()
    }

    /** Applies [change] atomically, then deletes directories that are no longer active. */
    /**
     * Writes the new state. With [collectGarbage] it also deletes directories the state no longer names; only a
     * caller holding the sync lock may do that, or it would delete a pack a running sync has written but not
     * committed yet.
     */
    @Synchronized
    fun commit(collectGarbage: Boolean = true, change: Change.() -> Unit) {
        val c = Change().apply(change)
        val old = readState()
        val packs = if (c.replaceAllPacks) LinkedHashMap() else LinkedHashMap(old.packs)
        for ((lang, dir) in c.packs) if (dir == null) packs.remove(lang) else packs[lang] = dir.name
        val next = State(
            modelDir = c.model?.name ?: old.modelDir,
            packs = packs,
            broken = (old.broken - c.clearBroken + c.addBroken).intersect(packs.keys),
            modelBroken = c.modelBroken ?: (old.modelBroken && c.model == null),
            catalogFilled = (old.catalogFilled + c.catalogFilled).filter { (lang, dir) -> packs[lang] == dir },
        )
        writeState(next)
        _installed.value = read()
        if (collectGarbage) collectGarbage(next)
    }

    /** A new, empty directory for a pack; it becomes active only through [commit]. */
    fun newPackDir(lang: String, version: Int): File = File(langsDir, "$lang-$version-${UUID.randomUUID().toString().take(8)}").also { it.mkdirs() }

    fun newModelDir(id: String): File = File(modelsDir, "$id-${UUID.randomUUID().toString().take(8)}").also { it.mkdirs() }

    /** Writes a pack's files into [dir] (from [newPackDir]). */
    fun writePack(dir: File, content: PackContent, meta: PackMeta) {
        File(dir, InstalledPack.CARDS).writeBytes(content.cardsJsonGz())
        File(dir, InstalledPack.INDEX).writeBytes(content.indexBytes())
        File(dir, InstalledPack.META).writeText(meta.toJson().toString())
    }

    /** Moves verified downloads of a full pack into [dir]; the files were checked against the catalog. */
    fun writePack(dir: File, cardsGz: File, index: File, meta: PackMeta) {
        moveOrCopy(cardsGz, File(dir, InstalledPack.CARDS))
        moveOrCopy(index, File(dir, InstalledPack.INDEX))
        File(dir, InstalledPack.META).writeText(meta.toJson().toString())
    }

    fun writeModel(dir: File, model: InstalledModel, embedder: File, corners: File?) {
        moveOrCopy(embedder, File(dir, InstalledModel.EMBEDDER))
        corners?.let { moveOrCopy(it, File(dir, InstalledModel.CORNERS)) }
        File(dir, InstalledModel.META).writeText(model.toJson().toString())
    }

    /** Drops a directory that never became active. */
    fun discard(dir: File) {
        dir.deleteRecursively()
    }

    fun cachedCatalog(): Pair<String, String?>? =
        if (catalogFile.isFile) catalogFile.readText() to etagFile.takeIf { it.isFile }?.readText()?.ifBlank { null } else null

    /** Stores the catalog first and its ETag second, so an ETag never describes a catalog that isn't stored. */
    fun saveCatalog(body: String, etag: String?) {
        root.mkdirs()
        etagFile.delete()
        atomicWrite(catalogFile, body)
        if (etag != null) atomicWrite(etagFile, etag)
    }

    /** Removes downloads that are complete; partial ones stay for a resume. */
    fun clearDownloads(keepPartial: Boolean = true) {
        downloadDir.listFiles().orEmpty().filter { !keepPartial || !it.name.endsWith(".part") }.forEach { it.delete() }
    }

    private fun read(): InstalledPacks {
        val s = readState()
        val model = s.modelDir?.let { name -> runCatching { InstalledModel.read(File(modelsDir, name)) }.getOrNull() }
        val packs = s.packs.mapNotNull { (lang, name) ->
            val dir = File(langsDir, name)
            runCatching { lang to InstalledPack(PackMeta.fromJson(JSONObject(File(dir, InstalledPack.META).readText())), dir) }.getOrNull()
        }.toMap()
        // A pack whose pack.json can't be read counts as broken, so the user is offered a fresh download.
        val unreadable = s.packs.keys - packs.keys
        return InstalledPacks(
            model = model,
            packs = packs,
            broken = s.broken + unreadable,
            modelBroken = s.modelBroken || (s.modelDir != null && model == null),
            catalogFilled = s.catalogFilled,
        )
    }

    private fun readState(): State {
        if (!stateFile.isFile) return State(null, emptyMap(), emptySet(), false, emptyMap())
        val o = JSONObject(stateFile.readText())
        val packs = o.optJSONObject("packs") ?: JSONObject()
        val filled = o.optJSONObject("catalogFilled") ?: JSONObject()
        val broken = o.optJSONArray("broken") ?: JSONArray()
        return State(
            modelDir = o.optStringOrNull("model"),
            packs = packs.keys().asSequence().associateWith { packs.getString(it) },
            broken = List(broken.length()) { broken.getString(it) }.toSet(),
            modelBroken = o.optBoolean("modelBroken"),
            catalogFilled = filled.keys().asSequence().associateWith { filled.getString(it) },
        )
    }

    private fun writeState(s: State) {
        val o = JSONObject()
            .put("model", s.modelDir ?: JSONObject.NULL)
            .put("packs", JSONObject(s.packs as Map<*, *>))
            .put("broken", JSONArray(s.broken.sorted()))
            .put("modelBroken", s.modelBroken)
            .put("catalogFilled", JSONObject(s.catalogFilled as Map<*, *>))
        root.mkdirs()
        atomicWrite(stateFile, o.toString())
    }

    private fun collectGarbage(s: State) {
        modelsDir.listFiles().orEmpty().filter { it.name != s.modelDir }.forEach { it.deleteRecursively() }
        val active = s.packs.values.toSet()
        langsDir.listFiles().orEmpty().filter { it.name !in active }.forEach { it.deleteRecursively() }
    }

    /** Deletes directories that no state names, e.g. left over by a sync that was killed. Call before a sync. */
    @Synchronized
    fun cleanUp() = collectGarbage(readState())

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun moveOrCopy(from: File, to: File) {
        if (from.renameTo(to)) return
        from.copyTo(to, overwrite = true)
        if (!from.delete()) throw IOException("Cannot remove ${from.name} after copying")
    }
}
