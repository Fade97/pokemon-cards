// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.contributions

import android.util.Log
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.ContributionEntity
import de.fabianduerkop.cardindex.data.db.ContributionItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** The consent text the user accepted; the backend accepts only versions it knows (`CONSENT_VERSIONS`). */
object ContributionConsent {
    const val VERSION = "train-1"

    /**
     * Sent with not-a-card scans (#244), which only debug builds collect: such a crop may show anything in a home,
     * which [VERSION] doesn't cover. A release needs its own consent text first.
     */
    const val NOT_CARD_VERSION = "debug-1"
}

/** How a run of the upload queue ended, for the worker. */
sealed interface QueueOutcome {
    data object Done : QueueOutcome

    /** Offline, a server error or still rate limited: run again later. Nothing was lost. */
    data object Retry : QueueOutcome
}

/**
 * Scan contributions (issue #14, ADR 0008): the rectified crop of a scan the user confirmed, uploaded only after the
 * opt-in and the consent (training only, ADR 0016). Uploads and withdrawals queue in Room and are sent by [ContributionWorker] when the
 * phone is online. The withdraw token is stored only in the app's database and never logged.
 */
class ContributionRepository(
    private val db: CardIndexDatabase,
    /** The app's files directory; queued crops are stored below it. */
    private val filesDir: File,
    private val api: ContributionApi,
    /** The consent version the user accepted while the toggle is on; null when contributing is off. */
    private val acceptedConsent: () -> String?,
    /** Schedules [processQueue] (WorkManager in the app). */
    private val requestRun: () -> Unit = {},
    /** Whether not-a-card scans are collected (#244): debug builds only, see [ContributionConsent.NOT_CARD_VERSION]. */
    val collectsNotCards: Boolean = false,
    private val clock: () -> Instant = Instant::now,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val dao get() = db.contributionDao()

    /**
     * Serializes queue steps and withdrawals. Without it, a withdrawal during an upload would delete the row while
     * the request is in flight: the server keeps the scan and the token in the answer has nowhere to go. With it,
     * the withdrawal waits for the upload, then finds the server id and queues the DELETE.
     */
    private val lock = Mutex()

    fun observe(): Flow<List<ContributionItem>> = dao.observeAll()

    /**
     * Queues the crop of a confirmed scan when the user contributes; returns the queue id (to take it back with
     * [withdraw] if the scan is undone), or null when contributing is off. Metadata is stripped before the file is
     * stored, so not even the queue holds any. [predicted] is the scanner's top match when the user picked another
     * card (#244).
     */
    suspend fun contribute(cardId: String, lang: String, jpeg: ByteArray, predicted: CardRef? = null): Long? {
        val consent = acceptedConsent() ?: return null
        return enqueue(ContributionKind.CARD, cardId, lang, consent, jpeg, predicted)
    }

    /**
     * Queues the crop of a scan the user marked as not a card (#244), with the card it was taken for; null when
     * contributing is off or this build doesn't collect them ([collectsNotCards]).
     */
    suspend fun contributeNotCard(jpeg: ByteArray, predicted: CardRef?): Long? {
        if (!collectsNotCards || acceptedConsent() == null) return null
        return enqueue(ContributionKind.NOT_CARD, "", "", ContributionConsent.NOT_CARD_VERSION, jpeg, predicted)
    }

    private suspend fun enqueue(
        kind: String,
        cardId: String,
        lang: String,
        consent: String,
        jpeg: ByteArray,
        predicted: CardRef?,
    ): Long {
        val clean = JpegMetadata.strip(jpeg)
        val relative = "$DIR/${UUID.randomUUID()}.jpg"
        val file = File(filesDir, relative)
        file.parentFile?.mkdirs()
        file.writeBytes(clean)
        val id = try {
            // Id and token are chosen here, before the first attempt, so every retry sends the same ones.
            dao.insert(
                ContributionEntity(
                    cardId = cardId, language = lang, cropPath = relative, consentVersion = consent, createdAt = clock(),
                    remoteId = UUID.randomUUID().toString(), withdrawToken = newWithdrawToken(), kind = kind,
                    predictedCardId = predicted?.id, predictedLanguage = predicted?.language,
                ),
            )
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        requestRun()
        return id
    }

    /** Takes one contribution back: a queued one is dropped here, an uploaded one is withdrawn on the server. */
    suspend fun withdraw(id: Long) = lock.withLock {
        val item = dao.byId(id) ?: return@withLock
        // Uploaded or queued: both may be on the server (see retract); the crop goes right away.
        if (retract(item)) requestRun()
    }

    suspend fun withdrawAll() = lock.withLock {
        for (item in dao.pendingUploads()) retract(item)
        if (dao.requestWithdrawalOfAll() > 0) requestRun()
    }

    /** Contributing was turned off: scans not sent yet are no longer covered by the consent, so they go. */
    suspend fun dropQueued() = lock.withLock {
        var marked = false
        for (item in dao.pendingUploads()) marked = retract(item) || marked
        if (marked) requestRun()
    }

    /**
     * Takes back a scan that was never confirmed as uploaded. One with a server id may still be on the server (an
     * earlier attempt stored it, the answer got lost), so it is withdrawn there too; a 404 counts as done.
     * Returns true when a DELETE was queued.
     */
    private suspend fun retract(item: ContributionEntity): Boolean {
        if (item.remoteId == null) {
            drop(item)
            return false
        }
        item.cropPath?.let { File(filesDir, it).delete() }
        dao.requestWithdrawal(item.id)
        return true
    }

    /**
     * Sends queued withdrawals first (a user who takes something back is waiting for that), then uploads. A `429`
     * is waited out as `Retry-After` says, a few times per run; anything else that can pass returns [QueueOutcome.Retry].
     */
    suspend fun processQueue(): QueueOutcome {
        var waits = 0
        while (true) {
            try {
                if (!step()) return QueueOutcome.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: ContributionRateLimitedException) {
                if (++waits > MAX_RATE_LIMIT_WAITS) return QueueOutcome.Retry
                sleep(e.retryAfterSeconds * 1000)
                continue
            } catch (e: IOException) {
                Log.w(TAG, "contribution queue paused: ${e.message}")
                return QueueOutcome.Retry
            }
        }
    }

    /**
     * One request; false when nothing is left. An upload whose answer is lost (timeout after the server stored it)
     * is sent again on the next run with the same id and token, and the server answers with the copy it has.
     */
    private suspend fun step(): Boolean = lock.withLock { stepLocked() }

    private suspend fun stepLocked(): Boolean {
        dao.pendingWithdrawals().firstOrNull()?.let { item ->
            try {
                api.withdraw(item.remoteId!!, item.withdrawToken!!)
            } catch (e: ContributionRejectedException) {
                // 401 can't happen with a stored token; nothing the app could send would do better.
                Log.w(TAG, "withdrawal refused: HTTP ${e.code}")
            }
            dao.delete(item.id)
            return true
        }
        val item = dao.pendingUploads().firstOrNull() ?: return false
        val consent = acceptedConsent()
        if (consent == null || (item.kind == ContributionKind.NOT_CARD && !collectsNotCards)) {
            // Turned off while queued (or a not-a-card scan in a build that doesn't send them): never send it, and
            // withdraw any copy an earlier attempt left on the server.
            retract(item)
            return true
        }
        val file = File(filesDir, item.cropPath!!)
        if (!file.isFile) {
            // Nothing left to send (e.g. a restored database without the crops), but an earlier attempt may have
            // stored it: withdraw rather than forget the token.
            retract(item)
            return true
        }
        try {
            val predicted = item.predictedCardId?.let { id -> item.predictedLanguage?.let { CardRef(id, it) } }
            val uploaded = api.upload(
                item.cardId, item.language, item.consentVersion, item.remoteId!!, item.withdrawToken!!, file.readBytes(),
                item.kind, predicted,
            )
            // The answer repeats the stored id and token; a backend without client ids would send its own, which are
            // then the ones that withdraw it.
            dao.markUploaded(item.id, uploaded.id, uploaded.withdrawToken, clock())
            file.delete()
        } catch (e: ContributionRejectedException) {
            // The server refuses this one for good (bad card id, broken image …): drop it, keep the queue moving.
            Log.w(TAG, "contribution of ${item.cardId} refused: HTTP ${e.code}")
            drop(item)
        }
        return true
    }

    private suspend fun drop(item: ContributionEntity) {
        dao.delete(item.id)
        item.cropPath?.let { File(filesDir, it).delete() }
    }

    companion object {
        const val DIR = "contributions"
        private const val TAG = "Contributions"
        private const val MAX_RATE_LIMIT_WAITS = 3
        private val random = SecureRandom()

        /** 32 random bytes, base64url without padding: the 43 characters the backend expects. */
        internal fun newWithdrawToken(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    }
}
