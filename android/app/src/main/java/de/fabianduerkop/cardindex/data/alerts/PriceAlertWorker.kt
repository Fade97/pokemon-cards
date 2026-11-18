// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.fabianduerkop.cardindex.MainActivity
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.model.Money
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * The daily price-alert check on the phone (#76, ADR 0013): from 07:00 Berlin, after the night's import, when online.
 * Only the Android part lives here; the rules and the check are [PriceAlertChecker]. Without Pro it does nothing and
 * keeps the alerts, so they work again when Pro comes back.
 */
class PriceAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        if (!container.entitlements.isPro.first()) return Result.success()
        return when (val result = container.priceAlertChecker.check()) {
            AlertCheckResult.PricesUnavailable -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            is AlertCheckResult.Checked -> {
                PriceAlertNotifications.post(applicationContext, result.hits)
                Result.success()
            }
        }
    }

    companion object {
        private const val CHECK = "price-alert-check"
        private const val MAX_ATTEMPTS = 4

        /**
         * The daily check, first at the next [PriceAlertSchedule.CHECK_TIME]. Keeps an existing schedule, so calling
         * it on every start or new alert doesn't move the time.
         */
        fun schedule(context: Context, now: Instant = Instant.now()) {
            val request = PeriodicWorkRequestBuilder<PriceAlertWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(PriceAlertSchedule.delayUntilNextCheck(now).toMillis(), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(CHECK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Debug builds: one check now (when online), to try alerts without waiting for the morning. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<PriceAlertWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("$CHECK-now", ExistingWorkPolicy.REPLACE, request)
        }

        /** No alerts left: no daily wake-up either. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(CHECK)
        }
    }
}

/** The "Price alerts" notification channel and one notification per fired alert. */
object PriceAlertNotifications {
    const val CHANNEL = "price_alerts"

    /** The card screen of an alert; matched by the deep link on the card route (CollectionsNavigation.kt). */
    fun cardUri(cardId: String, language: String): Uri =
        Uri.Builder().scheme("cardindex").authority("card").appendPath(cardId).appendPath(language).build()

    const val CARD_URI_PATTERN = "cardindex://card/{cardId}/{language}"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.alerts_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.alerts_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Android 13+ asks for the permission; before, notifications are allowed unless the user turned them off. */
    fun permitted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    suspend fun post(context: Context, hits: List<PriceAlertHit>) {
        if (hits.isEmpty() || !permitted(context)) return
        createChannel(context)
        val prints = context.appContainer.database.catalogDao()
            .cardsByIds(hits.map { it.alert.cardId }.distinct())
            .groupBy { it.id }
        val manager = NotificationManagerCompat.from(context)
        for (hit in hits) {
            val alert = hit.alert
            val card = prints[alert.cardId].orEmpty().let { cards ->
                cards.firstOrNull { it.language == alert.cardLanguage } ?: cards.firstOrNull()
            }
            val name = card?.let { "${it.localId} ${it.name}" } ?: alert.cardId
            val price = Money.format(hit.price.amountMinor, hit.price.currency)
            val threshold = Money.format(alert.thresholdMinor, alert.currency)
            val text = context.getString(
                if (alert.direction == AlertDirection.ABOVE) R.string.alerts_notification_above else R.string.alerts_notification_below,
                price,
                threshold,
            )
            val intent = Intent(Intent.ACTION_VIEW, cardUri(alert.cardId, alert.cardLanguage), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val tap = PendingIntent.getActivity(
                context,
                alert.uuid.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(name)
                .setContentText(text)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            try {
                manager.notify(alert.uuid, 0, notification)
            } catch (e: SecurityException) {
                return // permission revoked between the check and now
            }
        }
    }
}
