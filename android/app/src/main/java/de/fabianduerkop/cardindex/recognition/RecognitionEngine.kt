// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** How long each step took, in milliseconds. */
data class Timings(val detect: Long, val embed: Long, val knn: Long, val ocr: Long, val total: Long)

/** The result of one frame or photo. */
data class RecognitionResult(
    val frameWidth: Int,
    val frameHeight: Int,
    /** False when no card outline was found and the centred fallback box was used. */
    val found: Boolean,
    /** Card corners in frame pixels, TL, TR, BR, BL of the card as recognized (after the 180° check). */
    val quad: Quad,
    val detectionScore: Double,
    /** The card was upside down in the frame. */
    val flipped: Boolean,
    /** Top 5 by embedding similarity. */
    val candidates: List<Match>,
    /** [candidates] after the collector-number rerank. */
    val reranked: List<Match>,
    val reading: NumberReading?,
    val ocrText: String?,
    /** The rectified, upright card crop that was embedded. */
    val crop: Bitmap,
    val timings: Timings,
) {
    val best: Match get() = reranked.first()

    /** Similarity gap between the best and the second-best candidate by embedding. */
    val margin: Float get() = candidates[0].similarity - candidates[1].similarity
}

/** Model metadata from meta.json. */
data class ModelInfo(val model: String, val dim: Int, val cards: Int, val corners: String?)

/**
 * The on-device recognition pipeline of the test app, mirroring ml/pipeline.py: classic-CV detection → rectify to
 * 63:88 → embedding (LiteRT) for the crop and its 180° rotation → cosine kNN over the reference index →
 * collector-number OCR (ML Kit) → rerank. Not thread-safe: call it from one worker thread.
 */
class RecognitionEngine private constructor(
    private val embedder: Embedder,
    private val cornerDetector: CornerDetector?,
    val index: ReferenceIndex,
    val info: ModelInfo,
) : AutoCloseable {
    /** Whether the learned corner detector is in this build (assets/recognition/corners.tflite). */
    val hasLearnedDetector: Boolean get() = cornerDetector != null

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Recognizes the card in an upright ARGB bitmap (camera frame or photo). */
    @WorkerThread
    fun recognize(bitmap: Bitmap, readNumber: Boolean = true): RecognitionResult {
        val bgr = toBgr(bitmap)
        try {
            return recognize(bgr, readNumber)
        } finally {
            bgr.release()
        }
    }

    /** Recognizes the card in a BGR `CV_8UC3` image: [detect], then [identify]. */
    @WorkerThread
    fun recognize(bgr: Mat, readNumber: Boolean = true): RecognitionResult {
        val t0 = SystemClock.elapsedRealtime()
        val detection = detect(bgr)
        return identify(bgr, detection, readNumber, detectMs = SystemClock.elapsedRealtime() - t0)
    }

    /**
     * Finds the card outline only; cheap compared with [identify]. With [area], only that part of the frame is
     * searched (the live-scan guide); the quad is returned in full-frame pixels.
     */
    @WorkerThread
    fun detect(bgr: Mat, area: PixelRect? = null, kind: DetectorKind = DetectorKind.CLASSIC): Detection? {
        // The learned detector was trained on the guide area only; without a guide the classic one is used.
        if (kind == DetectorKind.LEARNED && cornerDetector != null && area != null) return cornerDetector.detect(bgr, area)
        if (area == null) return CardDetector.detect(bgr)
        val sub = bgr.submat(Rect(area.x, area.y, area.width, area.height))
        try {
            val d = CardDetector.detect(sub) ?: return null
            val offset = Pt(area.x.toDouble(), area.y.toDouble())
            return d.copy(quad = d.quad.map { it + offset })
        } finally {
            sub.release()
        }
    }

    /**
     * Identifies the card inside [detection] (or the centred fallback box when null): rectify, embed, kNN, number
     * OCR, rerank. [detectMs] is reported as the detection time; [languages] limits the search (null = all).
     */
    @WorkerThread
    fun identify(
        bgr: Mat,
        detection: Detection?,
        readNumber: Boolean = true,
        detectMs: Long = 0,
        languages: Set<String>? = null,
    ): RecognitionResult {
        val t1 = SystemClock.elapsedRealtime()
        val t0 = t1 - detectMs
        var quad = detection?.quad ?: CardDetector.centerCropFallback(bgr.cols(), bgr.rows())

        var crop = CardDetector.rectify(bgr, quad)
        val rotated = Mat().also { Core.rotate(crop, it, Core.ROTATE_180) }
        val e0 = embedder.embed(crop)
        val e1 = embedder.embed(rotated)
        val t2 = SystemClock.elapsedRealtime()

        // The detector cannot tell an upside-down card: keep the orientation with the better best match.
        val s0 = index.similarities(e0, languages)
        val s1 = index.similarities(e1, languages)
        val flipped = s1.max() > s0.max()
        val sims = if (flipped) s1 else s0
        if (flipped) {
            quad = quad.roll(2)
            crop.release()
            crop = rotated
        } else {
            rotated.release()
        }
        val candidates = index.topK(sims, 5)
        val t3 = SystemClock.elapsedRealtime()

        var reading: NumberReading? = null
        var ocrText: String? = null
        if (readNumber) {
            val lines = readNumberStrip(bgr, quad)
            ocrText = lines.joinToString(" | ").ifEmpty { null }
            reading = parseCollectorNumber(lines)
        }
        val t4 = SystemClock.elapsedRealtime()

        val cropBitmap = toBitmap(crop)
        crop.release()
        return RecognitionResult(
            frameWidth = bgr.cols(),
            frameHeight = bgr.rows(),
            found = detection != null,
            quad = quad,
            detectionScore = detection?.score ?: 0.0,
            flipped = flipped,
            candidates = candidates,
            reranked = rerank(candidates, reading),
            reading = reading,
            ocrText = ocrText,
            crop = cropBitmap,
            timings = Timings(t1 - t0, t2 - t1, t3 - t2, t4 - t3, t4 - t0),
        )
    }

    /**
     * OCR of the bottom-left number strip (`NUM_BOX` in ml/pipeline.py) of a 630 × 880 rectification, upscaled 2×.
     */
    private fun readNumberStrip(bgr: Mat, quad: Quad): List<String> {
        val big = CardDetector.rectify(bgr, quad, 630, 880)
        val strip = Mat(big, Rect(0, (0.905 * 880).toInt(), (0.50 * 630).toInt(), (0.99 * 880).toInt() - (0.905 * 880).toInt()))
        val up = Mat().also { Imgproc.resize(strip, it, Size(), 2.0, 2.0, Imgproc.INTER_CUBIC) }
        big.release()
        val bitmap = toBitmap(up)
        up.release()
        val text = Tasks.await(textRecognizer.process(InputImage.fromBitmap(bitmap, 0)))
        return text.textBlocks.flatMap { b -> b.lines.map { it.text } }
    }

    override fun close() {
        embedder.close()
        cornerDetector?.close()
        textRecognizer.close()
    }

    companion object {
        const val ASSET_DIR = "recognition"

        /** Loads OpenCV, the model and the index from `assets/recognition/` (built by ml/app_assets.py). */
        @WorkerThread
        fun load(context: Context): RecognitionEngine {
            check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
            val assets = context.assets
            val names = assets.list(ASSET_DIR).orEmpty().toSet()
            val required = listOf("embedder.tflite", "index.bin", "cards.json", "meta.json")
            val missing = required.filterNot(names::contains)
            if (missing.isNotEmpty()) throw FileNotFoundException("missing in assets/$ASSET_DIR: ${missing.joinToString()}")
            fun bytes(name: String) = assets.open("$ASSET_DIR/$name").use { it.readBytes() }
            val model = direct(bytes("embedder.tflite"))
            val meta = JSONObject(String(bytes("meta.json")))
            val corners = meta.optString("corners").ifEmpty { null }
            val info = ModelInfo(meta.getString("model"), meta.getInt("dim"), meta.getInt("cards"), corners)
            val cards = ReferenceIndex.parseCards(String(bytes("cards.json")))
            val index = ReferenceIndex(cards, ReferenceIndex.parseVectors(bytes("index.bin")), info.dim)
            val embedder = Embedder(model)
            check(embedder.dim == info.dim) { "model dim ${embedder.dim} != index dim ${info.dim}" }
            val cornerDetector = if ("corners.tflite" in names) CornerDetector(direct(bytes("corners.tflite"))) else null
            return RecognitionEngine(embedder, cornerDetector, index, info)
        }

        private fun direct(b: ByteArray): ByteBuffer =
            ByteBuffer.allocateDirect(b.size).order(ByteOrder.nativeOrder()).put(b).also { it.rewind() }

        /** An ARGB bitmap as a BGR `CV_8UC3` Mat (the caller releases it). */
        fun toBgr(bitmap: Bitmap): Mat {
            val rgba = Mat()
            Utils.bitmapToMat(bitmap, rgba)
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()
            return bgr
        }

        private fun toBitmap(bgr: Mat): Bitmap {
            val rgba = Mat().also { Imgproc.cvtColor(bgr, it, Imgproc.COLOR_BGR2RGBA) }
            val bitmap = createBitmap(rgba.cols(), rgba.rows())
            Utils.matToBitmap(rgba, bitmap)
            rgba.release()
            return bitmap
        }
    }
}
