// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import android.util.Log
import de.fabianduerkop.cardindex.data.catalog.PackCatalogImporter
import de.fabianduerkop.cardindex.recognition.PackIntegrityException
import de.fabianduerkop.cardindex.recognition.sha256Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** Progress of one language in the current sync. */
sealed interface LanguageProgress {
    data class Downloading(val done: Long, val total: Long) : LanguageProgress

    data object Installing : LanguageProgress

    data class Failed(val reason: String) : LanguageProgress
}

data class PackSyncState(
    val running: Boolean = false,
    val progress: Map<String, LanguageProgress> = emptyMap(),
    /** Why the last sync stopped, e.g. no connection. */
    val error: String? = null,
    /** The last sync ended in [SyncOutcome.Failed]: retrying the same files won't help, so nothing is "waiting". */
    val failed: Boolean = false,
)

/** How a sync ended, for the worker. */
sealed interface SyncOutcome {
    data object Done : SyncOutcome

    /** Network trouble: try again later; partial downloads are kept. */
    data class Retry(val reason: String) : SyncOutcome

    /** Retrying won't help (a pack that keeps failing its checks, a catalog format this app doesn't know). */
    data class Failed(val reason: String) : SyncOutcome
}

/** docs/packs.md, "Catalog polling": once a day, and when the user opens the language settings. */
object CatalogPolling {
    /** Slightly less than a day, so a daily worker that runs a little early still polls. */
    val INTERVAL: Duration = Duration.ofHours(20)

    fun isDue(lastPolledAt: Instant?, now: Instant): Boolean =
        lastPolledAt == null || lastPolledAt.isAfter(now) || Duration.between(lastPolledAt, now) >= INTERVAL
}

/**
 * Brings the installed packs in line with the user's languages and the catalog (issue #39, docs/packs.md): full
 * packs or delta chains, every file checked, a model change switched in one step. One sync runs at a time.
 */
class PackManager(
    private val store: PackStore,
    private val prefs: PackPreferences,
    private val http: PackHttp,
    private val importer: PackCatalogImporter,
    /** Schedules [sync] in the background (WorkManager in the app, nothing in tests). */
    private val requestSync: () -> Unit = {},
    private val clock: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    val installed: StateFlow<InstalledPacks> get() = store.installed

    private val _state = MutableStateFlow(PackSyncState())
    val state: StateFlow<PackSyncState> = _state.asStateFlow()

    private val _catalog = MutableStateFlow(cachedCatalogOrNull())
    /** The last catalog seen, for download sizes; null before the first successful request. */
    val catalog: StateFlow<PackCatalog?> = _catalog.asStateFlow()

    private val _wanted = MutableStateFlow(prefs.wanted)
    /** The languages the user chose, installed or not. */
    val wantedFlow: StateFlow<Set<String>> = _wanted.asStateFlow()
    val wanted: Set<String> get() = _wanted.value

    var pickerDone: Boolean
        get() = prefs.pickerDone
        set(value) { prefs.pickerDone = value }

    private fun cachedCatalogOrNull(): PackCatalog? =
        store.cachedCatalog()?.let { (body, _) -> runCatching { PackCatalog.parse(body) }.getOrNull() }

    /** Asks the backend for a newer catalog (`If-None-Match`); a `304` keeps the stored one. */
    suspend fun refreshCatalog(): PackCatalog {
        val cached = store.cachedCatalog()
        val cachedCatalog = cached?.let { (body, _) -> runCatching { PackCatalog.parse(body) }.getOrNull() }
        val etag = cached?.second.takeIf { cachedCatalog != null }
        val catalog = when (val result = http.fetchCatalog(etag)) {
            CatalogFetch.NotModified -> cachedCatalog ?: throw IOException("Pack catalog: 304 without a stored catalog")
            is CatalogFetch.Fetched -> PackCatalog.parse(result.body).also { store.saveCatalog(result.body, result.etag) }
        }
        prefs.lastPolledAt = clock()
        _catalog.value = catalog
        return catalog
    }

    fun isPollDue(): Boolean = CatalogPolling.isDue(prefs.lastPolledAt, clock())

    /** Adds languages to the wanted set and schedules their download. */
    fun addLanguages(languages: Collection<String>) {
        prefs.wanted = prefs.wanted + languages
        _wanted.value = prefs.wanted
        // A new request: an earlier sync's failure must not hide that this one is waiting for a network.
        _state.update { it.copy(failed = false) }
        requestSync()
    }

    /** Removes a language and its pack right away. Its cards stay in the catalog: owned copies point to them. */
    suspend fun removeLanguage(lang: String) = mutex.withLock {
        prefs.wanted = prefs.wanted - lang
        _wanted.value = prefs.wanted
        store.commit { packs[lang] = null }
        _state.update { it.copy(progress = it.progress - lang) }
    }

    /** A broken pack: mark it so the next sync downloads it in full, and schedule that sync. */
    fun repair(lang: String) {
        // Outside the sync lock, so no garbage collection here: the next sync cleans up.
        store.commit(collectGarbage = false) { addBroken += lang }
        _state.update { it.copy(failed = false) }
        requestSync()
    }

    /** The worker stopped retrying after network errors: nothing is waiting any more until the next app start. */
    fun gaveUp() = _state.update { it.copy(failed = true) }

    /**
     * Records what [PackLoader] found broken, so recognition skips it and the UI offers a fresh download. Only for
     * the directories that were loaded: a sync that installed newer ones meanwhile is not marked by the old ones.
     */
    fun reportLoad(load: PackLoad) {
        if (load.broken.isEmpty() && !load.modelBroken) return
        store.commit(collectGarbage = false) {
            load.broken.forEach { lang -> load.from.packs[lang]?.let { addBrokenIfActive[lang] = it.dir } }
            if (load.modelBroken) modelBrokenIfActive = load.from.model?.dir
        }
    }

    /**
     * One sync: catalog (from the network with [poll], else the stored one), plan, downloads, checks, atomic switch,
     * then the Room catalog for every pack whose cards are not in it yet.
     */
    suspend fun sync(poll: Boolean): SyncOutcome = mutex.withLock {
        // Nobody chose a language yet: the daily poll has nothing to update, so it doesn't touch the network.
        if (prefs.wanted.isEmpty() && store.installed.value.isEmpty) return@withLock SyncOutcome.Done
        _state.value = PackSyncState(running = true)
        val outcome = try {
            store.cleanUp()
            val catalog = if (poll || _catalog.value == null) refreshCatalog() else _catalog.value!!
            run(catalog)
        } catch (e: CancellationException) {
            // WorkManager stopped the job (the network went away, the mobile data switch changed): not running any
            // more, and no frozen progress bars; the next sync starts its own.
            _state.update { it.copy(running = false, progress = emptyMap()) }
            throw e
        } catch (e: UnsupportedPackFormatException) {
            SyncOutcome.Failed(e.message.orEmpty())
        } catch (e: IOException) {
            SyncOutcome.Retry(e.message ?: "network error")
        } catch (e: PackIntegrityException) {
            SyncOutcome.Failed(e.message.orEmpty())
        } catch (e: Exception) {
            // A malformed catalog or pack file (bad JSON, missing field): retrying the same bytes won't help. Not only
            // RuntimeException: Android's JSONException is a checked exception and would leave the sync "running".
            Log.e(TAG, "pack sync failed", e)
            SyncOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        runCatching { fillCatalog() }.onFailure { Log.e(TAG, "filling the card catalog failed", it) }
        _state.update {
            it.copy(
                running = false,
                error = when (outcome) {
                    SyncOutcome.Done -> null
                    is SyncOutcome.Retry -> outcome.reason
                    is SyncOutcome.Failed -> outcome.reason
                },
                failed = outcome is SyncOutcome.Failed,
            )
        }
        outcome
    }

    private suspend fun run(catalog: PackCatalog): SyncOutcome {
        val now = store.installed.value
        val plan = PackPlanner.plan(
            catalog = catalog,
            activeModel = now.model?.id,
            modelOk = !now.modelBroken,
            installed = now.packs.mapValues { it.value.meta },
            wanted = prefs.wanted,
            forceFull = now.broken,
        )
        if (plan.remove.isNotEmpty()) store.commit { plan.remove.forEach { packs[it] = null } }
        val model = plan.model ?: return SyncOutcome.Done
        plan.downloads.values.firstOrNull { !it.entry.modelSha256.equals(model.embedder.sha256, ignoreCase = true) }?.let {
            return SyncOutcome.Failed("${it.entry.lang}: the catalog names a model whose file has another checksum")
        }
        return if (plan.modelChange) switchModel(plan, model) else update(plan, model)
    }

    /** A new model: everything is downloaded and checked first, then model and packs switch in one commit. */
    private suspend fun switchModel(plan: SyncPlan, model: PackModel): SyncOutcome {
        val created = ArrayList<File>()
        try {
            val modelDir = downloadModel(model, plan.downloads.keys.firstOrNull()).also(created::add)
            val dirs = LinkedHashMap<String, File>()
            for ((lang, download) in plan.downloads) {
                dirs[lang] = installFull(download.entry).also(created::add)
            }
            store.commit {
                this.model = modelDir
                replaceAllPacks = true
                dirs.forEach { (lang, dir) -> packs[lang] = dir }
                clearBroken += dirs.keys
            }
            store.clearDownloads()
            _state.update { it.copy(progress = emptyMap()) }
            return SyncOutcome.Done
        } catch (e: Exception) {
            // Keep the old model and packs working; the new files are thrown away (downloads stay for a resume).
            created.forEach(store::discard)
            if (e is PackIntegrityException) markFailed(plan.downloads.keys, e.message.orEmpty())
            throw e
        }
    }

    private suspend fun update(plan: SyncPlan, model: PackModel): SyncOutcome {
        if (plan.downloadModel) {
            val dir = downloadModel(model, plan.downloads.keys.firstOrNull())
            try {
                store.commit {
                    this.model = dir
                    modelBroken = false
                }
            } catch (e: Exception) {
                store.discard(dir)
                throw e
            }
        }
        var retry: IOException? = null
        var failed: String? = null
        for ((lang, download) in plan.downloads) {
            try {
                val dir = install(download)
                try {
                    store.commit {
                        packs[lang] = dir
                        clearBroken += lang
                    }
                } catch (e: Exception) {
                    store.discard(dir)
                    throw e
                }
                _state.update { it.copy(progress = it.progress - lang) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "downloading $lang failed", e)
                markFailed(setOf(lang), e.message ?: "network error")
                retry = e
            } catch (e: PackIntegrityException) {
                Log.w(TAG, "$lang failed its checks", e)
                markFailed(setOf(lang), e.message.orEmpty())
                failed = e.message
            }
        }
        // Complete downloads of a language that failed on the network stay for the next attempt.
        if (retry == null) store.clearDownloads()
        return when {
            retry != null -> SyncOutcome.Retry(retry.message ?: "network error")
            failed != null -> SyncOutcome.Failed(failed)
            else -> SyncOutcome.Done
        }
    }

    private suspend fun install(download: PackDownload): File = when (download) {
        is PackDownload.Full -> installFull(download.entry)
        is PackDownload.Deltas -> try {
            installDeltas(download)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // docs/packs.md: a delta whose result differs is discarded and the full pack downloaded.
            Log.w(TAG, "delta chain for ${download.entry.lang} failed, downloading the full pack", e)
            installFull(download.entry)
        }
    }

    private suspend fun installFull(entry: PackEntry): File {
        val index = entry.indexFile ?: throw PackIntegrityException("${entry.lang} has no index")
        val progress = Progress(entry.lang, entry.size)
        val cardsFile = progress.track { http.download(entry.cardsFile, it) }
        val indexFile = progress.track { http.download(index, it) }
        setProgress(entry.lang, LanguageProgress.Installing)
        // The index stays a file and is hashed as a stream, as in installDeltas: read into rows it was ~70–95 MB of
        // Java heap for the English pack on first run, enough to crash a mid-range phone.
        val cards = PackCards.parse(cardsFile.readBytes())
        if (cards.lang != entry.lang || cards.version != entry.version) {
            throw PackIntegrityException("${entry.path()} is ${cards.lang} ${cards.version}")
        }
        val rows = cards.indexed.size
        val dim = if (rows == 0) 0 else entry.dim
        if (rows > 0 && (dim <= 0 || indexFile.length() != rows.toLong() * dim * 4)) {
            throw PackIntegrityException("${entry.lang}: index.bin (${indexFile.length()} bytes) does not fit $rows cards of dim ${entry.dim}")
        }
        StreamedPack(cards, dim, indexFile, sha256Hex(indexFile))
            .verify(entry.keysSha256, entry.indexSha256, entry.cards, entry.metadataCards)
        val dir = store.newPackDir(entry.lang, entry.version)
        try {
            store.writePack(dir, cardsFile, indexFile, PackMeta.of(entry, cards, dim, indexSha256 = entry.indexSha256!!))
        } catch (e: Exception) {
            store.discard(dir)
            throw e
        }
        return dir
    }

    /**
     * Applies the chain file to file: each step streams the previous index and the delta's rows into a new file in
     * the new pack directory, so no whole index sits on the heap (issue #89). The installed pack stays untouched.
     */
    private suspend fun installDeltas(download: PackDownload.Deltas): File {
        val entry = download.entry
        val installed = store.installed.value.packs[entry.lang] ?: throw PackIntegrityException("${entry.lang} is not installed")
        // An empty chain would move the installed index.bin into the new directory.
        check(download.chain.isNotEmpty()) { "${entry.lang}: empty delta chain" }
        var pack = StreamedPack(PackCards.parse(installed.cardsFile.readBytes()), installed.meta.dim, installed.indexFile, installed.meta.indexSha256)
        val progress = Progress(entry.lang, download.size)
        val dir = store.newPackDir(entry.lang, entry.version)
        try {
            for ((step, delta) in download.chain.withIndex()) {
                val cards = progress.track { http.download(delta.cards, it) }
                val rows = delta.index?.let { f -> progress.track { http.download(f, it) } }
                setProgress(entry.lang, LanguageProgress.Installing)
                val next = pack.applyDelta(cards.readBytes(), rows, File(dir, "index-$step.part"))
                if (pack.index != installed.indexFile) pack.index.delete()
                pack = next
                pack.verify(delta.result.keysSha256, delta.result.indexSha256, delta.result.cards, delta.result.metadataCards)
            }
            pack.verify(entry.keysSha256, entry.indexSha256, entry.cards, entry.metadataCards)
            if (pack.cardFile.version != entry.version) {
                throw PackIntegrityException("${entry.lang}: the delta chain ends at ${pack.cardFile.version}, not ${entry.version}")
            }
            store.writePack(dir, pack.cardFile, pack.index, PackMeta.of(entry, pack.cardFile, pack.dim, pack.indexSha256))
        } catch (e: Exception) {
            store.discard(dir)
            throw e
        }
        return dir
    }

    private suspend fun downloadModel(model: PackModel, progressLang: String?): File {
        val progress = progressLang?.let { Progress(it, model.size) }
        val embedder = progress?.track { http.download(model.embedder, it) } ?: http.download(model.embedder)
        val corners = model.corners?.let { f -> progress?.track { http.download(f, it) } ?: http.download(f) }
        val dir = store.newModelDir(model.id)
        try {
            store.writeModel(dir, InstalledModel(model.id, dir, model.dim, model.embedder.sha256, model.corners?.sha256), embedder, corners)
        } catch (e: Exception) {
            store.discard(dir)
            throw e
        }
        return dir
    }

    /** Puts the cards of every installed pack that isn't in Room yet into the catalog (idempotent, crash-safe). */
    private suspend fun fillCatalog() {
        val now = store.installed.value
        for ((lang, pack) in now.packs) {
            if (now.catalogFilled[lang] == pack.dir.name || lang in now.broken) continue
            val (sets, cards) = PackContent.parseMetadata(pack.cardsFile.readBytes())
            importer.import(lang, sets, cards.map { it.first })
            store.commit { catalogFilled[lang] = pack.dir.name }
        }
    }

    private fun markFailed(languages: Collection<String>, reason: String) =
        _state.update { s -> s.copy(progress = s.progress + languages.associateWith { LanguageProgress.Failed(reason) }) }

    private fun setProgress(lang: String, progress: LanguageProgress) =
        _state.update { it.copy(progress = it.progress + (lang to progress)) }

    /** Download progress of one language over several files. */
    private inner class Progress(private val lang: String, private val total: Long) {
        private var finished = 0L

        suspend fun track(download: suspend ((Long) -> Unit) -> File): File {
            setProgress(lang, LanguageProgress.Downloading(finished, total))
            val file = download { done -> setProgress(lang, LanguageProgress.Downloading(finished + done, total)) }
            finished += file.length()
            return file
        }
    }

    private fun PackEntry.path() = cardsFile.path

    private companion object {
        const val TAG = "PackManager"
    }
}
