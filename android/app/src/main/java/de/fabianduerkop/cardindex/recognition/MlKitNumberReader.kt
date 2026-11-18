// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor

/**
 * [NumberReader] with ML Kit's Latin text recognition (Google Play services library). This is the only file of the
 * recognition package that uses ML Kit, so a build without it (#19) can leave this file out.
 */
class MlKitNumberReader : NumberReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun read(strip: Bitmap, onDone: (Result<List<String>>) -> Unit) {
        // Called back on the thread that finishes the task, not the main thread, which may be busy.
        recognizer.process(InputImage.fromBitmap(strip, 0)).addOnCompleteListener(DIRECT) { task ->
            onDone(
                when {
                    task.isSuccessful -> Result.success(task.result.textBlocks.flatMap { b -> b.lines.map { it.text } })
                    else -> Result.failure(task.exception ?: CancellationException("ML Kit text recognition cancelled"))
                },
            )
        }
    }

    override fun close() = recognizer.close()

    private companion object {
        val DIRECT = Executor { it.run() }
    }
}
