// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Learned card-corner detector (LiteRT; `ml/` README, "Learned corner detector: exact I/O"). It looks at the
 * live-scan guide area only and finds the printed card edge, not the sleeve, toploader or binder-pocket edge.
 *
 * Input: the guide area stretched (no letterbox) to 192 × 256, float32 NHWC RGB in [0, 1]; normalization is inside
 * the model. Output `[1, 4, 3]`: per corner (TL, TR, BR, BL as seen in the image) x and y in [0, 1] of the guide area,
 * and the heatmap peak probability. The mean of the four peaks is the detection score (no card ≈ < 0.08–0.10).
 */
class CornerDetector(model: ByteBuffer, threads: Int = 4) : AutoCloseable {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads))
    private val input = ByteBuffer.allocateDirect(4 * WIDTH * HEIGHT * 3).order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(4) { FloatArray(3) } }
    private val pixels = ByteArray(WIDTH * HEIGHT * 3)

    /** Corners of the card inside [area] of a BGR `CV_8UC3` frame, in frame pixels; score = mean corner peak. */
    fun detect(bgr: Mat, area: PixelRect): Detection {
        val sub = bgr.submat(Rect(area.x, area.y, area.width, area.height))
        val resized = Mat()
        Imgproc.resize(sub, resized, Size(WIDTH.toDouble(), HEIGHT.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val rgb = Mat()
        Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
        rgb.get(0, 0, pixels)
        sub.release(); resized.release(); rgb.release()
        input.rewind()
        for (b in pixels) input.putFloat((b.toInt() and 0xFF) / 255f)
        input.rewind()
        interpreter.run(input, output)
        val corners = output[0]
        val quad = corners.map { Pt(area.x + it[0].toDouble() * area.width, area.y + it[1].toDouble() * area.height) }
        return Detection(quad, corners.sumOf { it[2].toDouble() } / 4)
    }

    override fun close() = interpreter.close()

    companion object {
        const val WIDTH = 192
        const val HEIGHT = 256

        /** Mean corner peak below which the frame counts as "no card" (synthetic: no card ≤ 0.08 at the 95th pct). */
        const val DEFAULT_MIN_SCORE = 0.10
    }
}

/** Which detector finds the card outline. */
enum class DetectorKind { LEARNED, CLASSIC }
