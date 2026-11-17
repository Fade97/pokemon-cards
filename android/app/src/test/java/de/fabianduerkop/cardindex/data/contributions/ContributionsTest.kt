// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.contributions

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.testing.inMemoryDatabase
import de.fabianduerkop.cardindex.testing.seedCatalog
import kotlinx.coroutines.flow.first
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
        assertEquals("r1", stored.remoteId)
        assertEquals("token-r1", stored.withdrawToken)
        assertNull(stored.cropPath)
        assertFalse(file.exists())
        assertFalse("the token must not show up in logs", stored.toString().contains("token-r1"))
        val item = repo.observe().first().single()
        assertEquals("Card 006", item.cardName)
    }

    @Test
    fun offlineKeepsTheQueueAndRateLimitIsWaitedOut() = runTest {
        repo.contribute("sv03.5-006", "de", jpegWithMetadata)
        api.failNext += IOException("offline")
        assertEquals(QueueOutcome.Retry, repo.processQueue())
        assertNull(db.contributionDao().pendingUploads().single().remoteId)

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
        assertTrue(db.contributionDao().byId(uploaded)!!.withdrawRequested)
        assertEquals(QueueOutcome.Done, repo.processQueue())
        assertEquals(listOf("r1" to "token-r1"), api.withdrawals)
        assertTrue(repo.observe().first().isEmpty())
    }

    @Test
    fun withdrawAllCoversQueuedAndUploaded() = runTest {
        repo.contribute("sv03.5-006", "de", jpegWithMetadata)
        repo.contribute("sv03.5-025", "de", jpegWithMetadata)
        repo.processQueue()
        repo.contribute("sv03.5-001", "en", jpegWithMetadata)

        repo.withdrawAll()
        assertEquals(QueueOutcome.Done, repo.processQueue())

        assertEquals(listOf("r1", "r2"), api.withdrawals.map { it.first })
        assertEquals(2, api.uploads.size)
        assertTrue(repo.observe().first().isEmpty())
    }

    @Test
    fun httpClientSendsExactlyTheContract() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"abc","withdrawToken":"t0k"}"""))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(422))
        server.start()
        try {
            val http = HttpContributionApi(server.url("/").toString(), OkHttpClient())
            val result = http.upload("sv03.5-006", "de", "cc0-1", byteArrayOf(1, 2, 3))
            assertEquals("abc", result.id)
            assertFalse(result.toString().contains("t0k"))
            val post = server.takeRequest()
            assertEquals("POST", post.method)
            assertEquals("/v1/contributions?cardId=sv03.5-006&lang=de&consent=cc0-1", post.path)
            assertEquals("image/jpeg", post.getHeader("Content-Type"))
            assertNull(post.getHeader("Authorization"))

            val limited = assertThrows(ContributionRateLimitedException::class.java) {
                kotlinx.coroutines.runBlocking { http.upload("sv03.5-006", "de", "cc0-1", byteArrayOf(1)) }
            }
            assertEquals(30L, limited.retryAfterSeconds)
            server.takeRequest()

            http.withdraw("abc", "t0k") // 404 counts as done
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/v1/contributions/abc", delete.path)
            assertEquals("Bearer t0k", delete.getHeader("Authorization"))

            assertThrows(ContributionRejectedException::class.java) {
                kotlinx.coroutines.runBlocking { http.upload("x", "de", "cc0-1", byteArrayOf(1)) }
            }
        } finally {
            server.shutdown()
        }
    }

    private class Upload(val cardId: String, val lang: String, val consent: String, val jpeg: ByteArray)

    private class FakeApi : ContributionApi {
        val uploads = mutableListOf<Upload>()
        val withdrawals = mutableListOf<Pair<String, String>>()
        val failNext = ArrayDeque<IOException>()

        override suspend fun upload(cardId: String, lang: String, consent: String, jpeg: ByteArray): UploadedContribution {
            failNext.removeFirstOrNull()?.let { throw it }
            uploads += Upload(cardId, lang, consent, jpeg)
            val id = "r${uploads.size}"
            return UploadedContribution(id, "token-$id")
        }

        override suspend fun withdraw(id: String, withdrawToken: String) {
            failNext.removeFirstOrNull()?.let { throw it }
            withdrawals += id to withdrawToken
        }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
