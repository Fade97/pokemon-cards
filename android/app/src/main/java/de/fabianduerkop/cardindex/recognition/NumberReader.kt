// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import android.graphics.Bitmap
import androidx.annotation.WorkerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The OCR behind the collector-number rerank (#19). [RecognitionEngine] cuts and upscales the number strip; the
 * reader only turns that image into text lines. [MlKitNumberReader] is the one in the app today; a build without
 * Google libraries can bring its own. The engine gets it from [de.fabianduerkop.cardindex.data.AppContainer].
 */
interface NumberReader : AutoCloseable {
    /**
     * Starts reading the text lines of [strip] and returns without waiting. Calls [onDone] once, on any thread, with
     * the lines top to bottom or the error.
     */
    fun read(strip: Bitmap, onDone: (Result<List<String>>) -> Unit)
}

/** A new number read was skipped because the last one has not finished yet (it timed out and is still running). */
class NumberReaderBusyException : IllegalStateException("the last number OCR is still running")

/**
 * Waits for [reader] at most [timeoutMs] per read, and runs only one read at a time: after a timeout the reader may
 * still be working, so new reads fail at once with [NumberReaderBusyException] until it is done, instead of
 * queueing behind it (#19). Thread-safe, though the engine calls it from one worker thread.
 */
class TimedNumberReader(private val reader: NumberReader, private val timeoutMs: Long) {
    private val running = AtomicBoolean(false)

    /** Whether a read (possibly one that timed out) has not finished yet. */
    val busy: Boolean get() = running.get()

    /**
     * The text lines of [strip]. Throws [TimeoutException] after [timeoutMs], [NumberReaderBusyException] while an
     * earlier read still runs, or the reader's own error.
     */
    @WorkerThread
    fun read(strip: Bitmap): List<String> {
        if (!running.compareAndSet(false, true)) throw NumberReaderBusyException()
        val answered = AtomicBoolean(false)
        var result: Result<List<String>>? = null // written before countDown, read after await: no race
        val done = CountDownLatch(1)
        try {
            reader.read(strip) { r ->
                // Only the first answer counts; a reader that calls back twice must not free a later read's slot.
                if (answered.compareAndSet(false, true)) {
                    result = r
                    running.set(false)
                    done.countDown()
                }
            }
        } catch (t: Throwable) {
            if (answered.compareAndSet(false, true)) running.set(false)
            throw t
        }
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw TimeoutException("number OCR took over $timeoutMs ms")
        return result!!.getOrThrow()
    }
}
