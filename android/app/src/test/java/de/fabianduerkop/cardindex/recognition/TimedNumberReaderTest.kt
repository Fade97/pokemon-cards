// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/** The OCR seam of #19: [TimedNumberReader] with a fake [NumberReader] instead of ML Kit. */
@RunWith(AndroidJUnit4::class)
class TimedNumberReaderTest {
    /** Answers with [answer] right away, or keeps the callback for the test when [answer] is null. */
    private class FakeReader(var answer: Result<List<String>>? = null) : NumberReader {
        val pending = mutableListOf<(Result<List<String>>) -> Unit>()
        var reads = 0

        override fun read(strip: Bitmap, onDone: (Result<List<String>>) -> Unit) {
            reads++
            answer?.let(onDone) ?: pending.add(onDone)
        }

        override fun close() = Unit
    }

    private val strip = createBitmap(8, 4)

    @Test
    fun returnsTheReadersLines() {
        val reader = FakeReader(Result.success(listOf("Pikachu", "025/165")))
        assertEquals(listOf("Pikachu", "025/165"), TimedNumberReader(reader, 1_000).read(strip))
    }

    @Test
    fun rethrowsTheReadersError() {
        val reader = FakeReader(Result.failure(IllegalArgumentException("bad image")))
        val ocr = TimedNumberReader(reader, 1_000)
        assertThrows(IllegalArgumentException::class.java) { ocr.read(strip) }
        assertFalse(ocr.busy)
    }

    @Test
    fun waitsForAnAnswerFromAnotherThread() {
        val reader = object : NumberReader {
            override fun read(strip: Bitmap, onDone: (Result<List<String>>) -> Unit) {
                thread { Thread.sleep(50); onDone(Result.success(listOf("TG05/TG30"))) }
            }

            override fun close() = Unit
        }
        assertEquals(listOf("TG05/TG30"), TimedNumberReader(reader, 5_000).read(strip))
    }

    @Test
    fun aTimedOutReadSkipsTheNextOnesUntilItIsDone() {
        val reader = FakeReader()
        val ocr = TimedNumberReader(reader, 50)

        assertThrows(TimeoutException::class.java) { ocr.read(strip) }
        assertTrue(ocr.busy)
        // The stuck read still runs: new ones fail at once and never reach the reader, so nothing queues behind it.
        val t0 = System.nanoTime()
        repeat(3) { assertThrows(NumberReaderBusyException::class.java) { ocr.read(strip) } }
        assertTrue("busy reads must not wait for the timeout", (System.nanoTime() - t0) / 1_000_000 < 50)
        assertEquals(1, reader.reads)

        // Once it is done (its late answer is dropped), reads work again.
        reader.pending.single()(Result.success(listOf("old")))
        assertFalse(ocr.busy)
        reader.answer = Result.success(listOf("001/165"))
        assertEquals(listOf("001/165"), ocr.read(strip))
        assertEquals(2, reader.reads)
    }

    @Test
    fun aReaderThatAnswersTwiceDoesNotFreeALaterReadsSlot() {
        val reader = FakeReader()
        val ocr = TimedNumberReader(reader, 50)
        assertThrows(TimeoutException::class.java) { ocr.read(strip) }
        val first = reader.pending.single()
        first(Result.success(emptyList()))
        assertThrows(TimeoutException::class.java) { ocr.read(strip) }
        // The first read's callback again: the second read is still running.
        first(Result.success(emptyList()))
        assertTrue(ocr.busy)
    }

    @Test
    fun aReaderThatThrowsFreesTheSlot() {
        val reader = object : NumberReader {
            var fail = true

            override fun read(strip: Bitmap, onDone: (Result<List<String>>) -> Unit) {
                if (fail) throw IllegalStateException("recognizer closed")
                onDone(Result.success(listOf("ok")))
            }

            override fun close() = Unit
        }
        val ocr = TimedNumberReader(reader, 1_000)
        assertThrows(IllegalStateException::class.java) { ocr.read(strip) }
        assertFalse(ocr.busy)
        reader.fail = false
        assertEquals(listOf("ok"), ocr.read(strip))
    }
}
