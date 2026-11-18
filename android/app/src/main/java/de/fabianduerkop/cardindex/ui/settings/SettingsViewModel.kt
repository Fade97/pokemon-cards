// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.fabianduerkop.cardindex.data.AppContainer
import de.fabianduerkop.cardindex.data.alerts.PriceAlert
import de.fabianduerkop.cardindex.data.backup.BackupFileType
import de.fabianduerkop.cardindex.data.backup.BackupFormatException
import de.fabianduerkop.cardindex.data.backup.ImportResult
import de.fabianduerkop.cardindex.data.contributions.ContributionConsent
import de.fabianduerkop.cardindex.data.prices.PriceRegion
import de.fabianduerkop.cardindex.data.prices.PriceSourcePreference
import de.fabianduerkop.cardindex.data.prices.RefreshResult
import de.fabianduerkop.cardindex.model.PriceSource
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferOwnPhotos: Boolean = false,
    val priceSource: PriceSourcePreference = PriceSourcePreference.AUTO,
    /** What AUTO means in this region. */
    val regionSource: PriceSource = PriceRegion.defaultSource(),
    val refreshing: Boolean = false,
    val refreshResult: RefreshResult? = null,
    val backupBusy: Boolean = false,
    val backupMessage: BackupMessage? = null,
    /** Scan contributions are on (the current consent text was accepted). */
    val contributing: Boolean = false,
)

/** A price alert in the Settings list, with the card it watches ("025 Pikachu"). */
data class AlertListItem(val alert: PriceAlert, val title: String)

/** Outcome of the last export or import, shown under the backup buttons. */
sealed interface BackupMessage {
    data class Exported(val copies: Int) : BackupMessage
    data class Imported(val result: ImportResult) : BackupMessage
    data class Failed(val reason: String) : BackupMessage
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val settings = container.settings
    private val refresh = MutableStateFlow<Pair<Boolean, RefreshResult?>>(false to null)
    private val backup = MutableStateFlow<Pair<Boolean, BackupMessage?>>(false to null)

    val state: StateFlow<SettingsUiState> = combine(
        settings.preferOwnPhotosFlow,
        settings.priceSourceFlow,
        refresh,
        backup,
        settings.contributionConsentFlow,
    ) { photos, source, (refreshing, result), (backupBusy, message), consent ->
        SettingsUiState(
            preferOwnPhotos = photos,
            priceSource = source,
            refreshing = refreshing,
            refreshResult = result,
            backupBusy = backupBusy,
            backupMessage = message,
            contributing = consent == ContributionConsent.VERSION,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(preferOwnPhotos = settings.preferOwnPhotos, priceSource = settings.priceSource),
    )

    /** Debug builds only (#17): Pro without sign-in or billing, see DebugEntitlements. */
    val debugPro: StateFlow<Boolean> =
        settings.debugProFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.debugPro)

    fun setDebugPro(value: Boolean) { settings.debugPro = value }

    /** Pro (#76): every price alert, oldest first. Null without Pro. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val alerts: StateFlow<List<AlertListItem>?> = container.entitlements.isPro.distinctUntilChanged().flatMapLatest { pro ->
        if (!pro) {
            flowOf(null)
        } else {
            container.priceAlerts.observeAll().mapLatest { alerts ->
                val cards = container.database.catalogDao().cardsByIds(alerts.map { it.cardId }.distinct()).groupBy { it.id }
                alerts.map { alert ->
                    val prints = cards[alert.cardId].orEmpty()
                    val card = prints.firstOrNull { it.language == alert.cardLanguage } ?: prints.firstOrNull()
                    AlertListItem(alert, card?.let { "${it.localId} ${it.name}" } ?: alert.cardId)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun deleteAlert(uuid: String) {
        viewModelScope.launch { container.priceAlerts.delete(uuid) }
    }

    fun setPreferOwnPhotos(value: Boolean) { settings.preferOwnPhotos = value }

    fun setPriceSource(value: PriceSourcePreference) { settings.priceSource = value }

    /** Turning contributions on goes through the consent screen; off works right here and drops unsent scans. */
    fun stopContributing() {
        settings.stopContributing()
        viewModelScope.launch { container.contributions.dropQueued() }
    }

    fun export(resolver: ContentResolver, uri: Uri, type: BackupFileType) = runBackup {
        val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write the file")
        container.backup.export(type, out)
        BackupMessage.Exported(container.database.copyDao().all().size)
    }

    fun import(resolver: ContentResolver, uri: Uri) = runBackup {
        val input = resolver.openInputStream(uri) ?: throw IOException("Cannot read the file")
        BackupMessage.Imported(container.backup.import(input))
    }

    private fun runBackup(work: suspend () -> BackupMessage) {
        if (backup.value.first) return
        backup.value = true to null
        viewModelScope.launch {
            val message = try {
                work()
            } catch (e: BackupFormatException) {
                BackupMessage.Failed(e.message.orEmpty())
            } catch (e: IOException) {
                BackupMessage.Failed(e.message.orEmpty())
            } catch (e: SecurityException) {
                BackupMessage.Failed(e.message.orEmpty())
            }
            backup.value = false to message
        }
    }

    /** Fetches the prices of every owned card now, ignoring the 12-hour cache age. */
    fun refreshPrices() {
        if (refresh.value.first) return
        refresh.value = true to null
        viewModelScope.launch {
            val ids = container.collections.observeAllCopies().first().map { it.card.id }
            refresh.value = false to container.prices.refresh(ids, force = true)
        }
    }
}
