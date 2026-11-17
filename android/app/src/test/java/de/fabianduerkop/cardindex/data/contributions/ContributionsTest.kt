// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.contributions

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.testing.inMemoryDatabase
import de.fabianduerkop.cardindex.testing.seedCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ContributionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: CardIndexDatabase
    private val api = FakeApi()
    private var consent: String? = ContributionConsent.VERSION
    private var runs = 0
    private val slept = mutableListOf<Long>()
    private lateinit var repo: ContributionRepository

    /** SOI, APP0 (JFIF), APP1 (EXIF with a fake GPS string), COM, DQT, SOS + data, EOI. */
    private val jpegWithMetadata = bytes(
        0xFF, 0xD8,
        0xFF, 0xE0, 0x00, 0x07, 'J'.code, 'F'.code, 'I'.code, 'F'.code, 0x00,
        0xFF, 0xE1, 0x00, 0x0A, 'E'.code, 'x'.code, 'i'.code, 'f'.code, 'G'.code, 'P'.code, 'S'.code, '!'.code,
        0xFF, 0xFE, 0x00, 0x05, 'h'.code, 'i'.code, '!'.code,
        0xFF, 0xDB, 0x00, 0x04, 0x01, 0x02,
        0xFF, 0xDA, 0x00, 0x03, 0x01, 0x11, 0x22, 0xFF, 0x00, 0x33,
        0xFF, 0xD9,
    )

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repo = ContributionRepository(
            db, tmp.root, api,
            acceptedConsent = { consent },
            requestRun = { runs++ },
            clock = { Instant.parse("2026-11-17T10:00:00Z") },
            sleep = { slept += it },
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun metadataSegmentsAreStrippedAndTheImageKept() {
        val clean = JpegMetadata.strip(jpegWithMetadata)
        assertTrue(JpegMetadata.hasMetadata(jpegWithMetadata))
        assertFalse(JpegMetadata.hasMetadata(clean))
        assertFalse(String(clean, Charsets.ISO_8859_1).contains("GPS"))
        assertArrayEquals(
            bytes(
                0xFF, 0xD8,
                0xFF, 0xE0, 0x00, 0x07, 'J'.code, 'F'.code, 'I'.code, 'F'.code, 0x00,
                0xFF, 0xDB, 0x00, 0x04, 0x01, 0x02,
                0xFF, 0xDA, 0x00, 0x03, 0x01, 0x11, 0x22, 0xFF, 0x00, 0x33,
                0xFF, 0xD9,
            ),
            clean,
        )
        assertThrows(IllegalArgumentException::class.java) { JpegMetadata.strip(bytes(0x89, 'P'.code, 'N'.code, 'G'.code)) }
    }

    @Test
    fun nothingIsQueuedWithoutConsent() = runTest {
        consent = null
        assertNull(repo.contribute("sv03.5-006", "de", jpegWithMetadata))
        assertTrue(repo.observe().first().isEmpty())
        assertEquals(0, runs)
    }

    @Test
    fun queuedScanIsUploadedWithoutMetadataAndItsTokenKept() = runTest {
        db.seedCatalog()
        val id = repo.contribute("sv03.5-006", "de", jpegWithMetadata)!!
        assertEquals(1, runs)
        val queued = db.contributionDao().byId(id)!!
        val file = File(tmp.root, queued.cropPath!!)
        assertFalse(JpegMetadata.hasMetadata(file.readBytes()))

        assertEquals(QueueOutcome.Done, repo.processQueue())

        val upload = api.uploads.single()
        assertEquals(listOf("sv03.5-006", "de", "cc0-1"), listOf(upload.cardId, upload.lang, upload.consent))
        assertFalse(JpegMetadata.hasMetadata(upload.jpeg))
        val stored = db.contributionDao().byId(id)!!
        // Id and token were chosen when the scan was queued and sent as they are.
        assertEquals(queued.remoteId, stored.remoteId)
        assertEquals(queued.withdrawToken, stored.withdrawToken)
        assertEquals(stored.remoteId to stored.withdrawToken, upload.id to upload.token)
        assertTrue(UUID_V4.matches(stored.remoteId!!))
        assertTrue(TOKEN.matches(stored.withdrawToken!!))
        assertEquals(Instant.parse("2026-11-17T10:00:00Z"), stored.uploadedAt)
        assertNull(stored.cropPath)
        assertFalse(file.exists())
        assertFalse("the token must not show up in logs", stored.toString().contains(stored.withdrawToken!!))
        val item = repo.observe().first().single()
        assertEquals("Card 006", item.cardName)
    }

    @Test
    fun offlineKeepsTheQueueAndRateLimitIsWaitedOut() = runTest {
        repo.contribute("sv03.5-006", "de", jpegWithMetadata)
        api.failNext += IOException("offline")
        assertEquals(QueueOutcome.Retry, repo.processQueue())
        assertNull(db.contributionDao().pendingUploads().single().uploadedAt)

        api.failNext += ContributionRateLimitedException(60)
        assertEquals(QueueOutcome.Done, repo.processQueue())
        assertEquals(listOf(60_000L), slept)
        assertEquals(1, api.uploads.size)
    }

    @Test
    fun endlessRateLimitEndsTheRunForALaterRetry() = runTest {
        repo.contribute("sv03.5-006", "de", jpegWithMetadata)
        repeat(10) { api.failNext += ContributionRateLimitedException(60) }
        assertEquals(QueueOutcome.Retry, repo.processQueue())
        assertEquals(3, slept.size)
        assertEquals(1, db.contributionDao().pendingUploads().size)
    }

    @Test
    fun lostAnswerThenRetry_leavesOneServerCopyThatTheStoredTokenWithdraws() = runTest {
        val id = repo.contribute("sv03.5-006", "de", jpegWithMetadata)!!
        val queued = db.contributionDao().byId(id)!!
        api.loseNextAnswer = true
        // The server stored it, the answer never arrived.
        assertEquals(QueueOutcome.Retry, repo.processQueue())
        assertEquals(1, api.uploads.size)
        assertNull(db.contributionDao().byId(id)!!.uploadedAt)

        assertEquals(QueueOutcome.Done, repo.processQueue())
        assertEquals(2, api.requests)
        assertEquals("still one copy on the server", 1, api.uploads.size)
        val stored = db.contributionDao().byId(id)!!
        assertEquals(queued.remoteId to queued.withdrawToken, stored.remoteId to stored.withdrawToken)
        assertEquals(api.uploads.single().id to api.uploads.single().token, stored.remoteId to stored.withdrawToken)

        repo.withdraw(id)
        assertEquals(QueueOutcome.Done, repo.processQueue())
        assertEquals(listOf(stored.remoteId to stored.withdrawToken), api.withdrawals)
        assertTrue(api.withdrawn(stored.remoteId!!))
    }

    @Test
    fun refusedUploadIsDroppedAndTheQueueMovesOn() = runTest {
        repo.contribute("bad", "de", jpegWithMetadata)
        repo.contribute("sv03.5-006", "de", jpegWithMetadata)
        api.failNext += ContributionRejectedException(400)
        assertEquals(QueueOutcome.Done, repo.processQueue())
        assertEquals(listOf("sv03.5-006"), repo.observe().first().map { it.contribution.cardId })
    }

    @Test
    fun queuedScansGoWhenContributingIsTurnedOff() = runTest {
        repo.contribute("sv03.5-006", "de", jpegWithMetadata)
        consent = null
        assertEquals(QueueOutcome.Done, repo.processQueue())
        assertTrue(api.uploads.isEmpty())
        assertTrue(repo.observe().first().isEmpty())
        assertTrue(File(tmp.root, ContributionRepository.DIR).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun withdrawDropsQueuedOnesLocallyAndSendsTheTokenForUploadedOnes() = runTest {
        val uploaded = repo.contribute("sv03.5-006", "de", jpegWithMetadata)!!
        repo.processQueue()
        val queued = repo.contribute("sv03.5-025", "de", jpegWithMetadata)!!

        repo.withdraw(queued)
        assertNull(db.contributionDao().byId(queued))

        repo.withdraw(uploaded)
        val row = db.contributionDao().byId(uploaded)!!
        assertTrue(row.withdrawRequested)
        assertEquals(QueueOutcome.Done, repo.processQueue())
        assertEquals(listOf(row.remoteId to row.withdrawToken), api.withdrawals)
        assertTrue(repo.observe().first().isEmpty())
    }

    @Test
    fun withdrawDuringTheUpload_waitsForIt_andThenWithdrawsOnTheServer() = runTest {
        val id = repo.contribute("sv03.5-006", "de", jpegWithMetadata)!!
        val row = db.contributionDao().byId(id)!!
        val gate = CompletableDeferred<Unit>()
        api.beforeUploadReturns = { gate.await() }

        val queue = launch { repo.processQueue() }
        advanceUntilIdle() // the upload is in flight
        val undo = launch { repo.withdraw(id) }
        advanceUntilIdle()
        assertTrue("the withdrawal must wait for the upload", undo.isActive)

        gate.complete(Unit)
        queue.join()
        undo.join()
        // The running queue usually sends the DELETE itself right after the upload; a later run covers the rest.
        assertEquals(QueueOutcome.Done, repo.processQueue())

        assertEquals(1, api.uploads.size)
        assertEquals(listOf(row.remoteId to row.withdrawToken), api.withdrawals)
        assertNull(db.contributionDao().byId(id))
        assertTrue(repo.observe().first().isEmpty())
    }

    @Test
    fun withdrawAllCoversQueuedAndUploaded() = runTest {
        val first = repo.contribute("sv03.5-006", "de", jpegWithMetadata)!!
        val second = repo.contribute("sv03.5-025", "de", jpegWithMetadata)!!
        repo.processQueue()
        repo.contribute("sv03.5-001", "en", jpegWithMetadata)
        val sent = listOf(first, second).map { db.contributionDao().byId(it)!!.remoteId }

        repo.withdrawAll()
        assertEquals(QueueOutcome.Done, repo.processQueue())

        assertEquals(sent, api.withdrawals.map { it.first })
        assertEquals(2, api.uploads.size)
        assertTrue(repo.observe().first().isEmpty())
    }

    @Test
    fun httpClientSendsExactlyTheContract() = runTest {
        val server = MockWebServer()
        val id = "0b6f5c1e-8a4d-4f2b-9c3e-1d2a3b4c5d6e"
        val token = "A".repeat(43)
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"$id","withdrawToken":"$token"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"$id","withdrawToken":"$token"}"""))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(422))
        server.enqueue(MockResponse().setResponseCode(409))
        server.start()
        try {
            val http = HttpContributionApi(server.url("/").toString(), OkHttpClient())
            val result = http.upload("sv03.5-006", "de", "cc0-1", id, token, byteArrayOf(1, 2, 3))
            assertEquals(id, result.id)
            assertFalse(result.toString().contains(token))
            val post = server.takeRequest()
            assertEquals("POST", post.method)
            assertEquals("/v1/contributions?cardId=sv03.5-006&lang=de&consent=cc0-1&id=$id", post.path)
            assertEquals("image/jpeg", post.getHeader("Content-Type"))
            assertEquals("Bearer $token", post.getHeader("Authorization"))

            // The same upload again (its answer was lost): 200 with the same values.
            val again = http.upload("sv03.5-006", "de", "cc0-1", id, token, byteArrayOf(1, 2, 3))
            assertEquals(id to token, again.id to again.withdrawToken)
            server.takeRequest()

            val limited = assertThrows(ContributionRateLimitedException::class.java) {
                kotlinx.coroutines.runBlocking { http.upload("sv03.5-006", "de", "cc0-1", id, token, byteArrayOf(1)) }
            }
            assertEquals(30L, limited.retryAfterSeconds)
            server.takeRequest()

            http.withdraw("abc", "t0k") // 404 counts as done
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/v1/contributions/abc", delete.path)
            assertEquals("Bearer t0k", delete.getHeader("Authorization"))

            assertThrows(ContributionRejectedException::class.java) {
                kotlinx.coroutines.runBlocking { http.upload("x", "de", "cc0-1", id, token, byteArrayOf(1)) }
            }
            server.takeRequest()
            // The id is taken by another token: sending it again won't help.
            val taken = assertThrows(ContributionRejectedException::class.java) {
                kotlinx.coroutines.runBlocking { http.upload("sv03.5-006", "de", "cc0-1", id, "B".repeat(43), byteArrayOf(1)) }
            }
            assertEquals(409, taken.code)
        } finally {
            server.shutdown()
        }
    }

    private class Upload(val cardId: String, val lang: String, val consent: String, val id: String, val token: String, val jpeg: ByteArray)

    /** The backend's contract: one stored contribution per id; the same id and token again get it back. */
    private class FakeApi : ContributionApi {
        /** Contributions stored on the server. */
        val uploads = mutableListOf<Upload>()
        var requests = 0
        val withdrawals = mutableListOf<Pair<String, String>>()
        val failNext = ArrayDeque<IOException>()
        var beforeUploadReturns: suspend () -> Unit = {}

        /** The next upload is stored, but its answer is lost on the way back. */
        var loseNextAnswer = false

        override suspend fun upload(
            cardId: String,
            lang: String,
            consent: String,
            id: String,
            withdrawToken: String,
            jpeg: ByteArray,
        ): UploadedContribution {
            requests++
            failNext.removeFirstOrNull()?.let { throw it }
            beforeUploadReturns()
            val existing = uploads.firstOrNull { it.id == id }
            if (existing == null) {
                uploads += Upload(cardId, lang, consent, id, withdrawToken, jpeg)
            } else if (existing.token != withdrawToken) {
                throw ContributionRejectedException(409)
            }
            if (loseNextAnswer) {
                loseNextAnswer = false
                throw IOException("timeout")
            }
            return UploadedContribution(id, withdrawToken)
        }

        override suspend fun withdraw(id: String, withdrawToken: String) {
            failNext.removeFirstOrNull()?.let { throw it }
            withdrawals += id to withdrawToken
        }

        fun withdrawn(id: String): Boolean = uploads.any { it.id == id && (it.id to it.token) in withdrawals }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private companion object {
        val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    }
}
