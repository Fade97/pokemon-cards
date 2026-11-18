// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import de.fabianduerkop.cardindex.data.alerts.PriceAlertNotifications
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.data.packs.PackSyncWorker
import de.fabianduerkop.cardindex.ui.CardIndexApp
import de.fabianduerkop.cardindex.ui.theme.CardIndexTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        schedulePackWork()
        PriceAlertNotifications.createChannel(this)
        lifecycleScope.launch { appContainer.priceAlerts.syncSchedule() }
        setContent { CardIndexTheme { CardIndexApp() } }
    }

    /**
     * The daily catalog poll, plus a sync now when the wanted languages are not all installed (a download that was
     * interrupted, or packs to fetch again after a restore from backup, which leaves them out).
     */
    private fun schedulePackWork() {
        val packs = appContainer.packs
        PackSyncWorker.schedulePolling(this)
        val installed = packs.installed.value
        if (packs.wanted != installed.packs.keys || installed.broken.isNotEmpty() || installed.modelBroken) {
            PackSyncWorker.enqueue(this)
        }
    }
}
