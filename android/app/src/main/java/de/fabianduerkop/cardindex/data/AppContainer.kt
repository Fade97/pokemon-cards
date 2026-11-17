// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data

import android.content.Context
import de.fabianduerkop.cardindex.BuildConfig
import de.fabianduerkop.cardindex.CardIndexApplication
import de.fabianduerkop.cardindex.data.backup.BackupRepository
import de.fabianduerkop.cardindex.data.catalog.CatalogSource
import de.fabianduerkop.cardindex.data.catalog.RoomCatalogSource
import de.fabianduerkop.cardindex.data.collections.CollectionRepository
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.images.OwnPhotos
import de.fabianduerkop.cardindex.data.images.ScanImageStore
import de.fabianduerkop.cardindex.data.prices.HttpPriceApi
import de.fabianduerkop.cardindex.data.prices.PriceRegion
import de.fabianduerkop.cardindex.data.prices.PriceRepository
import de.fabianduerkop.cardindex.data.settings.AppSettings
import de.fabianduerkop.cardindex.model.PriceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

/** App-wide objects, created on first use. Plain constructor wiring; the app is small enough not to need a DI library. */
class AppContainer(private val context: Context) {
    val database: CardIndexDatabase by lazy { CardIndexDatabase.build(context) }
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
        PriceRepository(database.catalogDao(), HttpPriceApi(BuildConfig.PRICE_API_BASE_URL, httpClient))
    }

    val backup: BackupRepository by lazy { BackupRepository(database) }

    /** The market whose price leads; the other one is shown smaller. */
    val primaryPriceSource: Flow<PriceSource> by lazy { settings.priceSourceFlow.map { PriceRegion.effective(it) } }
}

val Context.appContainer: AppContainer get() = (applicationContext as CardIndexApplication).container
