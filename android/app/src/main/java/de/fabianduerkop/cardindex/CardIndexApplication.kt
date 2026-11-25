// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex

import de.fabianduerkop.cardindex.ui.UiLocale
import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import de.fabianduerkop.cardindex.data.AppContainer
import de.fabianduerkop.cardindex.data.backup.AndroidBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

class CardIndexApplication : Application(), SingletonImageLoader.Factory {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        UiLocale.init(this)
        // After a restore to a new phone, the collection comes from the backup snapshot (AndroidBackup), not the
        // database, which isn't backed up. Runs once per install.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                AndroidBackup.restoreIfNeeded(filesDir, noBackupFilesDir, container.backup) {
                    container.database.copyDao().all().isEmpty()
                }
            }
        }
    }

    /**
     * Card images come from the TCGdex CDN, never from the APK (ADR 0009). Coil keeps them in a disk cache regardless
     * of the CDN's cache headers, so a collection browsed once stays visible offline. A full EN set at low quality
     * is ~5 MB; 250 MB holds dozens of sets before the oldest images are evicted.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.httpClient })) }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("card_images").toOkioPath())
                .maxSizeBytes(250L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()
}
