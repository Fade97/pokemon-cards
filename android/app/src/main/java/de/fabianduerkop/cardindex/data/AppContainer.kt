// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data

import android.content.Context
import de.fabianduerkop.cardindex.BuildConfig
import de.fabianduerkop.cardindex.CardIndexApplication
import de.fabianduerkop.cardindex.data.alerts.CachedCurrentPrices
import de.fabianduerkop.cardindex.data.alerts.PriceAlertChecker
import de.fabianduerkop.cardindex.data.alerts.PriceAlertRepository
import de.fabianduerkop.cardindex.data.alerts.PriceAlertWorker
import de.fabianduerkop.cardindex.data.alerts.RoomPriceAlertStore
import de.fabianduerkop.cardindex.data.backup.BackupRepository
import de.fabianduerkop.cardindex.data.catalog.CatalogSource
import de.fabianduerkop.cardindex.data.catalog.PackCatalogImporter
import de.fabianduerkop.cardindex.data.catalog.RoomCatalogSource
import de.fabianduerkop.cardindex.data.collections.CollectionRepository
import de.fabianduerkop.cardindex.data.contributions.ContributionConsent
import de.fabianduerkop.cardindex.data.contributions.ContributionRepository
import de.fabianduerkop.cardindex.data.contributions.ContributionWorker
import de.fabianduerkop.cardindex.data.contributions.HttpContributionApi
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.images.OwnPhotos
import de.fabianduerkop.cardindex.data.images.ScanImageStore
import de.fabianduerkop.cardindex.data.packs.PackHttp
import de.fabianduerkop.cardindex.data.packs.PackManager
import de.fabianduerkop.cardindex.data.packs.PackPreferences
import de.fabianduerkop.cardindex.data.packs.PackStore
import de.fabianduerkop.cardindex.data.packs.PackSyncWorker
import de.fabianduerkop.cardindex.data.prices.HttpPriceApi
import de.fabianduerkop.cardindex.data.prices.HttpPriceHistoryApi
import de.fabianduerkop.cardindex.data.prices.PriceHistoryRepository
import de.fabianduerkop.cardindex.data.pro.DebugEntitlements
import de.fabianduerkop.cardindex.data.pro.Entitlements
import de.fabianduerkop.cardindex.data.pro.NoEntitlements
import de.fabianduerkop.cardindex.data.prices.PriceRegion
import de.fabianduerkop.cardindex.data.prices.PriceRepository
import de.fabianduerkop.cardindex.data.settings.AppSettings
import de.fabianduerkop.cardindex.model.PriceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.util.Locale

/** App-wide objects, created on first use. Plain constructor wiring; the app is small enough not to need a DI library. */
class AppContainer(private val context: Context) {
    // The same instance the view models get from CardIndexDatabase.get: Room only notifies observers of its own writes.
    val database: CardIndexDatabase by lazy { CardIndexDatabase.get(context) }
    val catalog: CatalogSource by lazy { RoomCatalogSource(database.catalogDao()) }
    val settings: AppSettings by lazy { AppSettings(context) }
    val scans: ScanImageStore by lazy { ScanImageStore(context.filesDir.resolve("scans")) }
    val collections: CollectionRepository by lazy { CollectionRepository(database, catalog, scans) }

    /** Own photos plus the preference, for every screen that shows card images. */
    val ownPhotos: Flow<OwnPhotos> by lazy {
        combine(scans.copiesWithPhoto, settings.preferOwnPhotosFlow) { uuids, prefer ->
            OwnPhotos(uuids, prefer, scans::fileFor)
        }
    }

    /** One client for images and prices, so both share its connection pool. */
    val httpClient: OkHttpClient by lazy { OkHttpClient() }

    val prices: PriceRepository by lazy {
        PriceRepository(database.catalogDao(), HttpPriceApi(BuildConfig.API_BASE_URL, httpClient))
    }

    /** Pro (#17). Until sign-in and billing exist (#16), only debug builds can switch it on. */
    val entitlements: Entitlements by lazy {
        if (BuildConfig.DEBUG) DebugEntitlements(settings, BuildConfig.PRO_TEST_TOKEN) else NoEntitlements
    }

    val priceHistory: PriceHistoryRepository by lazy {
        PriceHistoryRepository(
            database.priceHistoryDao(),
            HttpPriceHistoryApi(BuildConfig.API_BASE_URL, httpClient),
            entitlements,
        )
    }

    /** Pro price alerts (#76), checked on the phone until sign-in and push exist (ADR 0013). */
    private val priceAlertStore by lazy { RoomPriceAlertStore(database.priceAlertDao()) }

    val priceAlerts: PriceAlertRepository by lazy {
        PriceAlertRepository(
            priceAlertStore,
            schedule = { PriceAlertWorker.schedule(context) },
            unschedule = { PriceAlertWorker.cancel(context) },
        )
    }

    val priceAlertChecker: PriceAlertChecker by lazy {
        PriceAlertChecker(priceAlertStore, CachedCurrentPrices(prices, database.catalogDao()))
    }

    val backup: BackupRepository by lazy { BackupRepository(database) }

    /**
     * Scan contributions (#14). Only while the consent version the user accepted is one this app shows: a newer text
     * needs a new consent.
     */
    val contributions: ContributionRepository by lazy {
        ContributionRepository(
            db = database,
            filesDir = context.filesDir,
            api = HttpContributionApi(BuildConfig.API_BASE_URL, httpClient),
            acceptedConsent = { settings.contributionConsent?.takeIf { it == ContributionConsent.VERSION } },
            requestRun = { ContributionWorker.enqueue(context) },
        )
    }

    /** Language packs in app-private storage that is not backed up: they can always be downloaded again. */
    val packs: PackManager by lazy {
        val store = PackStore(context.noBackupFilesDir.resolve("packs"))
        PackManager(
            store = store,
            prefs = PackPreferences(context),
            http = PackHttp(BuildConfig.API_BASE_URL, httpClient, store.downloadDir),
            // Set names in the phone's language when that pack is installed, else in English.
            importer = PackCatalogImporter(database) { listOf(Locale.getDefault().language, "en") },
            requestSync = { PackSyncWorker.enqueue(context) },
        )
    }

    /** The market whose price leads; the other one is shown smaller. */
    val primaryPriceSource: Flow<PriceSource> by lazy { settings.priceSourceFlow.map { PriceRegion.effective(it) } }
}

val Context.appContainer: AppContainer get() = (applicationContext as CardIndexApplication).container
