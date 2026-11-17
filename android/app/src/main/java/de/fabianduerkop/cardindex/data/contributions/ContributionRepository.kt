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
    const val VERSION = "cc0-1"
}

/** How a run of the upload queue ended, for the worker. */
sealed interface QueueOutcome {
    data object Done : QueueOutcome

    /** Offline, a server error or still rate limited: run again later. Nothing was lost. */
    data object Retry : QueueOutcome
}

/**
 * Scan contributions (issue #14, ADR 0008): the rectified crop of a scan the user confirmed, uploaded only after the
 * opt-in and the CC0 consent. Uploads and withdrawals queue in Room and are sent by [ContributionWorker] when the
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
     * stored, so not even the queue holds any.
     */
    suspend fun contribute(cardId: String, lang: String, jpeg: ByteArray): Long? {
        val consent = acceptedConsent() ?: return null
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
                    remoteId = UUID.randomUUID().toString(), withdrawToken = newWithdrawToken(),
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
        if (item.uploadedAt == null) {
            drop(item)
        } else {
            dao.requestWithdrawal(id)
            requestRun()
        }
    }

    suspend fun withdrawAll() = lock.withLock {
        dao.pendingUploads().forEach { drop(it) }
        if (dao.requestWithdrawalOfAll() > 0) requestRun()
    }

    /** Contributing was turned off: scans not sent yet are no longer covered by the consent, so they go. */
    suspend fun dropQueued() = lock.withLock {
        dao.pendingUploads().forEach { drop(it) }
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
        if (consent == null) {
            // Turned off while queued: never send it.
            drop(item)
            return true
        }
        val file = File(filesDir, item.cropPath!!)
        if (!file.isFile) {
            dao.delete(item.id)
            return true
        }
        try {
            val uploaded = api.upload(
                item.cardId, item.language, item.consentVersion, item.remoteId!!, item.withdrawToken!!, file.readBytes(),
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
