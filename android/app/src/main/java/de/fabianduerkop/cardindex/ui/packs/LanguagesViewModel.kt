// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.packs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.fabianduerkop.cardindex.data.AppContainer
import de.fabianduerkop.cardindex.data.packs.InstalledPacks
import de.fabianduerkop.cardindex.data.packs.LanguageProgress
import de.fabianduerkop.cardindex.data.packs.PackCatalog
import de.fabianduerkop.cardindex.data.packs.PackSyncState
import de.fabianduerkop.cardindex.data.packs.UnsupportedPackFormatException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/** The pack languages of format version 1 (docs/packs.md), in the order the picker lists them. */
val PACK_LANGUAGES = listOf("en", "de", "fr", "it", "es", "pt", "nl", "pl", "ja")

/** The language's name in the phone's language, e.g. "German" for `de`. */
fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

data class LanguageRow(
    val code: String,
    val wanted: Boolean,
    val installedVersion: Int?,
    /** Bytes on disk. */
    val storage: Long,
    val broken: Boolean,
    val progress: LanguageProgress?,
    /** Download size of the full pack, from the catalog. */
    val downloadSize: Long?,
    /** False when the catalog has no usable pack for it (yet). */
    val available: Boolean,
)

sealed interface CatalogStatus {
    data object Loading : CatalogStatus
    data object Ready : CatalogStatus
    data class Unavailable(val reason: String, val needsAppUpdate: Boolean = false) : CatalogStatus
}

data class LanguagesUiState(
    val rows: List<LanguageRow> = emptyList(),
    /** Size of the model download, counted once however many languages are picked; null when it is installed. */
    val modelDownload: Long? = null,
    val modelStorage: Long = 0,
    val modelBroken: Boolean = false,
    val catalog: CatalogStatus = CatalogStatus.Loading,
    val syncing: Boolean = false,
    val syncError: String? = null,
) {
    val installed: List<LanguageRow> get() = rows.filter { it.wanted || it.installedVersion != null }
    val addable: List<LanguageRow> get() = rows.filter { !it.wanted && it.installedVersion == null }

    /** What picking [languages] costs to download, model included. */
    fun downloadSize(languages: Set<String>): Long =
        rows.filter { it.code in languages }.sumOf { it.downloadSize ?: 0 } + (modelDownload ?: 0)

    /**
     * The first-run picker's choice: [picked] once the user touched it, else the device language, else English.
     * Only languages with a pack count: a disabled row shows no tick, so it must not be downloaded either
     * (a Dutch phone would otherwise "download" nothing, issue #56).
     */
    fun pickerChoice(picked: Set<String>?, deviceLanguage: String): Set<String> {
        val available = rows.filter { it.available }.map { it.code }.toSet()
        val start = picked ?: listOf(deviceLanguage, "en").firstOrNull { it in available }?.let(::setOf).orEmpty()
        return start intersect available
    }
}

/** Language management (Settings) and the first-run picker. Opening either polls the catalog (docs/packs.md). */
class LanguagesViewModel(private val container: AppContainer) : ViewModel() {
    private val packs = container.packs
    private val catalogStatus = MutableStateFlow<CatalogStatus>(CatalogStatus.Loading)
    private val wanted = MutableStateFlow(packs.wanted)

    val state: StateFlow<LanguagesUiState> = combine(
        packs.installed, packs.state, packs.catalog, catalogStatus, wanted,
    ) { installed, sync, catalog, status, wanted -> build(installed, sync, catalog, status, wanted) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LanguagesUiState())

    init {
        refreshCatalog()
    }

    fun refreshCatalog() {
        catalogStatus.value = CatalogStatus.Loading
        viewModelScope.launch {
            catalogStatus.value = try {
                packs.refreshCatalog()
                CatalogStatus.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsupportedPackFormatException) {
                CatalogStatus.Unavailable(e.message.orEmpty(), needsAppUpdate = true)
            } catch (e: Exception) {
                CatalogStatus.Unavailable(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun add(languages: Set<String>) {
        if (languages.isEmpty()) return
        packs.addLanguages(languages)
        wanted.value = packs.wanted
    }

    fun remove(lang: String) {
        viewModelScope.launch {
            packs.removeLanguage(lang)
            wanted.value = packs.wanted
        }
    }

    /** Downloads a broken or failed pack again in full. */
    fun repair(lang: String) = packs.repair(lang)

    /** First run: the user picked [languages] (possibly none, "not now"). */
    fun finishPicker(languages: Set<String>) {
        add(languages)
        packs.pickerDone = true
    }

    private fun build(
        installed: InstalledPacks,
        sync: PackSyncState,
        catalog: PackCatalog?,
        status: CatalogStatus,
        wanted: Set<String>,
    ): LanguagesUiState {
        val codes = (PACK_LANGUAGES + installed.packs.keys + wanted).distinct()
        val rows = codes.map { code ->
            val pack = installed.packs[code]
            val entry = catalog?.packs?.get(code)
            LanguageRow(
                code = code,
                wanted = code in wanted,
                installedVersion = pack?.meta?.version,
                storage = pack?.size ?: 0,
                broken = code in installed.broken,
                progress = sync.progress[code],
                downloadSize = entry?.size,
                available = entry?.usable == true,
            )
        }
        // The model the catalog's packs use; only a download when it isn't the installed one.
        val catalogModel = catalog?.packs?.values?.firstOrNull { it.usable }?.model?.let(catalog.models::get)
        val modelDownload = catalogModel?.takeIf { it.id != installed.model?.id || installed.modelBroken }?.size
        return LanguagesUiState(
            rows = rows,
            modelDownload = modelDownload,
            modelStorage = installed.model?.size ?: 0,
            modelBroken = installed.modelBroken,
            catalog = if (status is CatalogStatus.Unavailable && !status.needsAppUpdate && catalog != null) CatalogStatus.Ready else status,
            syncing = sync.running,
            syncError = sync.error,
        )
    }
}
